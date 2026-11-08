package cc.slokker.itv.ui.player.engine

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uma ligação ao fornecedor de cada vez: a nova só abre depois de a anterior fechar. */
class StreamGateTest {
    /** Um "servidor" que conta as ligações abertas ao mesmo tempo. */
    private class Server {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

        fun factory(name: String) = DataSource.Factory { Conn(this, name) }
    }

    private class Conn(private val server: Server, private val name: String) : DataSource {
        @Volatile var opened = false

        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            val n = server.active.incrementAndGet()
            server.peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            server.events += "open $name"
            opened = true
            return 0
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
        override fun getUri(): Uri? = null
        override fun close() {
            if (!opened) return
            opened = false
            server.active.decrementAndGet()
            server.events += "close $name"
        }
    }

    // O Uri do Android não existe nos testes no PC (e o DataSpec exige um): o "servidor" falso não o lê.
    private val spec: DataSpec = run {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = f.get(null)
        unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, DataSpec::class.java) as DataSpec
    }

    private fun gate(graceMs: Long = 800, maxWaitMs: Long = 3_000) = StreamGate(graceMs, maxWaitMs) { System.nanoTime() / 1_000_000 }

    private fun thread(block: () -> Unit) = Thread(block).apply { isDaemon = true; start() }

    @Test
    fun aNovaEsperaQueAAntigaFeche() {
        val gate = gate()
        val server = Server()
        gate.next()
        val a = gate.factory(server.factory("a"), exclusive = true).createDataSource()
        a.open(spec)
        gate.next()
        val b = gate.factory(server.factory("b"), exclusive = true).createDataSource()
        val done = CountDownLatch(1)
        thread { b.open(spec); done.countDown() }
        // Enquanto a antiga está aberta, a nova não abre.
        assertTrue(!done.await(200, TimeUnit.MILLISECONDS))
        a.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("open a", "close a", "open b"), server.events.toList())
        assertEquals(1, server.peak.get())
    }

    @Test
    fun aAntigaPresaFechaSePorForaDepoisDaFolga() {
        val gate = gate(graceMs = 100, maxWaitMs = 5_000)
        val server = Server()
        gate.next()
        gate.factory(server.factory("presa"), exclusive = true).createDataSource().open(spec)
        gate.next()
        val started = System.nanoTime()
        gate.factory(server.factory("nova"), exclusive = true).createDataSource().open(spec)
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("esperou $waitedMs ms", waitedMs in 90..2_000)
        assertEquals(listOf("open presa", "close presa", "open nova"), server.events.toList())
        assertEquals(1, server.peak.get())
    }

    @Test
    fun umaGeracaoUltrapassadaNaoVoltaALigar() {
        val gate = gate()
        val server = Server()
        gate.next()
        val old = gate.factory(server.factory("velha"), exclusive = true)
        gate.next()
        try {
            old.createDataSource().open(spec)
            fail("devia recusar")
        } catch (_: IOException) {
        }
        assertEquals(0, server.active.get())
        assertEquals(0, gate.openCount)
    }

    @Test
    fun hlsNaMesmaGeracaoPodeTerDoisPedidos() {
        val gate = gate()
        val server = Server()
        gate.next()
        val f = gate.factory(server.factory("hls"), exclusive = false)
        val playlist = f.createDataSource().apply { open(spec) }
        // O bocado abre logo, com a lista ainda aberta (o HLS do ExoPlayer faz isto).
        val segment = f.createDataSource().apply { open(spec) }
        assertEquals(2, server.active.get())
        playlist.close()
        segment.close()
        assertEquals(0, gate.openCount)
    }

    @Test
    fun tsNaMesmaGeracaoUmaDeCadaVez() {
        val gate = gate()
        val server = Server()
        gate.next()
        val f = gate.factory(server.factory("ts"), exclusive = true)
        val first = f.createDataSource().apply { open(spec) }
        val done = CountDownLatch(1)
        thread { f.createDataSource().open(spec); done.countDown() }
        assertTrue(!done.await(200, TimeUnit.MILLISECONDS))
        first.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, server.peak.get())
    }

    @Test
    fun umaAberturaQueFalhaNaoFicaABloquear() {
        val gate = gate(graceMs = 5_000, maxWaitMs = 10_000)
        gate.next()
        val failing = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw IOException("404")
                override fun read(buffer: ByteArray, offset: Int, length: Int) = 0
                override fun getUri(): Uri? = null
                override fun close() = Unit
            }
        }
        try {
            gate.factory(failing, exclusive = true).createDataSource().open(spec)
        } catch (_: IOException) {
        }
        assertEquals(0, gate.openCount)
        gate.next()
        val started = System.nanoTime()
        gate.factory(Server().factory("m3u8"), exclusive = false).createDataSource().open(spec)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
    }

    @Test
    fun oVlcEsperaPeloExoPlayerAnterior() {
        val gate = gate()
        val server = Server()
        gate.next()
        val exo = gate.factory(server.factory("exo"), exclusive = true).createDataSource().apply { open(spec) }
        gate.next()
        val done = CountDownLatch(1)
        thread { gate.awaitIdle(); done.countDown() }
        assertTrue(!done.await(200, TimeUnit.MILLISECONDS))
        exo.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }
}

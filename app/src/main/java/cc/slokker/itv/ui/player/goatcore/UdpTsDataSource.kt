@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Um canal em UDP ou RTP (as listas IPTV das operadoras: `udp://@239.1.1.1:1234`, `rtp://@…`): junta-se ao grupo
 * multicast (ou escuta a porta, em unicast) e entrega o TS que vem nos pacotes. No RTP tira-se o cabeçalho de cada
 * pacote; um `udp://` que afinal traz RTP também (o TS começa sempre por 0x47, o RTP pela versão 2).
 */
class UdpTsDataSource(private val context: Context) : BaseDataSource(true) {
    private var socket: MulticastSocket? = null
    private var group: InetAddress? = null
    private var lock: WifiManager.MulticastLock? = null
    private var uri: Uri? = null
    private val packet = DatagramPacket(ByteArray(MAX_PACKET), MAX_PACKET)
    private var at = 0
    private var end = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val u = dataSpec.uri
        uri = u
        val host = u.host?.takeIf { it.isNotEmpty() }
        val port = u.port.takeIf { it > 0 } ?: DEFAULT_PORT
        try {
            val address = host?.let { InetAddress.getByName(it) }
            val s = MulticastSocket(null).apply {
                reuseAddress = true
                soTimeout = TIMEOUT_MS
                receiveBufferSize = 4 * 1024 * 1024
            }
            if (address != null && address.isMulticastAddress) {
                s.bind(InetSocketAddress(port))
                // No Wi-Fi o Android deita fora o multicast sem este pedido.
                lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("goattv-udp")?.apply { setReferenceCounted(false); acquire() }
                @Suppress("DEPRECATION")
                s.joinGroup(address)
                group = address
            } else {
                // Unicast: o fornecedor manda para esta box; escuta-se a porta (em todos os endereços).
                s.bind(InetSocketAddress(port))
            }
            socket = s
        } catch (e: IOException) {
            close()
            throw e
        }
        at = 0
        end = 0
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (at >= end) {
            val s = socket ?: throw IOException("closed")
            try {
                s.receive(packet)
            } catch (e: SocketTimeoutException) {
                throw IOException("no UDP data for ${TIMEOUT_MS / 1000} s", e)
            }
            at = payloadStart(packet.data, packet.length)
            end = payloadEnd(packet.data, packet.length)
            if (at >= end) return 0
        }
        val n = minOf(length, end - at)
        System.arraycopy(packet.data, at, buffer, offset, n)
        at += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val s = socket
        socket = null
        if (s != null) {
            @Suppress("DEPRECATION")
            group?.let { runCatching { s.leaveGroup(it) } }
            s.close()
            transferEnded()
        }
        group = null
        lock?.let { runCatching { it.release() } }
        lock = null
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = UdpTsDataSource(context)
    }

    companion object {
        private const val MAX_PACKET = 65_536
        private const val DEFAULT_PORT = 1234
        /** Sem nada há este tempo, a ligação conta como caída (o leitor volta a tentar). */
        private const val TIMEOUT_MS = 8_000

        /** Os esquemas que esta fonte lê. */
        fun handles(scheme: String): Boolean = scheme == "udp" || scheme == "rtp"

        /** Onde começa o TS no pacote: logo no início, ou depois do cabeçalho RTP (com os CSRC e a extensão). */
        fun payloadStart(data: ByteArray, length: Int): Int {
            if (length < 12 || data[0] == 0x47.toByte()) return 0
            val b0 = data[0].toInt() and 0xFF
            if (b0 shr 6 != 2) return 0
            var start = 12 + (b0 and 0x0F) * 4
            if (b0 and 0x10 != 0 && start + 4 <= length) {
                val words = ((data[start + 2].toInt() and 0xFF) shl 8) or (data[start + 3].toInt() and 0xFF)
                start += 4 + words * 4
            }
            return start.coerceAtMost(length)
        }

        /** Onde acaba o TS: no fim do pacote, ou antes do enchimento do RTP (bit P: o último byte diz quantos). */
        fun payloadEnd(data: ByteArray, length: Int): Int {
            if (payloadStart(data, length) == 0 || data[0].toInt() and 0x20 == 0) return length
            return (length - (data[length - 1].toInt() and 0xFF)).coerceAtLeast(0)
        }
    }
}

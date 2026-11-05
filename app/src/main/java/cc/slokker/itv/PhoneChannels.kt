package cc.slokker.itv

import android.util.Log
import cc.slokker.itv.data.LanRequest
import cc.slokker.itv.data.PhoneChannelList
import cc.slokker.itv.ui.AppViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.Buffer
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Os canais da GoatTV para o GoatRemote (o comando no telemóvel), na rede de casa, só para outros aparelhos com
 * endereços privados e em pedidos diretos (ver [LanRequest]). As mesmas regras da porta 6470 do GoatLauncher.
 *
 *   GET /channels      a lista aberta: grupos e canais pela ordem do ecrã, sem escondidos nem bloqueados
 *                      ([PhoneChannelList]). 503 enquanto não há lista (ativação, a carregar, erro, sem listas).
 *   GET /logo/<chave>  o logótipo do canal, pedido pela TV ao fornecedor (o telemóvel nunca fala com ele)
 *   POST /play/<chave> põe o canal a dar, como escolhê-lo na lista. 409 se a GoatTV não estiver à frente (o
 *                      telemóvel abre-a pelo GoatLauncher e tenta outra vez), 404 se não puder ser visto.
 *
 * Só nas TVs (leanback): num telemóvel a porta nem abre. O estado vem do [AppViewModel] da atividade, que a
 * [MainActivity] regista em [attach]; mexe-se nele sempre na thread principal.
 */
class PhoneChannels(private val container: AppContainer) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Porta: ${e.javaClass.simpleName}") },
    )

    /** Só desta porta (mais um para esperar ligações): nunca ocupa os threads do Dispatchers.IO do resto da app. */
    private val listener = Dispatchers.IO.limitedParallelism(MAX_CONNECTIONS + 1)
    private val slots = Semaphore(MAX_CONNECTIONS)

    /** Logótipos servidos há pouco (URL → imagem), os mais antigos saem primeiro. */
    private val logos = LinkedHashMap<String, Logo>(16, 0.75f, true)
    private var logoBytes = 0L

    private class Logo(val type: String, val bytes: ByteArray)

    fun start() {
        scope.launch(listener) {
            val server = runCatching { ServerSocket(PORT) }.getOrElse {
                Log.w(TAG, "Porta $PORT ocupada", it)
                return@launch
            }
            while (true) {
                // Com tudo ocupado, os seguintes esperam na fila do sistema: um telemóvel pede muitos logótipos de uma vez.
                slots.acquire()
                val client = runCatching { server.accept() }.getOrNull()
                if (client == null) {
                    slots.release()
                    delay(ACCEPT_RETRY_MS)
                    continue
                }
                // Quem não pode falar é fechado logo: não fica com lugar nem thread.
                if (!runCatching { allowedPeer(client) }.getOrDefault(false)) {
                    runCatching { client.close() }
                    slots.release()
                    continue
                }
                scope.launch(listener) {
                    try {
                        connection(client)
                    } finally {
                        slots.release()
                    }
                }
            }
        }
    }

    /** Uma ligação dura no máximo [MAX_CONNECTION_MS], e o que falhar nela fica nela: a app continua. */
    private suspend fun connection(client: Socket) {
        val deadline = scope.launch(Dispatchers.Default) {
            delay(MAX_CONNECTION_MS)
            runCatching { client.close() }
        }
        try {
            client.use { serve(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Ligação: ${e.javaClass.simpleName}")
        } finally {
            deadline.cancel()
        }
    }

    private suspend fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val request = LanRequest.read(client.getInputStream().buffered()) ?: return
        if (!request.allowed) {
            reply(client, TEXT, "recusado".toByteArray(), status = "403 Forbidden")
            return
        }
        val method = request.method
        val path = request.path
        when {
            method == "GET" && path == "/channels" -> channels(client)
            method == "GET" && path.startsWith("/logo/") -> logo(client, PhoneChannelList.decodeKey(path.removePrefix("/logo/")))
            method == "POST" && path.startsWith("/play/") -> play(client, PhoneChannelList.decodeKey(path.removePrefix("/play/")))
            else -> notFound(client)
        }
    }

    private suspend fun channels(client: Socket) {
        // O estado tira-se na thread principal; o JSON (pode ser grande) monta-se aqui.
        val list = withContext(Dispatchers.Main.immediate) { viewModel?.phoneChannelList() }
        if (list == null) {
            reply(client, TEXT, "sem lista".toByteArray(), status = "503 Service Unavailable")
            return
        }
        reply(client, "application/json; charset=utf-8", list.json())
    }

    private suspend fun logo(client: Socket, key: String?) {
        val url = key?.let { withContext(Dispatchers.Main.immediate) { viewModel?.phoneLogoUrl(it) } }
        val logo = url?.let { cached(it) ?: fetch(it)?.also { image -> remember(it, image) } }
        if (logo == null) return notFound(client)
        reply(client, logo.type, logo.bytes)
    }

    private suspend fun play(client: Socket, key: String?) {
        val status = withContext(Dispatchers.Main.immediate) {
            val vm = viewModel
            val channel = key?.let { vm?.phoneChannel(it) }
            when {
                vm == null || !vm.phoneReady() -> "503 Service Unavailable"
                channel == null -> "404 Not Found"
                // Só se troca o canal com a GoatTV à frente; atrás de outra app, o telemóvel abre-a primeiro.
                !resumed -> "409 Conflict"
                else -> {
                    vm.playFromPhone(channel)
                    "200 OK"
                }
            }
        }
        reply(client, TEXT, (if (status == "200 OK") "ok" else status.substringAfter(' ')).toByteArray(), status = status)
    }

    private fun cached(url: String): Logo? = synchronized(logos) { logos[url] }

    private fun remember(url: String, logo: Logo) = synchronized(logos) {
        logos.put(url, logo)?.let { logoBytes -= it.bytes.size }
        logoBytes += logo.bytes.size
        val oldest = logos.entries.iterator()
        while ((logos.size > MAX_CACHED_LOGOS || logoBytes > MAX_CACHED_BYTES) && oldest.hasNext()) {
            logoBytes -= oldest.next().value.bytes.size
            oldest.remove()
        }
    }

    /**
     * Vai buscar o logótipo [url] ao fornecedor com o cliente dos logótipos da app (o mesmo User-Agent), até
     * [MAX_LOGO_BYTES]. null se falhar, se for maior ou se não for uma imagem.
     */
    private fun fetch(url: String): Logo? {
        try {
            val call = container.iptvHttp.newCall(Request.Builder().url(url).build())
            // Antes do fim da ligação ao telemóvel: depois disso já ninguém espera a resposta.
            call.timeout().timeout(LOGO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body.contentLength() > MAX_LOGO_BYTES) return null
                val buffer = Buffer()
                val source = body.source()
                while (buffer.size <= MAX_LOGO_BYTES && source.read(buffer, 8192) != -1L) Unit
                if (buffer.size > MAX_LOGO_BYTES) return null
                val bytes = buffer.readByteArray()
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                return when {
                    type != null && type.startsWith("image/") && type.length > "image/".length -> Logo(type, bytes)
                    isPng(bytes) -> Logo("image/png", bytes)
                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Logótipo: ${e.javaClass.simpleName}")
            return null
        }
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= PNG.size && PNG.indices.all { bytes[it] == PNG[it] }

    private fun notFound(client: Socket) = reply(client, TEXT, "não há".toByteArray(), status = "404 Not Found")

    private fun reply(client: Socket, type: String, body: ByteArray, status: String = "200 OK") {
        val head = "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    private fun allowedPeer(client: Socket): Boolean = isLocal(client.inetAddress) && !isThisTv(client)

    /** Só a rede de casa: nunca quem venha de fora. */
    private fun isLocal(address: InetAddress): Boolean = when (address) {
        is Inet4Address, is Inet6Address ->
            address.isSiteLocalAddress || address.isLinkLocalAddress ||
                (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC) // fc00::/7
        else -> false
    }

    /** A porta é para os telemóveis: uma ligação da própria TV (por qualquer dos seus endereços) não conta. */
    private fun isThisTv(client: Socket): Boolean {
        val peer = client.inetAddress
        return peer.isLoopbackAddress || peer == client.localAddress ||
            runCatching { NetworkInterface.getByInetAddress(peer) != null }.getOrDefault(false)
    }

    companion object {
        const val PORT = 6471
        private const val TAG = "PhoneChannels"
        private const val TEXT = "text/plain; charset=utf-8"

        /** Chega para dois telemóveis a pedir os logótipos todos ao mesmo tempo. */
        private const val MAX_CONNECTIONS = 16
        private const val MAX_CONNECTION_MS = 10_000L
        private const val READ_TIMEOUT_MS = 5000
        private const val ACCEPT_RETRY_MS = 1000L
        private const val LOGO_TIMEOUT_MS = 8_000L
        private const val MAX_LOGO_BYTES = 1024L * 1024
        private const val MAX_CACHED_LOGOS = 300
        private const val MAX_CACHED_BYTES = 16L * 1024 * 1024
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** O ViewModel da atividade aberta (o estado da app), ou null sem atividade. Só na thread principal. */
        @Volatile
        private var viewModel: AppViewModel? = null

        /** A GoatTV está à frente (atividade em primeiro plano): só aí o /play troca o canal. */
        @Volatile
        var resumed = false

        fun attach(vm: AppViewModel) {
            viewModel = vm
        }

        fun detach(vm: AppViewModel) {
            if (viewModel === vm) viewModel = null
        }
    }
}

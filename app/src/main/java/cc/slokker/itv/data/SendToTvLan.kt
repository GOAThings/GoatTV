package cc.slokker.itv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlin.coroutines.resume

/** O nome deste aparelho como a pessoa o vê ("Sala", "Pixel 9"): o das definições, senão o modelo. */
fun deviceName(context: Context): String {
    val named = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
    return SendToTv.clean(named.orEmpty()).ifEmpty { SendToTv.clean(Build.MODEL.orEmpty()) }
}

/** Uma TV com a GoatTV encontrada na rede ([pkg] vazio: sem GoatLauncher para a trazer para a frente, ex. Apple TV). */
data class FoundTv(val name: String, val host: String, val port: Int, val pkg: String)

/**
 * O lado do telemóvel do "Enviar para a TV" ([SendToTv]): procura as TVs na rede (mDNS, [SendToTv.SERVICE]) e manda
 * o canal. O telemóvel nunca abre porta nenhuma; só faz pedidos, como o GoatRemote.
 */
class TvFinder(private val context: Context) {
    private val _tvs = MutableStateFlow<List<FoundTv>>(emptyList())
    val tvs: StateFlow<List<FoundTv>> = _tvs.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var job: Job? = null
    private val nsd get() = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    /** Começa a procurar (até [stop]). Cada TV anunciada confirma-se com um /hello antes de aparecer. */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        job = scope.launch(Dispatchers.IO) {
            demoHost?.let { add(it, SendToTv.PORT, it) }
            // O NsdManager só resolve um de cada vez: os encontrados ficam em fila.
            for (info in found) {
                val resolved = resolve(info) ?: continue
                val host = resolved.host?.takeIf { it is Inet4Address || !it.isLinkLocalAddress }?.hostAddress ?: continue
                add(host, resolved.port, resolved.serviceName.orEmpty())
            }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { Log.w(TAG, "Procura: $error") }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                _tvs.update { list -> list.filterNot { it.name == name && it.host != demoHost } }
            }
        }
        listener = l
        runCatching { nsd?.discoverServices(SendToTv.SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "Procura: ${it.javaClass.simpleName}") }
        job?.invokeOnCompletion { found.close() }
    }

    fun stop() {
        listener?.let { l -> runCatching { nsd?.stopServiceDiscovery(l) } }
        listener = null
        job?.cancel()
        job = null
    }

    private suspend fun add(host: String, port: Int, announced: String) {
        val hello = get(host, port, "/hello")?.let { SendToTv.parseHello(it) }
        // Sem /hello não é uma GoatTV que responda (a do aviso de testes aparece na mesma, pelo endereço).
        if (hello == null && host != demoHost) return
        val tv = FoundTv(hello?.name?.ifEmpty { null } ?: SendToTv.clean(announced).ifEmpty { host }, host, port, hello?.pkg.orEmpty())
        _tvs.update { list -> list.filterNot { it.host == host } + tv }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val manager = nsd ?: return@suspendCancellableCoroutine cont.resume(null)
        runCatching {
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) { if (cont.isActive) cont.resume(null) }
                override fun onServiceResolved(info: NsdServiceInfo) { if (cont.isActive) cont.resume(info) }
            })
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    companion object {
        private const val TAG = "TvFinder"

        /** Só em debug (`--es demo_tv <ip>`): esse endereço aparece na folha como uma TV encontrada. */
        @Volatile
        var demoHost: String? = null

        /**
         * Manda o canal a [tv] (o corpo de [SendToTv.body]). Com a GoatTV atrás de outra app pede ao GoatLauncher para
         * a trazer e tenta outra vez ([SendToTv.step]). O estado da última resposta (0 sem resposta) e o canal aberto.
         */
        suspend fun send(tv: FoundTv, body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
            var launched = false
            var last = 0 to ""
            for (attempt in 0..SendToTv.RETRIES) {
                val (status, text) = request(tv.host, tv.port, "POST", "/open", body)
                last = status to SendToTv.parseOpened(text)
                when (SendToTv.step(status, attempt, launched, tv.pkg.isNotEmpty())) {
                    SendToTv.STEP_LAUNCH -> {
                        launched = true
                        if (request(tv.host, SendToTv.LAUNCHER_PORT, "POST", SendToTv.launcherPath(tv.pkg)).first != SendToTv.OK) break
                    }
                    SendToTv.STEP_WAIT -> Unit
                    else -> break
                }
                delay(SendToTv.RETRY_MS)
            }
            last
        }

        private fun get(host: String, port: Int, path: String): String? =
            request(host, port, "GET", path).takeIf { it.first == SendToTv.OK }?.second

        /** Um pedido direto (sem Origin, Host = o IP), como os do GoatRemote. O estado (0 sem resposta) e o corpo. */
        private fun request(host: String, port: Int, method: String, path: String, body: String? = null): Pair<Int, String> = try {
            val connection = URL("http", host, port, path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_MS
                connection.readTimeout = READ_MS
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                if (body != null) {
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                } else if (method == "POST") {
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(0)
                    connection.outputStream.close()
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { input ->
                    val bytes = ByteArray(SendToTv.MAX_BODY_BYTES)
                    var read = 0
                    while (read < bytes.size) {
                        val n = input.read(bytes, read, bytes.size - read)
                        if (n < 0) break
                        read += n
                    }
                    String(bytes, 0, read, Charsets.UTF_8)
                }.orEmpty()
                status to text
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            0 to ""
        }

        private const val CONNECT_MS = 3000
        private const val READ_MS = 4000
    }
}

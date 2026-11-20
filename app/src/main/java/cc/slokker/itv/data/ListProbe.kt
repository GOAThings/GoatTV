package cc.slokker.itv.data

import cc.slokker.itv.data.xtream.XtreamSource
import cc.slokker.itv.playback.ListVerdict
import cc.slokker.itv.playback.StreamProbe
import cc.slokker.itv.playback.TokenCounter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Uma linha do painel "Testar esta lista": a lista ou o guia. [host]: só o nome do servidor. */
class ListProbeRow(
    val guide: Boolean,
    val host: String,
    /** -1: o servidor não respondeu. */
    val status: Int,
    val redirects: Int,
    val ms: Long,
    val bytes: Long,
    /** Canais (lista) ou programas (guia). */
    val count: Int,
    val verdict: ListVerdict,
)

data class ListProbeState(
    val running: Boolean = false,
    val rows: List<ListProbeRow> = emptyList(),
    /** As listas de portal (Stalker) não se medem sem abrir sessão no portal. */
    val portal: Boolean = false,
    /** A lista não tem endereço de guia. */
    val noGuide: Boolean = false,
    val done: Boolean = false,
)

/**
 * "Testar esta lista": pede ao fornecedor a lista de canais e o guia (os mesmos endereços que a app usa) e conta o
 * que vem, sem abrir nenhuma stream. Lê o corpo aos poucos e só guarda o contador. Nada do endereço (caminho,
 * utilizador, palavra-passe) chega à interface: só o nome do servidor.
 */
class ListProbe(private val scope: CoroutineScope, private val http: OkHttpClient) {
    private val _state = MutableStateFlow(ListProbeState())
    val state: StateFlow<ListProbeState> = _state.asStateFlow()
    private var job: Job? = null

    /** [knownGuideUrl]: o guia que o M3U trouxe (só se sabe na lista em uso). */
    fun start(playlist: Playlist, knownGuideUrl: String?) {
        if (_state.value.running) return
        if (playlist.type == PlaylistType.STALKER) {
            _state.value = ListProbeState(portal = true, done = true)
            return
        }
        job = scope.launch {
            _state.value = ListProbeState(running = true)
            val rows = ArrayList<ListProbeRow>()
            val list = when (playlist.type) {
                PlaylistType.XTREAM -> runCatching {
                    XtreamSource.baseUrl(playlist).newBuilder().addPathSegment("player_api.php")
                        .addQueryParameter("username", playlist.username.orEmpty())
                        .addQueryParameter("password", playlist.password.orEmpty())
                        .addQueryParameter("action", "get_live_streams").build().toString()
                }.getOrNull()
                else -> playlist.url?.trim()?.takeIf { it.isNotEmpty() }
            }
            val xtream = playlist.type == PlaylistType.XTREAM
            if (list != null) rows += fetch(list, if (xtream) "\"stream_id\"" else "#EXTINF", guide = false)
            _state.value = ListProbeState(running = true, rows = rows.toList())
            val guideUrl = playlist.epgUrl?.takeIf { it.isNotBlank() } ?: knownGuideUrl?.takeIf { it.isNotBlank() }
                ?: if (xtream) runCatching { XtreamSource.xmltvUrl(playlist) }.getOrNull() else null
            if (guideUrl != null) rows += fetch(guideUrl, "<programme", guide = true)
            _state.value = ListProbeState(rows = rows, noGuide = guideUrl == null, done = true)
        }
    }

    fun clear() {
        job?.cancel()
        _state.value = ListProbeState()
    }

    private suspend fun fetch(url: String, token: String, guide: Boolean): ListProbeRow = withContext(Dispatchers.IO) {
        val host = StreamProbe.host(url)
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            return@withContext ListProbeRow(guide, host, -1, 0, ms(), 0, 0, ListVerdict.UNREACHABLE)
        }
        val counter = TokenCounter(token)
        try {
            http.newCall(request).execute().use { res ->
                var redirects = 0
                var prior = res.priorResponse
                while (prior != null) { redirects++; prior = prior.priorResponse }
                var bytes = 0L
                if (res.isSuccessful) {
                    val source = res.body.source()
                    val raw = source.peek().let { it.request(2) && it.readByte() == 0x1f.toByte() && it.readByte() == 0x8b.toByte() }
                    val counted = CountingInput(source.inputStream())
                    val stream: InputStream = if (raw) GZIPInputStream(counted, 64 * 1024) else counted
                    val limit = if (guide) SafeInput.MAX_GUIDE_BYTES else SafeInput.MAX_M3U_BYTES
                    val buf = ByteArray(64 * 1024)
                    // O que se lê é texto: um pedaço de bytes de cada vez chega para procurar o marcador.
                    while (counted.bytes < limit) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        counter.feed(String(buf, 0, n, Charsets.ISO_8859_1))
                    }
                    bytes = counted.bytes
                }
                val took = ms()
                ListProbeRow(guide, host, res.code, redirects, took, bytes, counter.count, StreamProbe.listVerdict(res.code, took, counter.count))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ListProbeRow(guide, host, -1, 0, ms(), 0, 0, ListVerdict.UNREACHABLE)
        }
    }

    private class CountingInput(private val inner: InputStream) : InputStream() {
        var bytes = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bytes++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bytes += it }
        override fun close() = inner.close()
    }
}

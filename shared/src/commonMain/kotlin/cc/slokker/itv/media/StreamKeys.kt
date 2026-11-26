package cc.slokker.itv.media

import cc.slokker.itv.data.StreamDrm
import cc.slokker.itv.data.m3u.M3uStreamProps
import kotlin.concurrent.Volatile

/**
 * O DRM dos canais que se vão abrir, pelo endereço do stream, e as chaves ClearKey que já se conhecem (as da lista
 * e as que os servidores de licenças deram), para os leitores do HLS/DASH ([Cenc]) as encontrarem sem passar o canal
 * por todo o caminho até lá. O mesmo no Android e na Apple: quem faz as tentativas de um canal chama [remember].
 */
object StreamKeys {
    @Volatile
    private var drms: Map<String, StreamDrm> = emptyMap()
    @Volatile
    private var keys: Map<String, String> = emptyMap()
    @Volatile
    private var headers: Map<String, Map<String, String>> = emptyMap()

    /** O canal do endereço [url] tem [drm] e pede os cabeçalhos [extra] (nada, se forem null). */
    fun remember(url: String, drm: StreamDrm?, extra: Map<String, String>?) {
        val u = DashManifest.fetchUrl(url)
        if (drm != null && drms[u] != drm) {
            drms = trimmed(drms) + (u to drm)
            drm.keys?.let { learn(it) }
        }
        if (!extra.isNullOrEmpty() && headers[u] != extra) headers = trimmed(headers) + (u to extra)
    }

    /** Uma lista grande não enche a memória: ficam os últimos canais abertos. */
    private fun <T> trimmed(map: Map<String, T>): Map<String, T> =
        if (map.size >= MAX_STREAMS) map.entries.drop(map.size - MAX_STREAMS / 2).associate { it.toPair() } else map

    /** O DRM do stream em [url] (com ou sem a marca do DASH), se o canal disse algum. */
    fun drmFor(url: String): StreamDrm? = drms[DashManifest.fetchUrl(url)]

    /** Os cabeçalhos a mais que a lista pede para o stream em [url] (o cookie, o Origin…); vazio se nenhum. */
    fun headersFor(url: String): Map<String, String> = headers[DashManifest.fetchUrl(url)].orEmpty()

    /** Junta chaves (KID → chave, em hexadecimal). */
    fun learn(found: Map<String, String>) {
        if (found.isEmpty() || found.all { keys[it.key] == it.value }) return
        keys = keys + found
    }

    /**
     * A chave do KID [kid] (hexadecimal) para um stream com [drm]: a que se conhece, ou, se a lista só deu uma chave,
     * essa (há listas com o KID errado ou sem ele).
     */
    fun key(kid: String, drm: StreamDrm?): ByteArray? {
        val hex = keys[kid] ?: drm?.keys?.get(kid) ?: drm?.keys?.values?.singleOrNull() ?: return null
        return M3uStreamProps.hex(hex).takeIf { it.size == 16 }
    }

    /** O servidor de licenças ClearKey a pedir pelas chaves que faltam (null: não há, ou não é ClearKey). */
    fun licenseUrl(drm: StreamDrm?): String? = drm?.takeIf { it.isClearKey }?.licenseUrl

    /** O corpo do pedido ao servidor de licenças ClearKey pelos [kids]. */
    fun licenseRequest(kids: Collection<String>): String = M3uStreamProps.licenseRequest(kids)

    /** A resposta do servidor de licenças: as chaves que trouxe ficam conhecidas; quantas eram. */
    fun licenseResponse(json: String): Int {
        val found = M3uStreamProps.licenseKeys(json)
        learn(found)
        return found.size
    }

    /** Porque é que um stream cifrado não se toca, para dizer ao utilizador (o sistema de DRM do canal). */
    fun why(drm: StreamDrm?): String = when (drm?.system) {
        StreamDrm.WIDEVINE -> "Widevine DRM"
        StreamDrm.PLAYREADY -> "PlayReady DRM"
        StreamDrm.CLEARKEY -> "ClearKey without the right key"
        else -> "DRM without a key in the playlist"
    }

    private const val MAX_STREAMS = 2000
}

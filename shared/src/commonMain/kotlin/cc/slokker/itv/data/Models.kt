package cc.slokker.itv.data

// Os modelos que o núcleo partilhado lê e devolve (as listas M3U, o catch-up): os mesmos campos na Apple (GoatCore
// Models.swift), que os converte nos seus.

/** O grupo dos canais sem grupo (como o site). */
const val OTHERS_GROUP = "Outros"

data class Category(val id: String, val name: String)

/**
 * Um canal dentro de uma lista.
 *
 * [uid] é a posição na lista (única, usada como chave nas listas da UI).
 * [key] é o `channelKey` sincronizado com a conta (favoritos, último canal).
 */
data class Channel(
    val uid: Int,
    val key: String,
    val num: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    val streamId: String? = null,
    val url: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    /** Id do canal no guia XMLTV (`tvg-id` / `epg_channel_id`). */
    val epgId: String? = null,
    /** Dias de catch-up que o servidor guarda deste canal (0 = sem catch-up). */
    val archiveDays: Int = 0,
    /**
     * M3U: como se pede o catch-up (ver M3uCatchup: default, append, shift, flussonic, flussonic-ts, xc); null nos
     * outros tipos, que têm a sua forma. Fica null nas listas guardadas antes desta versão (até se atualizarem).
     */
    val catchupType: String? = null,
    /** M3U: o modelo do URL do catch-up (`catchup-source`, do canal ou do `#EXTM3U`), nos tipos default e append. */
    val catchupSource: String? = null,
)

/** Uma forma de abrir um canal. O player tenta-as por ordem (fallback ts ⇄ m3u8). */
data class StreamCandidate(val url: String, val format: String) {
    val isHls: Boolean get() = format == StreamFormat.HLS

    /** Uma gravação no disco (`file://`): não é uma ligação ao fornecedor. */
    val isLocal: Boolean get() = url.startsWith("file:")
}

object StreamFormat {
    const val TS = "ts"
    const val HLS = "m3u8"
}

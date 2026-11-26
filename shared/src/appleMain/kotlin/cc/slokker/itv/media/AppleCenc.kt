package cc.slokker.itv.media

import platform.Foundation.NSData

/**
 * O [Cenc] de uma faixa do HlsReader (Apple), numa chamada só pela ponte. O pedido ao servidor de licenças é do Swift
 * (a rede é dele): antes de [process], [missingKids] diz os KID de um `moov` novo que ainda não têm chave; o Swift
 * pede-as ([licenseUrl], [licenseBody], [licenseHeaders]) e entrega a resposta em [licenseAnswer].
 */
class AppleCenc(url: String) {
    private val drm = StreamKeys.drmFor(url)
    private var asked: Set<String> = emptySet()
    private val cenc = Cenc({ StreamKeys.key(it, drm) })

    /** O servidor de licenças ClearKey do canal, ou null. */
    val licenseUrl: String? get() = StreamKeys.licenseUrl(drm)

    /** Os cabeçalhos que a lista pede para o servidor de licenças. */
    val licenseHeaders: Map<String, String> get() = drm?.licenseHeaders.orEmpty()

    /** Os KID do `moov` em [data] que ainda não têm chave (vazio sem `moov`, ou com as chaves todas). */
    fun missingKids(data: NSData): List<String> {
        val b = AppleMedia.bytes(data)
        if (!Cenc.hasMoov(b)) return emptyList()
        var kids: Set<String> = emptySet()
        // Numa cópia: só se quer saber o que falta.
        Cenc({ StreamKeys.key(it, drm) }, { kids = it }).process(b)
        asked = kids
        return kids.toList()
    }

    /** O pedido ao servidor de licenças pelos KID de [missingKids]. */
    fun licenseBody(): String = StreamKeys.licenseRequest(asked)

    /** A resposta do servidor de licenças; quantas chaves trouxe. */
    fun licenseAnswer(json: String): Int = StreamKeys.licenseResponse(json)

    /** [data] decifrado (igual, se não vier cifrado). */
    fun process(data: NSData): NSData {
        val b = AppleMedia.bytes(data)
        cenc.process(b)
        return AppleMedia.data(b)
    }

    /** Porque é que o stream não se toca (cifrado sem a chave, ou um esquema que não se decifra), ou null. */
    fun problem(): String? = cenc.unsupported ?: if (cenc.encrypted && cenc.missing.isNotEmpty()) "DRM: ${StreamKeys.why(drm)}" else null
}

package cc.slokker.itv.data.m3u

import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.StreamFormat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * O catch-up das listas M3U, como os outros players o leem (a referência é o README e o código do Kodi IPTV Simple;
 * ver DECISIONS, fase 6). Na lista, por canal ou no `#EXTM3U` para todos:
 * - `catchup` (ou `catchup-type`): default, append, shift, flussonic (fs, flussonic-hls, flussonic-ts) ou xc;
 * - `catchup-source`: o URL (default) ou o que se junta ao do canal (append), com marcadores (ver [expand]);
 * - `catchup-days` (ou `timeshift`, `tvg-rec`): quantos dias o servidor guarda.
 * Só os dias, sem tipo (`timeshift="3"`, `tvg-rec="3"`), é o "shift" (`?utc=…&lutc=…`), ou o xc se o canal for de um
 * painel Xtream (`/live/U/P/123.ts`). O tipo vod (que precisa do `catchup-id` do guia) não se suporta.
 */
object M3uCatchup {
    const val DEFAULT = "default"
    const val APPEND = "append"
    const val SHIFT = "shift"
    const val FLUSSONIC = "flussonic"
    const val FLUSSONIC_TS = "flussonic-ts"
    const val XC = "xc"

    /** Os dias quando a lista dá o tipo mas não os dias (o "Catchup days" do Kodi, que vem a 5). */
    const val DEFAULT_DAYS = 5

    private const val MAX_DAYS = 31

    /** Os nomes que as listas dão aos tipos. */
    private val TYPES = mapOf(
        "default" to DEFAULT, "append" to APPEND, "shift" to SHIFT, "timeshift" to SHIFT,
        "flussonic" to FLUSSONIC, "flussonic-hls" to FLUSSONIC, "flussonic-ts" to FLUSSONIC_TS, "fs" to FLUSSONIC_TS,
        "xc" to XC, "xtream" to XC, "xtreamcodes" to XC,
    )

    /** O catch-up de um canal: o tipo, o modelo do URL e os dias. */
    class Spec(val type: String, val source: String?, val days: Int)

    /**
     * O catch-up do canal de [url] com os atributos [attrs] do `#EXTINF` (os do `#EXTM3U`, [header], valem para os
     * canais que não dizem), ou null se não tiver (ou se for de um tipo que não se sabe fazer).
     */
    fun spec(attrs: Map<String, String>, header: Map<String, String>, url: String): Spec? {
        fun value(vararg names: String): String {
            for (from in listOf(attrs, header)) for (n in names) from[n]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            return ""
        }
        val named = value("catchup", "catchup-type").lowercase()
        val source = value("catchup-source")
        val days = value("catchup-days", "timeshift", "tvg-rec").toDoubleOrNull()?.toInt() ?: 0
        val type = when {
            named.isNotEmpty() -> TYPES[named] ?: return null
            source.isNotEmpty() -> DEFAULT
            // Como o QiTV: um painel Xtream reconhece-se pelo id numérico no fim do URL.
            days > 0 -> if (xtreamParts(url)?.get(3)?.all(Char::isDigit) == true) XC else SHIFT
            else -> return null
        }
        // O default e o append são o modelo: sem ele não há URL (no Kodi caem no "formato da consulta" das definições).
        if ((type == DEFAULT || type == APPEND) && (source.isEmpty() || expand(source, 0, 0, 0, TimeZone.UTC) == null)) return null
        // O xc e o Flussonic fazem-se a partir do URL do canal: um URL que não é desses não tem catch-up.
        if (type == XC && xtreamParts(url) == null) return null
        if ((type == FLUSSONIC || type == FLUSSONIC_TS) && flussonic(url, type == FLUSSONIC_TS) == null) return null
        return Spec(type, source.takeIf { it.isNotEmpty() && (type == DEFAULT || type == APPEND) }, (if (days > 0) days else DEFAULT_DAYS).coerceAtMost(MAX_DAYS))
    }

    /**
     * Ao minuto ou ao segundo: o xc e os modelos só com {Y}{m}{d}{H}{M} pedem minutos certos (o vídeo começa no
     * minuto), os outros o segundo pedido.
     */
    fun precisionMs(channel: Channel): Long = when (channel.catchupType) {
        XC -> 60_000L
        DEFAULT, APPEND -> if (secondsPrecise(channel.catchupSource.orEmpty())) 1_000L else 60_000L
        else -> 1_000L
    }

    /**
     * As tentativas para ver [channel] de [startS] a [endS] (segundos Unix, já com a correção da hora), agora [nowS].
     * As datas ({Y}, {H}…, o xc) vão na hora de [zone] (a deste aparelho, como no Kodi). Vazia se o URL do canal não
     * servir para o tipo.
     */
    fun candidates(channel: Channel, startS: Long, endS: Long, nowS: Long, zone: TimeZone = TimeZone.currentSystemDefault()): List<StreamCandidate> {
        val url = channel.url ?: return emptyList()
        val urls: List<String> = when (channel.catchupType) {
            DEFAULT -> listOfNotNull(channel.catchupSource?.let { expand(it, startS, endS, nowS, zone) })
            APPEND -> listOfNotNull(channel.catchupSource?.let { expand(it, startS, endS, nowS, zone) }?.let { url + it })
            SHIFT -> listOf(url + (if ('?' in url) "&" else "?") + "utc=$startS&lutc=$nowS")
            FLUSSONIC, FLUSSONIC_TS -> listOfNotNull(flussonic(url, channel.catchupType == FLUSSONIC_TS)?.let { expand(it, startS, endS, nowS, zone) })
            XC -> xtream(url, startS, endS, zone)
            else -> emptyList()
        }.filter { it.startsWith("http://") || it.startsWith("https://") }
        return urls.flatMap { formats(it, url) }
    }

    /**
     * [template] com os marcadores preenchidos, ou null se tiver algum que não se conhece (o pedido sairia errado):
     * - {utc} ${start} {start}: o início; {utcend} ${end} {end}: o fim; {lutc} ${now} {now} ${timestamp}: agora
     *   (segundos Unix);
     * - {duration} ${duration}: a duração em segundos, {duration:60} dividida (em minutos); {offset} ${offset}
     *   {offset:N}: há quanto tempo começou;
     * - {Y} {m} {d} {H} {M} {S}: o início; {utc:Y-m-d:H-M} ${start:YmdHMS} (e os outros nomes): uma hora escrita
     *   com essas letras.
     */
    fun expand(template: String, startS: Long, endS: Long, nowS: Long, zone: TimeZone): String? {
        var unknown = false
        val out = PLACEHOLDER.replace(template) { m ->
            val name = m.groupValues[1]
            val arg = m.groups[2]?.value
            val key = name.lowercase()
            val moment = when (key) {
                "utc", "start" -> startS
                "utcend", "end" -> endS
                "lutc", "now", "timestamp" -> nowS
                else -> null
            }
            when {
                arg == null && name.length == 1 && name[0] in PARTS -> format(startS, name, zone)
                moment != null && arg == null -> moment.toString()
                moment != null -> format(moment, arg!!, zone)
                key == "duration" || key == "offset" -> {
                    val seconds = (if (key == "duration") endS - startS else nowS - startS).coerceAtLeast(0)
                    val divisor = arg?.toLongOrNull()
                    when {
                        arg == null -> seconds.toString()
                        divisor != null && divisor > 0 -> (seconds / divisor).toString()
                        else -> { unknown = true; m.value }
                    }
                }
                else -> { unknown = true; m.value }
            }
        }
        return out.takeIf { !unknown && '{' !in it }
    }

    private val PLACEHOLDER = Regex("""\$?\{([A-Za-z]+)(?::([^{}]*))?\}""")
    private const val PARTS = "YmdHMS"

    /** [seconds] escrito com as letras de [pattern] (Y m d H M S; o resto fica como está), na hora de [zone]. */
    private fun format(seconds: Long, pattern: String, zone: TimeZone): String {
        val t = Instant.fromEpochSeconds(seconds).toLocalDateTime(zone)
        // Algarismos latinos sempre (também com a app em árabe ou hindi).
        fun two(n: Int) = n.toString().padStart(2, '0')
        return pattern.map { ch ->
            when (ch) {
                'Y' -> t.year.toString().padStart(4, '0')
                'm' -> two(t.month.number)
                'd' -> two(t.day)
                'H' -> two(t.hour)
                'M' -> two(t.minute)
                'S' -> two(t.second)
                else -> ch.toString()
            }
        }.joinToString("")
    }

    /** O modelo diz o segundo (um instante Unix, um {S}, um "há quanto tempo"), ou só o minuto? */
    private fun secondsPrecise(template: String): Boolean = PLACEHOLDER.findAll(template).any { m ->
        val key = m.groupValues[1].lowercase()
        val arg = m.groups[2]?.value
        when {
            m.groupValues[1] == "S" || key == "offset" -> true
            key == "utc" || key == "start" -> arg == null || 'S' in arg
            else -> false
        }
    }

    /**
     * O modelo do Flussonic feito a partir do URL do canal (como o Kodi): `…/canal/mpegts` (e os .ts) →
     * `…/canal/timeshift_abs-{utc}.ts`; `…/canal/index.m3u8` (ou mono, video…) → `…/canal/index-{utc}-{duration}.m3u8`,
     * a lista do arquivo com princípio e fim (o Kodi usa o "timeshift_rel", que corre como um direto: aqui a posição no
     * programa conta-se desde o início do vídeo). Outro URL: o último bocado troca-se pelo do arquivo, TS com o tipo
     * flussonic-ts/fs e HLS com o flussonic (como o Kodi). A query do canal (o token) fica.
     */
    private fun flussonic(url: String, ts: Boolean): String? {
        val m = URL_PARTS.matchEntire(url) ?: return null
        val (origin, path, query) = m.destructured
        val dir = path.substringBeforeLast('/', "")
        val last = path.substringAfterLast('/')
        return when {
            last.endsWith(".m3u8") -> "$origin$dir/${last.removeSuffix(".m3u8").ifEmpty { "index" }}-{utc}-{duration}.m3u8$query"
            last == "mpegts" || last.endsWith(".ts") -> "$origin$dir/timeshift_abs-{utc}.ts$query"
            dir.isEmpty() -> if (ts) "$origin$path/timeshift_abs-{utc}.ts$query" else "$origin$path/index-{utc}-{duration}.m3u8$query"
            ts -> "$origin$dir/timeshift_abs-{utc}.ts$query"
            else -> "$origin$dir/index-{utc}-{duration}.m3u8$query"
        }
    }

    private val URL_PARTS = Regex("""(https?://[^/?#]+)(/[^?#]*)(\?[^#]*)?""")

    /** O URL de um canal de um painel Xtream (`…/live/U/P/123.ts` ou `…/U/P/123`): a origem, U, P, o id e a extensão. */
    private fun xtreamParts(url: String): List<String>? =
        XC_URL.matchEntire(url)?.groupValues?.drop(1)

    // A do Kodi: ^(http[s]?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/\.]+)(\.m3u[8]?)?$ (aqui também .ts).
    private val XC_URL = Regex("""(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/.?]+)(\.m3u8?|\.ts)?""")

    /** O catch-up xc: o `/timeshift/` do Xtream, ao minuto, e o `timeshift.php` (os servidores aceitam um ou os dois). */
    private fun xtream(url: String, startS: Long, endS: Long, zone: TimeZone): List<String> {
        val (origin, user, pass, id, ext) = xtreamParts(url) ?: return emptyList()
        val minutes = ((endS - startS + 59) / 60).coerceAtLeast(1)
        val start = format(startS, "Y-m-d:H-M", zone)
        val file = id + if (ext.startsWith(".m3u")) ".m3u8" else ".ts"
        return listOf(
            "$origin/timeshift/$user/$pass/$minutes/$start/$file",
            "$origin/streaming/timeshift.php?username=$user&password=$pass&stream=$id&start=$start&duration=$minutes",
        )
    }

    /** Como abrir [url]: pela extensão; sem ela, primeiro o formato do canal ([live]) e depois o outro. */
    private fun formats(url: String, live: String): List<StreamCandidate> {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val livePath = live.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> listOf(StreamCandidate(url, StreamFormat.HLS))
            path.endsWith(".ts") || path.endsWith("/mpegts") || path.endsWith(".mpg") -> listOf(StreamCandidate(url, StreamFormat.TS))
            livePath.endsWith(".m3u8") -> listOf(StreamCandidate(url, StreamFormat.HLS), StreamCandidate(url, StreamFormat.TS))
            else -> listOf(StreamCandidate(url, StreamFormat.TS), StreamCandidate(url, StreamFormat.HLS))
        }
    }
}

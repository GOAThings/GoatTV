package cc.slokker.itv.data.xtream

import cc.slokker.itv.data.BadJson
import cc.slokker.itv.data.CatchupRules
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.InputRules
import cc.slokker.itv.data.JsonRows
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/** A conta Xtream, do `player_api.php` sem ação. */
class XtreamLoginInfo(
    val auth: String?,
    val status: String?,
    val activeConnections: Int?,
    val maxConnections: Int?,
    /** Os formatos que a conta pode pedir ("ts", "m3u8"…), em minúsculas. */
    val allowedFormats: Set<String>,
    /** O fuso do servidor, para o catch-up. */
    val timezone: String?,
)

/**
 * As respostas da API Xtream (`player_api.php`), as mesmas no Android e na Apple. Os pedidos são de cada plataforma;
 * as listas grandes (canais) leem-se aos poucos com [JsonRows]. Uma resposta que não é o JSON esperado dá [BadJson].
 */
object XtreamParse {
    private val CATEGORY_KEYS = setOf("category_id", "category_name")
    private val STREAM_KEYS = setOf("num", "name", "stream_id", "stream_icon", "category_id", "epg_channel_id", "tv_archive", "tv_archive_duration")

    /** `get_live_categories`. Sem id não é um grupo; sem nome fica o id. */
    @Throws(BadJson::class)
    fun categories(input: (CharArray) -> Int): List<Category> =
        JsonRows(input, CATEGORY_KEYS).read().orEmpty().mapNotNull { c ->
            val id = c["category_id"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Category(id, c["category_name"]?.trim().orEmpty().ifEmpty { id })
        }

    /** `get_live_streams`, com o nome a dar aos canais sem nome ([channelName] do id). */
    @Throws(BadJson::class)
    fun streams(input: (CharArray) -> Int, channelName: (String) -> String): List<Channel> {
        val rows = JsonRows(input, STREAM_KEYS).read().orEmpty()
        val out = ArrayList<Channel>(rows.size)
        for ((i, s) in rows.withIndex()) {
            val id = s["stream_id"]?.takeIf { it.isNotBlank() } ?: continue
            out += Channel(
                uid = i,
                key = id,
                num = s["num"]?.let(InputRules::int) ?: (i + 1),
                name = s["name"]?.trim().orEmpty().ifEmpty { channelName(id) },
                logo = s["stream_icon"]?.takeIf { it.isNotBlank() },
                categoryId = s["category_id"].orEmpty(),
                streamId = id,
                epgId = s["epg_channel_id"]?.trim()?.takeIf { it.isNotEmpty() },
                archiveDays = CatchupRules.xtreamArchiveDays(s["tv_archive"], s["tv_archive_duration"]),
            )
        }
        return out
    }

    @Throws(BadJson::class)
    fun categoriesText(text: String): List<Category> = categories(reader(text))

    @Throws(BadJson::class)
    fun streamsText(text: String, channelName: (String) -> String): List<Channel> = streams(reader(text), channelName)

    /** A conta, ou null se a resposta não a tiver (o servidor não respondeu como Xtream). */
    fun login(text: String): XtreamLoginInfo? {
        val json = parse(text) as? JsonObject ?: return null
        val info = json["user_info"] as? JsonObject ?: return null
        val formats = when (val f = info["allowed_output_formats"]) {
            is JsonArray -> f.mapNotNull { text(it) }
            is JsonPrimitive -> text(f)?.split(',').orEmpty()
            else -> emptyList()
        }
        return XtreamLoginInfo(
            auth = text(info["auth"]),
            status = text(info["status"]),
            activeConnections = text(info["active_cons"])?.let(InputRules::int),
            maxConnections = text(info["max_connections"])?.let(InputRules::int),
            allowedFormats = formats.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
            timezone = text((json["server_info"] as? JsonObject)?.get("timezone"))?.takeIf { it.isNotBlank() },
        )
    }

    /** `get_short_epg`: os títulos vêm em Base64. Por ordem de início. */
    fun shortEpg(text: String): List<EpgEntry> {
        val listings = ((parse(text) as? JsonObject)?.get("epg_listings") as? JsonArray) ?: return emptyList()
        return listings.mapNotNull { l ->
            val o = l as? JsonObject ?: return@mapNotNull null
            val start = text(o["start_timestamp"])?.toLongOrNull()?.let(InputRules::epochMs) ?: return@mapNotNull null
            val stop = text(o["stop_timestamp"])?.toLongOrNull()?.let(InputRules::epochMs) ?: return@mapNotNull null
            val raw = text(o["title"]).orEmpty()
            EpgEntry((InputRules.base64Text(raw) ?: raw).trim(), start, stop)
        }.sortedBy { it.startMs }
    }

    private fun reader(text: String): (CharArray) -> Int {
        var at = 0
        return { buf ->
            if (at >= text.length) {
                -1
            } else {
                val n = minOf(buf.size, text.length - at)
                for (i in 0 until n) buf[i] = text[at + i]
                at += n
                n
            }
        }
    }

    private fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text.removePrefix("\uFEFF"))
    } catch (e: Exception) {
        null
    }

    /** Um valor simples como texto (os servidores misturam números e textos); null para null, listas e objetos. */
    private fun text(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
}

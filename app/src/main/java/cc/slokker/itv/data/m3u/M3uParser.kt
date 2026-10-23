package cc.slokker.itv.data.m3u

import cc.slokker.itv.ui.i18n.I18n
import cc.slokker.itv.data.Category
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.PlaylistContent
import java.security.MessageDigest

/**
 * Parser M3U/M3U8 em streaming: lê linha a linha, por isso aguenta listas com dezenas de MB.
 * Só guarda canais de TV em direto; ignora filmes e séries.
 */
object M3uParser {

    class Result(val categories: List<Category>, val channels: List<Channel>, val epgUrl: String?)

    private val ATTR = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parse(lines: Sequence<String>): Result {
        val channels = ArrayList<Channel>()
        val groups = LinkedHashSet<String>()
        val usedKeys = HashSet<String>()
        var epgUrl: String? = null

        var pending: Extinf? = null
        var group: String? = null
        var userAgent: String? = null
        var referrer: String? = null

        for (raw in lines) {
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val attrs = attributes(line)
                    epgUrl = (attrs["x-tvg-url"] ?: attrs["url-tvg"])
                        ?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF") -> {
                    pending = parseExtinf(line)
                    group = null
                    userAgent = null
                    referrer = null
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=") -> userAgent = opt.substringAfter('=').trim()
                        opt.startsWith("http-referrer=") -> referrer = opt.substringAfter('=').trim()
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pending ?: continue
                    pending = null
                    if (isVod(line)) continue

                    // group-title; vazio ou em falta, o último #EXTGRP deste canal; sem nenhum, "Outros" (como o site).
                    val category = info.attrs["group-title"]?.trim()?.takeIf { it.isNotEmpty() }
                        ?: group?.trim()?.takeIf { it.isNotEmpty() }
                        ?: PlaylistContent.OTHERS
                    groups += category

                    // channelKey: tvg-id; se faltar (ou se repetir), hash curto do URL.
                    val tvgId = info.attrs["tvg-id"]?.trim().orEmpty()
                    val key = if (tvgId.isNotEmpty() && usedKeys.add(tvgId)) tvgId else urlKey(line)
                    usedKeys += key

                    val index = channels.size
                    channels += Channel(
                        uid = index,
                        key = key,
                        num = index + 1,
                        name = info.name.ifEmpty { info.attrs["tvg-name"].orEmpty() }.ifEmpty { I18n.s.channelName((index + 1).toString()) },
                        logo = info.attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        categoryId = category,
                        url = line,
                        userAgent = userAgent,
                        referrer = referrer,
                        epgId = tvgId.ifEmpty { null },
                    )
                }
            }
        }
        return Result(groups.map { Category(it, it) }, channels, epgUrl)
    }

    private class Extinf(val attrs: Map<String, String>, val name: String)

    private fun parseExtinf(line: String): Extinf {
        // O nome vem depois da primeira vírgula fora de aspas (os atributos podem ter vírgulas).
        var inQuotes = false
        var comma = -1
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) { comma = i; break }
            }
        }
        val head = if (comma >= 0) line.substring(0, comma) else line
        val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
        return Extinf(attributes(head), name)
    }

    private fun attributes(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Heurística para filmes e séries: `/movie/`, `/series/`, `.mp4` ou `.mkv`. */
    fun isVod(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return "/movie/" in path || "/series/" in path || path.endsWith(".mp4") || path.endsWith(".mkv")
    }

    fun urlKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return "u" + digest.take(6).joinToString("") { "%02x".format(it) }
    }
}

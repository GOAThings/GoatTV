package cc.slokker.itv.data

import kotlinx.serialization.json.JsonPrimitive

/** Um grupo como o ecrã o mostra, com os seus canais pela ordem do ecrã. */
class PhoneGroup(val category: Category, val channels: List<Channel>)

/** O que as definições escondem ou bloqueiam ("lista:grupo" e "lista:canal", como nas [Prefs]). */
class PhoneRules(val hiddenGroups: Set<String>, val hiddenChannels: Set<String>, val lockedGroups: Set<String>) {
    constructor(prefs: Prefs) : this(prefs.hiddenGroups, prefs.hiddenChannels, prefs.lockedGroups)

    /** Bloqueado nas definições, desbloqueado ou não com o PIN (como o Audience.PERSISTENT do catálogo do Android). */
    fun isLockedSetting(playlistId: String, groupId: String) = "$playlistId:$groupId" in lockedGroups

    fun isHidden(playlistId: String, groupId: String) = "$playlistId:$groupId" in hiddenGroups

    /** O telemóvel pode ver e abrir [channel]: nem escondido (ele ou o grupo) nem bloqueado. */
    fun allowed(playlistId: String, channel: Channel): Boolean =
        !isLockedSetting(playlistId, channel.categoryId) && !isHidden(playlistId, channel.categoryId) &&
            "$playlistId:${channel.key}" !in hiddenChannels
}

/**
 * Os canais que o GoatRemote vê (GET /channels e GET /now da porta dos telemóveis). Cada plataforma tira o estado na
 * thread principal e o JSON é montado aqui, fora dela.
 *
 * Nunca aparecem grupos ou canais escondidos, nem grupos bloqueados pelo controlo parental tal como está nas
 * definições, mesmo que tenham sido desbloqueados com o PIN nesta sessão: o telemóvel não pede PIN.
 */
object PhoneList {
    const val MAX_BYTES = 8 * 1024 * 1024

    /**
     * O JSON do /channels, com no máximo [maxBytes] em UTF-8: se não couber tudo, os canais cortam-se e vai
     * `"truncated": true`. [firstUid] diz, para cada channelKey, o uid do primeiro canal da lista com essa chave (é esse
     * que o /play e o /logo usam); [current] é o canal a ver, já procurado pela chave. [logos] são os logótipos que
     * substituem os da lista (channelKey → URL, "" = nenhum), como no ecrã.
     */
    fun json(
        playlistId: String,
        playlistName: String,
        groups: List<PhoneGroup>,
        rules: PhoneRules,
        favorites: Set<String>,
        current: Channel?,
        logos: Map<String, String>,
        firstUid: Map<String, Int>,
        maxBytes: Int = MAX_BYTES,
    ): String {
        val seen = HashSet<String>()
        val shown = groups
            .filter { !rules.isHidden(playlistId, it.category.id) && !rules.isLockedSetting(playlistId, it.category.id) }
            .map { group ->
                // Cada canal uma só vez, no seu grupo, e só o que o /play abriria com essa chave.
                PhoneGroup(group.category, group.channels.filter { ch ->
                    ch.categoryId == group.category.id && rules.allowed(playlistId, ch) && firstUid[ch.key] == ch.uid && seen.add(ch.key)
                })
            }
            .filter { it.channels.isNotEmpty() }

        val currentKey = current?.takeIf { rules.allowed(playlistId, it) }?.key
        val out = StringBuilder()
        out.append("{\"playlist\":{\"id\":").append(quote(playlistId)).append(",\"name\":").append(quote(playlistName)).append("},")
        out.append("\"current\":").append(currentKey?.let(::quote) ?: "null").append(",\"groups\":[")
        shown.forEachIndexed { i, group ->
            if (i > 0) out.append(',')
            out.append("{\"id\":").append(quote(group.category.id)).append(",\"name\":").append(quote(group.category.name)).append('}')
        }
        // O objeto fica aberto para os canais entrarem um a um, a contar os bytes.
        out.append("],\"channels\":[")
        val budget = maxBytes - utf8Length(out) - TRUNCATED_END.length
        var used = 0
        var count = 0
        var truncated = false
        loop@ for (group in shown) {
            for (ch in group.channels) {
                val item = "{\"key\":${quote(ch.key)},\"num\":${ch.num},\"name\":${quote(ch.name)},\"group\":${quote(group.category.id)}," +
                    "\"logo\":${logoUrl(ch, logos) != null},\"favorite\":${ch.key in favorites}}"
                val need = utf8Length(item) + if (count > 0) 1 else 0
                if (used + need > budget) {
                    truncated = true
                    break@loop
                }
                if (count > 0) out.append(',')
                out.append(item)
                used += need
                count++
            }
        }
        out.append(if (truncated) TRUNCATED_END else END)
        return out.toString()
    }

    /**
     * O JSON do /now: o canal a ver e, com guia, o programa e o seguinte. É pequeno, para o telemóvel perguntar a
     * cada poucos segundos sem pedir a lista inteira.
     */
    fun nowJson(current: String?, now: EpgEntry?, next: EpgEntry?): String {
        fun programme(e: EpgEntry?) = e?.let { "{\"title\":${quote(it.title)},\"start\":${it.startMs},\"end\":${it.endMs}}" } ?: "null"
        return "{\"current\":${current?.let(::quote) ?: "null"},\"now\":${programme(now)},\"next\":${programme(next)}}"
    }

    private const val END = "]}"
    private const val TRUNCATED_END = "],\"truncated\":true}"

    /** O logótipo do canal tal como o ecrã o mostra (o escolhido, ou o da lista), se for um endereço http(s). */
    fun logoUrl(channel: Channel, logos: Map<String, String>): String? =
        (logos[channel.key] ?: channel.logo)?.trim()?.takeIf(::isHttpUrl)

    /** Um endereço http(s) com servidor, sem espaços pelo meio. */
    fun isHttpUrl(text: String): Boolean {
        val lower = text.lowercase()
        val rest = when {
            lower.startsWith("http://") -> text.substring(7)
            lower.startsWith("https://") -> text.substring(8)
            else -> return false
        }
        if (rest.any { it.isWhitespace() || it.code < 0x20 }) return false
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith('[')) authority.substringBefore(']').drop(1) else authority.substringBefore(':')
        val port = if (authority.startsWith('[')) authority.substringAfter(']', "").removePrefix(":") else authority.substringAfter(':', "")
        return host.isNotEmpty() && (port.isEmpty() || (port.length <= 5 && port.all { it in '0'..'9' } && port.toInt() in 1..65535))
    }

    /**
     * A chave de um caminho (`/logo/<chave>`), que o telemóvel manda em percent-encoding. O pedido foi lido byte a
     * byte (cada byte um carácter), por isso os bytes de fora do ASCII também contam. null se estiver mal escrita.
     */
    fun decodeKey(encoded: String): String? {
        val bytes = ArrayList<Byte>(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val char = encoded[i]
            if (char == '%') {
                if (i + 2 >= encoded.length) return null
                val high = hex(encoded[i + 1])
                val low = hex(encoded[i + 2])
                if (high < 0 || low < 0) return null
                bytes.add((high * 16 + low).toByte())
                i += 3
            } else {
                if (char.code > 0xFF) return null
                bytes.add(char.code.toByte())
                i++
            }
        }
        // UTF-8 a sério: sequências partidas ou inválidas recusam a chave.
        val key = try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            return null
        }
        return key.takeIf { it.isNotEmpty() }
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Um texto em JSON (o resto do UTF-8 vai tal como está, e < > & também: é JSON para uma app, não para HTML). */
    fun quote(s: String): String = JsonPrimitive(s).toString()

    private fun utf8Length(s: CharSequence): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            n += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}

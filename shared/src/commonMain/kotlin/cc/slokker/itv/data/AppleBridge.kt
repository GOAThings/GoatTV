package cc.slokker.itv.data

/**
 * A porta da Apple (GoatCore em Swift) para [ChannelMatch], [ChannelVariants] e [ChannelNames]: só tipos que chegam
 * ao Swift sem surpresas (as classes Apple* de topo, sem sealed, sem lambdas, sem Int?: "nenhum" é -1). O Swift põe
 * por cima os seus tipos de sempre (GoatCore ChannelMatch.swift), por isso o resto da app não muda.
 */
object AppleBridge {
    fun parse(name: String): AppleParsed = ChannelMatch.parse(name).let { AppleParsed(it.base, it.country, it.quality, it.shift) }

    fun epgKey(id: String?): String? = ChannelMatch.epgKey(id)

    fun levenshtein(a: String, b: String): Int = ChannelMatch.levenshtein(a, b)

    fun groupKey(name: String): String = ChannelMatch.groupKey(name)

    fun find(matcher: AppleMatcher, name: String, epgId: String?, chosen: String?, group: String?): AppleResult =
        result(matcher.matcher.find(name, epgId, chosen, group))

    fun ranked(matcher: AppleMatcher, name: String, epgId: String?, limit: Int): List<Int> = matcher.matcher.ranked(name, epgId, limit)

    fun decide(key: String, name: String, epgId: String?, group: String?, matcher: AppleMatcher, chosen: String?, manual: Boolean): AppleResult =
        result(ChannelMatch.decide(ChannelMatch.Entry(key, name, epgId, group), matcher.matcher, chosen, manual))

    /** [ChannelMatch.failovers], com as chaves da lista principal em [mainKeys]. */
    fun failovers(
        key: String, name: String, epgId: String?, group: String?, matcher: AppleMatcher, chosen: List<String>?, manual: Boolean,
        variants: List<String>, mainKeys: Set<String>,
    ): List<String> =
        ChannelMatch.failovers(ChannelMatch.Entry(key, name, epgId, group), matcher.matcher, chosen, manual, variants, mainKeys::contains)

    fun review(main: AppleEntries, matcher: AppleMatcher, overrides: Map<String, List<String>>, manual: Boolean): List<AppleRow> =
        ChannelMatch.review(main.list, matcher.matcher, overrides, manual).map { r ->
            AppleRow(r.main, STATES.getValue(r.state), r.backup ?: -1, r.suggestion ?: -1, r.failovers)
        }

    /** [ChannelVariants.of] com as chaves e os nomes lado a lado. */
    fun variants(keys: List<String>, names: List<String>): Map<String, List<String>> = ChannelVariants.of(keys.zip(names))

    /** [ChannelNames.apply] com as regras partidas em [finds] e [replaces] (lado a lado). */
    fun names(name: String, finds: List<String>, replaces: List<String>): String =
        ChannelNames.apply(name, finds.zip(replaces) { f, r -> NameRule(f, r) })

    private val STATES = mapOf(
        ChannelMatch.State.AUTO to "auto",
        ChannelMatch.State.CHOSEN to "chosen",
        ChannelMatch.State.OFF to "off",
        ChannelMatch.State.UNSURE to "unsure",
        ChannelMatch.State.MISSING to "missing",
        ChannelMatch.State.NOT_SET to "not_set",
    )

    private fun result(r: ChannelMatch.Result): AppleResult = when (r) {
        is ChannelMatch.Result.Match -> AppleResult("match", r.index)
        is ChannelMatch.Result.Unsure -> AppleResult("unsure", r.count)
        ChannelMatch.Result.Missing -> AppleResult("missing", 0)
    }
}

/** Um nome partido ([ChannelMatch.parse]). */
class AppleParsed(val base: String, val country: String?, val quality: Int, val shift: Int)

/** Os canais de uma lista, juntos um a um (uma lista de objetos com nulos não passa direita para o Swift). */
class AppleEntries {
    internal val list = ArrayList<ChannelMatch.Entry>()

    fun add(key: String, name: String, epgId: String?, group: String?) {
        list += ChannelMatch.Entry(key, name, epgId, group)
    }
}

/** Os canais de uma lista de reserva, prontos a procurar ([ChannelMatch.Matcher]). */
class AppleMatcher(entries: AppleEntries) {
    internal val matcher = ChannelMatch.Matcher(entries.list.toList())

    /** A posição do canal [key], ou -1. */
    fun indexOfKey(key: String): Int = matcher.indexOfKey(key) ?: -1
}

/** [ChannelMatch.Result]: [kind] "match" (o canal [value]), "unsure" ([value] candidatos) ou "missing". */
class AppleResult(val kind: String, val value: Int)

/** [ChannelMatch.ReviewRow]: [state] como o Swift (auto, chosen, off, unsure, missing, not_set); -1 = nenhum. */
class AppleRow(val main: Int, val state: String, val backup: Int, val suggestion: Int, val failovers: List<String>)

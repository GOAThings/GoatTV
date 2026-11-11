package cc.slokker.itv.data

/** Uma regra de limpar nomes: [find] passa a [replace] ("" = tirar), sem olhar a maiúsculas. */
data class NameRule(val find: String, val replace: String)

/**
 * Os nomes dos canais como se mostram: as regras da lista (Prefs.nameRules), pela ordem, e por cima o nome escolhido
 * canal a canal (Prefs.channelNames). Igual na Apple TV (GoatCore ChannelNames) e com a mesma tabela de testes
 * (channel_match.json, "names").
 */
object ChannelNames {
    private val SPACES = Regex("\\s+")

    /** [name] depois das [rules]: os espaços repetidos ficam um, sem espaços nas pontas. Se não sobrar nada, fica [name]. */
    fun apply(name: String, rules: List<NameRule>): String {
        if (rules.isEmpty()) return name
        var s = name
        for (r in rules) s = s.replace(r.find, r.replace, ignoreCase = true)
        s = s.replace(SPACES, " ").trim()
        return s.ifEmpty { name }
    }
}

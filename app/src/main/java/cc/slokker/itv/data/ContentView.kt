package cc.slokker.itv.data

/**
 * Os canais de uma lista como se mostram: os nomes limpos (Prefs.nameRules e Prefs.channelNames, ver ChannelNames) e,
 * com "Juntar variantes" (Prefs.mergeVariants), cada canal numa linha só (ver ChannelVariants). As chaves não mudam
 * (favoritos, recentes, failovers e o índice da conta continuam a apontar para os mesmos canais).
 */
object ContentView {
    data class Options(val merge: Boolean, val rules: List<NameRule>, val names: Map<String, String>) {
        val none: Boolean get() = !merge && rules.isEmpty() && names.isEmpty()
    }

    fun options(p: Prefs, playlistId: String): Options {
        val prefix = "$playlistId:"
        return Options(
            merge = playlistId in p.mergeVariants,
            rules = p.nameRules[playlistId].orEmpty(),
            names = p.channelNames.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) },
        )
    }

    fun apply(raw: PlaylistContent, o: Options): PlaylistContent {
        if (o.none) return raw
        val renamed = raw.channels.map { c ->
            val name = o.names[c.key] ?: ChannelNames.apply(c.name, o.rules)
            if (name == c.name) c else c.copy(name = name)
        }
        if (!o.merge) return PlaylistContent(raw.playlist, raw.categories, renamed, raw.account, raw.guideUrl, raw.fetchedAt)
        // As variantes pelos nomes da lista (as regras podiam tirar o que as distingue).
        val variants = ChannelVariants.of(raw.channels.map { it.key to it.name })
        if (variants.isEmpty()) return PlaylistContent(raw.playlist, raw.categories, renamed, raw.account, raw.guideUrl, raw.fetchedAt)
        val hiddenKeys = variants.values.flatten().toSet()
        val byKey = renamed.associateBy { it.key }
        // A linha que fica leva o guia e o logótipo de uma variante quando não tem os seus.
        val shown = renamed.filter { it.key !in hiddenKeys }.map { c ->
            val others = variants[c.key]?.mapNotNull { byKey[it] } ?: return@map c
            c.copy(
                epgId = c.epgId?.takeIf { it.isNotBlank() } ?: others.firstNotNullOfOrNull { it.epgId?.takeIf { e -> e.isNotBlank() } },
                logo = c.logo?.takeIf { it.isNotBlank() } ?: others.firstNotNullOfOrNull { it.logo?.takeIf { l -> l.isNotBlank() } },
            )
        }
        val used = shown.map { it.categoryId }.toSet()
        return PlaylistContent(
            raw.playlist, raw.categories.filter { it.id in used }, shown, raw.account, raw.guideUrl, raw.fetchedAt,
            hidden = renamed.filter { it.key in hiddenKeys },
            variants = variants,
        )
    }
}

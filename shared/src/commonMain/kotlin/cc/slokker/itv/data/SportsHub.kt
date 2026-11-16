package cc.slokker.itv.data

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Um canal de desporto da lista, com o guia dele (os programas, passado incluído). */
data class SportsSource(val key: String, val name: String, val group: String, val entries: List<EpgEntry>)

/**
 * Um canal onde dá um jogo. [rank]: 0 do país da pessoa, 1 da língua dela, 2 do país do jogo, 3 outro (é a ordem em
 * que se tenta abrir).
 */
data class SportsChannel(val key: String, val name: String, val country: String?, val rank: Int, val quality: Int)

/**
 * Um jogo (ou uma corrida) que o guia tem. Com equipas, [home] e [away] (pela ordem do título); numa corrida vêm vazios
 * e o nome está em [name]. [channels]: onde dá, pela ordem em que se tenta abrir.
 */
data class SportsGame(
    val id: String,
    val sport: String,
    val league: String,
    val home: String,
    val away: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val channels: List<SportsChannel>,
) {
    fun isLive(now: Long): Boolean = startMs <= now && now < endMs

    /** O nome para mostrar numa linha: "Benfica – Porto" ou o da corrida. */
    val title: String get() = if (home.isNotEmpty()) "$home – $away" else name
}

/**
 * O que já se percebeu dos títulos e dos grupos, de uma vez para a outra: o Sports Hub volta a fazer as contas a cada
 * minuto e com guia novo, e numa lista grande são dezenas de milhares de títulos. Um por lista aberta.
 */
class SportsMemory {
    internal val titles = HashMap<String, Any>()
    internal val groups = HashMap<String, String>()
    internal val sports = HashMap<String, String>()
}

/** O que o Sports Hub mostra: os jogos a decorrer (primeiro) e os próximos, e os canais de desporto. */
data class SportsHubResult(val games: List<SportsGame>, val channels: List<SportsChannel>, val sports: List<String>)

/**
 * Sports Hub, fase 1: os jogos saem do guia dos canais de desporto ("Benfica - Porto", "Liga: A vs B", "Lakers @
 * Celtics"), sem resultados (o guia não os tem). O mesmo jogo em vários canais é um só, com os canais ordenados pelo
 * país e língua da pessoa; as repetições (o mesmo jogo horas depois) e os resumos ficam de fora.
 */
object SportsHub {
    /**
     * Mostrar o Sports Hub (botões e tecla de cor) nas quatro apps. Desligado: está parado até se decidir avançar
     * (fase 2, resultados e logótipos); o código fica.
     */
    const val ENABLED = false

    const val FOOTBALL = "football"
    const val BASKETBALL = "basketball"
    const val AMFOOTBALL = "amfootball"
    const val HOCKEY = "hockey"
    const val BASEBALL = "baseball"
    const val TENNIS = "tennis"
    const val MOTOR = "motor"
    const val FIGHT = "fight"
    const val RUGBY = "rugby"
    const val CRICKET = "cricket"
    const val HANDBALL = "handball"
    const val VOLLEYBALL = "volleyball"
    const val GOLF = "golf"
    const val OTHER = "other"

    /** A ordem dos desportos nos filtros. */
    val SPORTS = listOf(FOOTBALL, BASKETBALL, TENNIS, MOTOR, AMFOOTBALL, HOCKEY, BASEBALL, FIGHT, RUGBY, CRICKET, HANDBALL, VOLLEYBALL, GOLF, OTHER)

    /** As cores dos círculos das equipas (ARGB), escolhidas pelo nome: a mesma equipa tem sempre a mesma. */
    val COLORS = listOf(0xFF1D4ED8, 0xFFB91C1C, 0xFF0F766E, 0xFF7C3AED, 0xFFC2410C, 0xFF15803D, 0xFFBE185D, 0xFF0369A1, 0xFF4D7C0F, 0xFF9333EA)

    /** Até quando se mostram os próximos jogos. */
    const val HORIZON_MS = 36 * 3600_000L

    /** O mesmo jogo noutro canal começa até tanto antes ou depois (os guias não batem certo ao minuto). */
    private const val SAME_AIRING_MS = 40 * 60_000L

    /** Mais tarde que isto depois da primeira vez é repetição. */
    private const val REPLAY_AFTER_MS = 3 * 3600_000L

    /** Canal de desporto pelo nome dele ou do grupo ("Sport TV 1", "PT | DESPORTO", "DAZN 2", "NBA League Pass"). */
    fun isSportsChannel(name: String, group: String): Boolean = sportsText(name) || sportsText(group)

    private fun sportsText(s: String): Boolean {
        val f = fold(s)
        if (SPORTS_STEMS.any { it in f }) return true
        val w = words(f)
        return w.any { it in SPORTS_WORDS }
    }

    /** Duas a três letras para o círculo da equipa: "Benfica" → "BEN", "Real Madrid" → "RM". */
    fun initials(team: String): String {
        val words = team.split(' ', '-', '.').filter { it.isNotEmpty() && fold(it) !in TEAM_NOISE && it.any(Char::isLetter) }
            .ifEmpty { team.split(' ').filter { it.isNotEmpty() } }
        if (words.isEmpty()) return "?"
        val letters = if (words.size == 1) words[0].filter(Char::isLetterOrDigit).take(3) else words.take(3).map { it.first { c -> c.isLetterOrDigit() } }.joinToString("")
        return letters.uppercase()
    }

    /** O índice em [COLORS] da equipa. */
    fun colorIndex(team: String): Int {
        var h = 0
        for (c in teamKey(team)) h = h * 31 + c.code
        return (h and 0x7fffffff) % COLORS.size
    }

    /**
     * Os jogos de [sources] à volta de [now]: os a decorrer e os que começam até [HORIZON_MS]. [country] e [language]
     * (ISO em minúsculas, da pessoa) ordenam os canais de cada jogo.
     */
    fun build(sources: List<SportsSource>, now: Long, country: String?, language: String?, memory: SportsMemory): SportsHubResult {
        class Airing(val parsed: Parsed, val start: Long, val end: Long, val channel: String)
        val airings = ArrayList<Airing>()
        val userCountries = countriesOf(country, language)
        val thisYear = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).year
        val byKey = HashMap<String, SportsSource>()
        for (src in sources) {
            byKey[src.key] = src
            // Só se o título não disser o desporto (numa lista grande são milhares de canais: só quando é preciso).
            val channelSport by lazy { memory.sports.getOrPut(src.name + "\u0000" + src.group) { sportOf(fold(src.name + " " + src.group)) ?: "" }.ifEmpty { null } }
            // Só a janela que interessa: o passado recente (para reconhecer repetições) e os próximos jogos.
            for (e in src.entries) {
                if (e.endMs < now - 20 * 3600_000L || e.startMs > now + HORIZON_MS) continue
                val p = memory.titles.getOrPut(e.title) { parse(e.title) ?: NotAGame } as? Parsed ?: continue
                if (p.year in 1900 until thisYear - 1) continue
                if (p.dated >= 0 && !nearDay(p.dated, e.startMs)) continue
                val sport = p.sport.ifEmpty { channelSport ?: FOOTBALL }
                airings += Airing(if (sport == p.sport) p else p.copy(sport = sport), e.startMs, e.endMs, src.key)
            }
        }
        // Os programas que se repetem muitas vezes no mesmo canal são programas, não jogos ("Golf - pasja i sport").
        val perChannel = HashMap<Pair<String, String>, Int>()
        for (a in airings) perChannel[a.channel to a.parsed.key] = (perChannel[a.channel to a.parsed.key] ?: 0) + 1
        val kept = airings.filter { (perChannel[it.channel to it.parsed.key] ?: 0) <= 3 }.sortedBy { it.start }

        // Junta as vezes do mesmo jogo: à mesma hora são o mesmo jogo noutro canal; horas depois, repetição.
        class Group(val first: Airing) {
            val airings = ArrayList<Airing>().also { it += first }
            var replay = false
        }
        val groups = ArrayList<Group>()
        val byPair = HashMap<String, ArrayList<Group>>()
        for (a in kept) {
            val candidates = a.parsed.tokens.flatMap { byPair[it].orEmpty() }.distinct()
            val same = candidates.firstOrNull { g -> samePair(g.first.parsed, a.parsed) }
            when {
                same == null -> {
                    val g = Group(a)
                    groups += g
                    for (t in a.parsed.tokens) byPair.getOrPut(t) { ArrayList() } += g
                }
                a.start - same.first.start <= SAME_AIRING_MS -> same.airings += a
                a.start - same.first.start >= REPLAY_AFTER_MS -> Unit
                else -> same.airings += a
            }
        }
        // O país e a qualidade de cada canal, só dos que têm jogos.
        val channelInfo = HashMap<String, SportsChannel>()
        fun info(key: String): SportsChannel? = channelInfo[key] ?: byKey[key]?.let { src ->
            val parse = ChannelMatch.parse(src.name)
            val chCountry = memory.groups.getOrPut(src.group) { GroupCountries.of(src.group) ?: "" }.ifEmpty { null } ?: parse.country
            SportsChannel(src.key, src.name, chCountry, rankOf(chCountry, country, userCountries), parse.quality).also { channelInfo[key] = it }
        }
        val games = ArrayList<SportsGame>()
        for (g in groups) {
            val first = g.first
            val end = g.airings.maxOf { it.end }
            if (end <= now || first.start > now + HORIZON_MS) continue
            val p = first.parsed
            val gameCountries = p.countries
            val channels = g.airings.map { it.channel }.distinct().mapNotNull { info(it) }.map { c ->
                if (c.rank == 3 && c.country != null && c.country in gameCountries) c.copy(rank = 2) else c
            }.sortedWith(compareBy<SportsChannel> { it.rank }.thenByDescending { it.quality }.thenBy { it.name })
            games += SportsGame(
                id = "${p.sport}:${p.key}:${first.start / 60_000}",
                sport = p.sport, league = p.league, home = p.home, away = p.away, name = p.name,
                startMs = first.start, endMs = end, channels = channels,
            )
        }
        val sorted = games.sortedWith(
            compareBy<SportsGame> { if (it.isLive(now)) 0 else 1 }
                .thenBy { if (it.isLive(now)) it.channels.first().rank else 0 }
                .thenBy { if (it.isLive(now)) -it.channels.size else 0 }
                .thenBy { it.startMs },
        )
        // Os canais onde dão os jogos (não os de desporto todos: há listas com centenas, e canais "sem evento"):
        // primeiro os que estão a dar um jogo agora.
        val live = sorted.filter { it.isLive(now) }.flatMap { g -> g.channels.map { it.key } }.toSet()
        val channels = sorted.flatMap { it.channels }.distinctBy { it.key }.sortedWith(
            compareBy<SportsChannel> { if (it.key in live) 0 else 1 }.thenBy { it.rank }.thenByDescending { it.quality }.thenBy { it.name },
        )
        val sports = SPORTS.filter { s -> sorted.any { it.sport == s } }
        return SportsHubResult(sorted, channels, sports)
    }

    /** A data [dated] (mês × 100 + dia) é a do dia de [ms], do anterior ou do seguinte (os fusos não batem certo). */
    private fun nearDay(dated: Int, ms: Long): Boolean = (-1..1).any { d ->
        val t = Instant.fromEpochMilliseconds(ms + d * 86_400_000L).toLocalDateTime(TimeZone.currentSystemDefault())
        t.monthNumber * 100 + t.dayOfMonth == dated
    }

    /** O mesmo par de equipas (pela mesma ordem ou trocada: há guias que as trocam). */
    private fun samePair(a: Parsed, b: Parsed): Boolean {
        if (a.home.isEmpty() || b.home.isEmpty()) return a.key == b.key
        return sameTeam(a.homeTokens, b.homeTokens) && sameTeam(a.awayTokens, b.awayTokens) ||
            sameTeam(a.homeTokens, b.awayTokens) && sameTeam(a.awayTokens, b.homeTokens)
    }

    /** Uma palavra em comum, ou uma que começa pela outra ("Trabzon" e "Trabzonspor"). */
    private fun sameTeam(a: Set<String>, b: Set<String>): Boolean =
        a.any { x -> x in b || x.length >= 4 && b.any { y -> y.length >= 4 && (x.startsWith(y) || y.startsWith(x)) } }

    private fun rankOf(channel: String?, country: String?, userCountries: Set<String>): Int = when {
        channel == null -> 3
        channel == country -> 0
        channel in userCountries -> 1
        else -> 3
    }

    /** Os países (e regiões) que falam a língua da pessoa, mais o dela. */
    private fun countriesOf(country: String?, language: String?): Set<String> {
        val out = HashSet<String>()
        country?.let { out += it }
        val lang = language ?: return out
        out += LANG_COUNTRIES[lang] ?: listOf(lang)
        return out
    }

    // ---- Títulos ---------------------------------------------------------------------------------

    /** Um título de jogo já desfeito: as equipas (ou o nome da corrida), a competição e o desporto. */
    internal data class Parsed(
        val home: String,
        val away: String,
        val name: String,
        val league: String,
        val sport: String,
        val key: String,
        val homeTokens: Set<String>,
        val awayTokens: Set<String>,
        val countries: Set<String>,
        /** A data no título (mês × 100 + dia), ou -1. */
        val dated: Int,
        /** O ano mais recente no título, ou -1 ("F1: Rennen 2000 in Suzuka" é um clássico). */
        val year: Int,
    ) {
        val tokens: Set<String> get() = if (home.isEmpty()) setOf(key) else homeTokens + awayTokens
    }

    /** Na [SportsMemory], os títulos que não são jogos. */
    private object NotAGame

    /** O jogo do título, ou null se não é um jogo (série, notícias, resumo, repetição…). */
    internal fun parse(title: String): Parsed? {
        if (title.length < 5 || title.length > 160) return null
        // Antes do trabalho todo: sem separador de equipas nem corrida, não é jogo (é quase tudo o que o guia tem).
        if (!QUICK_SEPARATORS.any { it in title } && QUICK_RACE.none { title.contains(it, ignoreCase = true) }) return null
        var s = title.trim().replace("\\/", "/")
        if (REPLAY_MARK.containsMatchIn(s)) return null
        s = LIVE_PREFIX.replace(s, "").let { LIVE_TAG.replace(it, " ") }.trim()
        val folded = fold(s)
        val fw = words(folded)
        if (fw.any { it in NOT_LIVE || it in NOT_MATCH }) return null
        if (NOT_LIVE_PHRASES.any { it in folded }) return null
        // A data do jogo no título ("SERBIA - GERMANY 13/11/26"): noutro dia é repetição (ver build).
        val dated = TRAILING_DATE.find(s)?.let { m -> m.groupValues[2].toInt() * 100 + m.groupValues[1].toInt() } ?: -1
        val year = YEAR.findAll(s).map { it.value.toInt() }.maxOrNull() ?: -1
        // Datas, parênteses e "/ …" no fim: "SERBIA - GERMANY 13/11/26", "Senegal vs Sudan (AFCON)".
        var prev: String
        do {
            prev = s
            s = TRAILING_NOISE.replace(s, "").trim()
        } while (s != prev && s.isNotEmpty())

        // Corridas: sem equipas, com o nome do Grande Prémio ("Mundial F1 - GP de Japón", "MotoGP: Corrida").
        val race = fw.any { it in RACE_WORDS } || RACE_PHRASES.any { it in folded }
        if (race && STRONG_SEPARATORS.none { it.containsMatchIn(s) }) {
            // "On Board F1" ou "MotoGP Films" não são corridas: é preciso o Grande Prémio ou a sessão.
            if (s.length > 70 || RACE_PHRASES.none { it in folded } && "gp" !in fw && fw.none { it in SESSION_WORDS }) return null
            return Parsed("", "", s, "", MOTOR, teamKey(s), emptySet(), emptySet(), emptySet(), dated, year)
        }
        val split = splitTeams(s) ?: return null
        val (rawHome, rawAway, league) = split
        val dashOnly = split.dashOnly
        val home = cleanSide(rawHome, allowLong = !dashOnly) ?: return null
        val away = cleanSide(SENTENCE_DOT.split(rawAway, 2)[0], allowLong = !dashOnly) ?: return null
        if (fold(home) == fold(away)) return null
        val homeTokens = teamTokens(home)
        val awayTokens = teamTokens(away)
        if (homeTokens.isEmpty() || awayTokens.isEmpty()) return null
        // Sem desporto no título fica vazio: o do canal decide (ver build).
        val sport = sportOf(folded) ?: ""
        val countries = HashSet<String>()
        GroupCountries.exactName(home)?.let { countries += it }
        GroupCountries.exactName(away)?.let { countries += it }
        leagueCountry(fold(league))?.let { countries += it }
        val key = listOf(teamKey(home), teamKey(away)).sorted().joinToString("|")
        return Parsed(titleCase(home), titleCase(away), "", league.trim().trim(':', '-', '–', ',', '|', ' '), sport, key, homeTokens, awayTokens, countries, dated, year)
    }

    private class Split(val home: String, val away: String, val league: String, val dashOnly: Boolean) {
        operator fun component1() = home
        operator fun component2() = away
        operator fun component3() = league
    }

    private fun splitTeams(s: String): Split? {
        for (sep in STRONG_SEPARATORS) {
            val m = sep.find(s) ?: continue
            var left = s.substring(0, m.range.first)
            var right = s.substring(m.range.last + 1)
            var league = ""
            // "Liga: A vs B", "MLTT - Week 5: A vs B", "Premier League - A v B".
            prefixCut(left)?.let { (prefix, rest) ->
                league = prefix
                left = rest
            }
            // "A vs B - Liga …", "A v B, Liga", "A vs B | Liga".
            val suffix = SUFFIX_CUT.find(right)
            if (suffix != null) {
                if (league.isEmpty()) league = right.substring(suffix.range.last + 1)
                right = right.substring(0, suffix.range.first)
            }
            // "Lakers @ Celtics": a da casa é a segunda.
            if (sep === AT) return Split(right, left, league, dashOnly = false)
            return Split(left, right, league, dashOnly = false)
        }
        // Só traços: "Liga: A - B", "Nogomet – Liga: A – B", "A - B, Premier liga".
        var body = s
        var league = ""
        val colon = s.lastIndexOf(": ")
        if (colon > 0 && DASH.containsMatchIn(s.substring(colon))) {
            league = s.substring(0, colon)
            body = s.substring(colon + 2)
        }
        val comma = body.indexOf(", ")
        if (comma > 0 && DASH.containsMatchIn(body.substring(0, comma))) {
            if (league.isEmpty()) league = body.substring(comma + 2)
            body = body.substring(0, comma)
        }
        // "Баскетбол. Единая лига ВТБ. Енисей - Уралмаш": a competição acaba no último ". " antes do traço.
        val dot = SENTENCE_DOT.findAll(body.substringBefore(" - ").substringBefore(" – ")).lastOrNull()
        if (dot != null) {
            if (league.isEmpty()) league = body.substring(0, dot.range.first + 1)
            body = body.substring(dot.range.last + 1)
        }
        val parts = DASH.split(body).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 2) return Split(parts[0], parts[1], league, dashOnly = true)
        if (parts.size > 2) {
            // "Liga Portugal - Benfica - Porto", "Benfica - Porto - Jornada 12": fora os bocados que são competição.
            val teams = parts.filter { p -> words(fold(p)).none { it in ROUND_WORDS || it in LEAGUE_WORDS } && p.none(Char::isDigit) }
            if (teams.size == 2) {
                val rest = parts.filter { it !in teams }.joinToString(" - ")
                return Split(teams[0], teams[1], league.ifEmpty { rest }, dashOnly = true)
            }
        }
        return null
    }

    /** O que vem antes das equipas no lado esquerdo ("Liga: ", "Premier League - "), e o resto. */
    private fun prefixCut(left: String): Pair<String, String>? {
        val colon = left.lastIndexOf(": ")
        val dash = DASH.findAll(left).lastOrNull()
        val cut = maxOf(colon + 1, dash?.range?.last ?: -1)
        if (cut <= 0) return null
        return left.substring(0, if (cut == colon + 1) colon else dash!!.range.first) to left.substring(cut + 1)
    }

    /** Uma equipa limpa, ou null se não parece uma (muitas palavras, só números, "S1", "Final"…). */
    private fun cleanSide(raw: String, allowLong: Boolean): String? {
        val s = INNER_PARENS.replace(raw, " ").trim().trim(',', ':', '-', '–', '|', '/', '.', ' ').replace(SPACES, " ")
        if (s.length < 2 || s.length > 40) return null
        if (s.none(Char::isLetter)) return null
        val w = words(fold(s))
        if (w.isEmpty() || w.size > (if (allowLong) 6 else 4)) return null
        if (w.any { it in ROUND_WORDS }) return null
        if (w.size == 1 && (w[0] in SPORT_NAMES || EPISODE.matches(w[0]))) return null
        if (w.any { it in LEAGUE_WORDS || it in EVENT_WORDS || it in SPORT_WORDS_SIDE }) return null
        // Anos e datas ("2026", "14.03.2025") não são nomes de equipas.
        if (Regex("\\d{4}").containsMatchIn(s)) return null
        return s
    }

    private fun titleCase(s: String): String {
        val letters = s.filter(Char::isLetter)
        if (letters.length < 4 || letters != letters.uppercase()) return s
        return s.split(' ').joinToString(" ") { w -> if (w.length <= 3) w else w.lowercase().replaceFirstChar { it.uppercase() } }
    }

    /** As palavras que identificam a equipa (sem "FC", "SC", "de"…), para juntar o mesmo jogo em canais diferentes. */
    private fun teamTokens(team: String): Set<String> {
        // As seleções juntam-se pelo país, em qualquer língua ("Kosovo – Israel", "Kosowo – Izrael").
        GroupCountries.exactName(team)?.let { return setOf("#$it") }
        return words(fold(team)).filter { it !in TEAM_NOISE && it.length >= 3 }.toSet().ifEmpty { words(fold(team)).toSet() }
    }

    private fun teamKey(team: String): String = teamTokens(team).sorted().joinToString(" ")

    private fun sportOf(folded: String): String? {
        val w = words(folded)
        for ((sport, keys) in SPORT_KEYS) {
            if (w.any { it in keys.words } || keys.phrases.any { it in folded }) return sport
        }
        return null
    }

    private fun leagueCountry(folded: String): String? = LEAGUE_COUNTRIES.firstOrNull { it.first in folded }?.second

    private fun fold(s: String): String = nfkd(s.lowercase()).filter { it.category != CharCategory.NON_SPACING_MARK }

    private fun words(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (c in s) {
            if (c.isLetterOrDigit()) {
                cur.append(c)
            } else if (cur.isNotEmpty()) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private class Keys(val words: Set<String>, val phrases: List<String> = emptyList())

    // ---- Tabelas ---------------------------------------------------------------------------------

    private val QUICK_SEPARATORS = listOf(" - ", " – ", " — ", " vs", " Vs", " VS", " v ", " V ", " x ", " X ", " @ ")
    private val QUICK_RACE = listOf("gp", "f1", "prix", "premio", "prémio", "formula", "fórmula", "formel", "formule", "moto", "nascar", "indycar", "rally", "superbike", "preis")
    private val VS = Regex("\\s+vs?\\.?\\s+", RegexOption.IGNORE_CASE)
    private val X = Regex("\\s+[xX]\\s+")
    private val AT = Regex("\\s+@\\s+")
    private val STRONG_SEPARATORS = listOf(VS, X, AT)
    private val DASH = Regex("\\s+[-–—]\\s+")
    private val SUFFIX_CUT = Regex("\\s+[-–—|]\\s+|,\\s+|:\\s+")
    private val SENTENCE_DOT = Regex("(?<=\\p{L}{3})\\.\\s+")
    private val INNER_PARENS = Regex("\\([^)]*\\)|\\[[^]]*]")
    private val SPACES = Regex("\\s+")
    private val EPISODE = Regex("[se]\\d+|ep\\d+")
    private val TRAILING_DATE = Regex("\\s(\\d{1,2})[./](\\d{1,2})([./]\\d{2,4})?$")
    private val YEAR = Regex("\\b(19|20)\\d\\d\\b")
    private val TRAILING_NOISE = Regex("(\\.\\s*(прямая трансляция|трансляция)|\\s*\\([^)]*\\)|\\s*\\[[^]]*]|\\s+\\d{1,2}[./]\\d{1,2}([./]\\d{2,4})?|\\s+/\\s*[^/]+|\\s+-\\s+(directo|direto|live|en vivo|en directo))$", RegexOption.IGNORE_CASE)
    private val REPLAY_MARK = Regex("\\((r|rpt|rep|repeticao|repetição|replay|wh|hl)\\)|\\bHL\\b", RegexOption.IGNORE_CASE)
    private val LIVE_PREFIX = Regex(
        "^(ישיר!?|live|ao vivo|em direto|direto|directo|en directo|en vivo|en direct|uživo|uzivo|canlı|canli|na żywo|na zywo|in diretta|diretta|live now)\\s*[:|\\-–]?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val LIVE_TAG = Regex("\\[(live|en vivo|ao vivo)]|\\((l|live)\\)", RegexOption.IGNORE_CASE)

    /** Palavras que dizem que não é o jogo em direto (resumo, repetição, programa à volta do jogo). */
    private val NOT_LIVE = setOf(
        "highlights", "highlight", "resumo", "resumos", "resumen", "resumenes", "resume", "studio", "estudio", "studij",
        "magazine", "revista", "preview", "antevisao", "previa", "analise", "analisis", "replay", "reprise", "repeticao",
        "repeticion", "diferido", "delayed", "wiederholung", "bant", "noticias", "news", "nachrichten", "notizie",
        "originals", "documentary", "documentario", "documental", "classic", "classics", "classicos", "talk", "podcast",
        "στιγμιοτυπα", "повтор", "обзор", "ozet", "skrot", "sazetak", "pregled", "goals", "golos", "goles", "show",
        "360", "pregame", "postgame", "prematch", "konferenz", "multiliga", "redzone",
    )
    private val NOT_LIVE_PHRASES = listOf("melhores momentos", "mejores momentos", "best of", "pre match", "post match", "pre-match", "post-match", "por dentro")

    private val ROUND_WORDS = setOf(
        "final", "finale", "finals", "finais", "semifinal", "semifinals", "semi", "meia", "quarter", "quartos", "round",
        "ronda", "jornada", "matchday", "md", "week", "hafta", "krog", "kolo", "giornata", "journee", "spieltag", "tbd",
        "qualifying", "qualification", "qualificacao", "slalom", "etapa", "stage", "season", "temporada", "episode",
        "episodio", "trka", "loser", "winner", "vencedor", "jan", "feb", "apr", "may", "jun", "jul", "aug", "sep", "sept",
        "oct", "nov", "dec",
    )
    private val LEAGUE_WORDS = setOf("liga", "league", "lig", "ligue", "cup", "copa", "taca", "coppa", "pokal", "premier", "serie", "uefa", "fifa", "championship", "nations", "afcon", "nba", "nfl", "nhl", "mlb", "atp", "wta", "ufc")
    /** Palavras de provas, não de equipas ("World Cup - Verona", "World Multisport Championship - Abu Dhabi"). */
    private val EVENT_WORDS = setOf(
        "world", "tour", "open", "masters", "grand", "prix", "championships", "mundial", "mundo", "series", "trophy", "cup",
        "cupa", "mondiala", "games", "olympics", "race", "circuit", "rennen", "corrida", "carrera", "kickoff", "gameday",
    )
    /** "Mali sportovi – veliki sportisti" é um programa ("Sporting" é uma equipa). */
    private val SPORT_WORDS_SIDE = setOf("sport", "sports", "sporty", "sportovi", "sportisti", "sportif", "sportive", "desporto", "deportes")
    /** Desportos sem jogos de um contra outro: ciclismo, esqui, triatlo, hipismo… ficam de fora. */
    private val NOT_MATCH = setOf(
        "cyclo", "ciclismo", "cycling", "kolarstwo", "ratapyoraily", "schi", "ski", "skijanje", "esqui", "skiing",
        "triathlon", "triatlo", "atletismo", "athletics", "lekkoatletyka", "gymnastics", "ginastica", "jumping",
        "equestrian", "hipismo", "patinaj", "skating", "patinagem", "regata", "rowing", "sailing", "obaranje",
        "romaniacs", "enduro", "truck", "rewind", "biathlon", "biatlo", "swimming", "natacao",
    )
    private val SPORT_NAMES = setOf(
        "golf", "tenis", "tennis", "triatlo", "triathlon", "ciclismo", "cycling", "esqui", "ski", "skiing", "atletismo",
        "athletics", "natacao", "swimming", "darts", "snooker", "futebol", "football", "soccer", "futbol", "nogomet",
        "fudbal", "fussball", "basquetebol", "basketball", "kosarka", "boks", "boxing", "boxe", "rugby", "cricket",
        "andebol", "handball", "voleibol", "volleyball", "odbojka", "hokej", "hockey", "formula", "motogp", "f1",
        "sport", "sports", "desporto", "deportes", "esports", "wrestling", "konferenz",
    )
    private val TEAM_NOISE = setOf(
        "fc", "cf", "sc", "ac", "afc", "cd", "sl", "sad", "club", "clube", "de", "del", "da", "do", "the", "us", "as", "ss",
        "ssc", "rc", "sv", "vfb", "vfl", "fk", "nk", "hnk", "gnk", "sk", "bk", "if", "ik", "cp", "ca", "ec", "se", "fbc",
        "bc", "kk", "hc", "hk", "calcio", "spor", "kulubu", "fussballclub", "u21", "u19", "u23",
    )
    private val RACE_WORDS = setOf("gp", "f1", "motogp", "moto2", "moto3", "indycar", "nascar", "wec", "wrc", "superbike", "superbikes")
    private val RACE_PHRASES = listOf("grand prix", "grande premio", "gran premio", "grand prix", "formula 1", "formule 1", "formel 1", "großer preis", "grosser preis")

    private val SESSION_WORDS = setOf("race", "corrida", "carrera", "gara", "course", "rennen", "qualifying", "qualificacao", "clasificacion", "qualifiche", "sprint", "wyscig", "utrka")

    private val SPORTS_STEMS = listOf("sport", "desport", "deport", "espn", "dazn", "eleven", "futbol", "football", "soccer", "calcio", "tennis", "golf", "racing", "fight", "boxing", "cricket", "rugby", "nogomet", "fudbal", "fussball", "ποδοσφ", "спорт", "футбол")
    private val SPORTS_WORDS = setOf("spor", "nba", "nfl", "nhl", "mlb", "f1", "ufc", "wwe", "mma", "tsn", "arena", "liga", "laliga", "premier", "bundesliga", "ligue", "motogp", "setanta", "golazo", "match", "tenis", "eurosport")

    private val SPORT_KEYS: List<Pair<String, Keys>> = listOf(
        // Primeiro: "Водное поло … Чемпионат мира" não é futebol, "tenis de mesa" não é ténis.
        OTHER to Keys(
            setOf("waterpolo", "vaterpolo", "polo", "поло", "darts", "dardos", "snooker", "padel", "squash", "badminton", "floorball", "bandy", "curling", "esports", "netball", "lacrosse", "kabaddi"),
            listOf("water polo", "polo aquatico", "tenis de mesa", "table tennis", "tennis de table"),
        ),
        AMFOOTBALL to Keys(setOf("nfl", "ncaaf"), listOf("american football", "futebol americano", "futbol americano", "football americain", "college football")),
        BASKETBALL to Keys(
            setOf("nba", "wnba", "euroleague", "evroliga", "euroliga", "eurocup", "acb", "bsl", "vtb", "basket", "basketball", "baschet", "basquetebol", "basquete", "baloncesto", "pallacanestro", "kosarka", "koszykowka", "баскетбол", "кошарка", "μπασκετ", "lnb", "aba"),
            listOf("college basketball", "ncaa basketball"),
        ),
        HOCKEY to Keys(setOf("nhl", "khl", "hockey", "hokej", "hokejs", "хоккеи", "хоккей", "eishockey", "jaakiekko", "ishockey", "shl"), listOf("ice hockey", "hoquei no gelo")),
        BASEBALL to Keys(setOf("mlb", "baseball", "beisebol", "beisbol", "npb", "kbo")),
        TENNIS to Keys(setOf("tennis", "tenis", "теннис", "atp", "wta", "wimbledon", "itf", "singolare", "singles"), listOf("roland garros", "us open", "australian open", "davis cup", "billie jean")),
        MOTOR to Keys(RACE_WORDS + setOf("rally", "dakar"), RACE_PHRASES),
        FIGHT to Keys(setOf("ufc", "mma", "boxing", "boxe", "boks", "boxeo", "pugilato", "wrestling", "wwe", "aew", "kickboxing", "bellator", "pfl", "glory", "ksw", "бокс")),
        RUGBY to Keys(setOf("rugby", "nrl", "urc"), listOf("six nations", "super rugby", "top 14")),
        CRICKET to Keys(setOf("cricket", "odi", "t20", "t20i", "ipl", "bbl", "cri", "blackcaps"), listOf("test match", "indian premier league")),
        HANDBALL to Keys(setOf("handball", "andebol", "balonmano", "rukomet", "rokomet", "pallamano", "гандбол", "handbold", "handboll", "ehf", "kezilabda"), listOf("pilka reczna", "piłka ręczna")),
        VOLLEYBALL to Keys(setOf("volleyball", "voleibol", "volei", "pallavolo", "odbojka", "siatkowka", "волеибол", "волейбол", "volley")),
        GOLF to Keys(setOf("golf", "pga", "lpga", "liv"), listOf("ryder cup", "dp world tour")),
        FOOTBALL to Keys(
            setOf(
                "futebol", "football", "soccer", "futbol", "fussball", "calcio", "nogomet", "fudbal", "voetbal", "fotboll", "fotball",
                "fodbold", "jalkapallo", "futbal", "fotbal", "футбол", "ποδοσφαιρο", "laliga", "bundesliga", "eredivisie", "ligue",
                "efl", "mls", "afcon", "uefa", "fifa", "ekstraklasa", "superliga", "primeira", "brasileirao", "libertadores",
            ),
            listOf("premier league", "serie a", "champions league", "europa league", "conference league", "nations league", "liga portugal", "pilka nozna", "piłka nozna", "super lig", "fa cup", "copa del rey", "taca de portugal", "world cup"),
        ),
    )

    /** A competição diz o país do jogo (o canal desse país vem antes dos de fora). */
    private val LEAGUE_COUNTRIES = listOf(
        "liga portugal" to "pt", "primeira liga" to "pt", "taca de portugal" to "pt", "premier league" to "gb", "efl" to "gb",
        "fa cup" to "gb", "championship" to "gb", "laliga" to "es", "la liga" to "es", "copa del rey" to "es", "serie a" to "it",
        "coppa italia" to "it", "bundesliga" to "de", "dfb" to "de", "ligue 1" to "fr", "eredivisie" to "nl", "super lig" to "tr",
        "brasileirao" to "br", "ekstraklasa" to "pl", "mls" to "us", "nba" to "us", "nfl" to "us", "nhl" to "us", "mlb" to "us",
    )

    /** Os países de cada língua (para "um canal na minha língua" quando não há do meu país). */
    private val LANG_COUNTRIES = mapOf(
        "pt" to listOf("pt", "br", "ao", "mz"),
        "es" to listOf("es", "mx", "ar", "co", "cl", "pe", "ve", "ec", "uy", "py", "bo", "cr", "gt", "hn", "ni", "pa", "sv", "do", "cu", "pr", GroupCountries.LATAM),
        "en" to listOf("gb", "us", "ca", "au", "ie", "nz", "za"),
        "fr" to listOf("fr", "be", "ch", "ca", "lu", "mc"),
        "de" to listOf("de", "at", "ch", "li"),
        "it" to listOf("it", "ch", "sm"),
        "nl" to listOf("nl", "be"),
        "ar" to listOf(GroupCountries.ARAB, "sa", "ae", "eg", "ma", "dz", "tn", "qa", "kw", "bh", "om", "jo", "lb", "iq", "sy", "ly", "ye", "sd"),
        "tr" to listOf("tr", "cy"),
        "ru" to listOf("ru", "by", "kz"),
        "el" to listOf("gr", "cy"),
        "sv" to listOf("se"),
        "da" to listOf("dk"),
        "nb" to listOf("no"),
        "no" to listOf("no"),
        "sr" to listOf("rs", "ba", "me", GroupCountries.EXYU),
        "hr" to listOf("hr", "ba", GroupCountries.EXYU),
        "bs" to listOf("ba", GroupCountries.EXYU),
        "sl" to listOf("si", GroupCountries.EXYU),
        "mk" to listOf("mk", GroupCountries.EXYU),
        "cs" to listOf("cz"),
        "uk" to listOf("ua"),
        "ja" to listOf("jp"),
        "ko" to listOf("kr"),
        "zh" to listOf("cn", "tw", "hk"),
        "hi" to listOf("in"),
        "fa" to listOf("ir"),
    )
}

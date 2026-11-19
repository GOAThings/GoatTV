package cc.slokker.itv.data

import cc.slokker.itv.data.ProLineups.DOCS
import cc.slokker.itv.data.ProLineups.ENTERTAINMENT
import cc.slokker.itv.data.ProLineups.GENERAL
import cc.slokker.itv.data.ProLineups.KIDS
import cc.slokker.itv.data.ProLineups.MOVIES
import cc.slokker.itv.data.ProLineups.MUSIC
import cc.slokker.itv.data.ProLineups.NEWS
import cc.slokker.itv.data.ProLineups.SPORTS

/**
 * O tema de um canal de um país sem tabela (Modo TV Pro), pelo nome do grupo da lista ("PL | Sport", "DE: Filme") e,
 * se o grupo não diz, pelo nome do canal ("Polsat Sport", "Canal+ Film"). As palavras estão nas línguas das listas
 * que mais aparecem; o que não se percebe fica em "Mais canais" ([ProLineup.MORE]).
 */
object ProThemes {
    fun of(group: String?, channel: String): String =
        group?.let { theme(it) } ?: theme(channel) ?: ProLineup.MORE

    private fun theme(name: String): String? {
        val s = fold(name)
        // Pela ordem: "Sport News" é desporto, "Kids Movies" é infantil, "Music Docs" é música.
        for ((theme, stems) in STEMS) if (stems.any { it in s }) return theme
        return null
    }

    /** Pedaços de palavra que chegam (aparecem dentro de outras: "Sportkanal", "Kinderprogramm"). */
    private val STEMS = listOf(
        SPORTS to listOf(
            "sport", "deport", "desport", "futbol", "football", "fussball", "calcio", "voetbal", "pilka", "futebol",
            "eleven", "dazn", "espn", "bein", "tennis", "golf", "racing", "moto", "nba", "nfl", "nhl", "ufc", "wwe",
            "liga", "ligue", "premier league", "spor ",
        ),
        KIDS to listOf(
            "kid", "infant", "child", "enfant", "jeunesse", "kinder", "bambin", "ragazz", "nino", "dzieci", "bajk",
            "cocuk", "detsk", "детск", "cartoon", "animac", "animad", "animation", "anime", "disney", "nick", "junior", "baby", "toon", "boomerang",
            "gulli", "kika", "dibuj", "crianca",
        ),
        MUSIC to listOf("music", "musica", "musiq", "musik", "muzyk", "muzik", "muzic", "mtv", "vh1", "hits", "radio", "clip"),
        DOCS to listOf(
            "docu", "doku", "doc ", "discovery", "nat geo", "natgeo", "national geographic", "history", "histor",
            "nature", "natur", "animal", "planet", "science", "wissen", "viasat explore", "viasat nature",
        ),
        NEWS to listOf(
            "news", "notic", "nachricht", "info", "actualit", "journal", "noticia", "wiadomosc", "haber", "новост",
            "cnn", "bbc world", "euronews", "24h",
        ),
        MOVIES to listOf(
            "movie", "film", "cine", "kino", "pelicul", "filme", "fiction", "ficcion", "series", "serie", "hbo",
            "showtime", "starz", "cinemax", "axn", "fox", "star channel", "amc", "sky cinema", "canal+ premium",
        ),
        ENTERTAINMENT to listOf(
            "entertain", "entret", "intrattenim", "unterhalt", "rozrywk", "eglence", "divertiss", "lifestyle",
            "reality", "variety", "comedy", "food", "cook", "kitchen", "travel", "fashion", "novela", "telenovela",
        ),
        GENERAL to listOf("general", "generalist", "national", "nacional", "nazional", "regional", "local", "public", "main", "basic", "ogolne"),
    )

    private fun fold(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            sb.append(
                when (ch) {
                    'á', 'à', 'â', 'ã', 'ä', 'å', 'ą' -> 'a'
                    'é', 'è', 'ê', 'ë', 'ę' -> 'e'
                    'í', 'ì', 'î', 'ï', 'ı' -> 'i'
                    'ó', 'ò', 'ô', 'õ', 'ö', 'ø' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    'ç', 'ć', 'č' -> 'c'
                    'ñ', 'ń' -> 'n'
                    'ś', 'š', 'ş' -> 's'
                    'ź', 'ż', 'ž' -> 'z'
                    'ł' -> 'l'
                    'ğ' -> 'g'
                    '|', ':', '-', '_', '/', '[', ']', '(', ')' -> ' '
                    else -> ch
                },
            )
        }
        return " $sb "
    }
}

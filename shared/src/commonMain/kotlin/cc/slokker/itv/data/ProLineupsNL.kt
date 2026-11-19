package cc.slokker.itv.data

import cc.slokker.itv.data.ProLineup.Lineup
import cc.slokker.itv.data.ProLineups.DOCS
import cc.slokker.itv.data.ProLineups.ENTERTAINMENT
import cc.slokker.itv.data.ProLineups.GENERAL
import cc.slokker.itv.data.ProLineups.INTERNATIONAL
import cc.slokker.itv.data.ProLineups.KIDS
import cc.slokker.itv.data.ProLineups.MOVIES
import cc.slokker.itv.data.ProLineups.MUSIC
import cc.slokker.itv.data.ProLineups.NEWS
import cc.slokker.itv.data.ProLineups.SPORTS
import cc.slokker.itv.data.ProLineups.slot

/**
 * Países Baixos: a grelha digital da Ziggo (zenderoverzicht de outubro de 2026), sem as apps (Netflix, Videoland…),
 * os canais para adultos nem as versões UHD. Os canais que estão em dois números (Ziggo Sport 1, ESPN, Viaplay TV)
 * ficam no primeiro; os que a Ziggo não tem (Disney Junior à parte, VTM, Sky News, France 24, Bloomberg, DW) ficam em
 * números livres ao pé dos do mesmo género.
 */
internal object ProLineupsNL {
    val NL: Lineup = Lineup("nl", "nl", "Ziggo", ProLineups.GROUPS, listOf(
        // Generalistas e os canais da base (1–29).
        slot(1, "NPO 1", GENERAL, "Nederland 1"),
        slot(2, "NPO 2", GENERAL, "Nederland 2"),
        slot(3, "NPO 3", GENERAL, "Nederland 3"),
        slot(4, "RTL 4", GENERAL),
        slot(5, "RTL 5", GENERAL),
        slot(6, "SBS6", GENERAL),
        slot(7, "RTL 7", GENERAL),
        slot(8, "Veronica", GENERAL, "Veronica TV"),
        slot(9, "Net5", GENERAL),
        slot(10, "RTL 8", GENERAL),
        slot(11, "Star Channel", ENTERTAINMENT, "FOX NL"),
        slot(12, "RTL Z", NEWS),
        slot(13, "Viaplay TV", SPORTS, "Viaplay TV 1"),
        // As Ziggo Sport passaram a números em 2024: Select juntou-se à 1, Voetbal → 2, Racing → 3, Tennis → 4, Docu → 5, Golf → 6.
        slot(14, "Ziggo Sport 1", SPORTS, "Ziggo Sport|Ziggo Sport Select"),
        slot(15, "Comedy Central", ENTERTAINMENT),
        slot(16, "Paramount Network", ENTERTAINMENT, "Spike"),
        slot(17, "Discovery", DOCS, "Discovery Channel"),
        slot(18, "ESPN", SPORTS, "ESPN 1"),
        slot(19, "SBS9", GENERAL),
        slot(20, "Investigation Discovery", DOCS, "ID"),
        slot(21, "TLC", ENTERTAINMENT),
        slot(22, "BBC NL", ENTERTAINMENT, "BBC First"),
        slot(23, "MTV", ENTERTAINMENT, "MTV NL"),
        slot(24, "24Kitchen", ENTERTAINMENT),
        slot(25, "Eurosport 1", SPORTS, "Eurosport"),
        slot(26, "HGTV", ENTERTAINMENT),
        slot(27, "National Geographic", DOCS, "Nat Geo"),
        slot(28, "History", DOCS, "History Channel"),
        slot(29, "Nickelodeon", KIDS, "Nick"),

        // Bélgica, Alemanha, Reino Unido e outros estrangeiros da base (50–77).
        slot(50, "ONS", GENERAL),
        slot(51, "VRT 1", INTERNATIONAL, "Eén", foreign = true),
        slot(52, "VRT Canvas", INTERNATIONAL, "Canvas", foreign = true),
        slot(53, "Ketnet", KIDS, foreign = true),
        slot(54, "ARD", INTERNATIONAL, "Das Erste", foreign = true),
        slot(55, "ZDF", INTERNATIONAL, foreign = true),
        slot(56, "WDR", INTERNATIONAL, "WDR Fernsehen", foreign = true),
        slot(57, "NDR", INTERNATIONAL, "NDR Fernsehen", foreign = true),
        slot(58, "RTL", INTERNATIONAL, "RTL Deutschland|RTL Television", foreign = true),
        slot(59, "Sat.1", INTERNATIONAL, foreign = true),
        // Não está na Ziggo (está na KPN e na Telenet): número livre a seguir aos alemães.
        slot(60, "VTM", INTERNATIONAL, foreign = true),
        slot(61, "BBC One", INTERNATIONAL, "BBC 1", foreign = true),
        slot(62, "BBC Two", INTERNATIONAL, "BBC 2", foreign = true),
        slot(71, "TV5Monde", INTERNATIONAL, "TV5", foreign = true),
        slot(72, "Arte", INTERNATIONAL, foreign = true),
        slot(73, "TVE Internacional", INTERNATIONAL, "TVE|TVEi", foreign = true),
        slot(76, "TRT Türk", INTERNATIONAL, foreign = true),
        slot(77, "2M", INTERNATIONAL, "2M Monde", foreign = true),
        slot(80, "Njam!", ENTERTAINMENT),
        slot(81, "NPO 1 Extra", GENERAL),
        slot(82, "NPO 2 Extra", GENERAL),
        slot(83, "NPO Politiek en Nieuws", NEWS, "NPO Politiek|NPO Nieuws"),

        // Filmes, séries e entretenimento extra (100–140).
        slot(100, "FilmBox+ One", MOVIES, "FilmBox Plus One"),
        slot(101, "Film1 Premiere", MOVIES),
        slot(102, "Film1 Action", MOVIES),
        slot(103, "Film1 Family", MOVIES),
        slot(104, "Film1 Drama", MOVIES),
        slot(120, "RTL Crime", ENTERTAINMENT),
        slot(122, "Crime+Investigation", DOCS, "Crime and Investigation"),
        slot(123, "XITE", MUSIC),
        slot(125, "ShortsTV", MOVIES),
        slot(126, "E!", ENTERTAINMENT, "E Entertainment"),
        slot(129, "OUTtv", ENTERTAINMENT),
        slot(133, "Fashion TV", ENTERTAINMENT, foreign = true),
        slot(134, "MyZen TV", ENTERTAINMENT, "My Zen", foreign = true),
        slot(136, "Horse & Country", SPORTS, "Horse and Country|H&C TV", foreign = true),
        slot(140, "RTL Lounge", ENTERTAINMENT),

        // Documentários (201–208).
        slot(201, "Curiosity Channel", DOCS, "Curiosity"),
        slot(202, "Discovery Science", DOCS),
        slot(204, "Nat Geo Wild", DOCS, "National Geographic Wild"),
        slot(205, "Love Nature", DOCS),
        slot(208, "Animal Planet", DOCS),
        slot(230, "Family7", GENERAL),

        // Infantis (302–312). A Disney Junior partilha o 8 com a Veronica: aqui fica à parte no 304, livre.
        slot(302, "Disney Channel", KIDS, "Disney"),
        slot(303, "Nicktoons", KIDS),
        slot(304, "Disney Junior", KIDS, "Disney Jr"),
        slot(305, "Moonbug", KIDS, "Moonbug Kids"),
        slot(306, "Nick Jr", KIDS, "Nick Junior"),
        slot(307, "Cartoon Network", KIDS),
        slot(309, "Cartoonito", KIDS, "Boomerang"),
        slot(310, "Baby TV", KIDS),
        slot(311, "Duck TV", KIDS),
        slot(312, "RTL Telekids", KIDS, "Telekids"),

        // Desporto (400–424).
        slot(400, "Ziggo Sport Extra", SPORTS),
        slot(402, "Ziggo Sport 2", SPORTS, "Ziggo Sport Voetbal"),
        slot(403, "Ziggo Sport 3", SPORTS, "Ziggo Sport Racing"),
        slot(404, "Ziggo Sport 4", SPORTS, "Ziggo Sport Tennis"),
        slot(405, "Ziggo Sport 5", SPORTS, "Ziggo Sport Docu"),
        slot(406, "Ziggo Sport 6", SPORTS, "Ziggo Sport Golf"),
        slot(411, "Eurosport 2", SPORTS),
        slot(422, "ESPN 2", SPORTS),
        slot(423, "ESPN 3", SPORTS),
        slot(424, "ESPN 4", SPORTS),

        // Informação internacional (503–511); de 508 para a frente não estão na Ziggo (estão na KPN).
        slot(503, "CNN", NEWS, "CNN International", foreign = true),
        slot(504, "BBC News", NEWS, "BBC World News", foreign = true),
        slot(505, "Euronews", NEWS, "Euronews English", foreign = true),
        slot(506, "Al Jazeera English", NEWS, "Al Jazeera", foreign = true),
        slot(507, "CNBC Europe", NEWS, "CNBC", foreign = true),
        slot(508, "Sky News", NEWS, foreign = true),
        slot(509, "France 24", NEWS, "France 24 English", foreign = true),
        slot(510, "Bloomberg", NEWS, "Bloomberg TV", foreign = true),
        slot(511, "DW", NEWS, "DW English|Deutsche Welle", foreign = true),

        // Música (538–613).
        slot(538, "TV538", MUSIC, "538 TV"),
        slot(601, "TOP40 TV", MUSIC),
        slot(603, "Qmusic", MUSIC, "Qmusic Kijk Live|Q Music TV"),
        slot(604, "SLAM!TV", MUSIC),
        slot(605, "DanceTelevision", MUSIC),
        slot(606, "Stingray LiteTV", MUSIC, "LiteTV"),
        slot(608, "100% NL TV", MUSIC, "100% NL"),
        slot(609, "TV Oranje", MUSIC),
        slot(610, "192TV", MUSIC),
        slot(611, "Stingray Djazz", MUSIC, "Djazz|Djazz TV", foreign = true),
        slot(612, "Mezzo", MUSIC, foreign = true),
        slot(613, "Stingray Classica", MUSIC, "Classica", foreign = true),

        // Regionais (701–725).
        slot(701, "RTV Noord", GENERAL),
        slot(702, "Omrop Fryslân", GENERAL, "Omroep Friesland"),
        slot(703, "RTV Drenthe", GENERAL),
        slot(704, "RTV Oost", GENERAL),
        slot(705, "Omroep Gelderland", GENERAL),
        slot(706, "Omroep Flevoland", GENERAL),
        slot(707, "NH", GENERAL, "NH Nieuws"),
        slot(708, "RTV Utrecht", GENERAL),
        slot(709, "TV West", GENERAL, "Omroep West"),
        slot(710, "RTV Rijnmond", GENERAL, "Rijnmond"),
        slot(711, "Omroep Zeeland", GENERAL),
        slot(712, "Omroep Brabant", GENERAL),
        slot(713, "L1", GENERAL, "L1 TV|L1 Limburg"),
        slot(725, "AT5", GENERAL),

        // Indianos e turcos (760–778).
        slot(760, "Star Plus", INTERNATIONAL, foreign = true),
        slot(761, "Star Gold", INTERNATIONAL, foreign = true),
        slot(762, "Zee TV", INTERNATIONAL, foreign = true),
        slot(763, "Zee Cinema", INTERNATIONAL, foreign = true),
        slot(764, "Zing", INTERNATIONAL, foreign = true),
        slot(765, "Sony TV Asia", INTERNATIONAL, "Sony TV|Sony Entertainment Television", foreign = true),
        slot(766, "Sony Max", INTERNATIONAL, foreign = true),
        slot(767, "Colors TV", INTERNATIONAL, "Colors", foreign = true),
        slot(770, "Show Türk", INTERNATIONAL, foreign = true),
        slot(771, "Euro D", INTERNATIONAL, foreign = true),
        slot(772, "Euro Star", INTERNATIONAL, foreign = true),
        slot(773, "Habertürk", INTERNATIONAL, foreign = true),
        slot(774, "TV8 Int", INTERNATIONAL, "TV8 International", foreign = true),
        slot(776, "TRT Çocuk", INTERNATIONAL, foreign = true),
        slot(777, "ATV Avrupa", INTERNATIONAL, foreign = true),
        slot(778, "TRT Müzik", INTERNATIONAL, foreign = true),
    ))
}

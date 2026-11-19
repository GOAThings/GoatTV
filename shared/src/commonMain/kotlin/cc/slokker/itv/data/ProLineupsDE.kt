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
 * Alemanha: não há numeração oficial; é a ordem habitual das boxes (como a lista de origem da MagentaTV: Das Erste,
 * ZDF, RTL, SAT.1, ProSieben, VOX, kabel eins, RTL ZWEI) e, a seguir, blocos por tema com os nomes de novembro de 2026
 * (Sky Cinema renomeados em maio de 2026, o Disney Channel passa a Disney TV a 1 de dezembro de 2026).
 */
internal object ProLineupsDE {
    val DE: Lineup = Lineup("de", "de", "MagentaTV", ProLineups.GROUPS, listOf(
        // Os grandes.
        slot(1, "Das Erste", GENERAL, "ARD|ARD Das Erste"),
        slot(2, "ZDF", GENERAL),
        slot(3, "RTL", GENERAL, "RTL Television|RTL Deutschland"),
        slot(4, "SAT.1", GENERAL),
        slot(5, "ProSieben", GENERAL, "Pro7|Pro 7"),
        slot(6, "VOX", GENERAL),
        slot(7, "kabel eins", GENERAL, "Kabel 1"),
        slot(8, "RTL ZWEI", GENERAL, "RTL 2"),
        slot(9, "3sat", GENERAL, "3 sat"),
        slot(10, "arte", GENERAL, "Arte Deutsch"),
        slot(11, "ZDFneo", GENERAL, "ZDF neo"),
        slot(12, "ZDFinfo", DOCS, "ZDF info"),
        slot(13, "ONE", GENERAL, "ARD One|EinsFestival"),
        slot(14, "tagesschau24", NEWS, "tagesschau 24|EinsExtra"),
        slot(15, "phoenix", NEWS, "Phoenix TV"),
        slot(16, "ARD-alpha", DOCS, "BR alpha"),

        // Os terceiros programas.
        slot(20, "BR Fernsehen", GENERAL, "BR|Bayerisches Fernsehen|BR Süd|BR Nord"),
        slot(21, "WDR", GENERAL, "WDR Fernsehen|WDR Köln"),
        slot(22, "NDR", GENERAL, "NDR Fernsehen|NDR Hamburg|NDR Niedersachsen|NDR Schleswig-Holstein|NDR Mecklenburg-Vorpommern"),
        slot(23, "SWR", GENERAL, "SWR Fernsehen|SWR BW|SWR RP|SWR Baden-Württemberg|SWR Rheinland-Pfalz"),
        slot(24, "MDR", GENERAL, "MDR Fernsehen|MDR Sachsen|MDR Thüringen|MDR Sachsen-Anhalt"),
        slot(25, "hr-fernsehen", GENERAL, "hr|Hessischer Rundfunk"),
        slot(26, "rbb", GENERAL, "rbb Fernsehen|rbb Berlin|rbb Brandenburg"),
        slot(27, "SR Fernsehen", GENERAL, "SR|Saarländischer Rundfunk"),
        slot(28, "Radio Bremen TV", GENERAL, "Radio Bremen"),

        // Os privados mais pequenos.
        slot(30, "sixx", ENTERTAINMENT),
        slot(31, "SAT.1 Gold", ENTERTAINMENT),
        slot(32, "ProSieben MAXX", ENTERTAINMENT, "Pro7 MAXX|Pro 7 Maxx"),
        slot(33, "Nitro", ENTERTAINMENT, "RTL Nitro|RTLNitro"),
        slot(34, "RTLup", ENTERTAINMENT, "RTL up|RTLplus"),
        slot(35, "TELE 5", ENTERTAINMENT),
        slot(36, "DMAX", ENTERTAINMENT, "D MAX"),
        slot(37, "TLC", ENTERTAINMENT, "TLC Deutschland"),
        slot(38, "Comedy Central", ENTERTAINMENT, "Comedy Central Deutschland"),
        slot(39, "ServusTV Deutschland", ENTERTAINMENT, "ServusTV|Servus TV"),
        slot(40, "Bibel TV", GENERAL),
        slot(41, "QVC", ENTERTAINMENT, "QVC Deutschland"),
        slot(42, "HSE", ENTERTAINMENT, "HSE24|HSE 24"),

        // Informação.
        slot(50, "ntv", NEWS, "n-tv"),
        slot(51, "WELT", NEWS, "Welt TV|N24"),
        slot(52, "DW", NEWS, "DW Deutsch|Deutsche Welle"),
        slot(53, "euronews", NEWS, "Euronews English|Euronews Deutsch", foreign = true),
        slot(54, "CNN International", NEWS, "CNN|CNN Int", foreign = true),
        slot(55, "BBC News", NEWS, "BBC World News", foreign = true),
        slot(56, "Sky News", NEWS, "Sky News International", foreign = true),
        slot(57, "Bloomberg", NEWS, "Bloomberg TV", foreign = true),
        slot(58, "CNBC", NEWS, "CNBC Europe", foreign = true),
        slot(59, "France 24 English", NEWS, "France 24 EN", foreign = true),
        slot(60, "Al Jazeera English", NEWS, "Al Jazeera|AlJazeera", foreign = true),

        // Desporto.
        slot(70, "SPORT1", SPORTS, "Sport 1"),
        slot(71, "Eurosport 1", SPORTS, "Eurosport"),
        slot(72, "Eurosport 2", SPORTS),
        slot(73, "Sky Sport News", SPORTS),
        slot(74, "Sky Sport Top Event", SPORTS),
        slot(75, "Sky Sport Bundesliga", SPORTS, "Sky Sport Bundesliga 1|Sky Bundesliga|Sky Bundesliga 1"),
        slot(76, "Sky Sport Premier League", SPORTS, "Sky Premier League"),
        slot(77, "Sky Sport Mix", SPORTS),
        slot(78, "Sky Sport 1", SPORTS),
        slot(79, "Sky Sport 2", SPORTS),
        slot(80, "Sky Sport F1", SPORTS, "Sky Sport Formel 1"),
        slot(81, "Sky Sport Tennis", SPORTS),
        slot(82, "Sky Sport Golf", SPORTS),
        slot(83, "DAZN 1", SPORTS, "DAZN 1 Deutschland"),
        slot(84, "DAZN 2", SPORTS, "DAZN 2 Deutschland"),
        slot(85, "sportdigital FUSSBALL", SPORTS, "sportdigital|Sportdigital Fußball|sportdigital1+"),
        slot(87, "Motorvision TV", SPORTS, "Motorvision"),
        slot(88, "Sportdeutschland.TV", SPORTS, "Sporteurope.TV|Sportdeutschland TV"),

        // Filmes e séries.
        slot(100, "Sky Cinema Premieren", MOVIES, "Sky Cinema Premiere"),
        slot(101, "Sky Cinema Blockbuster", MOVIES, "Sky Cinema Highlights|Sky Cinema Best Of"),
        slot(102, "Sky Cinema Action", MOVIES),
        slot(103, "Sky Cinema Feelgood", MOVIES, "Sky Cinema Family"),
        slot(104, "Sky Cinema Classics", MOVIES),
        slot(105, "Sky Cinema Special", MOVIES),
        slot(106, "Sky Atlantic", MOVIES),
        slot(107, "Sky One", MOVIES, "Sky 1"),
        slot(108, "Sky Krimi", MOVIES),
        slot(109, "Sky Crime", MOVIES),
        slot(110, "Sky Replay", MOVIES),
        slot(111, "Universal TV", MOVIES, "Universal Channel"),
        slot(112, "13th Street", MOVIES, "13th Street Universal"),
        slot(113, "SYFY", MOVIES, "Syfy Deutschland"),
        slot(114, "Warner TV Serie", MOVIES, "TNT Serie"),
        slot(115, "Warner TV Film", MOVIES, "TNT Film"),
        slot(116, "Warner TV Comedy", MOVIES, "TNT Comedy"),
        slot(117, "AXN Black", MOVIES, "AXN|Sony Channel"),
        slot(118, "AXN White", MOVIES, "Sony AXN"),
        slot(119, "Kinowelt TV", MOVIES, "Kinowelt"),
        slot(120, "Romance TV", MOVIES),
        slot(121, "Heimatkanal", MOVIES),
        slot(122, "SAT.1 emotions", MOVIES),
        slot(123, "kabel eins classics", MOVIES, "Kabel 1 Classics"),
        slot(124, "RTL Crime", MOVIES),
        slot(125, "RTL Passion", MOVIES),
        slot(126, "RTL Living", ENTERTAINMENT),
        slot(162, "Fix & Foxi", KIDS, "Fix und Foxi"),

        // Documentários.
        slot(130, "kabel eins Doku", DOCS, "Kabel 1 Doku"),
        slot(131, "WELT DOKU", DOCS, "N24 Doku"),
        slot(132, "ntv Doku", DOCS, "n-tv Doku"),
        slot(133, "Discovery Channel", DOCS, "Discovery"),
        slot(134, "National Geographic", DOCS, "Nat Geo|NatGeo|National Geographic Channel"),
        slot(135, "Nat Geo Wild", DOCS, "National Geographic Wild"),
        slot(136, "History", DOCS, "History Channel"),
        slot(137, "Spiegel TV Wissen", DOCS, "Spiegel Wissen"),
        slot(138, "Spiegel Geschichte", DOCS),
        slot(139, "Curiosity Channel", DOCS, "Curiosity"),
        slot(140, "Animal Planet", DOCS),
        slot(141, "Crime + Investigation", DOCS, "Crime and Investigation|Crime & Investigation"),
        slot(142, "A&E", DOCS, "AE"),
        slot(143, "Love Nature", DOCS),
        slot(144, "Terra Mater", DOCS, "Terra Mater Wild"),

        // Infantis.
        slot(150, "KiKA", KIDS, "Kinderkanal|KI.KA"),
        slot(151, "Super RTL", KIDS, "RTL Super|Toggo"),
        slot(152, "Toggo plus", KIDS),
        slot(153, "Nickelodeon", KIDS, "Nick"),
        slot(154, "Disney Channel", KIDS, "Disney TV|Disney"),
        slot(155, "Disney Junior", KIDS, "Disney Jr"),
        slot(156, "Cartoon Network", KIDS),
        slot(157, "Cartoonito", KIDS, "Boomerang"),
        slot(158, "Nick Jr", KIDS, "Nick Junior"),
        slot(159, "Nicktoons", KIDS),
        slot(161, "Baby TV", KIDS, "BabyTV"),

        // Música.
        slot(170, "MTV", MUSIC, "MTV Germany|MTV Deutschland"),
        slot(171, "Deluxe Music", MUSIC),
        slot(172, "Folx TV", MUSIC, "Folx"),
        slot(173, "Schlager Deluxe", MUSIC),
        slot(174, "Classica", MUSIC),
        slot(175, "Mezzo", MUSIC, foreign = true),

        // Áustria e Suíça.
        slot(180, "ORF 1", INTERNATIONAL, "ORF eins|ORF1", foreign = true),
        slot(181, "ORF 2", INTERNATIONAL, "ORF2|ORF 2 Europe", foreign = true),
        slot(182, "ORF III", INTERNATIONAL, "ORF 3", foreign = true),
        slot(183, "ORF Sport+", INTERNATIONAL, "ORF Sport Plus", foreign = true),
        slot(184, "PULS 4", INTERNATIONAL, foreign = true),
        slot(185, "SRF 1", INTERNATIONAL, "SRF eins", foreign = true),
        slot(186, "SRF zwei", INTERNATIONAL, "SRF 2", foreign = true),
        slot(187, "SRF info", INTERNATIONAL, foreign = true),
        slot(188, "RSI LA 1", INTERNATIONAL, "RSI 1", foreign = true),
        slot(189, "TV5Monde", INTERNATIONAL, "TV5 Monde|TV5", foreign = true),
    ))
}

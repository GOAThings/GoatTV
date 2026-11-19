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
 * Itália: a numeração LCN do digitale terrestre (lista de outubro de 2025) de 1 a 70, e os canais da Sky Itália
 * (nomes da grelha de novembro de 2026) em números livres, por temas: entretenimento de 10, desporto de 80, cinema
 * de 100, documentários de 110, infantis de 120, música de 130 e informação estrangeira de 140.
 */
internal object ProLineupsIT {
    val IT: Lineup = Lineup("it", "it", "Sky Italia", ProLineups.GROUPS, listOf(
        // Generalistas (LCN).
        slot(1, "Rai 1", GENERAL, "Rai1|Rai Uno|RaiUno"),
        slot(2, "Rai 2", GENERAL, "Rai2|Rai Due|RaiDue"),
        slot(3, "Rai 3", GENERAL, "Rai3|Rai Tre|RaiTre|Rai 3 TGR"),
        slot(4, "Rete 4", GENERAL, "Rete4|Retequattro|Rete Quattro"),
        slot(5, "Canale 5", GENERAL, "Canale5"),
        slot(6, "Italia 1", GENERAL, "Italia1|Italia Uno"),
        slot(7, "LA7", GENERAL, "La 7"),
        slot(8, "TV8", GENERAL, "TV 8"),
        slot(9, "NOVE", GENERAL, "Nove TV|Canale Nove"),

        // Entretenimento da Sky (nos números das locais, que aqui não há).
        slot(10, "Sky Uno", ENTERTAINMENT, "SkyUno"),
        slot(11, "Sky Atlantic", ENTERTAINMENT, "SkyAtlantic"),
        slot(12, "Sky Serie", ENTERTAINMENT, "Sky Series|SkySerie"),
        slot(13, "Sky Investigation", ENTERTAINMENT, "Sky Investigation Italia"),
        slot(14, "Sky Crime", ENTERTAINMENT, "SkyCrime"),
        slot(15, "Sky Collection", ENTERTAINMENT),
        slot(16, "Comedy Central", ENTERTAINMENT, "Comedy Central Italia"),
        slot(17, "Gambero Rosso", ENTERTAINMENT, "Gambero Rosso Channel"),
        slot(18, "Class TV Moda", ENTERTAINMENT, "Class TV|ClassTV Moda"),

        // O resto do digitale terrestre (LCN).
        slot(20, "20 Mediaset", ENTERTAINMENT, "20Mediaset|Mediaset 20|Venti Mediaset"),
        slot(21, "Rai 4", MOVIES, "Rai4"),
        slot(22, "Iris", MOVIES, "Mediaset Iris"),
        slot(23, "Rai 5", DOCS, "Rai5"),
        slot(24, "Rai Movie", MOVIES, "RaiMovie"),
        slot(25, "Rai Premium", ENTERTAINMENT, "RaiPremium"),
        slot(26, "Cielo", ENTERTAINMENT, "Cielo TV"),
        slot(27, "Twentyseven", MOVIES, "27 Twentyseven|27Twentyseven"),
        slot(28, "TV2000", GENERAL, "TV 2000"),
        slot(29, "LA7 Cinema", MOVIES, "LA7Cinema|LA7d|La7 D"),
        slot(30, "La5", ENTERTAINMENT, "La 5"),
        slot(31, "Real Time", ENTERTAINMENT, "RealTime"),
        slot(32, "QVC", ENTERTAINMENT, "QVC Italia"),
        slot(33, "Food Network", ENTERTAINMENT, "Food Network Italia"),
        slot(34, "Cine34", MOVIES, "Cine 34"),
        slot(35, "Focus", DOCS, "Focus TV"),
        slot(36, "RTL 102.5", MUSIC, "RTL 102.5 TV|RTL1025"),
        slot(37, "Discovery", DOCS, "Discovery Channel|Discovery Italia"),
        slot(38, "Giallo", MOVIES, "Giallo TV"),
        slot(39, "Top Crime", MOVIES, "TOPcrime"),
        slot(40, "Boing", KIDS, "Boing Italia"),
        slot(41, "K2", KIDS),
        slot(42, "Rai Gulp", KIDS, "RaiGulp"),
        slot(43, "Rai Yoyo", KIDS, "RaiYoyo|Rai Yo Yo"),
        slot(44, "Frisbee", KIDS),
        slot(45, "Boing Plus", KIDS),
        slot(46, "Cartoonito", KIDS, "Cartoonito Italia"),
        slot(47, "Super!", KIDS, "Super Italia"),
        slot(48, "Rai News 24", NEWS, "RaiNews24|Rai News|RaiNews"),
        slot(49, "Mediaset Italia 2", ENTERTAINMENT, "Italia 2|Italia2"),
        slot(50, "Sky TG24", NEWS, "SkyTG24|TG24"),
        slot(51, "TGCom24", NEWS, "TGCOM 24|Tgcom24 Mediaset"),
        slot(52, "DMAX", DOCS, "D Max"),
        slot(54, "Rai Storia", DOCS, "RaiStoria"),
        slot(55, "Mediaset Extra", ENTERTAINMENT, "MediasetExtra"),
        slot(56, "HGTV", ENTERTAINMENT, "HGTV Home & Garden|Home & Garden TV"),
        slot(57, "Rai Scuola", DOCS, "RaiScuola"),
        slot(58, "Rai Sport", SPORTS, "RaiSport|Rai Sport +|Rai Sport Plus"),
        slot(59, "Motor Trend", SPORTS, "MotorTrend|Discovery Turbo"),
        slot(60, "Sportitalia", SPORTS, "Sport Italia"),
        slot(61, "iL61", GENERAL, "Il 61"),
        slot(62, "Donna TV", ENTERTAINMENT, "Donna Sport TV"),
        slot(64, "SuperTennis", SPORTS, "Super Tennis|SuperTennis TV"),
        slot(65, "Alma TV", ENTERTAINMENT),
        slot(66, "Radio 105 TV", MUSIC, "Radio 105|105 TV"),
        slot(67, "R101 TV", MUSIC, "R101|Radio 101"),
        slot(69, "Deejay TV", MUSIC, "Radio Deejay TV|DJ TV"),
        slot(70, "Radio Italia TV", MUSIC, "RadioItalia TV|Radio Italia"),

        // Informação italiana fora do LCN principal.
        slot(71, "Class CNBC", NEWS, "ClassCNBC"),
        slot(72, "Sky TG24 Primo Piano", NEWS, "TG24 Primo Piano"),

        // Desporto (Sky, DAZN e clubes).
        slot(80, "Sky Sport 24", SPORTS, "SkySport24"),
        slot(81, "Sky Sport Uno", SPORTS, "Sky Sport 1|SkySport Uno"),
        slot(82, "Sky Sport Calcio", SPORTS, "Sky Sport Serie A|Sky Calcio"),
        slot(83, "Sky Sport Tennis", SPORTS),
        slot(84, "Sky Sport Arena", SPORTS),
        slot(85, "Sky Sport Basket", SPORTS, "Sky Sport NBA"),
        slot(86, "Sky Sport Max", SPORTS),
        slot(87, "Sky Sport F1", SPORTS, "Sky Sport Formula 1"),
        slot(88, "Sky Sport MotoGP", SPORTS, "Sky Sport Moto GP"),
        slot(89, "Sky Sport Golf", SPORTS),
        slot(90, "Sky Sport Legend", SPORTS),
        slot(91, "Sky Sport Mix", SPORTS),
        slot(92, "DAZN 1", SPORTS, "Zona DAZN|Zona DAZN 1|DAZN1"),
        slot(93, "DAZN 2", SPORTS, "Zona DAZN 2|DAZN2"),
        slot(94, "Eurosport 1", SPORTS, "Eurosport"),
        slot(95, "Eurosport 2", SPORTS),
        slot(96, "Milan TV", SPORTS, "AC Milan TV"),
        slot(97, "Inter TV", SPORTS, "Inter Channel"),
        slot(98, "Horse TV", SPORTS),
        slot(99, "Caccia e Pesca", SPORTS, "Caccia & Pesca"),

        // Cinema da Sky.
        slot(100, "Sky Cinema Uno", MOVIES, "Sky Cinema 1|SkyCinema Uno"),
        slot(101, "Sky Cinema Stories", MOVIES, "Sky Cinema Due|Sky Cinema 2"),
        slot(102, "Sky Cinema Collection", MOVIES),
        slot(103, "Sky Cinema Family", MOVIES),
        slot(104, "Sky Cinema Action", MOVIES),
        slot(105, "Sky Cinema Suspense", MOVIES),
        slot(106, "Sky Cinema Romance", MOVIES),
        slot(107, "Sky Cinema Drama", MOVIES),
        slot(108, "Sky Cinema Comedy", MOVIES),

        // Documentários.
        slot(110, "Sky Documentaries", DOCS, "Sky Documentari"),
        slot(111, "Sky Nature", DOCS),
        slot(112, "Sky Adventure", DOCS),
        slot(113, "Sky Arte", DOCS, "SkyArte"),
        slot(114, "History", DOCS, "History Channel|History Italia"),
        slot(115, "National Geographic", DOCS, "Nat Geo|NatGeo|National Geographic Channel"),

        // Infantis da Sky.
        slot(120, "DeAKids", KIDS, "DeA Kids"),
        slot(121, "DeA Junior", KIDS, "DeAJunior"),
        slot(122, "Nickelodeon", KIDS, "Nick"),
        slot(123, "Nick Jr", KIDS, "Nick Junior"),
        slot(124, "Disney Junior", KIDS, "Disney Jr"),
        slot(125, "Cartoon Network", KIDS),
        slot(126, "Boomerang", KIDS),

        // Música.
        slot(130, "MTV", MUSIC, "MTV Italia"),
        slot(131, "Sky Classica", MUSIC),
        slot(132, "Radio Kiss Kiss TV", MUSIC, "Kiss Kiss TV"),
        slot(133, "RDS Social TV", MUSIC, "RDS TV"),
        slot(134, "Virgin Radio TV", MUSIC, "Virgin Radio"),
        slot(135, "Radio Monte Carlo TV", MUSIC, "Radio Monte Carlo|RMC TV"),
        slot(136, "Radiofreccia", MUSIC, "Radio Freccia"),
        slot(137, "Radio Zeta", MUSIC, "Radio Zeta TV"),
        slot(138, "VH1", MUSIC, "VH1 Italia"),

        // Informação e canais estrangeiros que a Sky tem.
        slot(140, "Sky News", NEWS, "Sky News International", foreign = true),
        slot(141, "CNN International", NEWS, "CNN|CNN Int|CNN Intl", foreign = true),
        slot(142, "BBC News", NEWS, "BBC World|BBC World News", foreign = true),
        slot(143, "Euronews", NEWS, "Euronews Italiano|Euronews Italian|Euronews English", foreign = true),
        slot(144, "France 24", NEWS, "France 24 English|France 24 Français", foreign = true),
        slot(145, "Al Jazeera", NEWS, "Al Jazeera English|AlJazeera", foreign = true),
        slot(146, "Bloomberg", NEWS, "Bloomberg TV|Bloomberg Television", foreign = true),
        slot(147, "CNBC", NEWS, "CNBC Europe", foreign = true),
        slot(148, "DW", NEWS, "DW English|Deutsche Welle", foreign = true),
        slot(149, "NHK World Japan", NEWS, "NHK World", foreign = true),
        slot(150, "CGTN", NEWS, foreign = true),
        slot(151, "Arirang", INTERNATIONAL, "Arirang TV|Arirang World", foreign = true),
        slot(152, "TV5Monde", INTERNATIONAL, "TV5 Monde|TV5|TV5Monde Europe", foreign = true),
        slot(153, "RTS Un", INTERNATIONAL, foreign = true),
        slot(154, "RSI LA 1", INTERNATIONAL, "RSI La1|RSI 1", foreign = true),
        slot(155, "RSI LA 2", INTERNATIONAL, "RSI La2|RSI 2", foreign = true),
        slot(156, "San Marino RTV", INTERNATIONAL, "RTV San Marino|San Marino TV|SMtv San Marino", foreign = true),
        slot(157, "Vatican Media", INTERNATIONAL, "Vatican Media Europa|Vatican News", foreign = true),
        slot(158, "Rai Italia", INTERNATIONAL, "Rai International"),
        slot(159, "Record TV Europa", INTERNATIONAL, "Record Europa|Record Internacional", foreign = true),
    ))
}

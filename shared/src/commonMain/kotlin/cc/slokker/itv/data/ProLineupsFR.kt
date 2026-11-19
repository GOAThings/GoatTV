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
 * França: de 1 a 26 a numeração da TNT da Arcom (a de 6 de junho de 2025, com a RMC Life no lugar da Chérie 25 desde
 * outubro de 2025) e, a partir de 30, a numeração do plano CANAL+ (outubro de 2026), com o CANAL+ no 40 como nas
 * boxes; os que o plano não tem (RMC Sport, Ligue 1+, Disney Channel…) ficam em números livres do mesmo tema, e o que
 * no plano passava de 199 (Mezzo) desce para antes dos "Mais canais".
 */
internal object ProLineupsFR {
    val FR: Lineup = Lineup("fr", "fr", "TNT + CANAL+", ProLineups.GROUPS, listOf(
        // A TNT gratuita (o C8 e o NRJ 12 fecharam em fevereiro de 2025; o CANAL+ saiu da TNT em junho de 2025).
        slot(1, "TF1", GENERAL, "TF 1"),
        slot(2, "France 2", GENERAL, "France2"),
        slot(3, "France 3", GENERAL, "France3"),
        slot(4, "France 4", GENERAL, "France4"),
        slot(5, "France 5", GENERAL, "France5"),
        slot(6, "M6", GENERAL, "M 6"),
        slot(7, "Arte", GENERAL, "Arte France"),
        slot(8, "LCP", NEWS, "LCP Public Sénat|Public Sénat|LCP Assemblée Nationale|LCP AN|La Chaîne Parlementaire"),
        slot(9, "W9", ENTERTAINMENT, "W 9"),
        slot(10, "TMC", ENTERTAINMENT, "TMC Monte Carlo"),
        slot(11, "TFX", ENTERTAINMENT, "NT1"),
        slot(12, "Gulli", KIDS),
        slot(13, "BFM TV", NEWS),
        slot(14, "CNews", NEWS, "iTélé|I Tele"),
        slot(15, "LCI", NEWS, "La Chaîne Info"),
        slot(16, "franceinfo", NEWS, "franceinfo TV|France Info TV"),
        slot(17, "CStar", ENTERTAINMENT, "D17"),
        slot(18, "T18", GENERAL, "CMI TV"),
        slot(19, "NOVO19", GENERAL, "OFTV"),
        slot(20, "TF1 Séries Films", MOVIES, "TF1 Series|HD1"),
        slot(21, "L'Équipe", SPORTS, "L'Équipe TV|La Chaîne L'Équipe|L'Équipe 21"),
        slot(22, "6ter", ENTERTAINMENT, "6 ter"),
        slot(23, "RMC Story", ENTERTAINMENT, "Numéro 23"),
        slot(24, "RMC Découverte", DOCS, "RMC Decouverte HD24"),
        slot(25, "RMC Life", ENTERTAINMENT, "Chérie 25"),
        slot(26, "Paris Première", ENTERTAINMENT, "Paris 1ère"),

        // Cinema (plano CANAL+).
        slot(30, "CANAL+ Box Office", MOVIES, "Canal Plus Box Office"),
        slot(31, "CANAL+ Grand Écran", MOVIES, "Canal Plus Grand Ecran"),
        slot(32, "CANAL+ Cinéma(s)", MOVIES, "Canal+ Cinémas|Canal Plus Cinema"),
        slot(33, "OCS", MOVIES, "OCS Max|Ciné+ OCS|OCS Géants"),
        slot(34, "Ciné+ Frisson", MOVIES, "Cine Plus Frisson"),
        slot(35, "Ciné+ Émotion", MOVIES, "Cine Plus Emotion"),
        slot(36, "Ciné+ Family", MOVIES, "Cine Plus Famiz|Ciné+ Famiz"),
        slot(37, "Ciné+ Festival", MOVIES, "Cine Plus Festival"),
        slot(38, "Ciné+ Classic", MOVIES, "Cine Plus Classic"),
        slot(40, "CANAL+", GENERAL, "Canal Plus"),
        slot(44, "Action", MOVIES),
        slot(45, "TCM Cinéma", MOVIES, "TCM"),

        // Séries.
        slot(50, "CANAL+ Séries", MOVIES, "Canal Plus Séries"),
        slot(51, "Polar+", MOVIES, "Polar Plus"),
        slot(52, "Série Club", MOVIES, "Serie Club TV"),
        slot(53, "Warner TV", MOVIES, "Warner TV France"),
        slot(55, "RTL9", ENTERTAINMENT, "RTL 9"),
        slot(56, "AB1", ENTERTAINMENT, "AB 1"),
        slot(57, "Novelas+", ENTERTAINMENT, "Novelas TV"),

        // Desporto.
        slot(60, "CANAL+ Sport 360", SPORTS, "Canal Plus Sport 360"),
        slot(61, "CANAL+ Foot", SPORTS, "Canal Plus Foot"),
        slot(62, "CANAL+ Sport", SPORTS, "Canal Plus Sport"),
        slot(63, "Infosport+", SPORTS, "Infosport Plus"),
        slot(64, "Ligue 1+", SPORTS, "Ligue 1 Plus|Ligue1+"),
        slot(65, "RMC Sport 1", SPORTS, "RMC Sport"),
        slot(66, "beIN Sports 1", SPORTS, "beIN Sport 1|beIN 1"),
        slot(67, "beIN Sports 2", SPORTS, "beIN Sport 2|beIN 2"),
        slot(68, "beIN Sports 3", SPORTS, "beIN Sport 3|beIN 3"),
        slot(69, "Eurosport 1", SPORTS, "Eurosport"),
        slot(70, "Eurosport 2", SPORTS),
        slot(71, "RMC Sport 2", SPORTS),
        slot(72, "Golf+", SPORTS, "Golf Plus"),
        slot(73, "Automoto", SPORTS, "Automoto La Chaîne"),
        slot(74, "Cheval TV", SPORTS),
        slot(75, "Equidia", SPORTS, "Equidia Live"),
        slot(76, "Sport en France", SPORTS),
        slot(77, "Red Bull TV", SPORTS, "RedBull TV"),
        slot(78, "After Foot TV", SPORTS, "AfterFoot TV"),

        // Entretenimento.
        slot(80, "Comédie+", ENTERTAINMENT, "Comedie Plus"),
        slot(81, "TV Breizh", ENTERTAINMENT),
        slot(84, "Téva", ENTERTAINMENT),
        slot(86, "TLC", ENTERTAINMENT, "TLC France"),
        slot(98, "TV5Monde", INTERNATIONAL, "TV5 Monde|TV5 Monde France Belgique Suisse|TV5", foreign = true),

        // Documentários.
        slot(109, "Histoire TV", DOCS),
        slot(110, "CANAL+ Docs", DOCS, "Canal Plus Docs|Canal+ Décalé"),
        slot(111, "Planète+", DOCS, "Planete Plus"),
        slot(112, "Planète+ Crime", DOCS, "Planète+ CI|Planète+ Crime Investigation"),
        slot(113, "Planète+ Aventure", DOCS, "Planète+ A&E|Planète+ Aventure Expérience"),
        slot(114, "Museum TV", DOCS),
        slot(115, "National Geographic", DOCS, "Nat Geo|NatGeo|National Geographic Channel"),
        slot(116, "Love Nature", DOCS),
        slot(117, "Ushuaïa TV", DOCS),
        slot(118, "Toute l'Histoire", DOCS),
        slot(119, "Discovery Channel", DOCS, "Discovery"),
        slot(120, "Discovery Investigation", DOCS, "Investigation Discovery"),
        slot(121, "Dog & Cat TV", DOCS, "Dog and Cat TV"),
        slot(122, "Seasons", DOCS),
        slot(123, "Chasse et Pêche", DOCS, "Chasse & Pêche"),
        slot(124, "Animaux", DOCS),
        slot(125, "TV Monaco", GENERAL),
        slot(126, "Le Figaro TV", NEWS, "Figaro TV"),
        slot(127, "8 Mont-Blanc", GENERAL, "TV8 Mont Blanc"),
        slot(128, "Mieux", ENTERTAINMENT, "Mieux TV"),

        // Infantis.
        slot(130, "CANAL+ Kids", KIDS, "Canal Plus Kids|Canal+ Family"),
        slot(131, "Piwi+", KIDS, "Piwi Plus"),
        slot(132, "Disney Channel", KIDS, "Disney"),
        slot(133, "Nickelodeon Junior", KIDS, "Nick Jr|Nick Junior"),
        slot(134, "Tiji", KIDS),
        slot(135, "Cartoonito", KIDS),
        slot(136, "Boomerang", KIDS),
        slot(137, "Télétoon+", KIDS, "Teletoon Plus"),
        slot(141, "Cartoon Network", KIDS),
        slot(142, "Nickelodeon", KIDS, "Nick"),
        slot(144, "Canal J", KIDS),
        slot(146, "Nicktoons", KIDS),

        // Nova geração.
        slot(152, "MTV", MUSIC, "MTV France"),
        slot(154, "Comedy Central", ENTERTAINMENT, "Comedy Central France"),
        slot(156, "Mangas", ENTERTAINMENT),
        slot(158, "MGG TV", ENTERTAINMENT, "MGG"),
        slot(159, "Warner TV Next", ENTERTAINMENT),

        // Informação.
        slot(160, "La Chaîne Météo", NEWS, "Chaîne Météo"),
        slot(166, "CNews Prime", NEWS),
        slot(167, "Europe 1 TV", NEWS),
        slot(168, "France 24", NEWS, "France 24 Français"),
        slot(169, "Euronews", NEWS, "Euronews Français"),
        slot(170, "BBC News", NEWS, "BBC World News", foreign = true),
        slot(171, "CNN International", NEWS, "CNN|CNN Int", foreign = true),
        slot(172, "BFM Business", NEWS, "BFM Biz"),
        slot(173, "Bloomberg", NEWS, "Bloomberg TV", foreign = true),
        slot(174, "CNBC", NEWS, "CNBC Europe", foreign = true),
        slot(175, "i24News", NEWS, "i24 News Français"),
        slot(176, "KTO", GENERAL, "KTO TV"),
        slot(177, "Sky News", NEWS, "Sky News International", foreign = true),
        slot(178, "Al Jazeera English", NEWS, "Al Jazeera|AlJazeera", foreign = true),

        // Música.
        slot(180, "Europe 2 Pop TV", MUSIC, "Europe 2 TV"),
        slot(181, "M6 Music", MUSIC, "M6 Music Hits"),
        slot(183, "NRJ Hits", MUSIC),
        slot(184, "Trace Urban", MUSIC),
        slot(186, "RFM TV", MUSIC),
        slot(188, "Melody", MUSIC, "Melody TV"),
        slot(189, "Mezzo", MUSIC),
        slot(190, "Mezzo Live", MUSIC),
    ))
}

package cc.slokker.itv.data

import cc.slokker.itv.data.ProLineup.Lineup
import cc.slokker.itv.data.ProLineup.Slot

/**
 * As tabelas dos operadores (Modo TV Pro). Os números e os grupos seguem a arrumação habitual das boxes do país; os
 * outros nomes são como os canais aparecem nas listas (nomes antigos, abreviaturas). Os grupos são ids: cada
 * plataforma diz-os na língua da pessoa.
 */
internal object ProLineups {
    const val GENERAL = "general"
    const val NEWS = "news"
    const val ENTERTAINMENT = "entertainment"
    const val MOVIES = "movies"
    const val SPORTS = "sports"
    const val KIDS = "kids"
    const val DOCS = "docs"
    const val MUSIC = "music"

    val GROUPS = listOf(GENERAL, NEWS, ENTERTAINMENT, MOVIES, SPORTS, KIDS, DOCS, MUSIC)

    val PT = Lineup(
        "pt", GROUPS,
        listOf(
            Slot(1, "RTP1", GENERAL, listOf("RTP 1")),
            Slot(2, "RTP2", GENERAL, listOf("RTP 2")),
            Slot(3, "SIC", GENERAL),
            Slot(4, "TVI", GENERAL),
            Slot(5, "SIC Notícias", NEWS, listOf("SIC N")),
            Slot(6, "RTP3", NEWS, listOf("RTP 3", "RTP Informação")),
            Slot(7, "CNN Portugal", NEWS, listOf("TVI24", "TVI 24")),
            Slot(8, "CMTV", NEWS, listOf("CM TV")),
            Slot(9, "Porto Canal", GENERAL),
            Slot(10, "NOW", NEWS, listOf("Canal NOW")),
            Slot(11, "RTP Memória", GENERAL, listOf("RTP Memoria")),
            Slot(12, "RTP Açores", GENERAL, listOf("RTP Acores")),
            Slot(13, "RTP Madeira", GENERAL),
            Slot(14, "ARTV", GENERAL, listOf("Canal Parlamento")),
            Slot(15, "SIC Mulher", ENTERTAINMENT),
            Slot(16, "SIC Radical", ENTERTAINMENT),
            Slot(17, "SIC Caras", ENTERTAINMENT),
            Slot(18, "TVI Reality", ENTERTAINMENT, listOf("TVI Direct")),
            Slot(19, "TVI Ficção", ENTERTAINMENT, listOf("TVI Ficcao")),
            Slot(20, "V+ TVI", ENTERTAINMENT, listOf("V Mais TVI")),
            Slot(21, "Globo", ENTERTAINMENT, listOf("Globo Portugal", "TV Globo")),
            Slot(22, "Casa e Cozinha", ENTERTAINMENT),
            Slot(23, "24 Kitchen", ENTERTAINMENT, listOf("24Kitchen")),
            Slot(24, "TLC", ENTERTAINMENT),
            Slot(25, "E!", ENTERTAINMENT, listOf("E Entertainment")),
            Slot(26, "Canal Q", ENTERTAINMENT),
            Slot(30, "TVCine Top", MOVIES, listOf("TV Cine Top", "TVCine 1")),
            Slot(31, "TVCine Edition", MOVIES, listOf("TV Cine Edition", "TVCine 2")),
            Slot(32, "TVCine Emotion", MOVIES, listOf("TV Cine Emotion", "TVCine 3")),
            Slot(33, "TVCine Action", MOVIES, listOf("TV Cine Action", "TVCine 4")),
            Slot(34, "AXN", MOVIES),
            Slot(35, "AXN White", MOVIES),
            Slot(36, "AXN Movies", MOVIES),
            Slot(37, "STAR Channel", MOVIES, listOf("FOX", "Fox Portugal")),
            Slot(38, "STAR Life", MOVIES, listOf("FOX Life")),
            Slot(39, "STAR Crime", MOVIES, listOf("FOX Crime")),
            Slot(40, "STAR Movies", MOVIES, listOf("FOX Movies")),
            Slot(41, "STAR Comedy", MOVIES, listOf("FOX Comedy")),
            Slot(42, "Hollywood", MOVIES, listOf("Canal Hollywood")),
            Slot(43, "Cinemundo", MOVIES),
            Slot(44, "AMC", MOVIES),
            Slot(45, "SYFY", MOVIES, listOf("Syfy Portugal")),
            Slot(50, "Sport TV 1", SPORTS, listOf("SportTV 1", "Sport TV1")),
            Slot(51, "Sport TV 2", SPORTS, listOf("SportTV 2", "Sport TV2")),
            Slot(52, "Sport TV 3", SPORTS, listOf("SportTV 3", "Sport TV3")),
            Slot(53, "Sport TV 4", SPORTS, listOf("SportTV 4", "Sport TV4")),
            Slot(54, "Sport TV 5", SPORTS, listOf("SportTV 5", "Sport TV5")),
            Slot(55, "Sport TV 6", SPORTS, listOf("SportTV 6", "Sport TV6")),
            Slot(56, "Sport TV 7", SPORTS, listOf("SportTV 7", "Sport TV7")),
            Slot(57, "Sport TV+", SPORTS, listOf("Sport TV Mais", "SportTV+")),
            Slot(58, "DAZN 1", SPORTS, listOf("Eleven 1", "Eleven Sports 1")),
            Slot(59, "DAZN 2", SPORTS, listOf("Eleven 2", "Eleven Sports 2")),
            Slot(60, "DAZN 3", SPORTS, listOf("Eleven 3", "Eleven Sports 3")),
            Slot(61, "DAZN 4", SPORTS, listOf("Eleven 4", "Eleven Sports 4")),
            Slot(62, "DAZN 5", SPORTS, listOf("Eleven 5", "Eleven Sports 5")),
            Slot(63, "DAZN 6", SPORTS, listOf("Eleven 6", "Eleven Sports 6")),
            Slot(64, "Benfica TV", SPORTS, listOf("BTV", "BTV 1", "Benfica TV 1")),
            Slot(65, "Sporting TV", SPORTS),
            Slot(66, "FC Porto TV", SPORTS, listOf("Porto TV")),
            Slot(67, "Canal 11", SPORTS, listOf("11")),
            Slot(68, "Eurosport 1", SPORTS, listOf("Eurosport")),
            Slot(69, "Eurosport 2", SPORTS),
            Slot(70, "Panda", KIDS, listOf("Canal Panda")),
            Slot(71, "Panda Kids", KIDS),
            Slot(72, "SIC K", KIDS),
            Slot(73, "Disney Channel", KIDS, listOf("Disney")),
            Slot(74, "Disney Junior", KIDS, listOf("Disney Jr")),
            Slot(75, "Cartoon Network", KIDS),
            Slot(76, "Cartoonito", KIDS, listOf("Boomerang")),
            Slot(77, "Nickelodeon", KIDS, listOf("Nick")),
            Slot(78, "Nick Jr", KIDS, listOf("Nick Junior")),
            Slot(79, "Baby TV", KIDS, listOf("BabyTV")),
            Slot(80, "JimJam", KIDS, listOf("Jim Jam")),
            Slot(85, "Discovery", DOCS, listOf("Discovery Channel")),
            Slot(86, "National Geographic", DOCS, listOf("Nat Geo", "NatGeo")),
            Slot(87, "Nat Geo Wild", DOCS, listOf("National Geographic Wild")),
            Slot(88, "História", DOCS, listOf("Canal História", "Historia", "History")),
            Slot(89, "Odisseia", DOCS),
            Slot(90, "Travel Channel", DOCS),
            Slot(91, "Blaze", DOCS),
            Slot(92, "Discovery Science", DOCS),
            Slot(95, "MTV Portugal", MUSIC, listOf("MTV")),
            Slot(96, "Afro Music", MUSIC),
            Slot(97, "Trace Urban", MUSIC),
            Slot(98, "Kiss TV", MUSIC),
        ),
    )
}

package cc.slokker.itv.data

import cc.slokker.itv.data.ProLineup.Lineup
import cc.slokker.itv.data.ProLineup.Slot

/**
 * As tabelas do Modo TV Pro. Os canais estão uma vez só ([PT_CHANNELS]), com o número em cada operador português (só
 * o da NOS se usa, ver [PT]; os outros ficam para comparar). Os outros nomes são como os canais aparecem nas listas (nomes antigos, abreviaturas). Os grupos são
 * ids: cada plataforma diz-os na língua da pessoa.
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
    const val INTERNATIONAL = "international"

    val GROUPS = listOf(GENERAL, NEWS, ENTERTAINMENT, MOVIES, SPORTS, KIDS, DOCS, MUSIC, INTERNATIONAL)

    /** Os canais sem número na tabela começam aqui (depois de todos os outros). */
    const val EXTRA_FROM = 300

    /** Um canal português (ou que os operadores portugueses têm) e o número dele em cada um. */
    private class Ch(
        val name: String,
        val group: String,
        val meo: Int? = null,
        val nos: Int? = null,
        val vodafone: Int? = null,
        val aliases: List<String> = emptyList(),
        val foreign: Boolean = false,
    )

    private fun ch(name: String, group: String, meo: Int? = null, nos: Int? = null, vodafone: Int? = null, aka: String = "", foreign: Boolean = false) =
        Ch(name, group, meo, nos, vodafone, aka.split('|').map { it.trim() }.filter { it.isNotEmpty() }, foreign)

    /**
     * Os números: MEO Fibra (grelha de setembro de 2026; os premium que ela não traz, como Sport TV 1 a 7, DAZN e
     * TVCine, em posições livres ao pé dos do mesmo género), NOS satélite (2025) e Vodafone (box HD, fevereiro de 2020).
     */
    private val PT_CHANNELS = listOf(
        ch("RTP1", GENERAL, 1, 1, 1, "RTP 1"),
        ch("RTP2", GENERAL, 2, 2, 2, "RTP 2"),
        ch("SIC", GENERAL, 3, 3, 3),
        ch("TVI", GENERAL, 4, 4, 4),
        ch("SIC Notícias", NEWS, 5, 5, 5, "SIC N"),
        ch("RTP Notícias", NEWS, 6, 6, 6, "RTP3|RTP 3|RTP Informação"),
        ch("CNN Portugal", NEWS, 7, 7, 7, "TVI24|TVI 24"),
        ch("CMTV", NEWS, 8, 8, 8, "CM TV"),
        ch("NOW", NEWS, 9, aka = "News Now|Canal NOW"),
        ch("Globo", ENTERTAINMENT, 10, 10, 10, "Globo Portugal|TV Globo"),
        ch("Canal 11", SPORTS, 11, vodafone = 11, aka = "11"),
        ch("V+ TVI", ENTERTAINMENT, 12, aka = "V Mais TVI"),
        ch("SIC Mulher", ENTERTAINMENT, 13, 11, 16),
        ch("SIC Novelas", ENTERTAINMENT, 14),
        ch("TVI Ficção", ENTERTAINMENT, 15, aka = "TVI Ficcao"),
        ch("Veja", ENTERTAINMENT, 17, aka = "Veja TV"),
        ch("Benfica TV", SPORTS, 18, 30, 32, "BTV|BTV 1|Benfica TV 1"),
        ch("BTV 2", SPORTS, nos = 31, aka = "Benfica TV 2"),
        ch("FC Porto TV", SPORTS, 19),
        ch("Sport TV+", SPORTS, 20, vodafone = 9, aka = "Sport TV Mais|SportTV+"),
        ch("Sport TV 1", SPORTS, 21, 20, 20, "SportTV 1|Sport TV1"),
        ch("Sport TV 2", SPORTS, 22, 21, 21, "SportTV 2|Sport TV2"),
        ch("Sport TV 3", SPORTS, 23, 22, 22, "SportTV 3|Sport TV3"),
        ch("Sport TV 4", SPORTS, 24, 23, 23, "SportTV 4|Sport TV4"),
        ch("Sport TV 5", SPORTS, 25, 24, 24, "SportTV 5|Sport TV5"),
        ch("Sport TV 6", SPORTS, 26, aka = "SportTV 6|Sport TV6"),
        ch("Sport TV 7", SPORTS, 27, aka = "SportTV 7|Sport TV7"),
        ch("DAZN 1", SPORTS, 28, vodafone = 41, aka = "Eleven 1|Eleven Sports 1"),
        ch("DAZN 2", SPORTS, 29, vodafone = 42, aka = "Eleven 2|Eleven Sports 2"),
        ch("DAZN 3", SPORTS, 30, vodafone = 43, aka = "Eleven 3|Eleven Sports 3"),
        ch("DAZN 4", SPORTS, 31, vodafone = 44, aka = "Eleven 4|Eleven Sports 4"),
        ch("DAZN 5", SPORTS, 32, vodafone = 45, aka = "Eleven 5|Eleven Sports 5"),
        ch("DAZN 6", SPORTS, 33, vodafone = 46, aka = "Eleven 6|Eleven Sports 6"),
        ch("A Bola TV", SPORTS, 34, vodafone = 31, aka = "ABola TV"),
        ch("Porto Canal", GENERAL, 35, 13, 13),
        ch("Sporting TV", SPORTS, 36, 34, 34),
        ch("W-Sport", SPORTS, 37, aka = "W Sport"),
        ch("Eurosport 1", SPORTS, 38, 130, 37, "Eurosport"),
        ch("Eurosport 2", SPORTS, 39, 131, 39),
        ch("NBA Sport TV", SPORTS, vodafone = 36, foreign = true),
        ch("Eurosport News", SPORTS, nos = 132, foreign = true),
        ch("Eurosport 2 Xtra", SPORTS, nos = 148),
        ch("Fuel TV", SPORTS, nos = 133, vodafone = 47, foreign = true),
        ch("Motorvision", SPORTS, nos = 134, aka = "Motorvision TV", foreign = true),
        ch("Toros TV", SPORTS, nos = 141, vodafone = 220, foreign = true),
        ch("Caça e Pesca", SPORTS, nos = 142, vodafone = 221, aka = "Caca e Pesca|Caça&Pesca"),
        ch("PFC", SPORTS, nos = 173, vodafone = 48, foreign = true),
        ch("Disney Channel", KIDS, 40, 40, 50, "Disney"),
        ch("Cartoon Network", KIDS, 41, 47, 53),
        ch("Panda Kids", KIDS, 42, 43, 55, "Panda Biggs|Biggs"),
        ch("SIC K", KIDS, 43, vodafone = 57),
        ch("Nickelodeon", KIDS, 44, 41, aka = "Nick"),
        ch("Disney Junior", KIDS, 45, 44, 51, "Disney Jr"),
        ch("Panda", KIDS, 46, 42, 54, "Canal Panda"),
        ch("Cartoonito", KIDS, 47, vodafone = 58, aka = "Boomerang"),
        ch("Nick Jr", KIDS, 48, aka = "Nick Junior"),
        ch("Baby TV", KIDS, 49, vodafone = 56, aka = "BabyTV"),
        ch("BabyFirst", KIDS, nos = 46, aka = "Baby First", foreign = true),
        ch("Lolly Kids", KIDS, 50),
        ch("JimJam", KIDS, 51, aka = "Jim Jam"),
        ch("Super RTL", KIDS, vodafone = 61, foreign = true),
        ch("Gametoon", KIDS, 110),
        ch("Cinemundo", MOVIES, 60, vodafone = 82),
        ch("Hollywood", MOVIES, 61, 55, 80, "Canal Hollywood"),
        ch("STAR Movies", MOVIES, 62, 68, 84, "FOX Movies"),
        ch("AMC", MOVIES, 63, vodafone = 103),
        ch("AXN Movies", MOVIES, 64, vodafone = 99),
        ch("TVCine Top", MOVIES, 65, 75, 70, "TV Cine Top|TVCine 1"),
        ch("TVCine Edition", MOVIES, 66, 76, 71, "TV Cine Edition|TVCine 2"),
        ch("TVCine Emotion", MOVIES, 67, 77, 72, "TV Cine Emotion|TVCine 3"),
        ch("TVCine Action", MOVIES, 68, 78, 73, "TV Cine Action|TVCine 4"),
        ch("TVSéries", MOVIES, nos = 80, aka = "TV Series"),
        ch("MOV", MOVIES, nos = 84),
        ch("STAR Channel", MOVIES, 70, 89, 86, "FOX|Fox Portugal"),
        ch("STAR Life", MOVIES, 71, 88, 90, "FOX Life"),
        ch("STAR Crime", MOVIES, 72, 91, 92, "FOX Crime"),
        ch("STAR Comedy", MOVIES, 73, 67, 95, "FOX Comedy"),
        ch("AXN", MOVIES, 74, 86, 88),
        ch("AXN White", MOVIES, 75, 93, 97),
        ch("AXN Black", MOVIES, nos = 87),
        ch("SYFY", MOVIES, 76, vodafone = 101, aka = "Syfy Portugal"),
        ch("Dizi", MOVIES, 77, aka = "Dizi Channel"),
        ch("VinTV", MOVIES, 78, aka = "Vin TV"),
        ch("AMC Break", MOVIES, 107),
        ch("AMC Crime", MOVIES, 114),
        ch("EuroChannel", MOVIES, vodafone = 218, aka = "Euro Channel", foreign = true),
        ch("Discovery", DOCS, 90, 100, 129, "Discovery Channel"),
        ch("História", DOCS, 91, 112, 135, "Canal História|Historia|History"),
        ch("Odisseia", DOCS, 92, 109, 136),
        ch("Nat Geo Wild", DOCS, 93, 107, 133, "National Geographic Wild"),
        ch("Travel Channel", DOCS, 94, 119, 144),
        ch("National Geographic", DOCS, 95, 116, 131, "Nat Geo|NatGeo"),
        ch("Discovery Science", DOCS, 96),
        ch("Blaze", DOCS, 97, vodafone = 139),
        ch("A&E", DOCS, nos = 114, aka = "AE"),
        ch("Discovery Showcase", DOCS, nos = 115, aka = "Discovery HD Showcase"),
        ch("Docubox", DOCS, vodafone = 138, foreign = true),
        ch("Travelxp", DOCS, 124, aka = "Travel XP"),
        ch("TVI Reality", ENTERTAINMENT, 99, 12, aka = "TVI Direct"),
        ch("RTP Memória", GENERAL, 100, 19, 17, "RTP Memoria"),
        ch("Canal Q", ENTERTAINMENT, 101, 15, 19),
        ch("SIC Radical", ENTERTAINMENT, 105, 9, 15),
        ch("TLC", ENTERTAINMENT, 118, 71, 141),
        ch("E!", ENTERTAINMENT, 119, 56, 142, "E Entertainment"),
        ch("SIC Caras", ENTERTAINMENT, 120, 14, 14),
        ch("Casa e Cozinha", ENTERTAINMENT, 122),
        ch("24 Kitchen", ENTERTAINMENT, 123, 95, 18, "24Kitchen"),
        ch("Fashion TV", ENTERTAINMENT, 126, 57, 146, "FashionTV", foreign = true),
        ch("BBC Entertainment", ENTERTAINMENT, nos = 58, vodafone = 152, foreign = true),
        ch("Globo Premium", ENTERTAINMENT, nos = 170),
        ch("Globo Now", ENTERTAINMENT, vodafone = 160),
        ch("Food Network", ENTERTAINMENT, vodafone = 148, foreign = true),
        ch("My Zen", ENTERTAINMENT, vodafone = 153, aka = "MyZen TV", foreign = true),
        ch("Luxe TV", ENTERTAINMENT, vodafone = 154, foreign = true),
        ch("Insight TV", ENTERTAINMENT, vodafone = 156, foreign = true),
        ch("Ginx", ENTERTAINMENT, vodafone = 157, aka = "Ginx Esports TV", foreign = true),
        ch("The QYOU", ENTERTAINMENT, vodafone = 158, aka = "QYOU", foreign = true),
        ch("Canal 180", ENTERTAINMENT, vodafone = 180),
        ch("Kuriakos TV", ENTERTAINMENT, vodafone = 216, aka = "Kuriakos"),
        ch("MTV Portugal", MUSIC, 130, 72, 111, "MTV"),
        ch("Trace Urban", MUSIC, 132, vodafone = 122, foreign = true),
        ch("MCM Pop", MUSIC, 133, 159, 118, foreign = true),
        ch("MCM Top", MUSIC, 134, vodafone = 116, foreign = true),
        ch("Kiss TV", MUSIC, 135),
        ch("Afro Music", MUSIC, 136, vodafone = 121),
        ch("Alma Lusa", MUSIC, 139),
        ch("Mezzo", MUSIC, 141, 157, 120, foreign = true),
        ch("Mezzo Live", MUSIC, vodafone = 125, foreign = true),
        ch("VH1", MUSIC, nos = 154, vodafone = 113, aka = "VH1 Europe", foreign = true),
        ch("VH1 Classic", MUSIC, nos = 155, foreign = true),
        ch("Classica", MUSIC, vodafone = 114, foreign = true),
        ch("Djazz", MUSIC, vodafone = 115, aka = "Djazz TV", foreign = true),
        ch("C Music", MUSIC, vodafone = 119, foreign = true),
        ch("Trace Toca", MUSIC, vodafone = 124, foreign = true),
        ch("RTP Açores", GENERAL, 160, 18, 185, "RTP Acores"),
        ch("RTP Madeira", GENERAL, 161, 17, 184),
        ch("ARTV", GENERAL, 162, 219, 188, "Canal Parlamento|AR TV"),
        ch("Localvisão TV", GENERAL, 163, vodafone = 189, aka = "Localvisao"),
        ch("Canção Nova", GENERAL, 182, 186, 187, "Cancao Nova|TV Canção Nova"),
        ch("ETV", NEWS, nos = 200, aka = "Económico TV"),
        ch("UM Europa", INTERNATIONAL, 184),
        ch("Record Europa", INTERNATIONAL, 192, 171, 162, "Record Internacional|TV Record", foreign = true),
        ch("Record News", INTERNATIONAL, 193, vodafone = 178, foreign = true),
        ch("Euronews", NEWS, 195, 203, 164, "Euronews English", foreign = true),
        ch("RTP África", INTERNATIONAL, 196, 190, 191, "RTP Africa"),
        ch("TPA Notícias", INTERNATIONAL, 199, aka = "TPA Noticias", foreign = true),
        ch("TPA Internacional", INTERNATIONAL, vodafone = 192, aka = "TPA", foreign = true),
        ch("TCV Internacional", INTERNATIONAL, vodafone = 194, aka = "TCV", foreign = true),
        ch("CNN", NEWS, 205, 204, 165, "CNN International", foreign = true),
        ch("Bloomberg", NEWS, 206, 211, 167, "Bloomberg TV", foreign = true),
        ch("Sky News", NEWS, 211, 205, 168, foreign = true),
        ch("BBC World News", NEWS, nos = 206, vodafone = 169, aka = "BBC News", foreign = true),
        ch("CNBC", NEWS, nos = 210, vodafone = 170, foreign = true),
        ch("France 24", NEWS, 216, 208, 176, "France 24 English", foreign = true),
        ch("i24News", NEWS, 217, aka = "i24 News", foreign = true),
        ch("NHK World Japan", NEWS, 219, vodafone = 215, aka = "NHK World", foreign = true),
        ch("CGTN", NEWS, 220, foreign = true),
        ch("Al Jazeera", NEWS, 225, 207, 171, "Al Jazeera English|AlJazeera", foreign = true),
        ch("TRT World", NEWS, 226, foreign = true),
        ch("RAI News", NEWS, vodafone = 172, aka = "RAI News 24", foreign = true),
        ch("DW", NEWS, vodafone = 175, aka = "DW English|Deutsche Welle", foreign = true),
        ch("TVE Internacional", INTERNATIONAL, 234, 221, 202, "TVEi|TVE i", foreign = true),
        ch("TVE 24h", INTERNATIONAL, 235, 212, 174, "24h|Canal 24 Horas", foreign = true),
        ch("TV Galicia", INTERNATIONAL, 236, 220, 200, "TVG", foreign = true),
        ch("TV5Monde", INTERNATIONAL, 250, 228, 204, "TV5 Monde|TV5", foreign = true),
        ch("BFM TV", INTERNATIONAL, 251, aka = "BFMTV", foreign = true),
        ch("BVN", INTERNATIONAL, 270, foreign = true),
        ch("CCTV 4", INTERNATIONAL, 278, foreign = true),
        ch("VOX", INTERNATIONAL, vodafone = 198, foreign = true),
        ch("RTL", INTERNATIONAL, vodafone = 199, foreign = true),
        ch("M6", INTERNATIONAL, vodafone = 203, foreign = true),
        ch("KBS World", INTERNATIONAL, vodafone = 209, foreign = true),
        ch("RAI Uno", INTERNATIONAL, vodafone = 210, aka = "RAI 1", foreign = true),
        ch("Pro TV Internațional", INTERNATIONAL, vodafone = 211, aka = "Pro TV International", foreign = true),
        ch("Arirang", INTERNATIONAL, vodafone = 217, aka = "Arirang TV", foreign = true),
    )

    /**
     * Os canais que a NOS não tem, em números livres no meio dos do mesmo tema (os outros ficam a partir de
     * [EXTRA_FROM]).
     */
    private val FILL = mapOf(
        "NOW" to 16,
        "Sport TV+" to 25, "Sport TV 6" to 26, "Sport TV 7" to 27, "Canal 11" to 28, "A Bola TV" to 29,
        "FC Porto TV" to 32, "W-Sport" to 33,
        "Cartoonito" to 45, "Nick Jr" to 48, "Baby TV" to 49, "Lolly Kids" to 50, "JimJam" to 51, "SIC K" to 52,
        "Super RTL" to 53, "Gametoon" to 54,
        "V+ TVI" to 59, "SIC Novelas" to 60, "TVI Ficção" to 61, "Veja" to 62, "Casa e Cozinha" to 63, "Canal 180" to 64,
        "Globo Now" to 65, "Food Network" to 66, "Cinemundo" to 69, "AMC" to 70, "AXN Movies" to 73, "SYFY" to 74,
        "Dizi" to 79, "VinTV" to 81, "AMC Break" to 82, "AMC Crime" to 83, "EuroChannel" to 85, "My Zen" to 90,
        "Luxe TV" to 92, "Insight TV" to 94,
        "Discovery Science" to 101, "Blaze" to 102, "Docubox" to 103, "Travelxp" to 104,
        "DAZN 1" to 135, "DAZN 2" to 136, "DAZN 3" to 137, "DAZN 4" to 138, "DAZN 5" to 139, "DAZN 6" to 140,
        "Trace Urban" to 150, "MCM Top" to 151, "Kiss TV" to 152, "Afro Music" to 153, "Alma Lusa" to 156,
        "Mezzo Live" to 158, "Classica" to 160, "Djazz" to 161, "C Music" to 162, "Trace Toca" to 163,
        "Localvisão TV" to 187, "Kuriakos TV" to 188, "UM Europa" to 189,
        "i24News" to 201, "NHK World Japan" to 202, "CGTN" to 209, "TRT World" to 213, "RAI News" to 214, "DW" to 215,
        "Record News" to 216, "TPA Notícias" to 217,
        "BFM TV" to 222, "BVN" to 223, "CCTV 4" to 224, "VOX" to 225, "RTL" to 226, "M6" to 227, "KBS World" to 229,
        "RAI Uno" to 230, "Pro TV Internațional" to 231, "Arirang" to 232, "TPA Internacional" to 233,
        "TCV Internacional" to 234,
    )

    /**
     * Portugal: a arrumação por temas da NOS (generalistas de 1 a 19, desporto a partir de 20, infantis de 40, filmes
     * e entretenimento de 55, documentários de 100, música de 154, informação de 200), com os que ela não tem em [FILL].
     */
    val PT: Lineup = run {
        var extra = EXTRA_FROM
        Lineup("pt", "pt", "NOS", GROUPS, PT_CHANNELS.map { c -> Slot(c.nos ?: FILL[c.name] ?: extra++, c.name, c.group, c.aliases, c.foreign) })
    }
}

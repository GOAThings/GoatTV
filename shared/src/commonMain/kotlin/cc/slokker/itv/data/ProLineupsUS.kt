package cc.slokker.itv.data

import cc.slokker.itv.data.ProLineup.Lineup
import cc.slokker.itv.data.ProLineups.DOCS
import cc.slokker.itv.data.ProLineups.ENTERTAINMENT
import cc.slokker.itv.data.ProLineups.GENERAL
import cc.slokker.itv.data.ProLineups.KIDS
import cc.slokker.itv.data.ProLineups.MOVIES
import cc.slokker.itv.data.ProLineups.MUSIC
import cc.slokker.itv.data.ProLineups.NEWS
import cc.slokker.itv.data.ProLineups.SPORTS
import cc.slokker.itv.data.ProLineups.slot

/**
 * Estados Unidos: a grelha nacional da DIRECTV por satélite (outubro de 2026). As cadeias abertas variam com a cidade:
 * ficam com os números de Nova Iorque (CBS 2, NBC 4, FOX 5, ABC 7, The CW 11, PBS 13) e com o nome genérico, sem as afiliadas.
 */
internal object ProLineupsUS {
    val US: Lineup = Lineup("us", "us", "DIRECTV", ProLineups.GROUPS, listOf(
        // Cadeias abertas (números de Nova Iorque).
        slot(2, "CBS", GENERAL, "CBS East|CBS West"),
        slot(4, "NBC", GENERAL, "NBC East|NBC West"),
        slot(5, "FOX", GENERAL, "FOX East|FOX West|FOX Network"),
        slot(7, "ABC", GENERAL, "ABC East|ABC West"),
        slot(11, "The CW", GENERAL, "CW|CW Network|The CW Network"),
        slot(13, "PBS", GENERAL, "PBS East|PBS West"),

        // Informação.
        slot(202, "CNN", NEWS, "CNN USA|CNN US"),
        slot(204, "HLN", NEWS, "Headline News|CNN Headline News"),

        // Desporto.
        slot(206, "ESPN", SPORTS, "ESPN East"),
        slot(207, "ESPNews", SPORTS, "ESPN News"),
        slot(208, "ESPNU", SPORTS, "ESPN U"),
        slot(209, "ESPN2", SPORTS, "ESPN 2"),
        slot(211, "NFL RedZone", SPORTS, "RedZone|NFL Red Zone"),
        slot(212, "NFL Network", SPORTS, "NFL Net|NFLN"),
        slot(213, "MLB Network", SPORTS, "MLB Net|MLBN"),
        slot(215, "NHL Network", SPORTS, "NHL Net"),
        slot(216, "NBA TV", SPORTS, "NBATV"),
        slot(217, "Tennis Channel", SPORTS, "Tennis Ch"),
        slot(218, "Golf Channel", SPORTS, "Golf|NBC Golf"),
        slot(219, "FS1", SPORTS, "Fox Sports 1|FOX Sports 1"),
        slot(221, "CBS Sports Network", SPORTS, "CBSSN|CBS Sports"),

        // Entretenimento.
        slot(229, "HGTV", ENTERTAINMENT, "Home & Garden TV|Home and Garden Television"),
        slot(230, "Magnolia Network", ENTERTAINMENT, "Magnolia|DIY Network"),
        slot(231, "Food Network", ENTERTAINMENT, "Food Network East"),
        slot(232, "Cooking Channel", ENTERTAINMENT),
        slot(233, "Game Show Network", ENTERTAINMENT, "GSN"),
        slot(236, "E!", ENTERTAINMENT, "E! Entertainment|E Entertainment|E! Entertainment Television|E! East"),
        slot(237, "Bravo", ENTERTAINMENT, "Bravo East|Bravo TV"),
        slot(238, "REELZ", ENTERTAINMENT, "ReelzChannel"),
        slot(239, "SundanceTV", ENTERTAINMENT, "Sundance TV|Sundance Channel"),
        slot(241, "Paramount Network", ENTERTAINMENT, "Paramount Network East|Spike|Spike TV"),
        slot(242, "USA Network", ENTERTAINMENT, "USA Network East|USA East"),
        slot(244, "Syfy", ENTERTAINMENT, "Syfy East|Sci Fi Channel"),
        slot(245, "TNT", ENTERTAINMENT, "TNT East"),
        slot(246, "truTV", ENTERTAINMENT, "Tru TV|truTV East"),
        slot(247, "TBS", ENTERTAINMENT, "TBS East"),
        slot(248, "FX", ENTERTAINMENT, "FX East|FX Network"),
        slot(249, "Comedy Central", ENTERTAINMENT, "Comedy Central East"),
        slot(251, "Oxygen True Crime", ENTERTAINMENT, "Oxygen|Oxygen East"),
        slot(252, "Lifetime", ENTERTAINMENT, "Lifetime East|Lifetime Television"),
        slot(253, "Lifetime Movie Network", MOVIES, "LMN|Lifetime Movies"),
        slot(254, "AMC", ENTERTAINMENT, "AMC East"),
        slot(256, "TCM", MOVIES, "Turner Classic Movies"),
        slot(258, "FXM", MOVIES, "FX Movie Channel|FX Movies"),
        slot(259, "FXX", ENTERTAINMENT, "FXX East"),
        slot(260, "WE tv", ENTERTAINMENT, "WEtv|WE Women's Entertainment"),
        slot(264, "BBC America", ENTERTAINMENT, "BBCA"),
        slot(265, "A&E", ENTERTAINMENT, "A E|AE|A&E East|Arts & Entertainment"),

        // Documentários.
        slot(261, "Discovery Life", DOCS, "Discovery Life Channel"),
        slot(269, "History", DOCS, "History Channel|The History Channel|History East"),
        slot(276, "National Geographic", DOCS, "Nat Geo|NatGeo|National Geographic Channel|Nat Geo East"),
        slot(277, "Travel Channel", ENTERTAINMENT, "Travel"),
        slot(278, "Discovery", DOCS, "Discovery Channel|Discovery East"),
        slot(279, "OWN", ENTERTAINMENT, "Oprah Winfrey Network"),
        slot(280, "TLC", ENTERTAINMENT, "TLC East|The Learning Channel"),
        slot(281, "MotorTrend", DOCS, "Motor Trend|MotorTrend TV|Velocity"),
        slot(282, "Animal Planet", DOCS, "Animal Planet East"),
        slot(283, "Nat Geo Wild", DOCS, "National Geographic Wild|NatGeo Wild"),
        slot(284, "Science Channel", DOCS, "Science|Discovery Science"),
        slot(285, "Investigation Discovery", DOCS, "ID|Investigation Discovery East|ID Discovery"),
        slot(287, "American Heroes Channel", DOCS, "AHC|American Heroes"),

        // Infantis.
        slot(288, "PBS Kids", KIDS, "PBS KIDS 24/7"),
        slot(289, "Disney Junior", KIDS, "Disney Jr|Disney Junior East"),
        slot(290, "Disney Channel", KIDS, "Disney|Disney Channel East"),
        slot(292, "Disney XD", KIDS, "Disney XD East"),
        slot(293, "BabyFirst", KIDS, "Baby First|BabyFirst TV"),
        slot(294, "Discovery Family", KIDS, "Discovery Family Channel"),
        slot(296, "Cartoon Network", KIDS, "Cartoon Network East"),
        slot(298, "Boomerang", KIDS),
        slot(299, "Nickelodeon", KIDS, "Nick|Nickelodeon East"),
        slot(301, "Nick Jr", KIDS, "Nick Junior"),
        slot(302, "Nicktoons", KIDS, "Nick Toons"),
        slot(303, "TeenNick", KIDS, "Teen Nick"),
        slot(304, "TV Land", ENTERTAINMENT, "TVLand"),

        slot(305, "ION", ENTERTAINMENT, "ION Television|ION TV|ION East"),
        slot(307, "NewsNation", NEWS, "WGN America|News Nation"),
        slot(311, "Freeform", ENTERTAINMENT, "Freeform East|ABC Family"),
        slot(312, "Hallmark Channel", ENTERTAINMENT, "Hallmark|Hallmark East"),
        slot(326, "Great American Family", ENTERTAINMENT, "GAC Family|Great American Country"),

        // Música.
        slot(327, "CMT", MUSIC, "Country Music Television"),
        slot(329, "BET", MUSIC, "Black Entertainment Television|BET East"),
        slot(330, "BET Her", MUSIC, "Centric"),
        slot(331, "MTV", MUSIC, "MTV East|MTV USA"),
        slot(332, "MTV2", MUSIC, "MTV 2"),
        slot(333, "IFC", MOVIES, "Independent Film Channel"),
        slot(335, "VH1", MUSIC, "VH1 East"),
        slot(336, "MTV Classic", MUSIC),
        slot(339, "Fuse", MUSIC, "Fuse TV"),
        slot(340, "AXS TV", MUSIC, "AXS"),

        // Informação.
        slot(346, "BBC News", NEWS, "BBC World News|BBC World", foreign = true),
        slot(349, "Newsmax", NEWS, "Newsmax TV"),
        slot(350, "C-SPAN", NEWS, "CSPAN|C-SPAN 1|CSPAN1"),
        slot(351, "C-SPAN 2", NEWS, "CSPAN2|CSPAN 2"),
        slot(353, "Bloomberg", NEWS, "Bloomberg TV|Bloomberg Television"),
        slot(355, "CNBC", NEWS, "CNBC USA|CNBC US"),
        slot(356, "MS NOW", NEWS, "MSNBC|MS NOW News"),
        slot(357, "CNBC World", NEWS),
        slot(358, "CNN International", NEWS, "CNNi|CNN Intl", foreign = true),
        slot(359, "Fox Business", NEWS, "Fox Business Network|FBN"),
        slot(360, "Fox News", NEWS, "Fox News Channel|FNC"),
        slot(362, "The Weather Channel", NEWS, "Weather Channel|TWC"),

        // Em espanhol.
        slot(402, "Univision", GENERAL, "Univisión|Univision East"),
        slot(404, "Galavisión", ENTERTAINMENT, "Galavision"),
        slot(406, "Telemundo", GENERAL, "Telemundo East|Telemundo Este"),
        slot(408, "UniMás", GENERAL, "UniMas|Unimas East|TeleFutura"),
        slot(410, "Universo", ENTERTAINMENT, "NBC Universo"),
        slot(419, "CNN en Español", NEWS, "CNN Espanol|CNN en Espanol"),
        slot(464, "TUDN", SPORTS, "TUDN USA|Univision Deportes"),

        // Filmes (premium).
        slot(501, "HBO", MOVIES, "HBO East|HBO 1|HBO1"),
        slot(502, "HBO Hits", MOVIES, "HBO 2|HBO2|HBO 2 East"),
        slot(503, "HBO Drama", MOVIES, "HBO Signature"),
        slot(506, "HBO Comedy", MOVIES),
        slot(507, "HBO Family", MOVIES, "HBO Family East"),
        slot(509, "HBO Movies", MOVIES, "HBO Zone"),
        slot(511, "HBO Latino", MOVIES),
        slot(515, "Cinemax", MOVIES, "Cinemax East|MAX East"),
        slot(525, "STARZ", MOVIES, "Starz East"),
        slot(529, "STARZ Edge", MOVIES),
        slot(535, "STARZ Encore", MOVIES, "Encore|Starz Encore East"),
        slot(545, "Paramount+ with Showtime", MOVIES, "Showtime|Showtime East|SHO"),
        slot(547, "Showtime 2", MOVIES, "SHO 2|SHO2"),
        slot(553, "The Movie Channel", MOVIES, "TMC|The Movie Channel East"),
        slot(558, "MGM+", MOVIES, "Epix|MGM Plus"),
        slot(559, "MGM+ Hits", MOVIES, "Epix 2|Epix2|MGM Plus Hits"),
        slot(560, "MGM+ Marquee", MOVIES, "Epix Hits|MGM Plus Marquee"),
        slot(565, "Hallmark Mystery", MOVIES, "Hallmark Movies & Mysteries|Hallmark Movies and Mysteries|HMM"),
        slot(570, "Smithsonian Channel", DOCS, "Smithsonian"),

        // Desporto (os de assinatura e os universitários).
        slot(602, "FanDuel TV", SPORTS, "TVG"),
        slot(606, "Outdoor Channel", SPORTS, "Outdoor"),
        slot(610, "Big Ten Network", SPORTS, "BTN|Big Ten"),
        slot(611, "SEC Network", SPORTS, "SECN|SEC"),
        slot(612, "ACC Network", SPORTS, "ACCN"),
        slot(618, "FS2", SPORTS, "Fox Sports 2|FOX Sports 2"),
        slot(620, "beIN Sports", SPORTS, "beIN Sports USA|beIN Sport USA"),
        slot(621, "beIN Sports en Español", SPORTS, "beIN Sports Espanol|beIN Sports en Espanol"),
    ))
}

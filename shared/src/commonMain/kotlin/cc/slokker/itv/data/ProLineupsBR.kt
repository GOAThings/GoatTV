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
 * Brasil: a grelha HD da Claro TV+ (numeração da antiga NET, canal SD + 500; conferida em novembro de 2026), sem os canais
 * da Disney que fecharam em fevereiro de 2025 (Disney Channel, Star Channel, FX, National Geographic, Cinecanal, Baby TV).
 * Os internacionais ficam com o número SD (199 a 209), que é onde a Claro os tem.
 */
internal object ProLineupsBR {
    val BR: Lineup = Lineup("br", "br", "Claro TV+", ProLineups.GROUPS, listOf(
        // Canais públicos e institucionais (só SD).
        slot(3, "TV Câmara", GENERAL, "TV Camara dos Deputados"),
        slot(6, "TV Justiça", GENERAL),
        slot(10, "TV Senado", GENERAL),

        // Internacionais (números SD).
        slot(199, "CGTN", INTERNATIONAL, "CGTN Español", foreign = true),
        slot(200, "CNN International", NEWS, "CNN Internacional|CNNi", foreign = true),
        slot(201, "Bloomberg", NEWS, "Bloomberg TV|Bloomberg Television", foreign = true),
        slot(202, "BBC News", NEWS, "BBC World News|BBC World", foreign = true),
        slot(203, "RAI Italia", INTERNATIONAL, "RAI International|RAI Internacional", foreign = true),
        slot(204, "TV5Monde", INTERNATIONAL, "TV5 Monde|TV5 Monde Amérique Latine", foreign = true),
        slot(205, "TVE Internacional", INTERNATIONAL, "TVE International|TVEi", foreign = true),
        slot(206, "DW", INTERNATIONAL, "DW TV|DW Español|Deutsche Welle", foreign = true),
        slot(207, "NHK World", INTERNATIONAL, "NHK World Japan", foreign = true),
        slot(208, "SIC Internacional", INTERNATIONAL, foreign = true),
        slot(209, "France 24", NEWS, "France 24 Français|France 24 English", foreign = true),

        slot(251, "Climatempo", NEWS, "Canal Climatempo"),

        // Abertos.
        slot(505, "Band", GENERAL, "Rede Bandeirantes|Bandeirantes|TV Band|Band SP"),
        slot(508, "RedeTV!", GENERAL, "Rede TV"),
        slot(509, "SBT", GENERAL, "SBT SP|SBT Nacional"),
        slot(516, "TV Cultura", GENERAL, "Cultura|Cultura SP"),
        slot(519, "Record", GENERAL, "Record TV|Rede Record|Record SP"),
        slot(521, "TV Gazeta", GENERAL, "Gazeta"),
        slot(524, "Globo", GENERAL, "TV Globo|Rede Globo|Globo SP|Globo RJ|Globo São Paulo|Globo Rio"),
        slot(530, "Like", ENTERTAINMENT, "Canal Like"),
        slot(531, "TV Brasil", GENERAL),
        slot(534, "Futura", DOCS, "Canal Futura"),
        slot(535, "Canal Off", ENTERTAINMENT, "Off"),

        // Grupo Globo.
        slot(537, "SporTV 3", SPORTS, "Sportv3"),
        slot(538, "SporTV 2", SPORTS, "Sportv2"),
        slot(539, "SporTV", SPORTS, "SporTV 1|Sportv1"),
        slot(540, "GloboNews", NEWS, "Globo News"),
        slot(541, "GNT", ENTERTAINMENT),
        slot(542, "Multishow", ENTERTAINMENT, "Multi Show"),
        slot(543, "Viva", ENTERTAINMENT, "Canal Viva"),

        // Variedades.
        slot(550, "E!", ENTERTAINMENT, "E! Entertainment|E Entertainment"),
        slot(551, "Fashion TV", ENTERTAINMENT, "FashionTV", foreign = true),
        slot(552, "TLC", ENTERTAINMENT, "Discovery TLC"),
        slot(553, "Arte 1", ENTERTAINMENT, "Arte1"),
        slot(554, "Food Network", ENTERTAINMENT),
        slot(555, "Discovery Home & Health", ENTERTAINMENT, "Discovery H&H|Home & Health|Home and Health"),
        slot(556, "Curta!", DOCS, "Curta"),
        slot(558, "Fish TV", SPORTS),
        slot(560, "HGTV", ENTERTAINMENT),
        slot(565, "Woohoo", SPORTS, "Canal Woohoo"),

        // Desporto e informação.
        slot(568, "TNT Sports", SPORTS, "TNT Sports Brasil"),
        slot(569, "ESPN 5", SPORTS, "ESPN Extra|ESPN5"),
        slot(570, "ESPN", SPORTS, "ESPN Brasil|ESPN 1|ESPN1"),
        slot(571, "ESPN 2", SPORTS, "ESPN2"),
        slot(572, "ESPN 3", SPORTS, "ESPN3"),
        slot(573, "ESPN 4", SPORTS, "ESPN4|Fox Sports"),
        slot(574, "ESPN 6", SPORTS, "ESPN6|Fox Sports 2"),
        slot(575, "BandSports", SPORTS, "Band Sports"),
        slot(576, "Jovem Pan News", NEWS, "JP News|Jovem Pan|JPNews"),
        slot(577, "CNN Brasil", NEWS),
        slot(578, "Record News", NEWS),
        slot(579, "BandNews", NEWS, "Band News|BandNews TV"),

        // Documentários.
        slot(581, "Discovery", DOCS, "Discovery Channel"),
        slot(582, "Animal Planet", DOCS),
        slot(583, "History", DOCS, "History Channel|The History Channel|Canal History"),
        slot(584, "Discovery Turbo", DOCS),
        slot(585, "Discovery Science", DOCS),
        slot(590, "Smithsonian Channel", DOCS, "Smithsonian"),
        slot(592, "Discovery Theater", DOCS, "Discovery Theater HD"),
        slot(593, "Discovery World", DOCS),
        slot(594, "History 2", DOCS, "H2"),

        // Infantis.
        slot(600, "Discovery Kids", KIDS),
        slot(601, "Gloob", KIDS),
        slot(603, "Nickelodeon", KIDS, "Nick"),
        slot(604, "Cartoon Network", KIDS, "Cartoon"),
        slot(607, "Nick Jr", KIDS, "Nick Junior"),
        slot(608, "Cartoonito", KIDS, "Boomerang"),
        slot(609, "TV Rá-Tim-Bum", KIDS, "TV Ra Tim Bum|Rá Tim Bum"),
        slot(610, "Tooncast", KIDS),
        slot(611, "Gloobinho", KIDS),
        slot(613, "ZooMoo", KIDS, "Zoo Moo"),

        // Música.
        slot(620, "Bis", MUSIC, "Canal Bis|Multishow Bis"),
        slot(621, "MTV", MUSIC, "MTV Brasil"),
        slot(623, "Music Box Brasil", MUSIC, "Music Box"),
        slot(624, "Trace Brazuca", MUSIC),
        slot(625, "MTV 00s", MUSIC, "MTV 00|MTV00s"),
        slot(628, "MTV Live", MUSIC, "MTV Live HD"),

        // Séries.
        slot(630, "Universal TV", ENTERTAINMENT, "Universal|Universal Channel"),
        slot(632, "Warner Channel", ENTERTAINMENT, "Warner|WBTV|Warner TV"),
        slot(633, "Sony Channel", ENTERTAINMENT, "Sony|Canal Sony"),
        slot(635, "AXN", ENTERTAINMENT),
        slot(636, "TBS", ENTERTAINMENT),
        slot(637, "Comedy Central", ENTERTAINMENT),
        slot(638, "A&E", ENTERTAINMENT, "A E|AE"),
        slot(639, "Investigação Discovery", DOCS, "Investigation Discovery|Discovery ID|ID"),
        slot(640, "Syfy", ENTERTAINMENT, "Sci Fi"),
        slot(641, "Lifetime", ENTERTAINMENT),
        slot(643, "TNT Séries", ENTERTAINMENT, "TNT Series"),
        slot(645, "AMC", ENTERTAINMENT),

        // Filmes.
        slot(647, "Eurochannel", MOVIES, "Euro Channel", foreign = true),
        slot(648, "Film & Arts", MOVIES, "Film&Arts|Film and Arts|Film Arts"),
        slot(650, "Canal Brasil", MOVIES),
        slot(651, "TNT", MOVIES, "TNT Brasil"),
        slot(652, "Megapix", MOVIES, "Mega Pix"),
        slot(654, "Space", MOVIES, "Canal Space"),
        slot(655, "Cinemax", MOVIES),
        slot(656, "Prime Box Brazil", MOVIES, "Prime Box Brasil|PrimeBox"),
        slot(657, "Studio Universal", MOVIES),
        slot(658, "Paramount Network", MOVIES, "Paramount Channel"),
        slot(659, "TCM", MOVIES, "Turner Classic Movies"),
        slot(661, "Telecine Premium", MOVIES, "TC Premium"),
        slot(662, "Telecine Action", MOVIES, "TC Action"),
        slot(663, "Telecine Touch", MOVIES, "TC Touch"),
        slot(664, "Telecine Fun", MOVIES, "TC Fun"),
        slot(665, "Telecine Pipoca", MOVIES, "TC Pipoca"),
        slot(666, "Telecine Cult", MOVIES, "TC Cult"),
        slot(671, "HBO", MOVIES, "HBO 1|HBO1"),
        slot(672, "HBO 2", MOVIES, "HBO2"),
        slot(673, "HBO Plus", MOVIES),
        slot(675, "HBO Family", MOVIES),
        slot(676, "HBO Signature", MOVIES),
        slot(677, "HBO Pop", MOVIES),
        slot(678, "HBO Mundi", MOVIES),
        slot(679, "HBO Xtreme", MOVIES, "HBO Extreme"),

        // Religiosos e agro.
        slot(684, "Novo Tempo", GENERAL, "TV Novo Tempo"),
        slot(685, "Canal Rural", GENERAL),
        slot(689, "AgroMais", GENERAL, "Agro+|Agro Mais"),
        slot(691, "TV Pai Eterno", GENERAL, "Pai Eterno"),
        slot(692, "Canção Nova", GENERAL, "TV Canção Nova"),
        slot(693, "Rede Vida", GENERAL, "TV Rede Vida"),
        slot(695, "TV Aparecida", GENERAL, "Aparecida"),

        // Premiere e Combate.
        slot(721, "Premiere", SPORTS, "Premiere Clubes|Premiere 1|Premiere1|Premiere FC"),
        slot(722, "Premiere 2", SPORTS, "Premiere2"),
        slot(723, "Premiere 3", SPORTS, "Premiere3"),
        slot(724, "Premiere 4", SPORTS, "Premiere4"),
        slot(725, "Premiere 5", SPORTS, "Premiere5"),
        slot(726, "Premiere 6", SPORTS, "Premiere6"),
        slot(727, "Premiere 7", SPORTS, "Premiere7"),
        slot(728, "Premiere 8", SPORTS, "Premiere8"),
        slot(740, "Combate", SPORTS, "Canal Combate"),
    ))
}

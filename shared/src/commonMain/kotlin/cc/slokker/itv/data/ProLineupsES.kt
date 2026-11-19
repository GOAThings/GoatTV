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
 * Espanha: os diais da Movistar Plus+ (lista de fevereiro de 2026); os autonómicos com o número da box via satélite
 * (151 a 166) e os que ela não traz em números livres ao pé desses.
 */
internal object ProLineupsES {
    val ES: Lineup = Lineup("es", "es", "Movistar Plus+", ProLineups.GROUPS, listOf(
        // Generalistas.
        slot(1, "La 1", GENERAL, "TVE 1|La1|TVE|TVE La 1"),
        slot(2, "La 2", GENERAL, "TVE 2|La2|TVE La 2"),
        slot(3, "Antena 3", GENERAL, "Antena3|A3"),
        slot(4, "Cuatro", GENERAL, "Canal Cuatro"),
        slot(5, "Telecinco", GENERAL, "Tele 5|Tele5"),
        slot(6, "La Sexta", GENERAL, "laSexta|La 6"),
        slot(7, "Movistar Plus+", GENERAL, "Movistar Plus|#0 por M+|M+ #0|Movistar #0|Cero por M+"),

        // Cinema e séries da Movistar.
        slot(12, "Cine por M+", MOVIES, "M+ Cine|Movistar Cine|Estrenos por M+|M+ Estrenos|Movistar Estrenos|M. Estrenos"),
        slot(13, "Series por M+", ENTERTAINMENT, "M+ Series|Movistar Series|M. Series"),
        slot(14, "Originales por M+", ENTERTAINMENT, "M+ Originales|Movistar Originales"),
        slot(15, "Los Goya por M+", MOVIES, "M+ Los Goya|Movistar Los Goya"),
        slot(16, "Multi Cine por M+", MOVIES, "M+ Multicine|M+ Multi Cine|Movistar Multicine"),
        slot(17, "Documentales por M+", DOCS, "M+ Documentales|Movistar Documentales|M. Documentales"),
        slot(18, "Los Oscars por M+", MOVIES, "M+ Los Oscars|Movistar Los Oscars|Oscars por M+"),
        slot(20, "Clásicos por M+", MOVIES, "M+ Clásicos|Movistar Clásicos|M. Clásicos"),
        slot(21, "Acción por M+", MOVIES, "M+ Acción|Movistar Acción|M. Acción"),
        slot(22, "Comedia por M+", MOVIES, "M+ Comedia|Movistar Comedia|M. Comedia"),
        slot(23, "Drama por M+", MOVIES, "M+ Drama|Movistar Drama|M. Drama"),
        slot(25, "Indie por M+", MOVIES, "M+ Indie|Movistar Indie"),
        slot(26, "Cine Español por M+", MOVIES, "M+ Cine Español|Movistar Cine Español|Cine Ñ por M+|M+ Cine Ñ|Movistar Cine Ñ"),

        // Canais de séries e filmes de terceiros.
        slot(29, "SkyShowtime", MOVIES, "SkyShowtime 1|Sky Showtime"),
        slot(30, "TCM", MOVIES, "TCM Cine Clásico"),
        slot(34, "BBC Top Gear", ENTERTAINMENT, "Top Gear"),
        slot(36, "Be Mad", ENTERTAINMENT, "BeMad|Be Mad TV"),
        slot(37, "Star Channel", ENTERTAINMENT, "FOX España|Fox Spain"),
        slot(38, "AXN", ENTERTAINMENT, "AXN España"),
        slot(39, "Warner TV", ENTERTAINMENT, "TNT España|Warner TV España"),
        slot(40, "Comedy Central", ENTERTAINMENT, "Paramount Comedy|Comedy Central España"),
        slot(41, "Calle 13", ENTERTAINMENT, "Calle13"),
        slot(42, "Cosmo", ENTERTAINMENT, "Canal Cosmo"),
        slot(43, "SYFY", ENTERTAINMENT, "Syfy España"),
        slot(44, "Trece", GENERAL, "13TV|13 TV|Trece TV"),
        slot(46, "Energy", ENTERTAINMENT, "Energy TV"),
        slot(47, "FDF", ENTERTAINMENT, "Factoría de Ficción|FDF Telecinco"),
        slot(48, "Neox", ENTERTAINMENT, "Antena 3 Neox"),
        slot(49, "Atreseries", ENTERTAINMENT, "A3Series|A3 Series"),

        // Desporto.
        slot(50, "Vamos por M+", SPORTS, "#Vamos|M+ Vamos|Movistar Vamos|Vamos|#Vamos por M+"),
        slot(51, "Vamos 2 por M+", SPORTS, "M+ Vamos 2|Movistar Vamos 2|Vamos 2"),
        slot(52, "Vamos 3 por M+", SPORTS, "M+ Vamos 3|Movistar Vamos 3|Vamos 3"),
        slot(53, "Primera Federación", SPORTS, "Primera Federación TV|1RFEF|1ª RFEF"),
        slot(54, "LaLiga por M+", SPORTS, "M+ LaLiga|Movistar LaLiga|M. LaLiga|LaLiga TV por M+|M+ LaLiga TV|Movistar LaLiga TV"),
        slot(55, "DAZN LaLiga", SPORTS, "DAZN La Liga|DAZN LaLiga TV"),
        slot(56, "LaLiga TV Hypermotion", SPORTS, "LaLiga Hypermotion|LaLiga Hypermotion TV|LaLiga SmartBank|LaLiga SmartBank TV|LaLiga 123 TV"),
        slot(57, "LaLiga 2 por M+", SPORTS, "M+ LaLiga 2|Movistar LaLiga 2|M. LaLiga 2"),
        slot(58, "DAZN LaLiga 2", SPORTS, "DAZN La Liga 2"),
        slot(59, "LaLiga TV Hypermotion 2", SPORTS, "LaLiga Hypermotion 2|LaLiga SmartBank 2|LaLiga SmartBank TV 2"),
        slot(60, "Liga de Campeones por M+", SPORTS, "M+ Liga de Campeones|Movistar Liga de Campeones|M. Liga de Campeones|Liga de Campeones"),
        slot(61, "Liga de Campeones 2 por M+", SPORTS, "M+ Liga de Campeones 2|Movistar Liga de Campeones 2|M. Liga de Campeones 2|Liga de Campeones 2"),
        slot(62, "Liga de Campeones 3 por M+", SPORTS, "M+ Liga de Campeones 3|Movistar Liga de Campeones 3|M. Liga de Campeones 3|Liga de Campeones 3"),
        slot(63, "Deportes por M+", SPORTS, "M+ Deportes|Movistar Deportes|M. Deportes"),
        slot(64, "Deportes 2 por M+", SPORTS, "M+ Deportes 2|Movistar Deportes 2|M. Deportes 2"),
        slot(65, "Deportes 3 por M+", SPORTS, "M+ Deportes 3|Movistar Deportes 3|M. Deportes 3"),
        slot(66, "Deportes 4 por M+", SPORTS, "M+ Deportes 4|Movistar Deportes 4|M. Deportes 4"),
        slot(67, "Golf por M+", SPORTS, "M+ Golf|Movistar Golf|M. Golf"),
        slot(68, "Golf 2 por M+", SPORTS, "M+ Golf 2|Movistar Golf 2|M. Golf 2"),
        slot(69, "DAZN F1", SPORTS, "DAZN Formula 1|DAZN Fórmula 1"),
        slot(70, "DAZN MotoGP", SPORTS, "DAZN Moto GP"),
        slot(71, "DAZN 1", SPORTS, "DAZN1"),
        slot(72, "DAZN 2", SPORTS, "DAZN2"),
        slot(73, "DAZN Baloncesto", SPORTS, "DAZN Baloncesto 1"),
        slot(74, "DAZN Baloncesto 2", SPORTS),
        slot(75, "DAZN Baloncesto 3", SPORTS),
        slot(76, "Eurosport 1", SPORTS, "Eurosport"),
        slot(77, "Eurosport 2", SPORTS),
        slot(78, "GOL", SPORTS, "Gol Play|Gol TV|GOLTV"),
        slot(79, "Teledeporte", SPORTS, "TDP|Tele Deporte|TVE Teledeporte"),
        slot(80, "Real Madrid TV", SPORTS, "RealMadrid TV|RMTV"),
        slot(81, "Caza y Pesca", SPORTS, "Caza & Pesca|Canal Caza y Pesca"),
        slot(82, "Iberalia TV", SPORTS, "Iberalia"),
        slot(83, "Garage TV", SPORTS, "El Garage TV"),
        slot(84, "Flash Deportes", SPORTS),
        slot(85, "Ubeat", SPORTS, "Ubeat TV"),

        // Documentários e estilo de vida.
        slot(86, "National Geographic", DOCS, "Nat Geo|NatGeo|National Geographic Channel"),
        slot(87, "Nat Geo Wild", DOCS, "National Geographic Wild|NatGeo Wild"),
        slot(88, "Historia", DOCS, "Canal Historia|History"),
        slot(89, "Discovery", DOCS, "Discovery Channel"),
        slot(90, "BBC Earth", DOCS),
        slot(91, "DMAX", DOCS, "D Max"),
        slot(92, "BBC Food", ENTERTAINMENT),
        slot(93, "BBC Lifestyle", ENTERTAINMENT),
        slot(94, "DKISS", ENTERTAINMENT, "D Kiss"),
        slot(95, "Divinity", ENTERTAINMENT),
        slot(96, "Nova", ENTERTAINMENT, "Antena 3 Nova|Nova TV"),
        slot(97, "Mega", ENTERTAINMENT, "Mega TV"),
        slot(98, "Ten", ENTERTAINMENT, "Ten TV"),

        // Infantis.
        slot(110, "Baby TV", KIDS, "BabyTV"),
        slot(111, "Disney Junior", KIDS, "Disney Jr"),
        slot(113, "Nick Jr", KIDS, "Nick Junior"),
        slot(114, "Nickelodeon", KIDS, "Nick"),
        slot(115, "DreamWorks", KIDS, "DreamWorks Channel"),
        slot(116, "Disney Channel", KIDS, "Disney"),
        slot(117, "Boing", KIDS),
        slot(118, "Clan", KIDS, "Clan TVE|Clan TV"),
        slot(119, "SX3", KIDS, "Super3|Super 3|Canal Super3"),

        // Música.
        slot(120, "MTV", MUSIC, "MTV España"),
        slot(121, "MTV 00s", MUSIC, "MTV OOs|MTV 2000s"),
        slot(123, "Mezzo", MUSIC),
        slot(124, "Mezzo Live", MUSIC),
        slot(125, "Stingray Classica", MUSIC, "Classica"),

        // Informação (as estrangeiras que a Movistar tem).
        slot(127, "24h", NEWS, "Canal 24 Horas|24 Horas|TVE 24h|24h TVE|Canal 24h"),
        slot(128, "BBC News", NEWS, "BBC World|BBC World News", foreign = true),
        slot(129, "CNN International", NEWS, "CNN|CNN Int|CNN Intl", foreign = true),
        slot(130, "Fox News", NEWS, "Fox News Channel", foreign = true),
        slot(131, "Euronews", NEWS, "Euronews English|Euronews Español", foreign = true),
        slot(132, "Al Jazeera", NEWS, "Al Jazeera English|AlJazeera", foreign = true),
        slot(133, "France 24", NEWS, "France 24 English|France 24 Español|France 24 Français", foreign = true),
        slot(134, "DW", NEWS, "DW Español|DW English|Deutsche Welle", foreign = true),
        slot(136, "CNBC", NEWS, "CNBC Europe", foreign = true),
        slot(137, "TV5Monde", INTERNATIONAL, "TV5 Monde|TV5", foreign = true),
        slot(138, "Bloomberg", NEWS, "Bloomberg TV|Bloomberg Television", foreign = true),
        slot(139, "El Toro TV", NEWS, "Toro TV"),
        slot(140, "Negocios TV", NEWS, "Negocios"),
        slot(142, "CGTN Español", INTERNATIONAL, "CGTN Espanol", foreign = true),
        slot(143, "Telefe Internacional", INTERNATIONAL, "Telefe", foreign = true),
        slot(144, "Las Estrellas", INTERNATIONAL, "Canal de las Estrellas", foreign = true),
        slot(145, "TV Chile", INTERNATIONAL, "TVN Chile", foreign = true),
        slot(146, "TV Colombia", INTERNATIONAL, foreign = true),
        slot(147, "Cubavisión Internacional", INTERNATIONAL, "Cubavisión", foreign = true),
        slot(148, "Telesur", INTERNATIONAL, "teleSUR", foreign = true),
        slot(149, "EWTN", INTERNATIONAL, "EWTN España", foreign = true),

        // Autonómicos.
        slot(151, "Canal Sur", GENERAL, "Canal Sur Andalucía|Canal Sur TV"),
        slot(152, "TVG", GENERAL, "Televisión de Galicia|TV Galicia|TVG Europa|TVG América"),
        slot(153, "Canal Extremadura", GENERAL, "Canal Extremadura Sat|Canal Extremadura TV"),
        slot(154, "TV3", GENERAL, "TV3 Cat|TV3 Catalunya|TV3 Cataluña|TV3CAT"),
        slot(155, "ETB Basque", GENERAL, "EITB Basque|ETB Sat|EITB"),
        slot(156, "Aragón TV", GENERAL, "Aragón TV Int|Aragón Televisión"),
        slot(157, "Telemadrid", GENERAL, "Telemadrid Int|Telemadrid Internacional"),
        slot(158, "À Punt", GENERAL, "APunt|À Punt Mèdia"),
        slot(159, "IB3", GENERAL, "IB3 Televisió|IB3 Global"),
        slot(160, "CMM", GENERAL, "CMM TV|Castilla-La Mancha Media"),
        slot(161, "TPA7", GENERAL, "TPA 7|RTPA|TPA Asturias"),
        slot(162, "La 7 CyL", GENERAL, "La 7 Castilla y León|La7 CyL|CyLTV"),
        slot(163, "Canal 33", GENERAL, "El 33|33 Cat"),
        slot(164, "Esport3", SPORTS, "Esport 3|Canal Esport3"),
        slot(165, "3/24", NEWS, "3 24|Canal 3/24|3Cat 324"),
        slot(166, "Betevé", GENERAL, "BTV Barcelona"),
        slot(167, "Televisión Canaria", GENERAL, "TV Canaria|Canarias TV|RTVC"),
        slot(168, "Navarra TV", GENERAL, "Navarra Televisión"),
        slot(169, "7RM", GENERAL, "7 Región de Murcia|La 7 Murcia|La 7 Región de Murcia"),
        slot(170, "Andalucía TV", GENERAL, "Canal Sur 2|Andalucía Televisión"),
        slot(171, "ETB 1", GENERAL, "EITB 1|ETB1"),
        slot(172, "ETB 2", GENERAL, "EITB 2|ETB2"),
    ))
}

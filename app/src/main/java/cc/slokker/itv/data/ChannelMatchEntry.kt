package cc.slokker.itv.data

/** O canal como entrada do ChannelMatch, com o nome do grupo dele em [content] (para desempatar cópias). */
fun ChannelMatch.entry(c: Channel, content: PlaylistContent? = null) = ChannelMatch.Entry(c.key, c.name, c.epgId, content?.categoryName(c.categoryId))

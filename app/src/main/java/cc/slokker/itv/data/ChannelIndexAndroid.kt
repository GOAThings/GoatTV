package cc.slokker.itv.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** O índice de [content] (ver ChannelIndex, no núcleo partilhado). */
fun ChannelIndex.Companion.of(content: PlaylistContent): ChannelIndex? = of(content.categories, content.channels)

/** O corpo do pedido, em gzip. */
fun ChannelIndex.gzipBody(generatedAt: Long = System.currentTimeMillis() / 1000): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(body(generatedAt).toByteArray()) } }.toByteArray()

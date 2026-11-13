@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import cc.slokker.itv.media.TsRemuxer

/**
 * Um canal HLS como se fosse uma ligação a um stream seguido (ver [HlsInputStream]): o gravador da pausa do direto
 * (ChannelFeed) grava-o no anel como grava um .ts, e o GoatCore pausa e recua num HLS como num .ts. Os segmentos vêm
 * pelo [upstream] (a ligação do fornecedor), um de cada vez. Os segmentos em fMP4 saem passados a TS ([TsRemuxer]),
 * para o anel e as gravações os lerem como os outros.
 */
class HlsDataSource(private val upstream: DataSource.Factory) : BaseDataSource(true) {
    private var stream: HlsInputStream? = null
    private var uri: Uri? = null
    private val ready = ArrayDeque<ByteArray>()
    private var readyAt = 0
    private var remuxer = TsRemuxer { ready.addLast(it) }
    private val input = ByteArray(64 * 1024)
    private var ended = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        stream = HlsInputStream(upstream, dataSpec.uri.toString())
        ready.clear()
        readyAt = 0
        ended = false
        remuxer = TsRemuxer { ready.addLast(it) }
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = stream ?: return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        while (ready.isEmpty()) {
            if (ended) return C.RESULT_END_OF_INPUT
            val n = s.read(input, 0, input.size)
            if (n < 0) {
                ended = true
                remuxer.flush()
            } else {
                remuxer.feed(input, 0, n)
            }
        }
        var copied = 0
        while (copied < length && ready.isNotEmpty()) {
            val chunk = ready.first()
            val n = minOf(length - copied, chunk.size - readyAt)
            chunk.copyInto(buffer, offset + copied, readyAt, readyAt + n)
            copied += n
            readyAt += n
            if (readyAt == chunk.size) {
                ready.removeFirst()
                readyAt = 0
            }
        }
        bytesTransferred(copied)
        return copied
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        stream?.let { s ->
            stream = null
            runCatching { s.close() }
            transferEnded()
        }
        uri = null
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = HlsDataSource(upstream)
    }
}

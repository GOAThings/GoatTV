@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Um canal HLS como se fosse uma ligação a um stream seguido (ver [HlsInputStream]): o gravador da pausa do direto
 * (ChannelFeed) grava-o no anel como grava um .ts, e o GoatCore pausa e recua num HLS como num .ts. Os segmentos vêm
 * pelo [upstream] (a ligação do fornecedor), um de cada vez.
 */
class HlsDataSource(private val upstream: DataSource.Factory) : BaseDataSource(true) {
    private var stream: HlsInputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        stream = HlsInputStream(upstream, dataSpec.uri.toString())
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = stream ?: return C.RESULT_END_OF_INPUT
        val n = s.read(buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
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

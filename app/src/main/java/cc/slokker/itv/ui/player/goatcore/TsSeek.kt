@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player.goatcore

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import cc.slokker.itv.media.TsDemuxer
import cc.slokker.itv.media.TsListener
import cc.slokker.itv.media.TsSample
import cc.slokker.itv.media.TsTrack

/**
 * Saltar dentro de um TS gravado (as gravações no disco, o catch-up do Stalker), onde o ExoPlayer usa o índice que
 * vai fazendo. Aqui:
 * 1. lê-se o primeiro e o último PTS (o início e o fim do ficheiro);
 * 2. estima-se o byte pela proporção;
 * 3. corrige-se uma vez com o PTS que lá estiver.
 * Cada leitura é uma ligação de cada vez (o portão).
 */
internal object TsSeek {
    /** Onde abrir ([position], em bytes) e o PTS do início do ficheiro ([firstPts], 90 kHz) para contar a posição. */
    class Plan(val position: Long, val firstPts: Long)

    private const val PROBE = 1024 * 1024
    private const val MASK = 0x1FFFFFFFFL

    /** O plano para chegar a [startMs] desde o início de [uri]; null se não der (sem tamanho, sem PTS). */
    fun plan(source: DataSource.Factory, uri: Uri, startMs: Long): Plan? {
        val length = length(source, uri) ?: return null
        val first = pts(read(source, uri, 0, PROBE), last = false) ?: return null
        if (startMs <= 0 || length <= 2L * PROBE) return Plan(0, first)
        val last = pts(read(source, uri, length - PROBE, PROBE), last = true) ?: return Plan(0, first)
        val span = (last - first) and MASK
        if (span <= 0) return Plan(0, first)
        val target = startMs * 90
        fun at(t: Long) = (length.toDouble() * t / span).toLong().coerceIn(0, length - PROBE)
        var position = at(target)
        // Uma correção: o débito não é constante, e a proporção do sítio onde caiu acerta o resto.
        pts(read(source, uri, position, PROBE), last = false)?.let { found ->
            val reached = (found - first) and MASK
            if (reached in 1 until span) position = at(target + (target - reached))
        }
        return Plan(position / 188 * 188, first)
    }

    /** A posição (ms) de [ptsMs] (o tempo seguido do motor, que começa no PTS cru) desde [firstPts]. */
    fun positionMs(ptsMs: Long, firstPts: Long): Long = ((ptsMs * 90 - firstPts) and MASK) / 90

    private fun length(source: DataSource.Factory, uri: Uri): Long? {
        val s = source.createDataSource()
        return try {
            s.open(DataSpec(uri)).takeIf { it != C.LENGTH_UNSET.toLong() && it > 0 }
        } finally {
            runCatching { s.close() }
        }
    }

    private fun read(source: DataSource.Factory, uri: Uri, position: Long, length: Int): ByteArray {
        val s = source.createDataSource()
        try {
            s.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length.toLong()).build())
            val out = ByteArray(length)
            var n = 0
            while (n < length) {
                val r = s.read(out, n, length - n)
                if (r == C.RESULT_END_OF_INPUT) break
                n += r
            }
            return out.copyOf(n)
        } finally {
            runCatching { s.close() }
        }
    }

    /**
     * O PTS do vídeo (ou, sem vídeo, de qualquer faixa) em [data]: o mais baixo das primeiras imagens, ou o mais alto
     * de todas com [last] (com imagens B a ordem não é a da apresentação).
     */
    private fun pts(data: ByteArray, last: Boolean): Long? {
        val video = ArrayList<Long>()
        val other = ArrayList<Long>()
        val d = TsDemuxer(object : TsListener {
            var tracks: List<TsTrack> = emptyList()
            override fun onTracks(tracks: List<TsTrack>) { this.tracks = tracks }
            override fun onSample(track: TsTrack, sample: TsSample) {
                if (sample.pts < 0) return
                if (track.kind == cc.slokker.itv.media.TrackKind.VIDEO) video += sample.pts else other += sample.pts
            }
        })
        d.feed(data, 0, data.size)
        d.flush()
        val list = video.ifEmpty { other }
        if (list.isEmpty()) return null
        return if (last) list.maxOf { it } else list.take(8).minOf { it }
    }
}

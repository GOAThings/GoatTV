package cc.slokker.itv.data

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import cc.slokker.itv.ui.player.cannotDecode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quando é que o ExoPlayer passa o canal para o VLC (áudio ou vídeo que não sabe descodificar). */
class CannotDecodeTest {
    private fun group(mime: String, support: Int) =
        Tracks.Group(TrackGroup(Format.Builder().setSampleMimeType(mime).build()), false, intArrayOf(support), booleanArrayOf(false))

    @Test
    fun audioMp2SemDescodificador() {
        val tracks = Tracks(listOf(group(MimeTypes.VIDEO_H264, C.FORMAT_HANDLED), group(MimeTypes.AUDIO_MPEG_L2, C.FORMAT_UNSUPPORTED_SUBTYPE)))
        assertTrue(tracks.cannotDecode())
    }

    @Test
    fun umaFaixaDeAudioQueDaChega() {
        val tracks = Tracks(
            listOf(
                group(MimeTypes.VIDEO_H264, C.FORMAT_HANDLED),
                group(MimeTypes.AUDIO_MPEG_L2, C.FORMAT_UNSUPPORTED_SUBTYPE),
                group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED),
            ),
        )
        assertFalse(tracks.cannotDecode())
    }

    @Test
    fun videoSemDescodificador() {
        val tracks = Tracks(listOf(group(MimeTypes.VIDEO_VC1, C.FORMAT_UNSUPPORTED_TYPE), group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED)))
        assertTrue(tracks.cannotDecode())
    }

    @Test
    fun acimaDasCapacidadesAindaTenta() {
        // Ex.: 4K numa box de 1080p: o ExoPlayer tenta na mesma, não se troca de player por isso.
        val tracks = Tracks(listOf(group(MimeTypes.VIDEO_H265, C.FORMAT_EXCEEDS_CAPABILITIES), group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED)))
        assertFalse(tracks.cannotDecode())
    }

    @Test
    fun soRadioSemVideo() {
        assertFalse(Tracks(listOf(group(MimeTypes.AUDIO_AAC, C.FORMAT_HANDLED))).cannotDecode())
    }
}

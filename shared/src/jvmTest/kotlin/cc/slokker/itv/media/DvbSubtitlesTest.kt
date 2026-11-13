package cc.slokker.itv.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Legendas DVB a sério (resources/ts/dvb_subtitles.ts: uma região de 300×60 em (100, 450) com retângulos branco,
 * amarelo e vermelho num fundo preto meio transparente, em códigos de 4 bits com corridas; aos 5 s, uma página
 * vazia). As cores são as que o ffmpeg desenha do mesmo ficheiro.
 */
class DvbSubtitlesTest {
    private fun pages(): List<Pair<Long, DvbSubtitlePage>> {
        val bytes = javaClass.getResourceAsStream("/ts/dvb_subtitles.ts")!!.readBytes()
        val out = ArrayList<Pair<Long, DvbSubtitlePage>>()
        var decoder: DvbSubtitleDecoder? = null
        val d = TsDemuxer(object : TsListener {
            override fun onTracks(tracks: List<TsTrack>) {
                val t = tracks.first { it.codec == Codec.DVB_SUBTITLE }
                val c = t.config!!
                decoder = DvbSubtitleDecoder(((c[0].toInt() and 0xFF) shl 8) or (c[1].toInt() and 0xFF), ((c[2].toInt() and 0xFF) shl 8) or (c[3].toInt() and 0xFF))
            }

            override fun onSample(track: TsTrack, sample: TsSample) {
                if (track.codec != Codec.DVB_SUBTITLE) return
                val data = sample.data
                decoder!!.decode(data.copyOfRange(2, data.size))?.let { out += sample.pts to it }
            }
        })
        d.feed(bytes, 0, bytes.size)
        d.flush()
        return out
    }

    private fun DvbSubtitleImage.at(x: Int, y: Int): IntArray {
        val i = ((y - this.y) * width + (x - this.x)) * 4
        return IntArray(4) { rgba[i + it].toInt() and 0xFF }
    }

    private fun near(expected: IntArray, actual: IntArray, what: String) =
        assertTrue(expected.indices.all { abs(expected[it] - actual[it]) <= 6 }, "$what: esperava ${expected.toList()}, veio ${actual.toList()}")

    @Test
    fun aPageAndThenAClear() {
        val list = pages()
        assertEquals(2, list.size)
        val (shownAt, page) = list[0]
        assertEquals(720 to 576, page.displayWidth to page.displayHeight)
        assertEquals(10, page.timeoutSeconds)
        val image = page.images.single()
        assertEquals(listOf(100, 450, 300, 60), listOf(image.x, image.y, image.width, image.height))
        // As cores do ffmpeg no mesmo ponto (RGB, opacas).
        near(intArrayOf(255, 255, 255, 255), image.at(150, 480), "branco")
        near(intArrayOf(253, 253, 0, 255), image.at(250, 480), "amarelo")
        near(intArrayOf(252, 0, 0, 255), image.at(350, 480), "vermelho")
        // O fundo: preto meio transparente (T = 128); os cantos: transparentes.
        near(intArrayOf(0, 0, 0, 127), image.at(395, 455), "fundo")
        near(intArrayOf(0, 0, 0, 0), image.at(101, 451), "canto")
        // Linhas pares e ímpares (os dois campos) iguais.
        near(image.at(150, 481), image.at(150, 480), "campo de baixo")
        // Aos 5 s, uma página sem regiões: limpa.
        val (clearedAt, cleared) = list[1]
        assertTrue(cleared.images.isEmpty())
        assertEquals(4 * 90_000L, clearedAt - shownAt, "4 s depois")
    }
}

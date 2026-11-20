package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs

/**
 * O desentrelaçamento do GoatCore (Definições → Desentrelaçar): o que fazer com um canal entrelaçado (os 576i/480i de
 * cabo e satélite, os 1080i HD), igual no Android e na Apple.
 *
 * - [FIELDS] ("Suave"): cada campo passa a ser uma imagem (50/60 por segundo num canal de 25/30), com as linhas que
 *   faltam interpoladas na direção da aresta (ELA): o movimento fica fluido, como numa TV.
 * - [BLEND] ("Normal"): os dois campos misturam-se numa imagem (as 25/30 do canal): mais leve, um pouco mais suave.
 * - [NONE]: o vídeo vai como vem.
 */
object Deinterlacing {
    const val NONE = 0
    const val FIELDS = 1
    const val BLEND = 2

    /** O descodificador não se sabe se desentrelaça (ver [InterlaceVerdict]). */
    const val DECODER_UNKNOWN = 0
    /** Dá as imagens tal como vêm, com os dois campos juntos (o "pente" no movimento): desentrelaça-se sempre. */
    const val DECODER_WOVEN = 1
    /** Já desentrelaça sozinho: não se faz nada (fazê-lo outra vez só tirava definição). */
    const val DECODER_DEINTERLACES = 2

    /**
     * O que fazer pela definição [setting] (Prefs.Deinterlace) e pelo vídeo: só nos entrelaçados ([interlaced], pelo
     * SPS ou pelo cabeçalho da sequência). O HDR e os 10 bits nunca: não há emissões entrelaçadas assim, e o caminho
     * deles (a TV em HDR, os 10 bits até ao ecrã) não passa pelo desentrelaçador.
     */
    fun mode(setting: String, interlaced: Boolean, bitDepth: Int = 8, hdr: Boolean = false): Int = when {
        !interlaced || hdr || bitDepth > 8 -> NONE
        setting == Prefs.Deinterlace.SMOOTH -> FIELDS
        setting == Prefs.Deinterlace.NORMAL -> BLEND
        else -> NONE
    }

    /**
     * O que se sabe do descodificador [name] (MediaCodecInfo.getName, em qualquer caixa). Os de software (os do Android
     * e o FFmpeg) dão sempre os campos juntos. O da NVIDIA (SHIELD) desentrelaça ele próprio: as imagens já saem sem
     * pente. Os outros (Amlogic, Realtek, MediaTek, Rockchip…) depende do modelo: decide o [InterlaceVerdict].
     */
    fun decoder(name: String): Int {
        val n = name.lowercase()
        return when {
            "nvidia" in n -> DECODER_DEINTERLACES
            n.startsWith("c2.android.") || n.startsWith("omx.google.") || "ffmpeg" in n -> DECODER_WOVEN
            else -> DECODER_UNKNOWN
        }
    }
}

/**
 * Decide, pelo que sai do descodificador, se ele já desentrelaçou: num canal entrelaçado com movimento, uma imagem com
 * os dois campos juntos tem "pente" (cada linha diferente das de cima e de baixo, que são do outro campo, e igual às
 * do mesmo campo); uma já desentrelaçada nunca tem. Quem desenha mede de vez em quando uma grelha de pontos da imagem
 * ([sample]): quantos têm pente e quantos têm detalhe na vertical (arestas, que existem nos dois casos).
 *
 * A heurística:
 * - Começa a desentrelaçar (um pente no ecrã nota-se muito mais do que um pouco menos de definição).
 * - [COMBED_NEEDED] medições com pente: o descodificador dá os campos juntos. Fica decidido de vez.
 * - [CLEAN_NEEDED] medições com detalhe e sem pente nenhum, sem nunca ter havido pente: o descodificador já
 *   desentrelaçou (ou o canal é progressivo codificado como entrelaçado, o "PsF" dos filmes): passa a mostrar tal como
 *   vem, mas continua a medir. O primeiro pente depois disso volta logo a desentrelaçar, e não se volta atrás.
 *
 * Não muda para trás e para a frente: uma cena parada de um canal entrelaçado não tem pente (os dois campos são
 * iguais), por isso "sem pente" só conta depois de muito tempo, e um pente decide para sempre.
 */
class InterlaceVerdict(
    /** O descodificador já se sabe que dá os campos juntos ([Deinterlacing.DECODER_WOVEN]): não há nada a medir. */
    woven: Boolean = false,
) {
    /** Desentrelaçar a imagem (true) ou mostrá-la como vem. */
    var deinterlace: Boolean = true
        private set

    /** Já se viu pente: o descodificador dá os campos juntos, não se mede mais. */
    var woven: Boolean = woven
        private set

    /** Ainda vale a pena medir ([sample]). */
    val measuring: Boolean get() = !woven

    private var combed = 0
    private var clean = 0

    /**
     * Uma medição de [total] pontos: [combedPoints] com pente, [detailPoints] com detalhe na vertical (ver o shader de
     * quem desenha). Devolve [deinterlace].
     */
    fun sample(combedPoints: Int, detailPoints: Int, total: Int): Boolean {
        if (woven || total <= 0) return deinterlace
        val minCombed = maxOf(MIN_COMBED_POINTS, total / 200)
        if (combedPoints >= minCombed && combedPoints * 10 >= detailPoints) {
            combed++
            clean = 0
            // Já a mostrar como vem: o pente nota-se, volta-se já.
            deinterlace = true
            if (combed >= COMBED_NEEDED) {
                woven = true
                deinterlace = true
            }
        } else if (detailPoints >= total / 50 && combedPoints * 100 <= detailPoints) {
            clean++
            if (combed == 0 && clean >= CLEAN_NEEDED) deinterlace = false
        }
        return deinterlace
    }

    companion object {
        const val COMBED_NEEDED = 3
        /** Medindo uma imagem em cada 8 (~6 por segundo num canal a 50), uns 25 segundos. */
        const val CLEAN_NEEDED = 150
        const val MIN_COMBED_POINTS = 6
    }
}

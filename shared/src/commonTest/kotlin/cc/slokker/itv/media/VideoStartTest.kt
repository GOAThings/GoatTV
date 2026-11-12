package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoStartTest {
    /** Ordem de descodificação de um open GOP: P, I (a de entrada), as B que se mostram antes dela, P, B… */
    @Test
    fun skipsLeadingPicturesOfTheFirstKeyframeOnly() {
        val s = VideoStart()
        val units = listOf(
            false to 1000L, // antes da primeira imagem-chave
            true to 4600L, // I de entrada
            false to 3400L, false to 4000L, // B que se mostram antes dela
            false to 7000L, false to 5800L, false to 6400L, // já depois: passam
            true to 9400L, false to 8200L, // a imagem-chave seguinte: as B dela já têm referências
        )
        assertEquals(
            listOf(false, true, false, false, true, true, true, true, true),
            units.map { (key, pts) -> s.pass(key, pts) },
        )
        s.restart()
        assertEquals(listOf(false, true, false), listOf(false to 10_000L, true to 13_000L, false to 12_000L).map { (k, p) -> s.pass(k, p) })
    }
}

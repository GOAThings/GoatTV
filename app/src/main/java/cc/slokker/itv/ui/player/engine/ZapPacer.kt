package cc.slokker.itv.ui.player.engine

import cc.slokker.itv.data.Prefs

/**
 * O atraso ao mudar de canal (Definições → Mudança de canal → Atraso ao mudar de canal, [Prefs.zapDelayMs]).
 *
 * Automático: uma tecla muda já. A passar canais depressa (outra mudança a menos de [FLIP_WINDOW_MS] da anterior)
 * só mudam o número e a informação no ecrã; o canal abre quando se pára ([SETTLE_MS] sem outra mudança). Assim não
 * se abre e fecha uma ligação por cada canal por onde se passa (o fornecedor só deixa uma, e recusa a nova enquanto
 * não largar a anterior). Com um tempo fixo (como o "Delay channel switch" do Kodi), espera-se sempre esse tempo.
 *
 * Quem chama espera o tempo devolvido e só então abre; uma mudança nova pelo meio cancela essa espera.
 */
internal class ZapPacer {
    private var lastZapAt: Long? = null

    /** Mudou-se de canal em [nowMs] (elapsedRealtime): quanto esperar antes de o abrir. */
    fun delayFor(nowMs: Long, setting: Int): Long {
        val since = lastZapAt?.let { nowMs - it }
        lastZapAt = nowMs
        return when (setting) {
            Prefs.ZapDelay.OFF -> 0L
            Prefs.ZapDelay.AUTO -> if (since != null && since < FLIP_WINDOW_MS) SETTLE_MS else 0L
            else -> setting.coerceAtLeast(0).toLong()
        }
    }

    companion object {
        /** Duas mudanças com menos do que isto entre elas: está-se a passar canais. */
        const val FLIP_WINDOW_MS = 500L

        /** A passar canais, o canal abre quando não há outra mudança durante isto. */
        const val SETTLE_MS = 350L
    }
}

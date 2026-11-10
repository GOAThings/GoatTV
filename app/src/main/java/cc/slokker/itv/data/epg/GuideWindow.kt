package cc.slokker.itv.data.epg

import cc.slokker.itv.data.Prefs

/**
 * Que programas do guia se guardam à volta de agora: [beforeMs] para trás (o guia mostra o que já passou, para ver em
 * catch-up) e [afterMs] para a frente. Com centenas de canais, muitos dias pesam na memória da box, por isso por
 * defeito é pouco (24 h e 36 h); as Definições → Guia deixam alargar.
 */
data class GuideWindow(val beforeMs: Long, val afterMs: Long) {
    fun keeps(startMs: Long, endMs: Long, now: Long) = endMs > now - beforeMs && startMs < now + afterMs

    companion object {
        val DEFAULT = GuideWindow(Prefs.GuideHours.PAST_DEFAULT * 3600_000L, Prefs.GuideHours.FUTURE_DEFAULT * 3600_000L)

        fun of(prefs: Prefs) = GuideWindow(prefs.epgPastHours * 3600_000L, prefs.epgFutureHours * 3600_000L)
    }
}

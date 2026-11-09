package cc.slokker.itv.ui.common

import cc.slokker.itv.ui.i18n.Strings

/** "1:05:09" ou "5:09": uma duração (o catch-up, quanto a pausa do direto vai atrás). */
internal fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "Hoje", "Ontem", "Amanhã" ou o dia da semana. */
internal fun dayLabel(s: Strings, t: Long): String {
    // Dias desde 1970 na hora local (dá certo também na passagem de ano).
    fun dayOf(ms: Long) = Math.floorDiv(ms + java.util.TimeZone.getDefault().getOffset(ms), 86_400_000L)
    val today = dayOf(System.currentTimeMillis())
    return when ((dayOf(t) - today).toInt()) {
        0 -> s.today
        -1 -> s.yesterday
        1 -> s.tomorrow
        else -> java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(s.locale, "EEEd"), s.locale).format(java.util.Date(t))
    }
}

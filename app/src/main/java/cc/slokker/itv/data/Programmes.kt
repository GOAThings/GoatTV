package cc.slokker.itv.data

// O que dá a uma hora, o que vem a seguir e quanto falta: a mesma regra em todos os ecrãs (TV, telemóvel, ecrã
// inicial, pausa do direto). As listas vêm por ordem de início, como o guia e o EPG curto as dão.

/** Está a dar às [t]: o início conta, o fim já é do programa seguinte. */
fun EpgEntry.isOn(t: Long): Boolean = startMs <= t && endMs > t

/** O programa que dá às [t], ou null (sem guia para essa hora). */
fun List<EpgEntry>.at(t: Long): EpgEntry? = firstOrNull { it.isOn(t) }

/** O programa seguinte: o primeiro que começa quando [current] acaba ou depois; sem [current], a partir de [t]. */
fun List<EpgEntry>.after(current: EpgEntry?, t: Long): EpgEntry? = firstOrNull { it.startMs >= (current?.endMs ?: t) }

/** Minutos que faltam até [endMs], contando o minuto que está a decorrer (no último minuto ainda diz 1). */
fun minutesLeft(endMs: Long, now: Long): Int = ((endMs - now) / 60_000 + 1).toInt()

/** Minutos que faltam para o programa acabar (ver [minutesLeft]). */
fun EpgEntry.minutesLeft(now: Long): Int = minutesLeft(endMs, now)

/**
 * Os programas a mostrar de um canal: o guia (passado incluído, já sem dois à mesma hora), ou, sem guia, o EPG curto
 * [shortEpg] do servidor, também sem repetidos. Os cartões que só mostram o EPG curto (início, favoritos, lista)
 * usam-no diretamente.
 */
fun programmes(guide: List<EpgEntry>, shortEpg: List<EpgEntry>): List<EpgEntry> =
    guide.ifEmpty { shortEpg.distinctBy { it.startMs } }

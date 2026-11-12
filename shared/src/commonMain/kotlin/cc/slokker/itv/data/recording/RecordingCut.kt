package cc.slokker.itv.data.recording

import cc.slokker.itv.data.RingEntry

/**
 * Onde cortar uma gravação no anel. O anel não sabe horas: sabe o tempo do vídeo (pelos PCR) de meio em meio
 * segundo ([RingEntry]) e o do último byte que chegou ([liveMediaMs], que é "agora"). Uma hora real `t`
 * fica no tempo do vídeo `liveMediaMs - (agora - t)`; como o relógio do anel avança com o tempo real quando a
 * ligação cai, isto não escorrega com as quebras.
 */
object RecordingCut {
    /**
     * O pedaço a gravar: começa num ponto do índice ([startOffset], o início de um pacote TS) e acaba quando o vídeo
     * chegar a [endMediaMs]. [late]: o início pedido já não está no anel (começa no mais antigo que há).
     */
    data class Range(
        val startOffset: Long,
        val startMediaMs: Long,
        val endMediaMs: Long,
        /** A hora real do início do ficheiro. */
        val fileStartMs: Long,
        val late: Boolean,
    )

    /**
     * O pedaço de [startWall] a [endWall] (horas reais), com o índice [entries] do anel, o tempo do vídeo no direto
     * [liveMediaMs] e a hora [nowWall]. O início é o último ponto do índice até ao instante pedido (nunca depois:
     * mais vale meio segundo a mais do que a menos). null sem índice.
     */
    fun range(entries: List<RingEntry>, liveMediaMs: Long, nowWall: Long, startWall: Long, endWall: Long): Range? {
        if (entries.isEmpty()) return null
        val wanted = mediaAt(liveMediaMs, nowWall, startWall)
        val start = atOrBefore(entries, wanted) ?: entries.first()
        val late = start.mediaMs > wanted + RingEntry.STEP_MS
        val end = mediaAt(liveMediaMs, nowWall, endWall).coerceAtLeast(start.mediaMs)
        return Range(start.offset, start.mediaMs, end, wallAt(liveMediaMs, nowWall, start.mediaMs), late)
    }

    /** O tempo do vídeo à hora real [wall]. */
    fun mediaAt(liveMediaMs: Long, nowWall: Long, wall: Long): Long = liveMediaMs - (nowWall - wall)

    /** A hora real do tempo do vídeo [mediaMs]. */
    fun wallAt(liveMediaMs: Long, nowWall: Long, mediaMs: Long): Long = nowWall - (liveMediaMs - mediaMs)

    /** O último ponto com o tempo até [mediaMs] (procura binária: o índice vai por ordem). */
    fun atOrBefore(entries: List<RingEntry>, mediaMs: Long): RingEntry? {
        var lo = 0
        var hi = entries.size - 1
        var found: RingEntry? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].mediaMs <= mediaMs) {
                found = entries[mid]
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}

package cc.slokker.itv.data

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Lê os pacotes MPEG-TS (188 bytes) de um fluxo que chega aos bocados e avisa de cada PCR (o relógio do emissor).
 * Volta a sincronizar sozinho quando perde o alinhamento (bytes a mais ou a menos depois de uma falha).
 * Decide também, logo nos primeiros KB, se o fluxo é mesmo TS ([isTs]).
 */
class TsScanner(private val onPcr: (offset: Long, pcrMs: Long) -> Unit) {
    private var pending = ByteArray(PACKET * 8)
    private var pendingLen = 0

    /** Posição absoluta (no anel) do primeiro byte de [pending]. */
    private var pendingStart = 0L
    private var segmentStart = 0L
    private var synced = false
    private var pcrPid = -1

    /** null: ainda não se sabe; true: TS; false: outra coisa (MP4, MKV…), não serve para o anel. */
    var isTs: Boolean? = null
        private set

    /** Início do último pacote completo (onde um leitor pode começar quando não há PCR). */
    var lastPacket = 0L
        private set

    /** Nova ligação a partir de [at]: o que sobrou da anterior já não continua. */
    fun reset(at: Long) {
        pendingLen = 0
        pendingStart = at
        segmentStart = at
        lastPacket = at
        synced = false
        pcrPid = -1
    }

    fun feed(data: ByteArray, off: Int, len: Int) {
        if (pendingLen + len > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + len))
        System.arraycopy(data, off, pending, pendingLen, len)
        pendingLen += len
        val p = pending
        var i = 0
        while (true) {
            if (!synced) {
                // Um 0x47 com mais três à distância de um pacote: é o início de um pacote.
                while (i + PACKET * 3 < pendingLen) {
                    if (p[i] == SYNC && p[i + PACKET] == SYNC && p[i + PACKET * 2] == SYNC && p[i + PACKET * 3] == SYNC) {
                        synced = true
                        if (isTs == null) isTs = true
                        break
                    }
                    i++
                }
                if (!synced) break
            }
            if (i + PACKET > pendingLen) break
            if (p[i] != SYNC) {
                synced = false
                continue
            }
            packet(p, i)
            i += PACKET
        }
        // Muitos KB logo no início sem um único pacote: não é TS.
        if (isTs == null && pendingStart + i - segmentStart >= NOT_TS_BYTES) isTs = false
        if (i > 0) {
            System.arraycopy(p, i, p, 0, pendingLen - i)
            pendingLen -= i
            pendingStart += i
        }
    }

    private fun packet(p: ByteArray, i: Int) {
        val offset = pendingStart + i
        lastPacket = offset
        val pid = ((p[i + 1].toInt() and 0x1F) shl 8) or (p[i + 2].toInt() and 0xFF)
        val adaptation = (p[i + 3].toInt() shr 4) and 3
        if (adaptation != 2 && adaptation != 3) return
        val length = p[i + 4].toInt() and 0xFF
        if (length < 7 || (p[i + 5].toInt() and 0x10) == 0) return
        // Um programa tem um só PID com o relógio; fica-se com o primeiro que aparece.
        if (pcrPid == -1) pcrPid = pid
        if (pid != pcrPid) return
        val base = ((p[i + 6].toLong() and 0xFF) shl 25) or
            ((p[i + 7].toLong() and 0xFF) shl 17) or
            ((p[i + 8].toLong() and 0xFF) shl 9) or
            ((p[i + 9].toLong() and 0xFF) shl 1) or
            ((p[i + 10].toLong() and 0xFF) shr 7)
        onPcr(offset, base / 90)
    }

    companion object {
        const val PACKET = 188
        private const val SYNC = 0x47.toByte()
        private const val NOT_TS_BYTES = 4_096L
    }
}

/**
 * O anel do "pausar o direto": os últimos minutos de um canal num ficheiro de tamanho fixo, escritos por uma
 * thread (a única ligação ao servidor) e lidos por outra (o player), que pode ir atrás do direto.
 *
 * As posições são absolutas (bytes desde o início); no ficheiro, a posição p fica em p % [capacity]. Quando a
 * escrita dá a volta, o mais antigo vai sendo reescrito: abaixo de [clobbered] já não se pode ler.
 * Cada ligação ao servidor é um segmento: um leitor não passa de um para o outro (os tempos do vídeo recomeçam),
 * recebe [BOUNDARY] e reabre no seguinte.
 *
 * O índice liga o tempo do vídeo (ms, calculado pelos PCR do TS, sempre a crescer) às posições no anel,
 * de [ENTRY_MS] em [ENTRY_MS]: é por ele que se recua "30 segundos".
 */
class TimeshiftRing(
    private val file: File,
    capacity: Long,
    maxAgeMs: Long,
    private val clock: () -> Long,
) : Closeable {
    data class Entry(val mediaMs: Long, val offset: Long)

    private val lock = Object()
    private val writeFile = RandomAccessFile(file, "rw")
    private val readFile = RandomAccessFile(file, "r")

    /** Tamanho do anel; pode diminuir uma vez (ver [fitToAge]) enquanto a escrita ainda não deu a volta. */
    @Volatile
    var capacity = capacity
        private set

    // Protegido por lock.
    private var written = 0L
    private var clobbered = 0L
    private val entries = ArrayDeque<Entry>()
    private val segments = ArrayList<Long>()
    private var closed = false
    private var finished = false
    private var failure: IOException? = null
    private var fitted = false
    private var maxAgeMs = maxAgeMs

    /** Abaixo disto (tempo do vídeo) já não se guarda: o programa anterior (ver [dropBefore]). */
    private var floorMs = Long.MIN_VALUE

    /** Segmentos já tirados do início: os ids não mudam (um leitor guarda o do seu). */
    private var removed = 0

    // Relógio do vídeo.
    private var media = 0L
    private var lastPcr = -1L
    private var lastTick = 0L
    private var lastPcrAt = 0L
    private var newSegment = false
    private val scanner = TsScanner(::onPcr)

    /** Muda o tempo máximo guardado; o tamanho do anel só se acerta por ele no primeiro minuto (ver [fitToAge]). */
    fun setMaxAge(ms: Long) = synchronized(lock) {
        if (ms == maxAgeMs) return
        maxAgeMs = ms
        trim()
    }

    /**
     * Esquece tudo antes de [mediaMs] (o início do programa que se está a ver): já não se pode recuar até lá e o
     * espaço volta a ser usado. Só avança; um leitor que já esteja atrás continua até ao próximo recomeço.
     */
    fun dropBefore(mediaMs: Long) = synchronized(lock) {
        val floor = mediaMs.coerceAtMost(media)
        if (floor <= floorMs) return
        floorMs = floor
        trim()
    }

    /** Ver [TsScanner.isTs]. */
    val isTs: Boolean? get() = synchronized(lock) { scanner.isTs }

    /** Bytes escritos desde o início. */
    val writtenBytes: Long get() = synchronized(lock) { written }

    /** Tempo do vídeo no direto (o último byte que chegou). */
    val liveMediaMs: Long get() = synchronized(lock) { media }

    /** O ponto mais antigo para onde se pode recuar. */
    val oldest: Entry? get() = synchronized(lock) { entries.firstOrNull() }

    /** Já não vem mais nada (a ligação acabou e não se volta a ligar, ou o anel fechou). */
    val isFinished: Boolean get() = synchronized(lock) { finished || closed }

    /** Quantos segmentos (ligações com dados) já houve. */
    val segmentCount: Int get() = synchronized(lock) { segments.size }

    // ---- Escrita (só a thread da ligação) ----------------------------------------------------

    /** Começa uma ligação nova (a primeira, ou depois de cair): os dados seguintes não continuam os anteriores. */
    fun beginSegment() = synchronized(lock) {
        segments.add(written)
        scanner.reset(written)
        lastPcr = -1L
        newSegment = true
        lock.notifyAll()
    }

    fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        val start: Long
        synchronized(lock) {
            if (closed) throw IOException("anel fechado")
            check(segments.isNotEmpty()) { "write sem beginSegment" }
            require(len <= capacity)
            start = written
            // Antes de escrever: o que estiver por baixo deixa de se poder ler.
            clobbered = maxOf(clobbered, start + len - capacity)
        }
        var pos = start % capacity
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left.toLong(), capacity - pos).toInt()
            writeFile.seek(pos)
            writeFile.write(data, o, n)
            o += n
            left -= n
            pos = 0
        }
        synchronized(lock) {
            written = start + len
            scanner.feed(data, off, len)
            val now = clock()
            // Sem PCR (ou ainda sem ele) o relógio anda com o tempo real em que os dados chegam.
            if (scanner.isTs != false && now - lastPcrAt > NO_PCR_MS) {
                if (lastTick != 0L) media += (now - lastTick).coerceAtLeast(0)
                lastTick = now
                addEntry(if (newSegment) segments.last() else scanner.lastPacket)
            }
            trim()
            fitToAge()
            lock.notifyAll()
        }
    }

    /** A ligação caiu ou acabou; [final]: não vem outra, com o erro que a fez cair (null = fim normal). */
    fun endSegment(final: Boolean, error: IOException? = null) = synchronized(lock) {
        if (final && !finished) {
            finished = true
            failure = error
        }
        lock.notifyAll()
    }

    private fun onPcr(offset: Long, pcrMs: Long) {
        val now = clock()
        if (lastPcr >= 0) {
            var d = pcrMs - lastPcr
            if (d < -PCR_WRAP_MS / 2) d += PCR_WRAP_MS
            // Um salto (canal trocado no servidor, relógio reposto): conta o tempo real que passou.
            media += if (d in 0..MAX_PCR_STEP_MS) d else arrival(now)
        } else {
            media += arrival(now)
        }
        lastPcr = pcrMs
        lastTick = now
        lastPcrAt = now
        addEntry(if (newSegment) segments.last() else offset)
    }

    private fun arrival(now: Long) = if (lastTick == 0L) 0L else (now - lastTick).coerceAtLeast(0)

    private fun addEntry(offset: Long) {
        val last = entries.lastOrNull()
        if (newSegment || last == null || media - last.mediaMs >= ENTRY_MS) {
            entries.addLast(Entry(media, offset.coerceAtLeast(last?.offset ?: 0L)))
            newSegment = false
        }
    }

    /** Tira do índice o que já foi reescrito, passou do tempo máximo ou é do programa anterior. */
    private fun trim() {
        while (entries.size > 1 && entries.first().mediaMs < floorMs) entries.removeFirst()
        while (entries.isNotEmpty() && (entries.first().offset < clobbered || media - entries.first().mediaMs > maxAgeMs)) {
            entries.removeFirst()
        }
        val floor = entries.firstOrNull()?.offset ?: clobbered
        while (segments.size > 1 && segments[1] <= floor) {
            segments.removeAt(0)
            removed++
        }
    }

    /**
     * Ao fim do primeiro minuto já se sabe o débito do canal: o anel fica com o tamanho para [maxAgeMs]
     * (com folga), para um canal leve não ocupar o máximo do disco. Só antes de a escrita dar a volta.
     */
    private fun fitToAge() {
        if (fitted || media < FIT_AFTER_MS) return
        fitted = true
        val wanted = (written.toDouble() / media * maxAgeMs * FIT_MARGIN).toLong().coerceAtLeast(MIN_CAPACITY)
        if (wanted < capacity && written <= wanted) capacity = wanted
    }

    // ---- Leitura (o player) ------------------------------------------------------------------

    /** O segmento que contém [pos] (o último que começa até lá). */
    fun segmentAt(pos: Long): Int = synchronized(lock) {
        var id = 0
        for (i in segments.indices) if (segments[i] <= pos) id = i
        id + removed
    }

    /** Onde começa o segmento [id], ou null se já saiu do anel. */
    fun segmentStart(id: Int): Long? = synchronized(lock) { segments.getOrNull(id - removed) }

    /**
     * Lê a partir de [pos], dentro do segmento [segment], até [len] bytes. Se ainda não há nada para ler espera
     * até [waitMs] (interrompível). Devolve os bytes lidos, ou [WAIT], [BOUNDARY], [OVERRUN] ou [END].
     * Uma ligação que acabou com erro (e sem outra a seguir) lança esse erro quando se chega ao fim dela.
     */
    fun read(pos: Long, segment: Int, buffer: ByteArray, off: Int, len: Int, waitMs: Long): Int {
        val n: Int
        synchronized(lock) {
            var waited = false
            while (true) {
                if (closed) return END
                // O segmento do leitor já saiu do anel (passou do tempo máximo): é como se tivesse sido reescrito.
                if (pos < clobbered || segment < removed) return OVERRUN
                val next = segments.getOrNull(segment - removed + 1)
                val end = next ?: written
                val available = end - pos
                if (available > 0) {
                    n = minOf(len.toLong(), available).toInt()
                    break
                }
                if (next != null) return BOUNDARY
                if (finished) {
                    failure?.let { throw it }
                    return END
                }
                if (waited) return WAIT
                try {
                    lock.wait(waitMs)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException().apply { initCause(e) }
                }
                waited = true
            }
        }
        // Fora do lock (a escrita não espera pela leitura); um RandomAccessFile não fecha se a thread for
        // interrompida, ao contrário de um FileChannel.
        try {
            synchronized(readFile) {
                var p = pos % capacity
                var o = off
                var left = n
                while (left > 0) {
                    val chunk = minOf(left.toLong(), capacity - p).toInt()
                    readFile.seek(p)
                    readFile.readFully(buffer, o, chunk)
                    o += chunk
                    left -= chunk
                    p = 0
                }
            }
        } catch (e: IOException) {
            if (synchronized(lock) { closed }) return END
            throw e
        }
        // Se a escrita passou por cima enquanto se lia, o que se leu já não é o que lá estava.
        synchronized(lock) { if (pos < clobbered) return OVERRUN }
        return n
    }

    /** O ponto do índice mais perto de [mediaMs] sem passar dele (ou o mais antigo). */
    fun entryAtOrBefore(mediaMs: Long): Entry? = synchronized(lock) {
        entries.lastOrNull { it.mediaMs <= mediaMs } ?: entries.firstOrNull()
    }

    /** O primeiro ponto do índice a partir de [offset] (para reabrir no início de um segmento). */
    fun entryFrom(offset: Long): Entry? = synchronized(lock) { entries.firstOrNull { it.offset >= offset } }

    /** Tempo do vídeo em [offset] (o ponto do índice antes dele). */
    fun mediaAt(offset: Long): Long? = synchronized(lock) { entries.lastOrNull { it.offset <= offset }?.mediaMs }

    /** Fecha e apaga o ficheiro; quem estiver à espera recebe [END]. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            lock.notifyAll()
        }
        runCatching { writeFile.close() }
        synchronized(readFile) { runCatching { readFile.close() } }
        file.delete()
    }

    companion object {
        const val WAIT = 0
        const val END = -1
        const val BOUNDARY = -2
        const val OVERRUN = -3

        /** Um ponto no índice a cada meio segundo de vídeo. */
        const val ENTRY_MS = 500L

        /** Sem PCR há mais do que isto: o relógio passa a ser o tempo real. */
        private const val NO_PCR_MS = 1_500L

        /** Entre dois PCR seguidos nunca há mais do que isto (o normal é 40–100 ms). */
        private const val MAX_PCR_STEP_MS = 5_000L

        /** O PCR tem 33 bits a 90 kHz: dá a volta a cada ~26,5 h. */
        private const val PCR_WRAP_MS = (1L shl 33) / 90
        private const val FIT_AFTER_MS = 60_000L
        private const val FIT_MARGIN = 1.15
        const val MIN_CAPACITY = 32L * 1024 * 1024
    }
}

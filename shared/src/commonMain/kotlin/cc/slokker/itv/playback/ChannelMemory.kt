package cc.slokker.itv.playback

import cc.slokker.itv.ui.player.engine.TrackInfo
import cc.slokker.itv.ui.player.engine.TrackPick
import kotlin.concurrent.Volatile

// O que a pessoa escolheu à mão em cada canal (a faixa de áudio, as legendas e a proporção da imagem), para o canal
// voltar como ficou. Só neste aparelho (não vai para a conta); o mesmo no Android e na Apple.

/**
 * A memória das faixas: o que se guarda de uma faixa escolhida à mão e, noutro dia, qual das faixas do canal é essa.
 * Os ids e os PID mudam entre sessões, por isso guarda-se o que a faixa é: a língua (ISO 639-1), o formato ("ac3",
 * "dvb", "teletext"…), o detalhe (a página do teletexto, o papel da faixa), os canais e, entre faixas iguais em tudo,
 * a posição. O texto guardado é `língua;formato;detalhe;canais;posição`, ou [OFF] (legendas desligadas).
 */
object TrackMemory {
    /** As legendas desligadas à mão. */
    const val OFF = "off"

    /** O que guardar da faixa [index] de [tracks]; [OFF] sem faixa (index < 0: as legendas desligadas). */
    fun memo(tracks: List<TrackInfo>, index: Int): String {
        val t = tracks.getOrNull(index) ?: return OFF
        val before = (0 until index).count { same(tracks[it], t) }
        return listOf(t.code.orEmpty(), clean(t.codec), clean(t.detail), channels(t).toString(), before.toString()).joinToString(";")
    }

    /**
     * A faixa de [tracks] (o índice) que corresponde a [memo], ou null se hoje não há nenhuma (ou sem memória, ou
     * [OFF]): aí valem as regras de sempre ([TrackPick]) e a memória fica (a faixa pode voltar amanhã).
     *
     * A língua tem de ser a mesma; entre as dessa língua, a que tiver o mesmo formato, detalhe e canais (por esta
     * ordem de importância). Uma faixa guardada sem língua só corresponde a outra sem língua do mesmo formato e detalhe.
     */
    fun find(tracks: List<TrackInfo>, memo: String): Int? {
        if (memo.isEmpty() || memo == OFF) return null
        val parts = memo.split(';')
        if (parts.size < 5) return null
        val (language, codec, detail) = parts
        val channels = parts[3].toIntOrNull() ?: 0
        val position = parts[4].toIntOrNull() ?: 0
        val usable = tracks.indices.filter { tracks[it].supported }
        val pool = if (language.isNotEmpty()) {
            usable.filter { tracks[it].code == language }
        } else {
            usable.filter { tracks[it].code == null && clean(tracks[it].codec) == codec && clean(tracks[it].detail) == detail }
        }
        if (pool.isEmpty()) return null
        fun score(i: Int): Int {
            val t = tracks[i]
            return (if (clean(t.codec) == codec) 4 else 0) + (if (clean(t.detail) == detail) 2 else 0) + (if (channels(t) == channels) 1 else 0)
        }
        val best = pool.maxOf(::score)
        val ties = pool.filter { score(it) == best }
        // Só entre faixas iguais em tudo é que a posição diz qual era.
        return if (best == 7) ties.getOrNull(position) ?: ties.first() else ties.first()
    }

    /** A faixa de áudio que as regras de sempre escolhem: a língua preferida e o surround ou, sem nada disso, a primeira que toca. */
    fun defaultAudio(tracks: List<TrackInfo>, language: String?, surround: Boolean): Int? =
        TrackPick.audio(tracks.map { it.copy(selected = false) }, language, surround) ?: tracks.indices.firstOrNull { tracks[it].supported }

    /**
     * O que guardar quando a pessoa escolhe a faixa de áudio [index]: "" se é a que as regras de sempre escolhiam
     * ([language] e [surround], como no [TrackPick.audio]): escolher essa outra vez limpa a memória do canal.
     */
    fun audioChoice(tracks: List<TrackInfo>, index: Int, language: String?, surround: Boolean): String =
        if (index !in tracks.indices || index == defaultAudio(tracks, language, surround)) "" else memo(tracks, index)

    /**
     * O mesmo para as legendas ([index] < 0: desligadas): "" se é o que a língua preferida [language] dava (sem
     * língua, ou sem legendas nela, desligadas).
     */
    fun textChoice(tracks: List<TrackInfo>, index: Int, language: String?): String {
        val usual = TrackPick.text(tracks.map { it.copy(selected = false) }, language) ?: -1
        val chosen = if (index in tracks.indices) index else -1
        return if (chosen == usual) "" else memo(tracks, chosen)
    }

    private fun same(a: TrackInfo, b: TrackInfo) =
        a.code == b.code && clean(a.codec) == clean(b.codec) && clean(a.detail) == clean(b.detail) && channels(a) == channels(b)

    private fun channels(t: TrackInfo) = if (t.channels > 0) t.channels else 0

    /** Sem os separadores do texto guardado. */
    private fun clean(s: String?): String = s.orEmpty().filter { it != ';' && it != '\t' && it != '\n' && it != '\r' }.lowercase()
}

/**
 * A memória por canal ("lista|canal" → o áudio, as legendas e a proporção escolhidos à mão; "" = nada, valem as
 * definições). Guardam-se os [MAX] canais usados há menos tempo. No Modo TV Pro a chave é o canal da lista única, não
 * a fonte que está a dar.
 *
 * O texto guardado ([encode]/[decode]) é uma linha por canal, do mais antigo para o mais recente:
 * `<áudio>\t<legendas>\t<proporção>\t<lista>|<canal>`.
 */
class ChannelMemory(entries: Map<String, List<String>> = emptyMap()) {
    // Cópia nova a cada mudança: quem lê nunca vê o mapa a meio de uma escrita.
    @Volatile
    private var map: Map<String, List<String>> = LinkedHashMap(entries)

    /** A faixa de áudio escolhida à mão no canal (ver [TrackMemory]); "" sem nenhuma. */
    fun audio(playlistId: String, channelKey: String): String = read(playlistId, channelKey, AUDIO)

    /** As legendas escolhidas à mão no canal ([TrackMemory.OFF] = desligadas); "" sem escolha. */
    fun text(playlistId: String, channelKey: String): String = read(playlistId, channelKey, TEXT)

    /** A proporção escolhida à mão no canal ("fit", "zoom", "fill"); "" = a das definições. */
    fun aspect(playlistId: String, channelKey: String): String = read(playlistId, channelKey, ASPECT)

    /** Guarda o áudio do canal ("" limpa). Devolve true se mudou alguma coisa (há que gravar). */
    fun setAudio(playlistId: String, channelKey: String, memo: String): Boolean = write(playlistId, channelKey, AUDIO, memo)

    fun setText(playlistId: String, channelKey: String, memo: String): Boolean = write(playlistId, channelKey, TEXT, memo)

    /**
     * Guarda a proporção [aspect] escolhida no canal; se é a das definições ([global]), limpa: só se guarda a que é
     * diferente.
     */
    fun setAspect(playlistId: String, channelKey: String, aspect: String, global: String): Boolean =
        write(playlistId, channelKey, ASPECT, if (aspect == global) "" else aspect)

    /**
     * O canal abriu-se: se tem memória, passa a ser o usado há menos tempo. Devolve true se a ordem mudou (há que
     * gravar).
     */
    fun touch(playlistId: String, channelKey: String): Boolean {
        val k = key(playlistId, channelKey)
        val current = map
        val entry = current[k] ?: return false
        if (current.keys.last() == k) return false
        map = LinkedHashMap(current).apply {
            remove(k)
            put(k, entry)
        }
        return true
    }

    fun encode(): String = map.entries.joinToString("\n") { (k, v) -> "${v[AUDIO]}\t${v[TEXT]}\t${v[ASPECT]}\t$k" }

    private fun read(playlistId: String, channelKey: String, item: Int): String = map[key(playlistId, channelKey)]?.get(item).orEmpty()

    private fun write(playlistId: String, channelKey: String, item: Int, value: String): Boolean {
        val k = key(playlistId, channelKey)
        // Uma chave com quebra de linha não cabe no texto guardado (não há listas assim; fica sem memória).
        if ('\n' in k || '\r' in k) return false
        val clean = value.filter { it != '\t' && it != '\n' && it != '\r' }
        val current = map
        val old = current[k]
        if ((old?.get(item) ?: "") == clean) return false
        val entry = (old ?: EMPTY).toMutableList().also { it[item] = clean }
        val next = LinkedHashMap(current)
        next.remove(k)
        // Um canal sem nada escolhido à mão não ocupa lugar.
        if (entry.any { it.isNotEmpty() }) next[k] = entry
        // Não cresce sem fim: os usados há mais tempo saem primeiro.
        while (next.size > MAX) next.remove(next.keys.first())
        map = next
        return true
    }

    private fun key(playlistId: String, channelKey: String) = "$playlistId|$channelKey"

    companion object {
        /** Quantos canais se guardam. */
        const val MAX = 500

        private const val AUDIO = 0
        private const val TEXT = 1
        private const val ASPECT = 2
        private val EMPTY = listOf("", "", "")

        fun decode(text: String?): ChannelMemory {
            val entries = LinkedHashMap<String, List<String>>()
            text.orEmpty().lineSequence().forEach { line ->
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4 || '|' !in parts[3]) return@forEach
                val entry = parts.subList(0, 3)
                if (entry.any { it.isNotEmpty() }) entries[parts[3]] = entry
            }
            while (entries.size > MAX) entries.remove(entries.keys.first())
            return ChannelMemory(entries)
        }
    }
}

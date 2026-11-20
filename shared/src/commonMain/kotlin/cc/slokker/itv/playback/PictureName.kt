package cc.slokker.itv.playback

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * O nome do ficheiro de uma imagem guardada do canal ("Guardar imagem" no leitor), igual no Android e na Apple: o nome
 * do canal e a hora, só com o que qualquer sistema de ficheiros aceita.
 */
object PictureName {
    const val MAX_CHANNEL = 60

    /** "RTP 1 2026-11-20 21.34.05" (sem a extensão), com a hora [epochMs] no fuso [zone]. */
    fun of(channel: String, epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        fun two(n: Int) = n.toString().padStart(2, '0')
        val stamp = "${t.year}-${two(t.month.number)}-${two(t.dayOfMonth)} ${two(t.hour)}.${two(t.minute)}.${two(t.second)}"
        return "${clean(channel)} $stamp"
    }

    /** O nome do canal sem o que não pode ir num nome de ficheiro (/ : * ? …); vazio fica "GoatTV". */
    fun clean(channel: String): String {
        val kept = channel.map { c -> if (c.isLetterOrDigit() || c in " -_+&()[]'" ) c else ' ' }.joinToString("")
        val name = kept.split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(MAX_CHANNEL).trim()
        return name.ifEmpty { "GoatTV" }
    }
}

package cc.slokker.itv.data.epg

import cc.slokker.itv.data.EpgEntry
import cc.slokker.itv.data.api.BackendApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.Reader

/**
 * Guia da conta de uma lista: os canais que a pessoa associou a um canal de guia (Definições → canal →
 * Guia). No XMLTV que o servidor devolve, o id de cada canal já é o channelKey da lista.
 * [programmes]: channelKey → programas por ordem.
 */
class AccountGuide(
    val playlistId: String,
    val etag: String?,
    val fetchedAt: Long,
    val programmes: Map<String, List<EpgEntry>>,
) {
    /** Só os programas da janela à volta de [now] (o guia guardado em disco, ou mantido por um 304). */
    fun trimmed(now: Long, fetchedAt: Long = this.fetchedAt): AccountGuide {
        val from = now - WINDOW_BEFORE_MS
        val to = now + WINDOW_AFTER_MS
        val kept = programmes.mapValues { (_, list) -> list.filter { it.endMs > from && it.startMs < to } }.filterValues { it.isNotEmpty() }
        return AccountGuide(playlistId, etag, fetchedAt, kept)
    }

    companion object {
        /**
         * Lê o XMLTV do servidor. Traz de ontem a +7 dias; guarda-se só a mesma janela do guia das listas
         * (24 h para trás, 36 h para a frente): com centenas de canais, 8 dias pesam na memória da box.
         */
        fun parse(playlistId: String, etag: String?, fetchedAt: Long, reader: Reader): AccountGuide {
            val programmes = HashMap<String, ArrayList<EpgEntry>>()
            val titles = HashMap<String, String>()
            val from = fetchedAt - WINDOW_BEFORE_MS
            val to = fetchedAt + WINDOW_AFTER_MS
            XmltvParser(
                onChannel = { _, _ -> },
                wantsProgramme = { _, start, end -> end > from && start < to },
                onProgramme = { ch, start, end, title ->
                    programmes.getOrPut(ch) { ArrayList() } += EpgEntry(titles.getOrPut(title) { title }, start, end)
                },
            ).parse(reader)
            programmes.values.forEach { list -> list.sortBy { it.startMs } }
            return AccountGuide(playlistId, etag, fetchedAt, programmes)
        }

        const val WINDOW_BEFORE_MS = 24 * 3600_000L
        const val WINDOW_AFTER_MS = 36 * 3600_000L
    }
}

class AccountGuideSource(private val backend: BackendApi) {

    /**
     * Descarrega o guia da conta de [playlistId]. Com o [previous] (mesmo ETag), um 304 devolve-o
     * com a hora atualizada, sem voltar a ler nada.
     */
    suspend fun load(playlistId: String, previous: AccountGuide?): AccountGuide = withContext(Dispatchers.IO) {
        val res = backend.epgGuide(playlistId, previous?.etag)
        val now = System.currentTimeMillis()
        if (res.code() == 304 && previous != null) return@withContext previous.trimmed(now, fetchedAt = now)
        if (!res.isSuccessful) throw HttpException(res)
        // O OkHttp tira o gzip sozinho (foi ele que pediu Accept-Encoding).
        val body = res.body() ?: throw java.io.IOException("Empty guide")
        body.use { AccountGuide.parse(playlistId, res.headers()["ETag"], now, it.charStream().buffered(64 * 1024)) }
    }
}

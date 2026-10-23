package cc.slokker.itv.data

import android.content.Context
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.epg.AccountGuide
import cc.slokker.itv.data.epg.XmltvGuide
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Cache em disco da config da conta e das listas já carregadas, para a app abrir logo no último canal
 * e atualizar por trás. Fica na pasta privada da app (sem backup) e é apagada ao terminar sessão.
 */
class CacheStore(context: Context, private val gson: Gson) {

    private val dir = File(context.filesDir, "cache")

    private class CachedPlaylist(
        val version: Int,
        val playlist: Playlist,
        val categories: List<Category>,
        val channels: List<Channel>,
        val account: XtreamAccount?,
        val guideUrl: String?,
        /** 0 em caches antigas: contam como desatualizadas. */
        val fetchedAt: Long,
    )

    private class CachedReminders(val reminders: List<Reminder>)

    suspend fun loadReminders(): List<Reminder> = read("reminders.json.gz", CachedReminders::class.java)?.reminders.orEmpty().filterNotNull()

    suspend fun saveReminders(reminders: List<Reminder>) = write("reminders.json.gz", CachedReminders(reminders))

    private class CachedGuide(val version: Int, val playlistId: String, val guide: XmltvGuide)

    suspend fun loadConfig(): ConfigResponse? = read("config.json.gz", ConfigResponse::class.java)

    suspend fun saveConfig(config: ConfigResponse) = write("config.json.gz", config)

    /** A lista em cache, só se tiver sido carregada com os mesmos dados (servidor, credenciais, URL). */
    suspend fun loadPlaylist(playlist: Playlist): PlaylistContent? {
        val cached = read(fileFor(playlist.id), CachedPlaylist::class.java) ?: return null
        if (cached.version != VERSION || cached.playlist != playlist || cached.channels.isNullOrEmpty()) return null
        return PlaylistContent(playlist, cached.categories, cached.channels, cached.account, cached.guideUrl, cached.fetchedAt)
    }

    suspend fun hasPlaylist(playlist: Playlist): Boolean = loadPlaylist(playlist) != null

    suspend fun savePlaylist(content: PlaylistContent) = write(
        fileFor(content.playlist.id),
        CachedPlaylist(VERSION, content.playlist, content.categories, content.channels, content.account, content.guideUrl, content.fetchedAt),
    )

    /** O guia XMLTV guardado da lista, só se vier do mesmo URL. */
    suspend fun loadGuide(playlistId: String, url: String): XmltvGuide? {
        val cached = read(guideFile(playlistId), CachedGuide::class.java) ?: return null
        if (cached.version != VERSION || cached.playlistId != playlistId || cached.guide?.url != url) return null
        return cached.guide.takeIf { it.programmes != null && it.names != null }
    }

    suspend fun saveGuide(playlistId: String, guide: XmltvGuide) = write(guideFile(playlistId), CachedGuide(VERSION, playlistId, guide))

    private class CachedAccountGuide(val version: Int, val guide: AccountGuide)

    /** O guia da conta guardado da lista (canais associados a um canal de guia). */
    suspend fun loadAccountGuide(playlistId: String): AccountGuide? {
        val cached = read(accountGuideFile(playlistId), CachedAccountGuide::class.java) ?: return null
        if (cached.version != VERSION || cached.guide?.playlistId != playlistId || cached.guide.programmes == null) return null
        return cached.guide
    }

    suspend fun saveAccountGuide(guide: AccountGuide) = write(accountGuideFile(guide.playlistId), CachedAccountGuide(VERSION, guide))

    suspend fun removeAccountGuide(playlistId: String) = withContext(Dispatchers.IO) { File(dir, accountGuideFile(playlistId)).delete() }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.deleteRecursively() }

    /** Esquece os canais e o guia de uma lista apagada. */
    suspend fun removePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        File(dir, fileFor(playlistId)).delete()
        File(dir, guideFile(playlistId)).delete()
    }

    private fun fileFor(playlistId: String) = "playlist_" + playlistId.filter { it.isLetterOrDigit() } + ".json.gz"

    private fun guideFile(playlistId: String) = "epg_" + playlistId.filter { it.isLetterOrDigit() } + ".json.gz"

    private fun accountGuideFile(playlistId: String) = "epg_account_" + playlistId.filter { it.isLetterOrDigit() } + ".json.gz"

    private suspend fun <T> read(name: String, type: Class<T>): T? = withContext(Dispatchers.IO) {
        val file = File(dir, name)
        if (!file.exists()) return@withContext null
        // Cache estragada ou de outra versão: ignora-se e volta-se a pedir à rede.
        runCatching { GZIPInputStream(file.inputStream()).bufferedReader().use { gson.fromJson(it, type) } }.getOrNull()
    }

    private suspend fun write(name: String, value: Any) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            // Escreve para um ficheiro temporário e troca: nunca fica meio escrito.
            val tmp = File(dir, "$name.tmp")
            GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { gson.toJson(value, it) }
            tmp.renameTo(File(dir, name))
        }
    }

    private companion object {
        /** Sobe quando o formato dos modelos mudar, para não ler caches antigas. */
        const val VERSION = 1
    }
}

package cc.slokker.itv.data

import android.content.Context
import cc.slokker.itv.data.api.ConfigResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Listas, favoritos e definições de quem usa a GoatTV sem conta: ficam só nesta TV, no mesmo formato
 * da config da conta. Está fora da cache (que se apaga ao terminar sessão) e não vai em backups.
 */
class LocalStore(context: Context, private val gson: Gson) {

    private val file = File(context.filesDir, "local/config.json")
    private val mutex = Mutex()

    suspend fun load(): ConfigResponse = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Ficheiro estragado: começa vazio (as listas voltam a adicionar-se).
            runCatching { file.takeIf { it.exists() }?.reader()?.use { gson.fromJson(it, ConfigResponse::class.java) } }.getOrNull()
                ?: ConfigResponse(user = null, device = null, playlists = emptyList(), favorites = null, settings = null)
        }
    }

    /** Em série: as gravações chegam ao disco pela ordem em que foram pedidas. */
    suspend fun save(config: ConfigResponse) = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                file.parentFile?.mkdirs()
                // Escreve para um ficheiro temporário e troca: nunca fica meio escrito.
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writer().use { gson.toJson(config, it) }
                tmp.renameTo(file)
            }
        }
    }
}

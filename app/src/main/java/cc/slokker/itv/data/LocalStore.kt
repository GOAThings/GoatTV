package cc.slokker.itv.data

import android.content.Context
import android.util.Log
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
            val empty = ConfigResponse(user = null, device = null, playlists = emptyList(), favorites = null, settings = null)
            if (!file.exists()) return@withLock empty
            val config = runCatching { file.reader().use { gson.fromJson(it, ConfigResponse::class.java) } }
                .onFailure { Log.w(TAG, "${file.name} ilegível", it) }
                .getOrNull()
            if (config == null) {
                // Ficheiro estragado (ou de um formato que esta versão não lê): começa vazio, mas o ficheiro fica de
                // lado com outro nome, para a próxima gravação não apagar as listas de vez (dá para as recuperar).
                val aside = File(file.parentFile, "${file.name}.bad-${System.currentTimeMillis()}")
                if (file.renameTo(aside)) Log.w(TAG, "${file.name} guardado como ${aside.name}") else Log.w(TAG, "Não deu para pôr ${file.name} de lado")
            }
            config ?: empty
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

    private companion object {
        const val TAG = "GoatTV"
    }
}

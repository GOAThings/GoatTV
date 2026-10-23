package cc.slokker.itv.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore("session")

/**
 * Guarda localmente só o token do dispositivo (nunca a password), o utilizador e o último canal visto.
 * [Keys.TOKEN_KIND] distingue o token de dispositivo (GoatTV) do token de pessoa que a i-tv guardava.
 */
class SessionStore(private val context: Context) {

    data class Session(val token: String, val userId: String, val email: String?, val deviceId: String)

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val TOKEN_KIND = stringPreferencesKey("token_kind")
        val LAST_PLAYLIST = stringPreferencesKey("last_playlist")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val AUTO_START = booleanPreferencesKey("auto_start")
        /** Sem conta: as listas estão nesta TV (LocalStore). */
        val LOCAL = booleanPreferencesKey("local")
    }

    private suspend fun prefs(): Preferences = context.dataStore.data.first()

    /**
     * A sessão guardada. Um token antigo (da i-tv, login da pessoa) não serve na conta Slokker:
     * apaga-se e a TV mostra o ecrã de ativação uma vez (os últimos canais ficam).
     */
    suspend fun load(): Session? {
        val p = prefs()
        val token = p[Keys.TOKEN] ?: return null
        val deviceId = p[Keys.DEVICE_ID]
        if (p[Keys.TOKEN_KIND] != KIND_DEVICE || deviceId == null) {
            clearLogin()
            return null
        }
        val userId = p[Keys.USER_ID] ?: return null
        return Session(token, userId, p[Keys.EMAIL], deviceId)
    }

    suspend fun save(session: Session) {
        context.dataStore.edit {
            it[Keys.TOKEN] = session.token
            it[Keys.USER_ID] = session.userId
            if (session.email != null) it[Keys.EMAIL] = session.email else it.remove(Keys.EMAIL)
            it[Keys.DEVICE_ID] = session.deviceId
            it[Keys.TOKEN_KIND] = KIND_DEVICE
        }
    }

    suspend fun updateToken(token: String) {
        context.dataStore.edit { it[Keys.TOKEN] = token }
    }

    suspend fun lastPlaylist(): String? = prefs()[Keys.LAST_PLAYLIST]

    suspend fun lastChannel(): String? = prefs()[Keys.LAST_CHANNEL]

    suspend fun saveLast(playlistId: String, channelKey: String) {
        context.dataStore.edit {
            it[Keys.LAST_PLAYLIST] = playlistId
            it[Keys.LAST_CHANNEL] = channelKey
        }
    }

    /** Cópia local de "Arrancar com a box": o arranque do sistema não tem sessão nem rede. */
    suspend fun autoStart(): Boolean = prefs()[Keys.AUTO_START] ?: false

    suspend fun setAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_START] = enabled }
    }

    /** A TV usa listas próprias, sem conta (só conta quando não há sessão de conta). */
    suspend fun isLocal(): Boolean = prefs()[Keys.LOCAL] ?: false

    suspend fun setLocal(local: Boolean) {
        context.dataStore.edit { it[Keys.LOCAL] = local }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    /** Só o login (token, utilizador, dispositivo): o último canal e o arranque automático ficam. */
    private suspend fun clearLogin() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN)
            it.remove(Keys.USER_ID)
            it.remove(Keys.EMAIL)
            it.remove(Keys.DEVICE_ID)
            it.remove(Keys.TOKEN_KIND)
        }
    }

    private companion object {
        const val KIND_DEVICE = "device"
    }
}

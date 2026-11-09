package cc.slokker.itv.data

import cc.slokker.itv.data.api.BackendApi
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.PlaylistDto
import cc.slokker.itv.data.api.toDto
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import retrofit2.Response

/**
 * Onde vive o perfil (listas, favoritos, definições): na conta GOAT ([AccountProfile]) ou só nesta TV
 * ([LocalProfile]). É aqui que se decide para onde vai cada gravação e o que cada modo permite, em vez de
 * `local`/`token`/`testMode` espalhados pelo ViewModel. O modo de teste é um [AccountProfile] que recusa escrever.
 */
interface ProfileRepository {
    /** O perfil é o da conta (o servidor manda); false = só nesta TV. */
    val isAccount: Boolean

    /** As alterações vão ao servidor (com conta, fora do modo de teste). */
    val syncs: Boolean

    /** Escolher o guia de um canal (IPTV-EPG.org, no servidor da conta). */
    val supportsEpgMapping: Boolean get() = isAccount

    /** Listas Stalker: só sem conta (a conta expõe o portal como M3U). */
    val supportsStalker: Boolean get() = !isAccount

    /** O índice de canais vai para a conta (para o site organizar a lista). */
    val uploadsIndex: Boolean get() = syncs

    /** Porque é que uma escrita na conta é recusada agora (modo de teste), ou null. */
    val refusal: String? get() = null

    /** A config a guardar em disco com [settings], [favorites] e [playlists] de agora, a partir da que veio ([base]). */
    fun withProfile(base: ConfigResponse, settings: JsonObject, favorites: JsonElement, playlists: List<Playlist>): ConfigResponse

    /** Guarda [config] em disco: sem conta é a única cópia; com conta é a cache do arranque rápido. */
    suspend fun save(config: ConfigResponse)

    /** Grava no perfil GoatTV da conta (PATCH). Só quando [syncs]. */
    suspend fun patch(body: JsonObject): Response<JsonObject>
}

/** Sem conta: tudo fica no LocalStore, com as listas, e nada vai ao servidor. */
class LocalProfile(private val store: LocalStore) : ProfileRepository {
    override val isAccount = false
    override val syncs = false

    override fun withProfile(base: ConfigResponse, settings: JsonObject, favorites: JsonElement, playlists: List<Playlist>) =
        base.copy(settings = settings, favorites = favorites, playlists = playlists.map { it.toDto() })

    override suspend fun save(config: ConfigResponse) {
        store.save(config)
    }

    override suspend fun patch(body: JsonObject): Response<JsonObject> = error("Sem conta não há perfil no servidor")
}

/**
 * Com conta: o servidor é que manda (as listas vêm da config tal como chegaram) e a cópia em disco é a cache da
 * config. As listas criam-se, mudam e apagam-se no servidor.
 */
open class AccountProfile(private val backend: BackendApi, private val cache: CacheStore) : ProfileRepository {
    override val isAccount = true
    override val syncs = true

    override fun withProfile(base: ConfigResponse, settings: JsonObject, favorites: JsonElement, playlists: List<Playlist>) =
        base.copy(settings = settings, favorites = favorites)

    override suspend fun save(config: ConfigResponse) {
        cache.saveConfig(config)
    }

    override suspend fun patch(body: JsonObject): Response<JsonObject> = backend.updateProfile(body)

    open suspend fun createPlaylist(body: JsonObject): PlaylistDto = backend.createPlaylist(body)

    open suspend fun updatePlaylist(id: String, body: JsonObject): PlaylistDto = backend.updatePlaylist(id, body)

    open suspend fun deletePlaylist(id: String) {
        backend.deletePlaylist(id)
    }
}

/**
 * Modo de teste (só em debug, ver AppContainer.testMode): lê a sessão e a cache da conta, mas não escreve nada nela
 * (nem definições, nem favoritos, nem listas, nem índice, nem guias).
 */
class TestModeProfile(backend: BackendApi, cache: CacheStore) : AccountProfile(backend, cache) {
    override val syncs = false
    override val refusal = REFUSAL

    override suspend fun patch(body: JsonObject): Response<JsonObject> = error(REFUSAL)
    override suspend fun createPlaylist(body: JsonObject): PlaylistDto = error(REFUSAL)
    override suspend fun updatePlaylist(id: String, body: JsonObject): PlaylistDto = error(REFUSAL)
    override suspend fun deletePlaylist(id: String) = error(REFUSAL)

    companion object {
        /** Só se vê em debug: não é traduzido. */
        const val REFUSAL = "Test mode"
    }
}

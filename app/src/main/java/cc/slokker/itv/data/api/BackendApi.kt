package cc.slokker.itv.data.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

const val BACKEND_URL = "https://thegoat.systems/"

/** Nome desta app na conta Slokker (cada TV fica registada como um dispositivo da GoatTV). */
const val BACKEND_APP = "goattv"

/**
 * API da conta Slokker para a GoatTV. A TV tem um login de dispositivo (não o da pessoa), que só serve para
 * esta app: lê listas e definições e grava favoritos e definições. Gerir listas é só no site.
 * O token vai em `Authorization: <token>` (ver AppContainer).
 */
interface BackendApi {
    @POST("api/devices/start")
    suspend fun deviceStart(@Body body: DeviceStartRequest): DeviceStartResponse

    @POST("api/devices/poll")
    suspend fun devicePoll(@Body body: DevicePollRequest): Response<DevicePollResponse>

    /** Renova o token do dispositivo (dura 1 ano). 401/404: a TV foi desligada na conta. */
    @POST("api/collections/devices/auth-refresh")
    suspend fun authRefresh(): AuthRefreshResponse

    @GET("api/goattv/config")
    suspend fun config(): ConfigResponse

    /**
     * Grava no perfil GoatTV. `settings_merge` junta só as chaves enviadas (JSON Merge Patch) e, com
     * `settings_rev`, responde 409 se alguém gravou entretanto; `favorites` substitui os favoritos.
     * 200 e 409 trazem `settings` (completo) e `settings_rev`; o 409 vem no corpo de erro.
     */
    @PATCH("api/goattv/profile")
    suspend fun updateProfile(@Body body: JsonObject): Response<JsonObject>

    // ---- Listas da conta a partir da TV (o site continua a ser o sítio recomendado: escreve-se melhor lá) ----

    /** Cria uma lista na conta: type ("xtream"/"m3u"), name, server, username, password, url, epg_url. */
    @POST("api/goattv/playlists")
    suspend fun createPlaylist(@Body body: JsonObject): PlaylistDto

    @PATCH("api/goattv/playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: String, @Body body: JsonObject): PlaylistDto

    /** Apaga a lista da conta (em todas as TVs), com as associações do guia dela. */
    @DELETE("api/goattv/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): JsonObject

    // ---- Guia da conta (só com conta; dados do IPTV-EPG.org, ver EPG-API.md do backend) ----

    /** Canais de guia com esse nome, agrupados por país (o do utilizador primeiro). */
    @GET("api/goattv/epg/search")
    suspend fun epgSearch(@Query("q") query: String): List<EpgCountryResult>

    /** Até 5 canais de guia prováveis para um canal da lista, com `score` de 0 a 1. */
    @GET("api/goattv/epg/suggest")
    suspend fun epgSuggest(
        @Query("playlist") playlistId: String,
        @Query("channel_key") channelKey: String,
        @Query("name") name: String,
    ): List<EpgGuideChannel>

    /** Os países do guia (`channels` = canais no catálogo; 0 enquanto o país ainda não foi lido). */
    @GET("api/goattv/epg/countries")
    suspend fun epgCountries(): List<EpgCountryInfo>

    @GET("api/goattv/epg/countries/{code}/channels")
    suspend fun epgCountryChannels(@Path("code") country: String): EpgCountryResult

    /** channelKey → canal de guia associado. */
    @GET("api/goattv/epg/mappings")
    suspend fun epgMappings(@Query("playlist") playlistId: String): Map<String, EpgMapping>

    @PUT("api/goattv/epg/mappings")
    suspend fun putEpgMapping(@Body body: EpgMappingRequest): EpgMapping

    @DELETE("api/goattv/epg/mappings")
    suspend fun deleteEpgMapping(@Query("playlist") playlistId: String, @Query("channel_key") channelKey: String): JsonObject

    /**
     * XMLTV só com os canais associados da lista, com os ids trocados pelos channelKeys.
     * Com [etag] (If-None-Match) responde 304 sem corpo quando nada mudou.
     */
    @Streaming
    @GET("api/goattv/epg/guide")
    suspend fun epgGuide(@Query("playlist") playlistId: String, @Header("If-None-Match") etag: String?): Response<ResponseBody>
}

// Todos os campos são anuláveis: o Gson não respeita a nulidade do Kotlin.

data class DeviceStartRequest(val app: String, @SerializedName("device_name") val deviceName: String)

data class DeviceStartResponse(
    val code: String?,
    @SerializedName("device_secret") val deviceSecret: String?,
    @SerializedName("verification_url") val verificationUrl: String?,
    @SerializedName("verification_url_complete") val verificationUrlComplete: String?,
    @SerializedName("expires_in") val expiresIn: Int?,
    val interval: Int?,
)

data class DevicePollRequest(@SerializedName("device_secret") val deviceSecret: String)

data class DevicePollResponse(val status: String?, val token: String?, val device: BackendDevice?, val user: BackendUser?)

/** Esta TV, tal como aparece em thegoat.systems/conta → Dispositivos. */
data class BackendDevice(val id: String?, val name: String?, val app: String?)

data class BackendUser(val id: String?, val email: String?)

data class AuthRefreshResponse(val token: String?, val record: JsonObject?)

data class ConfigResponse(
    val user: BackendUser?,
    val device: BackendDevice?,
    val playlists: List<PlaylistDto>?,
    val favorites: JsonElement?,
    val settings: JsonElement?,
    /** Versão do `settings` (soma 1 a cada gravação, venha de onde vier); 0 se nunca se gravou. */
    @SerializedName("settings_rev") val settingsRev: Long? = null,
)

data class PlaylistDto(
    val id: String?,
    val type: String?,
    val name: String?,
    val server: String?,
    val username: String?,
    val password: String?,
    val url: String?,
    @SerializedName("epg_url") val epgUrl: String?,
)

data class EpgCountryInfo(val code: String?, val name: String?, val channels: Int?, val guide: Boolean?)

/** Um país com canais de guia (pesquisa, ou todos os canais do país). */
data class EpgCountryResult(
    val country: String?,
    @SerializedName("country_name") val countryName: String?,
    val channels: List<EpgGuideChannel>?,
)

/** Um canal de guia. [now] é o programa a dar agora; [country] e [score] só vêm nas sugestões. */
data class EpgGuideChannel(
    val id: String?,
    val name: String?,
    val icon: String?,
    val now: String?,
    val country: String?,
    @SerializedName("country_name") val countryName: String?,
    val score: Double?,
)

data class EpgMapping(
    val country: String?,
    @SerializedName("channel_id") val channelId: String?,
    @SerializedName("channel_name") val channelName: String?,
    @SerializedName("guide_name") val guideName: String?,
    /** Logótipo do canal de guia (PNG, muitas vezes branco sobre transparente); null se não houver. */
    val icon: String? = null,
)

data class EpgMappingRequest(
    val playlist: String,
    @SerializedName("channel_key") val channelKey: String,
    val country: String,
    @SerializedName("channel_id") val channelId: String,
    @SerializedName("channel_name") val channelName: String,
)

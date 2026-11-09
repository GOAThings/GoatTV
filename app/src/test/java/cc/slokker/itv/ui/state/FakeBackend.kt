package cc.slokker.itv.ui.state

import android.content.ContextWrapper
import cc.slokker.itv.data.api.AuthRefreshResponse
import cc.slokker.itv.data.api.BackendApi
import cc.slokker.itv.data.api.ConfigResponse
import cc.slokker.itv.data.api.DevicePollRequest
import cc.slokker.itv.data.api.DevicePollResponse
import cc.slokker.itv.data.api.DeviceStartRequest
import cc.slokker.itv.data.api.DeviceStartResponse
import cc.slokker.itv.data.api.EpgCountryInfo
import cc.slokker.itv.data.api.EpgCountryResult
import cc.slokker.itv.data.api.EpgGuideChannel
import cc.slokker.itv.data.api.EpgMapping
import cc.slokker.itv.data.api.EpgMappingRequest
import cc.slokker.itv.data.api.PlaylistDto
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.File
import java.nio.file.Files

/** O backend da conta, só com o que os testes usam: cada pedido fica em [calls] e a resposta vem dos `on…`. */
class FakeBackend : BackendApi {
    val calls = mutableListOf<String>()
    val profileBodies = mutableListOf<JsonObject>()

    /** A resposta ao PATCH do perfil (por defeito 200 sem corpo). */
    var onUpdateProfile: (JsonObject) -> Response<JsonObject> = { Response.success(JsonObject()) }
    var onEpgMappings: (String) -> Map<String, EpgMapping> = { emptyMap() }
    var onPutEpgMapping: (EpgMappingRequest) -> EpgMapping = { EpgMapping(it.country, it.channelId, it.channelName, null) }
    var onDeleteEpgMapping: (String, String) -> Unit = { _, _ -> }
    var onEpgGuide: (String) -> Response<ResponseBody> = { Response.error(503, "".toResponseBody()) }

    override suspend fun updateProfile(body: JsonObject): Response<JsonObject> {
        calls += "updateProfile"
        profileBodies += body.deepCopy()
        return onUpdateProfile(body)
    }

    override suspend fun epgMappings(playlistId: String): Map<String, EpgMapping> {
        calls += "epgMappings:$playlistId"
        return onEpgMappings(playlistId)
    }

    override suspend fun putEpgMapping(body: EpgMappingRequest): EpgMapping {
        calls += "putEpgMapping:${body.channelKey}"
        return onPutEpgMapping(body)
    }

    override suspend fun deleteEpgMapping(playlistId: String, channelKey: String): JsonObject {
        calls += "deleteEpgMapping:$channelKey"
        onDeleteEpgMapping(playlistId, channelKey)
        return JsonObject()
    }

    override suspend fun deviceStart(body: DeviceStartRequest): DeviceStartResponse = TODO()
    override suspend fun devicePoll(body: DevicePollRequest): Response<DevicePollResponse> = TODO()
    override suspend fun authRefresh(): AuthRefreshResponse = TODO()
    override suspend fun deviceLogout(token: String): Response<Unit> = TODO()
    override suspend fun sendReport(body: JsonObject): Response<JsonObject> = TODO()
    override suspend fun config(etag: String?): Response<ConfigResponse> = TODO()
    override suspend fun putPlaylistIndex(playlistId: String, body: RequestBody, encoding: String): Response<JsonObject> = TODO()
    override suspend fun createPlaylist(body: JsonObject): PlaylistDto = TODO()
    override suspend fun updatePlaylist(id: String, body: JsonObject): PlaylistDto = TODO()
    override suspend fun deletePlaylist(id: String): JsonObject = TODO()
    override suspend fun epgSearch(query: String): List<EpgCountryResult> = TODO()
    override suspend fun epgSuggest(playlistId: String, channelKey: String, name: String): List<EpgGuideChannel> = TODO()
    override suspend fun epgCountries(): List<EpgCountryInfo> = TODO()
    override suspend fun epgCountryChannels(country: String): EpgCountryResult = TODO()
    override suspend fun epgGuide(playlistId: String, etag: String?): Response<ResponseBody> {
        calls += "epgGuide:$playlistId"
        return onEpgGuide(playlistId)
    }

    companion object {
        fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

        /** Uma resposta de erro com corpo JSON (o 409 do perfil traz o `settings` do servidor). */
        fun error(code: Int, body: String): Response<JsonObject> = Response.error(code, body.toResponseBody("application/json".toMediaType()))
    }
}

/** Uma pasta temporária a fazer de `filesDir`, para o LocalStore e a CacheStore escreverem a sério. */
class TempFiles {
    val dir: File = Files.createTempDirectory("goattv-state").toFile()
    val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    fun delete() {
        dir.deleteRecursively()
    }
}

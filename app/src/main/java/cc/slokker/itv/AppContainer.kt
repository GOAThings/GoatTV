package cc.slokker.itv

import android.content.Context
import cc.slokker.itv.data.CacheStore
import cc.slokker.itv.data.EpgRepository
import cc.slokker.itv.data.HomeScreen
import cc.slokker.itv.data.LocalStore
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.PlaylistType
import cc.slokker.itv.data.SessionStore
import cc.slokker.itv.data.Updater
import cc.slokker.itv.data.api.BACKEND_URL
import cc.slokker.itv.data.api.BackendApi
import cc.slokker.itv.data.epg.AccountGuideSource
import cc.slokker.itv.data.epg.XmltvSource
import cc.slokker.itv.data.m3u.M3uSource
import cc.slokker.itv.data.xtream.XtreamSource
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.create
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Alguns servidores IPTV bloqueiam o User-Agent por defeito do OkHttp/ExoPlayer. */
const val IPTV_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

/** Dependências da app (injeção manual). */
class AppContainer(context: Context) {

    val gson = Gson()
    val session = SessionStore(context)
    val home = HomeScreen(context)
    val cache = CacheStore(context, gson)
    val local = LocalStore(context, gson)

    /**
     * Modo de teste (só em debug, `adb shell am start ... --ez test_mode true`):
     * não abre streams nem grava nada na conta, para testar num emulador sem afetar a TV.
     */
    @Volatile
    var testMode = false

    /** Token atual em memória; o interceptor junta-o a cada pedido ao backend. */
    @Volatile
    var token: String? = null

    private val _sessionEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emite quando o backend responde 401 (ex.: "Terminar sessão em todas as TVs" no site). */
    val sessionEnded: SharedFlow<Unit> = _sessionEnded

    /** `goattv/<versão> (<plataforma>)`, por exemplo `goattv/0.8.8 (android-tv)`. */
    private val goatClient =
        "goattv/${BuildConfig.VERSION_NAME} (${if (android.os.Build.MANUFACTURER.equals("Amazon", true)) "fire-tv" else "android-tv"})"

    private val backendHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val current = token
            // Sem "Bearer": o backend espera o token tal como está. O X-Goat-Client diz ao site a app, a versão e o
            // tipo de TV (aparece em Dispositivos).
            val builder = chain.request().newBuilder().header("X-Goat-Client", goatClient)
            if (current != null) builder.header("Authorization", current)
            val response = chain.proceed(builder.build())
            if (response.code == 401 && current != null) _sessionEnded.tryEmit(Unit)
            response
        }
        .build()

    val backend: BackendApi = Retrofit.Builder()
        .baseUrl(BACKEND_URL)
        .client(backendHttp)
        // Com os null: o settings_merge apaga chaves mandando null (ex.: repor a ordem de um grupo), e o Gson
        // por defeito tira-os do pedido (o servidor recebia {} e não apagava nada; visto na SHIELD).
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().serializeNulls().create()))
        .build()
        .create()

    /** User-Agent para os servidores IPTV (o das definições, ou o predefinido). */
    @Volatile
    var userAgent: String = IPTV_USER_AGENT

    /** Cliente para servidores IPTV (listas, EPG, logos). */
    val iptvHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Modo de teste: nenhum pedido aos servidores IPTV (listas, EPG, logos), para não gastar o fornecedor.
            if (testMode) throw IOException("Test mode: no IPTV requests")
            chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
        }
        .build()

    /** Cliente sem nada à mistura (nem token, nem User-Agent IPTV): atualizações da app no GitHub. */
    private val plainHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val updater = Updater(context, plainHttp, gson)

    val xtream = XtreamSource(iptvHttp, gson)
    private val m3u = M3uSource(iptvHttp)
    val epg = EpgRepository(xtream, XmltvSource(iptvHttp), AccountGuideSource(backend), cache)

    suspend fun loadPlaylist(playlist: Playlist): PlaylistContent = when (playlist.type) {
        PlaylistType.XTREAM -> xtream.load(playlist)
        PlaylistType.M3U -> m3u.load(playlist)
    }
}

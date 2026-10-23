package cc.slokker.itv.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import cc.slokker.itv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Um canal para o ecrã inicial, com o programa que está a dar (se se souber). */
class HomeItem(val channel: Channel, val playlistId: String, val nowTitle: String?)

/**
 * Ecrã inicial da Android TV:
 *  - "A seguir" / "Continuar a ver": o último canal visto;
 *  - a linha da GoatTV (onde o launcher a mostra, ex.: Android TV Home da SHIELD): os canais recentes.
 * Cada entrada abre a app nesse canal por um link `itv://play?playlist=…&channel=…` (sem credenciais).
 * Os ids do que se publicou ficam num SharedPreferences, para se atualizar em vez de duplicar.
 */
class HomeScreen(private val context: Context) {

    private val helper by lazy { PreviewChannelHelper(context) }
    private val prefs by lazy { context.getSharedPreferences("home_screen", Context.MODE_PRIVATE) }
    private val mutex = Mutex()

    private val supported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Publica os [items] (o primeiro é o último canal visto). */
    suspend fun update(items: List<HomeItem>) {
        if (!supported || items.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { publishWatchNext(items.first()) }.onFailure { Log.w(TAG, "Watch Next", it) }
                runCatching { publishRow(items.take(MAX_ROW)) }.onFailure { Log.w(TAG, "Linha da app", it) }
            }
        }
    }

    /** Ao terminar sessão: tira tudo do ecrã inicial. */
    suspend fun clear() {
        if (!supported) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    prefs.getLong(KEY_WATCH_NEXT, -1).takeIf { it >= 0 }?.let {
                        context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null)
                    }
                    prefs.getLong(KEY_CHANNEL, -1).takeIf { it >= 0 }?.let { deleteRowPrograms(it) }
                }
                prefs.edit { remove(KEY_WATCH_NEXT) }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    private fun publishWatchNext(item: HomeItem) {
        val program = WatchNextProgram.Builder()
            .setType(TvContractCompat.WatchNextPrograms.TYPE_CHANNEL)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setLive(true)
            .setTitle(item.channel.name)
            .setDescription(item.nowTitle ?: "")
            .setPosterArtUri(posterOf(item.channel))
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setIntentUri(linkOf(item))
            .setInternalProviderId(item.playlistId + ":" + item.channel.key)
            .build()
        val existing = prefs.getLong(KEY_WATCH_NEXT, -1)
        // A pessoa pode tê-lo tirado da fila: nesse caso volta a entrar como novo.
        if (existing >= 0 && helper.getWatchNextProgram(existing) != null) {
            helper.updateWatchNextProgram(program, existing)
        } else {
            val id = helper.publishWatchNextProgram(program)
            prefs.edit { putLong(KEY_WATCH_NEXT, id) }
        }
    }

    private fun publishRow(items: List<HomeItem>) {
        val channelId = rowChannel()
        deleteRowPrograms(channelId)
        items.forEachIndexed { i, item ->
            val program = PreviewProgram.Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setLive(true)
                .setWeight(items.size - i) // o mais recente primeiro
                .setTitle(item.channel.name)
                .setDescription(item.nowTitle ?: "")
                .setPosterArtUri(posterOf(item.channel))
                .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(linkOf(item))
                .setInternalProviderId(item.playlistId + ":" + item.channel.key)
                .build()
            helper.publishPreviewProgram(program)
        }
    }

    /** A linha "GoatTV" (o canal por defeito da app, que o launcher mostra sem pedir). */
    private fun rowChannel(): Long {
        val saved = prefs.getLong(KEY_CHANNEL, -1)
        if (saved >= 0 && helper.getPreviewChannel(saved) != null) return saved
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.brand_mark)
        val channel = PreviewChannel.Builder()
            .setDisplayName(context.getString(R.string.app_name))
            .setAppLinkIntentUri(Uri.parse("$SCHEME://open"))
            .setLogo(logo)
            .build()
        val id = helper.publishDefaultChannel(channel)
        prefs.edit { putLong(KEY_CHANNEL, id) }
        return id
    }

    private fun deleteRowPrograms(channelId: Long) {
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null)
    }

    private fun posterOf(channel: Channel): Uri =
        channel.logo?.let { Uri.parse(it) }
            ?: Uri.parse("android.resource://${context.packageName}/${R.drawable.banner}")

    private fun linkOf(item: HomeItem): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority("play")
        .appendQueryParameter("playlist", item.playlistId)
        .appendQueryParameter("channel", item.channel.key)
        .build()

    companion object {
        const val SCHEME = "itv"
        private const val TAG = "ItvHome"
        private const val KEY_CHANNEL = "channel_id"
        private const val KEY_WATCH_NEXT = "watch_next_id"
        private const val MAX_ROW = 10

        /** (playlistId, channelKey) de um link `itv://play?...`, ou null. */
        fun parseLink(uri: Uri?): Pair<String, String>? {
            if (uri?.scheme != SCHEME || uri.host != "play") return null
            val playlist = uri.getQueryParameter("playlist")?.takeIf { it.isNotBlank() } ?: return null
            val channel = uri.getQueryParameter("channel")?.takeIf { it.isNotBlank() } ?: return null
            return playlist to channel
        }
    }
}

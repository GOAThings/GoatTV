@file:OptIn(UnstableApi::class)

package cc.slokker.itv.ui.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cc.slokker.itv.MainActivity
import cc.slokker.itv.data.Channel
import cc.slokker.itv.playback.RadioRules
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** O que a sessão de media (as teclas de media, "OK Google, pausa", a notificação da rádio) pede ao leitor. */
internal interface SessionControls {
    /** A pausa guarda o sítio (a pausa do direto, o catch-up, uma gravação). Sem ela, a pausa pára o stream. */
    val canPause: Boolean
    fun play()
    fun pause()

    /** Sem pausa: pára o stream (liberta a ligação) e, ao continuar, volta ao direto. */
    fun stop()
    fun resume()

    /** Canal seguinte / anterior (o CH+ e o CH− do comando). */
    fun next()
    fun previous()

    /** Chegou um comando da sessão (pausa, seguinte…): conta como alguém a ver (ver AppViewModel.noteInput). */
    fun onCommand() {}
}

/** O que a sessão mostra: o canal, o programa a dar (o guia) e o logótipo. */
internal data class SessionInfo(val key: String, val title: String, val programme: String?, val logo: String?, val radio: Boolean)

/**
 * O player do leitor visto pela sessão: o mesmo estado (a dar, a carregar, a posição), com o canal e o programa no
 * lugar do endereço, e os comandos da sessão desviados para o leitor. A pausa vai para a pausa do direto (ou pára o
 * stream, sem ela), o seguinte e o anterior mudam de canal. A lista tem três lugares (o anterior, o canal, o
 * seguinte) para o Media3 deixar pedir os dois; o anterior e o seguinte só servem para isso.
 */
internal class SessionPlayer(player: Player, private val controls: () -> SessionControls) : ForwardingSimpleBasePlayer(player) {
    var info: SessionInfo? = null
        set(value) {
            if (field == value) return
            field = value
            invalidateState()
        }

    /**
     * Parado pela sessão (sem a pausa do direto): mostra-se em pausa, à espera de "continuar". Acaba sozinho quando o
     * leitor volta a abrir (o botão do ecrã, outro canal).
     */
    private var stopped = false

    override fun getState(): State {
        val state = super.getState()
        if (stopped && state.playbackState != Player.STATE_IDLE) stopped = false
        val info = info ?: return state
        val commands = state.availableCommands.buildUpon()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            )
            // A lista de três lugares não é uma fila para mostrar.
            .remove(Player.COMMAND_GET_TIMELINE)
            .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .build()
        val metadata = MediaMetadata.Builder()
            .setTitle(info.title)
            .setDisplayTitle(info.title)
            .setArtist(info.programme)
            .setSubtitle(info.programme)
            .setArtworkUri(info.logo?.let(Uri::parse))
            .setIsPlayable(true)
            .setMediaType(if (info.radio) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
            .build()
        val item = MediaItem.Builder().setMediaId(info.key).setMediaMetadata(metadata).build()
        val now = MediaItemData.Builder("now:${info.key}")
            .setMediaItem(item)
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setIsDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .setTracks(state.playlist.getOrNull(state.currentMediaItemIndex)?.tracks ?: Tracks.EMPTY)
            .build()
        val builder = state.buildUpon()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(MediaItemData.Builder("previous").build(), now, MediaItemData.Builder("next").build()))
            .setCurrentMediaItemIndex(1)
        if (stopped) {
            builder.setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(Player.STATE_READY)
                .setPlayerError(null)
                .setIsLoading(false)
        }
        return builder.build()
    }

    /**
     * Ninguém estava a ver e o canal parou (ver AppViewModel.onIdleStop): a sessão fica em pausa, à espera de
     * "continuar" (o botão do ecrã bloqueado volta a abrir o canal).
     */
    fun stopForIdle() {
        stopped = true
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val c = controls()
        c.onCommand()
        when {
            playWhenReady && stopped -> {
                stopped = false
                c.resume()
            }
            playWhenReady -> c.play()
            c.canPause -> c.pause()
            else -> {
                c.stop()
                stopped = true
                invalidateState()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        controls().onCommand()
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controls().next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controls().previous()
            else -> Unit
        }
        return Futures.immediateVoidFuture()
    }

    // A sessão não liberta o player do leitor (é ele que o liberta).
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}

/**
 * A sessão de media do canal a dar, enquanto o leitor existe: as teclas de media e o Assistente ("OK Google, pausa",
 * "seguinte") chegam aqui, e o sistema mostra o canal, o programa e o logótipo. [keepInBackground]: no telemóvel,
 * com uma rádio a dar, a notificação de media (o serviço [RadioService]) mantém o som com a app em segundo plano.
 */
@Composable
internal fun PlayerMediaSession(
    player: Player?,
    info: SessionInfo,
    controls: SessionControls,
    keepInBackground: Boolean,
    /** Regista como parar a sessão quando ninguém está a ver (a notificação da rádio sai logo, mesmo com o ecrã bloqueado). */
    registerIdleStop: ((() -> Unit)?) -> Unit = {},
) {
    val context = LocalContext.current
    val latestControls by rememberUpdatedState(controls)
    val holder = remember { arrayOfNulls<MediaSession>(1) }
    var sessionPlayer by remember { mutableStateOf<SessionPlayer?>(null) }
    DisposableEffect(player) {
        if (player != null) {
            val wrapped = SessionPlayer(player) { latestControls }.also { it.info = info }
            sessionPlayer = wrapped
            val session = holder[0]
            if (session == null) {
                holder[0] = runCatching {
                    MediaSession.Builder(context, wrapped)
                        .setId("goattv-${sessionIds++}")
                        .setSessionActivity(openApp(context))
                        .build()
                }.onFailure { android.util.Log.w("GoatTV", "media session: ${it.message}") }.getOrNull()
            } else {
                session.player = wrapped
            }
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.let {
                RadioService.detach(it)
                it.release()
            }
            holder[0] = null
        }
    }
    SideEffect { sessionPlayer?.info = info }
    DisposableEffect(sessionPlayer) {
        val wrapped = sessionPlayer
        if (wrapped != null) {
            registerIdleStop {
                wrapped.stopForIdle()
                holder[0]?.let { RadioService.detach(it) }
            }
        }
        onDispose { registerIdleStop(null) }
    }
    LaunchedEffect(keepInBackground, holder[0]) {
        val session = holder[0] ?: return@LaunchedEffect
        if (keepInBackground) RadioService.attach(context, session) else RadioService.detach(session)
    }
}

/** É rádio? O que a lista diz até chegarem as faixas; depois, o stream (só som, sem vídeo: ver RadioRules). */
@Composable
internal fun rememberIsRadio(player: Player?, channel: Channel, group: String?): Boolean {
    var tracks by remember(player) { mutableStateOf(player?.currentTracks ?: Tracks.EMPTY) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val groups = tracks.groups
    return RadioRules.isRadio(
        hint = RadioRules.hint(channel.radio == true, channel.name, group),
        tracksKnown = groups.isNotEmpty(),
        hasVideo = groups.any { it.type == C.TRACK_TYPE_VIDEO },
        hasAudio = groups.any { it.type == C.TRACK_TYPE_AUDIO },
    )
}

private var sessionIds = 0

private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
    context, 0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/**
 * A notificação de media da rádio no telemóvel: com ela o Android deixa o som continuar com a app em segundo plano ou
 * o ecrã bloqueado, e tem os botões (pausa, canal anterior e seguinte). A sessão é a do leitor ([PlayerMediaSession]);
 * o vídeo não passa por aqui (pára em segundo plano, como sempre).
 */
class RadioService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        current?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = current

    override fun onDestroy() {
        if (instance === this) instance = null
        // A sessão é do leitor: não se liberta aqui.
        current?.let { runCatching { removeSession(it) } }
        super.onDestroy()
    }

    companion object {
        private var current: MediaSession? = null
        private var instance: RadioService? = null

        /** A rádio está a dar: a sessão passa a ter a notificação (o serviço arranca com a app ainda à vista). */
        internal fun attach(context: Context, session: MediaSession) {
            if (current === session && instance != null) return
            current?.let { old -> instance?.let { runCatching { it.removeSession(old) } } }
            current = session
            val service = instance
            if (service != null) {
                service.addSession(session)
            } else {
                runCatching { context.startService(Intent(context, RadioService::class.java)) }
                    .onFailure { android.util.Log.w("GoatTV", "radio service: ${it.message}") }
            }
        }

        /** Deixou de ser rádio (ou o leitor fechou): sai a notificação, e o serviço com ela. */
        internal fun detach(session: MediaSession) {
            if (current !== session) return
            current = null
            instance?.let {
                runCatching { it.removeSession(session) }
                it.stopSelf()
            }
        }
    }
}

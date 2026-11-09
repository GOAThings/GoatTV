package cc.slokker.itv.ui.player.engine

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import cc.slokker.itv.data.Catchup
import cc.slokker.itv.data.Channel
import cc.slokker.itv.data.Playlist
import cc.slokker.itv.data.PlaylistContent
import cc.slokker.itv.data.kind
import cc.slokker.itv.data.Prefs
import cc.slokker.itv.data.StreamCandidate
import cc.slokker.itv.data.catchupCandidates
import cc.slokker.itv.data.streamCandidates
import cc.slokker.itv.ui.common.ErrorInfo

/**
 * O que muda de propósito entre os players (o resto do [ChannelPlayback] é igual para todos). Onde a TV e o
 * telemóvel faziam diferente, vale o da TV: erro explicado e 3 tentativas a começar pelo primeiro formato, o
 * catch-up que cai continua onde ia, e o formato que funcionou fica guardado.
 */
internal data class PlaybackPolicy(
    /** Tentativas depois de o canal não arrancar (cada uma a começar pelo primeiro formato). */
    val maxStartRetries: Int,
    /** Espera mínima para voltar a ligar (também com "voltar a ligar" desligado, quando o servidor fecha a ligação). */
    val reconnectFloorMs: Long,
    /** Volta a tentar mesmo com "voltar a ligar" desligado (o segundo canal não tem cartão de erro nem OK). */
    val alwaysRetry: Boolean,
    /** Pausar o direto (o anel do [cc.slokker.itv.ui.player.Timeshift]). */
    val timeshift: Boolean,
    /** Canal que o ExoPlayer não descodifica: passa para o VLC (só nesse canal). */
    val vlcFallback: Boolean,
    /** O formato que funcionou depois de outro falhar fica na conta (só vale para a lista principal). */
    val learnFormat: Boolean,
    /** Alimenta a medição da ligação (a página Ligação e o aviso de paragens, só na TV). */
    val diagnostics: Boolean,
    /** Áudio Dolby/DTS em bruto, conforme as definições (o segundo canal é sempre PCM). */
    val passthrough: Boolean,
) {
    companion object {
        val TV = PlaybackPolicy(
            maxStartRetries = 3, reconnectFloorMs = 1_000L, alwaysRetry = false, timeshift = true,
            vlcFallback = true, learnFormat = true, diagnostics = true, passthrough = true,
        )

        /** Como a TV; a medição da ligação não tem onde aparecer no telemóvel. */
        val PHONE = TV.copy(diagnostics = false)

        /** O segundo canal (imagem na imagem): outra lista, sem pausa, sem VLC, PCM, a tentar sempre de 3 em 3 s. */
        val SECOND = PlaybackPolicy(
            maxStartRetries = Int.MAX_VALUE, reconnectFloorMs = 3_000L, alwaysRetry = true, timeshift = false,
            vlcFallback = false, learnFormat = false, diagnostics = false, passthrough = false,
        )
    }
}

/** O canal (ou o programa em catch-up) a abrir, e as formas de o abrir por ordem (fallback ts ⇄ m3u8). */
internal class PlaybackRequest(
    val channel: Channel,
    /** O programa em catch-up; null em direto. */
    val catchup: Catchup?,
    /** Catch-up: o instante real em que o servidor começa o vídeo (ao minuto no Xtream, ao segundo no M3U); 0 em direto. */
    val archiveStartMs: Long,
    val candidates: List<StreamCandidate>,
    /**
     * Stalker: os links caducam; cada tentativa desde o início pede um novo ao portal (no catch-up, o da gravação do
     * programa, que pode começar noutro instante e pedir um salto: ver [FreshLinks]).
     */
    val freshLinks: (suspend () -> FreshLinks)? = null,
) {
    val live: Boolean get() = catchup == null

    companion object {
        /**
         * O pedido para [channel] de [content]: o catch-up (Xtream e M3U com o URL feito aqui, Stalker com o link pedido
         * ao portal), o Stalker em direto (link pedido a cada tentativa) ou as tentativas pela ordem do formato
         * preferido. [correctionMin]: a correção da hora do catch-up (Definições → Guia).
         */
        fun of(
            content: PlaylistContent,
            channel: Channel,
            catchup: Catchup?,
            streamFormat: String?,
            stalker: StalkerLinks?,
            correctionMin: Int = 0,
        ): PlaybackRequest {
            val playlist = content.playlist
            if (catchup != null) {
                if (playlist.type.kind.linkAtPlayTime && stalker != null) {
                    // A gravação começa no início do programa do portal: até a resposta chegar, conta-se do pedido.
                    return PlaybackRequest(channel, catchup, catchup.startMs + catchup.positionMs, emptyList(), freshLinks = { stalker.archive(playlist, channel, catchup) })
                }
                val archive = catchupCandidates(playlist, catchup, content.account?.timezone, correctionMin)
                if (archive != null) return PlaybackRequest(channel, catchup, archive.startMs, archive.candidates)
            }
            if (playlist.type.kind.linkAtPlayTime && stalker != null) {
                return PlaybackRequest(channel, null, 0L, emptyList(), freshLinks = { FreshLinks(stalker.live(playlist, channel)) })
            }
            return PlaybackRequest(channel, null, 0L, streamCandidates(playlist, channel, streamFormat, content.account?.allowedFormats.orEmpty()))
        }
    }
}

/**
 * Os links pedidos ao abrir. No catch-up do Stalker, a gravação é a do programa inteiro: [archiveStartMs] é onde ela
 * começa e [seekMs] o salto até ao ponto pedido. Sem [candidates], o portal não tem gravação (erro de catch-up).
 */
internal class FreshLinks(val candidates: List<StreamCandidate>, val archiveStartMs: Long? = null, val seekMs: Long = 0L)

/** O que o player pede ao portal Stalker (os links caducam: pedem-se ao abrir). */
internal interface StalkerLinks {
    suspend fun live(playlist: Playlist, channel: Channel): List<StreamCandidate>

    /** A gravação de [catchup] no arquivo do portal. */
    suspend fun archive(playlist: Playlist, channel: Channel, catchup: Catchup): FreshLinks
}

/** Não arrancou: o erro (explicado quando se percebe porquê) e se ainda vai tentar outra vez. */
internal data class PlaybackFailure(
    val error: PlaybackException,
    /** null enquanto se analisa (ou sempre, no segundo canal, que não explica). */
    val explained: ErrorInfo? = null,
    val analysing: Boolean = true,
    /** A tentativa que vem a seguir (1…[maxRetries]); 0 = não volta a tentar sozinho (OK tenta). */
    val retry: Int,
    val maxRetries: Int,
    val retrySeconds: Int,
)

/** O que o [ChannelPlayback] pede à app. */
internal interface PlaybackHost {
    /** As definições atuais (lidas a cada tentativa: um User-Agent novo vale já na seguinte). */
    val prefs: Prefs
    val streamsDisabled: Boolean
    fun onFormatWorked(format: String)
    fun exitCatchup()
    fun seekCatchup(positionMs: Long)

    /** O canal passou para o VLC (o ExoPlayer não o descodifica). */
    fun onVlcFallback()

    /** Explica porque não arrancou (pode perguntar ao servidor); null = sem explicação. */
    suspend fun explain(error: PlaybackException, request: PlaybackRequest, format: String?): ErrorInfo?

    /** Quando começou o programa de [channel] que dá às [wallMs] (o anel da pausa guarda só esse). */
    fun programmeStartAt(channel: Channel, wallMs: Long): Long?
}

/** Como abrir uma tentativa (ver [loadStream]). */
internal data class OpenOptions(
    val nearLive: Boolean,
    val userAgent: String,
    /** Pelo anel da pausa do direto. */
    val ring: Boolean,
    val reconnectMs: Long,
    /** Onde começar dentro da stream (a gravação do Stalker no catch-up); 0 = o início. */
    val startMs: Long = 0L,
)

/**
 * O player visto pelo [ChannelPlayback]: o ExoPlayer ou o VLC ([PlayerEngine]) ou, nos testes, um falso.
 * Os eventos dele chegam ao controlador pelos `on…` com o próprio motor (os de um motor antigo ignoram-se).
 */
internal interface StreamEngine {
    /** O player do Media3 (para a imagem, as faixas e as definições); null nos testes. */
    val player: Player?
    val isVlc: Boolean

    /** Pode abrir pelo anel da pausa do direto (só o ExoPlayer). */
    val supportsRing: Boolean
    val idle: Boolean
    val positionMs: Long

    /** Fecha a stream anterior e abre [candidate] (uma ligação de cada vez, ver [StreamGate]). */
    fun open(candidate: StreamCandidate, channel: Channel, options: OpenOptions)

    /** Atrás da janela do direto: volta ao ponto em direto. */
    fun restartAtLive()
    fun stop()

    /**
     * Pára a stream (e larga a ligação) a meio de uma passagem de canais, mas guarda os descodificadores para o
     * canal onde se parar (ver [ChannelPlayback.tune]).
     */
    fun holdForZap()
    fun release()

    /** A leitura do anel acabou: true se continuou sozinho (fim de uma ligação, ou o ponto saiu do anel). */
    fun ringContinues(): Boolean

    /** De meio em meio segundo: o estado da pausa do direto. */
    fun tick(programmeStart: (Long) -> Long?)

    /** De segundo a segundo: a medição da ligação. */
    fun sampleNet()
}

/** O que obriga a criar um player novo (o resto das definições lê-se a cada tentativa). */
internal data class PlayerBuild(
    val engine: String,
    val buffer: String,
    val decoder: String,
    val audioDecoder: String,
    val audioPassthrough: Boolean,
    val hdr: String,
) {
    /** As definições com que se cria o player ([buildPlayer] lê só estas). */
    fun applyTo(prefs: Prefs): Prefs = prefs.copy(
        engine = engine, buffer = buffer, decoder = decoder, audioDecoder = audioDecoder, audioPassthrough = audioPassthrough, hdr = hdr,
    )

    companion object {
        fun of(prefs: Prefs, engine: String, passthrough: Boolean) = PlayerBuild(
            engine, prefs.buffer, prefs.decoder, prefs.audioDecoder, prefs.audioPassthrough && passthrough, prefs.hdr,
        )
    }
}

package cc.slokker.itv.playback

import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * O canal "congelado": a ligação continua (não cai, não fica a carregar, por isso a [PlaybackRules.STALL_MS] não o
 * apanha), mas o que se vê ou ouve parou. As falhas comuns da IPTV:
 *
 * - [VIDEO]: o som continua e a imagem parou (não sai imagem nova, ou o tempo dela não anda);
 * - [AUDIO]: a imagem continua e o som desapareceu (o canal tinha som e deixou de sair);
 * - [UNDECODABLE]: chegam bytes mas nada que se descodifique (só pacotes nulos, PAT/PMT, um segmento estragado);
 * - [PLAYLIST]: o HLS em direto serve sempre a mesma playlist (a numeração não avança; ver [HlsLiveCheck]).
 *
 * Quem usa chama [tick] de segundo a segundo com os contadores do motor (o GoatCore, no Android e na Apple), que só
 * crescem, e [reset] a cada abertura (canal novo, voltar a ligar). Julga-se só pelo que sai (as imagens e o tempo
 * delas, o som, os bytes, as amostras), nunca pelo conteúdo: um canal de imagem fixa, com o tempo a andar, está bem.
 *
 * Nunca dispara: nos primeiros [GRACE_MS] depois de abrir; em pausa, atrás do direto no anel, no catch-up, a esconder
 * uma falha de dados (ErrorConcealment) ou à espera de voltar a ligar (tudo isto é [tick]`(hold = true)`, e as contas
 * recomeçam quando acaba); num canal sem vídeo (rádio). A carregar ([tick]`(buffering = true)`) quem trata é a paragem
 * de sempre, menos nos casos em que o próprio sinal prova que os dados chegam: o som a sair ([VIDEO]: no GoatCore do
 * Android uma imagem que não sai também põe o estado "a carregar"), os bytes a chegar ([UNDECODABLE]) ou a playlist a
 * responder ([PLAYLIST]).
 *
 * Dispara uma vez por abertura (até ao [reset] seguinte). Igual no Android e na Apple; sem tipos aninhados nem Int?.
 */
class FreezeWatch {
    private var primed = false
    private var fired = false
    private var lastTick = -1L

    /** O primeiro segundo sem [tick]`(hold)` desde o [reset] (-1 = ainda não): o tempo de arranque conta daqui. */
    private var playingSince = -1L

    /** O fim da última pausa (ou o arranque): os tempos sem avançar contam no máximo desde aqui. */
    private var activeSince = 0L

    private var videoFrames = 0L
    private var videoPts = 0L
    private var audioFrames = 0L
    private var bytes = 0L
    private var samples = 0L

    // Quando cada um avançou a última vez, e desde quando avança sem parar (falhas até RUN_GAP_MS não contam).
    private var videoAt = 0L
    private var videoRunSince = 0L
    private var audioAt = 0L
    private var audioRunSince = 0L
    private var bytesAt = 0L
    private var samplesAt = 0L

    /** Desde quando chegam bytes sem amostras (-1 = não está a acontecer). */
    private var junkSince = -1L

    // Já se viu avançar desde o [reset]: um canal que nunca teve som não "perdeu" o som.
    private var seenVideo = false
    private var seenAudio = false
    private var seenSamples = false

    /** Abriu-se (outro canal, ou voltou a ligar): tudo de novo, com o tempo de arranque. */
    fun reset() {
        primed = false
        fired = false
        lastTick = -1L
        playingSince = -1L
        junkSince = -1L
        seenVideo = false
        seenAudio = false
        seenSamples = false
    }

    /**
     * Mais um segundo. Os contadores são os totais desde que o motor abriu: [videoFrames] as imagens mostradas (-1 =
     * não se sabe, conta só o [videoPtsMs]), [videoPtsMs] o tempo da última imagem mostrada (-1 = nenhuma),
     * [audioFrames] o som que saiu (blocos, ou ms de som tocado), [bytes] o que chegou da ligação e [samples] as
     * amostras de vídeo e do som escolhido que o demuxer tirou. [hasVideo]/[hasAudio]: o canal tem essas faixas.
     * [playlistStuckMs]: há quanto tempo a playlist do HLS em direto não traz segmentos novos ([HlsLiveCheck.stuckMs];
     * -1 = não é um HLS em direto) e [playlistTargetMs] a duração dos segmentos dela.
     *
     * Devolve [NONE], ou o que congelou ([VIDEO], [AUDIO], [UNDECODABLE], [PLAYLIST]): quem usa volta a ligar.
     */
    fun tick(
        nowMs: Long,
        videoFrames: Long,
        videoPtsMs: Long,
        audioFrames: Long,
        bytes: Long,
        samples: Long,
        hasVideo: Boolean,
        hasAudio: Boolean,
        hold: Boolean,
        buffering: Boolean,
        playlistStuckMs: Long,
        playlistTargetMs: Long,
    ): Int {
        if (fired) return NONE
        // Sem vídeo (rádio) não se julga; em pausa, ou com o relógio aos saltos (a app adormeceu), as contas recomeçam.
        val gap = lastTick >= 0 && (nowMs < lastTick || nowMs - lastTick > MAX_TICK_GAP_MS)
        if (!primed || hold || !hasVideo || gap) {
            rebase(nowMs, videoFrames, videoPtsMs, audioFrames, bytes, samples)
            if (!hold && hasVideo && playingSince < 0) playingSince = nowMs
            return NONE
        }
        lastTick = nowMs
        if (playingSince < 0) playingSince = nowMs

        // A imagem avança: saiu imagem nova e o tempo dela mudou (para trás também conta: o canal recomeçou).
        val framesMoved = if (videoFrames < 0) true else videoFrames != this.videoFrames
        val videoMoved = framesMoved && videoPtsMs >= 0 && videoPtsMs != videoPts
        if (videoMoved) {
            if (nowMs - videoAt > RUN_GAP_MS) videoRunSince = nowMs
            videoAt = nowMs
            seenVideo = true
        }
        if (audioFrames != this.audioFrames) {
            if (nowMs - audioAt > RUN_GAP_MS) audioRunSince = nowMs
            audioAt = nowMs
            seenAudio = true
        }
        val bytesMoved = bytes != this.bytes
        if (bytesMoved) bytesAt = nowMs
        if (samples != this.samples) {
            samplesAt = nowMs
            seenSamples = true
            junkSince = -1L
        } else if (bytesMoved && seenSamples && junkSince < 0) {
            junkSince = nowMs
        }
        this.videoFrames = videoFrames
        this.videoPts = videoPtsMs
        this.audioFrames = audioFrames
        this.bytes = bytes
        this.samples = samples

        if (nowMs - playingSince < GRACE_MS) return NONE
        val kind = judge(nowMs, hasAudio, buffering, playlistStuckMs, playlistTargetMs)
        if (kind != NONE) fired = true
        return kind
    }

    private fun judge(nowMs: Long, hasAudio: Boolean, buffering: Boolean, playlistStuckMs: Long, playlistTargetMs: Long): Int {
        val videoStill = nowMs - videoAt
        val audioStill = nowMs - audioAt
        // A imagem parou com o som a sair sem parar desde antes disso (o som prova que os dados chegam).
        if (seenVideo && hasAudio && videoStill >= VIDEO_FROZEN_MS && audioStill <= RECENT_MS && audioRunSince <= videoAt) return VIDEO
        // O som desapareceu com a imagem a andar sem parar desde antes disso.
        if (!buffering && seenAudio && hasAudio && audioStill >= AUDIO_MISSING_MS && videoStill <= RECENT_MS && videoRunSince <= audioAt) return AUDIO
        // Chegam bytes (há pelo menos JUNK_MS, e ainda agora) e nenhuma amostra.
        if (seenSamples && junkSince >= 0 && nowMs - samplesAt >= NO_SAMPLES_MS && nowMs - junkSince >= JUNK_MS && nowMs - bytesAt <= BYTES_RECENT_MS) {
            return UNDECODABLE
        }
        // A playlist do direto não avança (contado só desde que se está a ver: uma pausa não conta).
        if (playlistStuckMs >= 0 && min(playlistStuckMs, nowMs - activeSince) >= playlistLimitMs(playlistTargetMs)) return PLAYLIST
        return NONE
    }

    private fun rebase(nowMs: Long, videoFrames: Long, videoPtsMs: Long, audioFrames: Long, bytes: Long, samples: Long) {
        primed = true
        lastTick = nowMs
        activeSince = nowMs
        this.videoFrames = videoFrames
        this.videoPts = videoPtsMs
        this.audioFrames = audioFrames
        this.bytes = bytes
        this.samples = samples
        videoAt = nowMs
        videoRunSince = nowMs
        audioAt = nowMs
        audioRunSince = nowMs
        bytesAt = nowMs
        samplesAt = nowMs
        junkSince = -1L
    }

    companion object {
        const val NONE = 0
        const val VIDEO = 1
        const val AUDIO = 2
        const val UNDECODABLE = 3
        const val PLAYLIST = 4

        /** Depois de abrir, tanto tempo a ver antes de julgar (o arranque tem as suas esperas). */
        const val GRACE_MS = 10_000L

        /** A imagem parada com o som a sair. */
        const val VIDEO_FROZEN_MS = 6_000L

        /** Sem som com a imagem a andar. */
        const val AUDIO_MISSING_MS = 8_000L

        /** Sem amostras com bytes a chegar (há pelo menos [JUNK_MS], e há menos de [BYTES_RECENT_MS]). */
        const val NO_SAMPLES_MS = 8_000L
        const val JUNK_MS = 4_000L
        const val BYTES_RECENT_MS = 3_000L

        /** A playlist parada: o maior entre isto e [PLAYLIST_TARGETS] segmentos. */
        const val PLAYLIST_MIN_MS = 20_000L
        const val PLAYLIST_TARGETS = 3

        /** O outro lado "está a andar": avançou há no máximo isto. Falhas assim curtas não partem a sequência. */
        const val RECENT_MS = 2_500L
        const val RUN_GAP_MS = 2_500L

        /** Dois [tick] mais afastados do que isto: a app esteve parada, recomeça-se. */
        const val MAX_TICK_GAP_MS = 5_000L

        /** Quanto tempo a playlist pode ficar sem segmentos novos (segmentos de [targetMs]). */
        fun playlistLimitMs(targetMs: Long): Long = max(PLAYLIST_MIN_MS, PLAYLIST_TARGETS * targetMs)

        /** O nome para os registos (GoatFreeze). */
        fun label(kind: Int): String = when (kind) {
            VIDEO -> "video frozen, audio playing"
            AUDIO -> "audio missing, video playing"
            UNDECODABLE -> "bytes arriving, nothing decodable"
            PLAYLIST -> "HLS live playlist not advancing"
            else -> "none"
        }
    }
}

/**
 * A playlist de um HLS em direto (a do vídeo) a avançar: quem a lê chama [reloaded] a cada leitura. Uma leitura sem
 * segmentos novos é normal (lê-se a meio da duração de um); muitas seguidas, durante [FreezeWatch.playlistLimitMs],
 * querem dizer que o fornecedor serve sempre a mesma (ou recomeçou a numeração, que o leitor espera que continue).
 * Escreve-se da thread do leitor e lê-se de outra.
 */
class HlsLiveCheck {
    @Volatile private var emptySince = -1L
    @Volatile private var live = false

    /** A duração dos segmentos (ms), a da última playlist. */
    @Volatile var targetMs = 6_000L
        private set

    /** A playlist leu-se em [nowMs] com [added] segmentos novos; [live]: sem fim (um VOD não conta). */
    fun reloaded(nowMs: Long, added: Int, targetMs: Long, live: Boolean) {
        this.targetMs = targetMs
        this.live = live
        if (!live || added > 0) {
            emptySince = -1L
        } else if (emptySince < 0) {
            emptySince = nowMs
        }
    }

    /** Há quanto tempo (ms) as leituras não trazem nada novo; -1 = está a avançar, ou não é um direto. */
    fun stuckMs(nowMs: Long): Long {
        val since = emptySince
        return if (!live || since < 0) -1L else max(0L, nowMs - since)
    }
}

/**
 * A [HlsLiveCheck] do leitor HLS do canal principal (o que se vê, a ler diretamente ou pelo gravador da pausa do
 * direto): uma ligação de cada vez, por isso só há uma. Quem a abre põe-na em [current]; ao fechar, tira-a se ainda
 * for a dele ([release]).
 */
object HlsLiveStall {
    @Volatile var current: HlsLiveCheck? = null

    /** O leitor de [check] fechou. */
    fun release(check: HlsLiveCheck) {
        if (current === check) current = null
    }

    /** Ver [HlsLiveCheck.stuckMs] da atual; -1 sem nenhuma. */
    fun stuckMs(nowMs: Long): Long = current?.stuckMs(nowMs) ?: -1L

    /** A duração dos segmentos da atual (ms). */
    fun targetMs(): Long = current?.targetMs ?: 6_000L
}

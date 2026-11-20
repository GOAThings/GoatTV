package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aumentar a imagem no GoatCore (Definições → Imagem → Aumentar a imagem, Prefs.Upscale), igual no Android e na Apple:
 * o que fazer com cada vídeo e quando desistir.
 *
 * - [SHARP] ("Nítido"): um aumento que segue as arestas e um realce que se adapta ao contraste, ao jeito do AMD FSR 1
 *   (sem rede neural; leve, serve nas GPUs fracas).
 * - [AI] ("IA"): uma rede neural pequena que faz o Y 2× maior (a cor aumenta-se como sempre); precisa de uma GPU capaz,
 *   e sem ela, ou se não aguentar, volta ao [SHARP].
 * - [OFF]: a imagem vai como vem (o ecrã ou a box aumentam-na).
 *
 * Só no vídeo de 8 bits SDR mais pequeno do que o ecrã (pelo menos [MIN_SCALE] vezes), e só na imagem principal. Na
 * Apple, os dois modos são o MetalFX (o aumento espacial da Apple).
 */
object Upscaling {
    const val OFF = 0
    const val SHARP = 1
    const val AI = 2

    /** Nunca se faz uma imagem maior do que o 4K. */
    const val MAX_WIDTH = 3840
    const val MAX_HEIGHT = 2160

    /** Abaixo disto o ganho não se vê: fica como vem. */
    const val MIN_SCALE = 1.2f

    /** A rede faz sempre 2×: com menos de isto de diferença não compensa (o mesmo limite do shader original). */
    const val AI_MIN_SCALE = 1.3f

    /** A definição [setting] (Prefs.Upscale) como modo. */
    fun requested(setting: String): Int = when (setting) {
        Prefs.Upscale.SHARP -> SHARP
        Prefs.Upscale.AI -> AI
        else -> OFF
    }

    /** O nome do modo para o registo ("off", "sharp", "ai": os valores da definição). */
    fun name(mode: Int): String = when (mode) {
        SHARP -> Prefs.Upscale.SHARP
        AI -> Prefs.Upscale.AI
        else -> Prefs.Upscale.OFF
    }

    /** Quanto maior é o sítio onde se mostra ([screenWidth]×[screenHeight]) do que o vídeo, no lado que cresce menos. */
    fun scale(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Float =
        if (width <= 0 || height <= 0) 0f else min(screenWidth.toFloat() / width, screenHeight.toFloat() / height)

    /**
     * O modo para o vídeo [width]×[height] mostrado em [screenWidth]×[screenHeight]: o que a definição [setting] pede,
     * só no 8 bits SDR e com o ecrã pelo menos [MIN_SCALE] vezes maior. A rede ([AI]) só com a GPU capaz ([aiCapable])
     * e o ecrã [AI_MIN_SCALE] vezes maior; senão [SHARP]. Nunca acima de [ceiling] (o que esta resolução já mostrou
     * aguentar, ver [UpscaleMemory]).
     */
    fun mode(
        setting: String, width: Int, height: Int, screenWidth: Int, screenHeight: Int,
        bitDepth: Int = 8, hdr: Boolean = false, aiCapable: Boolean = true, ceiling: Int = AI,
    ): Int {
        var mode = requested(setting)
        if (mode == OFF || bitDepth > 8 || hdr) return OFF
        val scale = scale(width, height, screenWidth, screenHeight)
        if (scale < MIN_SCALE) return OFF
        if (mode == AI && (!aiCapable || scale < AI_MIN_SCALE)) mode = SHARP
        return min(mode, ceiling.coerceIn(OFF, AI))
    }

    /**
     * O tamanho da imagem aumentada: o vídeo [width]×[height] esticado até caber em [screenWidth]×[screenHeight] (com a
     * proporção do vídeo), nunca maior do que [MAX_WIDTH]×[MAX_HEIGHT] e em números pares (o 4:2:0). [0, 0] sem vídeo.
     */
    fun target(width: Int, height: Int, screenWidth: Int, screenHeight: Int): IntArray {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) return intArrayOf(0, 0)
        val s = min(min(screenWidth, MAX_WIDTH).toFloat() / width, min(screenHeight, MAX_HEIGHT).toFloat() / height)
        fun even(v: Float) = (v / 2f).roundToInt() * 2
        return intArrayOf(even(width * s).coerceIn(2, MAX_WIDTH), even(height * s).coerceIn(2, MAX_HEIGHT))
    }
}

/**
 * O que cada resolução de vídeo já mostrou aguentar nesta sessão (em memória, até a app fechar): depois de o [AI] não
 * acompanhar num 720p, os canais 720p seguintes começam no [Upscaling.SHARP]; depois de o nítido não acompanhar, vão
 * como vêm. Não é seguro entre threads (quem usa sincroniza).
 */
class UpscaleMemory {
    private val ceilings = HashMap<Long, Int>()

    private fun key(width: Int, height: Int): Long = width.toLong() shl 32 or (height.toLong() and 0xFFFFFFFFL)

    /** O máximo para [width]×[height]; [Upscaling.AI] se ainda não se sabe nada. */
    fun ceiling(width: Int, height: Int): Int = ceilings[key(width, height)] ?: Upscaling.AI

    /** [width]×[height] não aguentou mais do que [mode]. */
    fun lower(width: Int, height: Int, mode: Int) {
        val k = key(width, height)
        ceilings[k] = min(ceilings[k] ?: Upscaling.AI, mode.coerceIn(Upscaling.OFF, Upscaling.AI))
    }

    fun clear() = ceilings.clear()
}

/**
 * Mede se o aumento acompanha o vídeo e desce de modo quando não: [Upscaling.AI] → [Upscaling.SHARP] →
 * [Upscaling.OFF]. Cada imagem diz quanto tempo levou ([busyUs]) e quanto tinha ([intervalUs], o tempo entre imagens):
 * atrasada é a que levou mais de [BUDGET] do tempo que tinha. Em cada janela de [WINDOW_MS] conta-se; com mais de
 * [LATE_RATIO] das imagens atrasadas (e pelo menos [MIN_LATE]) a janela é má, e [BAD_WINDOWS] más seguidas (uns dois
 * segundos) descem um modo. O primeiro [WARMUP_MS] depois de começar ou de mudar de modo não conta (os shaders a
 * compilar, as primeiras imagens).
 */
class UpscaleGovernor(mode: Int) {
    /** O modo em que se está. */
    var mode: Int = mode.coerceIn(Upscaling.OFF, Upscaling.AI)
        private set

    private var startMs = Long.MIN_VALUE
    private var windowStartMs = Long.MIN_VALUE
    private var frames = 0
    private var late = 0
    private var badRun = 0

    /** A última janela (para o registo): imagens e atrasadas. */
    var lastFrames = 0
        private set
    var lastLate = 0
        private set

    /**
     * Uma imagem às [nowMs] que levou [busyUs] com [intervalUs] para isso. Devolve true quando o modo acabou de descer
     * (o novo está em [mode]).
     */
    fun frame(nowMs: Long, busyUs: Long, intervalUs: Long): Boolean {
        if (mode == Upscaling.OFF) return false
        if (startMs == Long.MIN_VALUE) {
            startMs = nowMs
            windowStartMs = nowMs + WARMUP_MS
        }
        if (nowMs < windowStartMs) return false
        frames++
        if (intervalUs > 0 && busyUs > intervalUs * BUDGET) late++
        if (nowMs - windowStartMs < WINDOW_MS) return false
        lastFrames = frames
        lastLate = late
        val bad = late >= MIN_LATE && late > frames * LATE_RATIO
        badRun = if (bad) badRun + 1 else 0
        frames = 0
        late = 0
        windowStartMs = nowMs
        if (badRun < BAD_WINDOWS) return false
        down()
        return true
    }

    /** Desce um modo já (falta à GPU o que este precisa, ou falhou). */
    fun down() {
        mode = (mode - 1).coerceAtLeast(Upscaling.OFF)
        startMs = Long.MIN_VALUE
        badRun = 0
        frames = 0
        late = 0
    }

    companion object {
        const val WARMUP_MS = 1_000L
        const val WINDOW_MS = 1_000L
        const val BAD_WINDOWS = 2
        const val LATE_RATIO = 0.1f
        const val MIN_LATE = 2
        /** A parte do intervalo entre imagens que o aumento pode gastar. */
        const val BUDGET = 0.9f
    }
}

/**
 * O [Upscaling], a [UpscaleMemory] e o [UpscaleGovernor] para o Swift (ponte plana, sem Int?): uma por sessão de vídeo,
 * com a memória partilhada pela app ([memory]). Os modos são os de [Upscaling] (0, 1, 2).
 */
class AppleUpscale(setting: String, private val memory: AppleUpscaleMemory) {
    private val wanted = setting
    private var governor: UpscaleGovernor? = null
    private var width = 0
    private var height = 0

    /** O modo para este vídeo e este ecrã (começa a medir de novo quando o vídeo muda de tamanho). */
    fun start(width: Int, height: Int, screenWidth: Int, screenHeight: Int, bitDepth: Int, hdr: Boolean, supported: Boolean): Int {
        val mode = if (!supported) Upscaling.OFF
        else Upscaling.mode(wanted, width, height, screenWidth, screenHeight, bitDepth, hdr, ceiling = memory.memory.ceiling(width, height))
        this.width = width
        this.height = height
        governor = UpscaleGovernor(mode)
        return mode
    }

    /** O modo em que se está agora (0 sem vídeo). */
    val mode: Int get() = governor?.mode ?: Upscaling.OFF

    /** Uma imagem que levou [busyUs] com [intervalUs]: true quando o modo desceu (fica guardado para esta resolução). */
    fun frame(nowMs: Long, busyUs: Long, intervalUs: Long): Boolean {
        val g = governor ?: return false
        if (!g.frame(nowMs, busyUs, intervalUs)) return false
        memory.memory.lower(width, height, g.mode)
        return true
    }

    /** Falhou (a GPU, o MetalFX): desce já, e fica guardado. */
    fun fail(): Int {
        val g = governor ?: return Upscaling.OFF
        g.down()
        memory.memory.lower(width, height, g.mode)
        return g.mode
    }

    val lastFrames: Int get() = governor?.lastFrames ?: 0
    val lastLate: Int get() = governor?.lastLate ?: 0

    /** O tamanho da imagem aumentada (ver [Upscaling.target]): a largura e a altura. */
    fun targetWidth(screenWidth: Int, screenHeight: Int): Int = Upscaling.target(width, height, screenWidth, screenHeight)[0]
    fun targetHeight(screenWidth: Int, screenHeight: Int): Int = Upscaling.target(width, height, screenWidth, screenHeight)[1]
}

/** A memória da app inteira (ver [UpscaleMemory]) para o Swift: uma só, guardada por quem usa. */
class AppleUpscaleMemory {
    internal val memory = UpscaleMemory()
}

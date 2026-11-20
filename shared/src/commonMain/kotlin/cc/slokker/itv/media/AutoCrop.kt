package cc.slokker.itv.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Tirar as barras pretas (Definições → Cortar barras pretas, Prefs.autoCrop), igual no Android e na Apple: os
// programas 4:3 dentro do 16:9, os filmes com barras em cima e em baixo, e as duas coisas juntas (a "janela"). O
// leitor só aumenta a imagem o que for preciso para a parte com imagem encher o ecrã, e só no modo Ajustar.
//
// Primeiro o que o canal diz (o AFD, ver [Afd]); sem ele, o que se vê na imagem ([BlackBars], uma grelha pequena do
// brilho, lida mais ou menos uma vez por segundo). Quem decide quando mudar é o [AutoCrop]: só depois de as barras
// estarem iguais vários segundos, nunca numa imagem escura, e sem cortar legendas ou logótipos que estejam nas barras.

/** Um rectângulo em frações da imagem (0..1), a contar de cima e da esquerda. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** A imagem toda (nada a cortar). */
    val isFull: Boolean get() = left <= EDGE && top <= EDGE && right >= 1f - EDGE && bottom >= 1f - EDGE

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)

        /** Menos do que isto (0,2 %) não conta como corte. */
        const val EDGE = 0.002f
    }
}

/**
 * O AFD (Active Format Description, ETSI TS 101 154 anexo B / ATSC A/53): o canal diz onde está a imagem dentro do
 * quadro. Vem nos dados do utilizador com o identificador "DTG1": no H.264 e no HEVC numa SEI
 * user_data_registered_itu_t_t35 (0xB5, 0x0031, "DTG1"), no MPEG-2 num user_data (0x000001B2, "DTG1"). Depois do
 * identificador: um byte com o active_format_flag (0x40) e, com ele, outro com o active_format nos 4 bits de baixo.
 */
object Afd {
    const val NONE = -1

    /** Só se procura no início da imagem: as SEI e os user_data vêm antes das fatias. */
    private const val SCAN_BYTES = 8192

    /**
     * O active_format desta imagem (Annex B, ou o MPEG-2 como vem no PES), ou [NONE]. Os bytes do "DTG1" e dos dois
     * a seguir nunca levam a prevenção de emulação (não há dois zeros seguidos), por isso lê-se direto, sem abrir as NAL.
     */
    fun code(data: ByteArray): Int {
        fun u(i: Int) = data[i].toInt() and 0xFF
        val end = min(data.size, SCAN_BYTES) - 6
        var i = 3
        while (i < end) {
            if (data[i].toInt() == 'D'.code && data[i + 1].toInt() == 'T'.code && data[i + 2].toInt() == 'G'.code && data[i + 3].toInt() == '1'.code) {
                val t35 = u(i - 3) == 0xB5 && u(i - 2) == 0x00 && u(i - 1) == 0x31
                val userData = i >= 4 && u(i - 4) == 0 && u(i - 3) == 0 && u(i - 2) == 1 && u(i - 1) == 0xB2
                if (t35 || userData) return if (u(i + 4) and 0x40 == 0) NONE else u(i + 5) and 0x0F
            }
            i++
        }
        return NONE
    }

    /**
     * Onde está a imagem para o [code] num quadro com a proporção [frameAspect] (largura ÷ altura, já com a do píxel),
     * ou null quando o AFD não diz que há barras (o quadro inteiro, "maior do que 16:9" sem medida, um código
     * desconhecido): aí decide a imagem. Os códigos 13 a 15 ("shoot and protect") são uma imagem 4:3 ou 16:9 inteira.
     */
    fun activeArea(code: Int, frameAspect: Float): CropRect? {
        if (frameAspect <= 0f) return null
        val (ratio, top) = when (code) {
            2 -> 16f / 9f to true
            3 -> 14f / 9f to true
            9, 13 -> 4f / 3f to false
            10, 14, 15 -> 16f / 9f to false
            11 -> 14f / 9f to false
            else -> return null
        }
        return box(ratio, frameAspect, top).takeUnless { it.isFull }
    }

    /** Uma imagem com a proporção [ratio] ao centro (ou em cima, [top]) de um quadro com a proporção [frameAspect]. */
    internal fun box(ratio: Float, frameAspect: Float, top: Boolean = false): CropRect {
        if (abs(ratio / frameAspect - 1f) < 0.02f) return CropRect.FULL
        return if (ratio > frameAspect) {
            val h = frameAspect / ratio
            val t = if (top) 0f else (1f - h) / 2f
            CropRect(0f, t, 1f, t + h)
        } else {
            val w = ratio / frameAspect
            CropRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        }
    }
}

/**
 * O que [BlackBars.measure] viu numa imagem: a fração da altura de cada barra em cima e em baixo ([letterbox]) e da
 * largura de cada uma dos lados ([pillarbox]), 0 sem barras; -1 quando nesse sentido não se pode decidir (as duas
 * barras diferentes, com uma legenda ou um logótipo numa delas, ou a orla da imagem escura). [rows] e [cols]: o
 * tamanho da grelha (a precisão da medida).
 */
class BarMeasure(val letterbox: Float, val pillarbox: Float, val rows: Int, val cols: Int)

/**
 * As barras pretas numa grelha pequena do brilho (o Y de 0 a 255, linha a linha, de cima para baixo; [black] é o
 * valor do preto: 16 no Y da gama limitada, 0 no RGB do Android). Uma linha (ou coluna) é barra com quase tudo preto
 * ([BAR_FILL]: um logótipo pequeno não a estraga). As barras de cada lado têm de ser iguais (as que se cortam são
 * sempre as duas): uma legenda ou um logótipo grande numa só faz esse sentido ficar sem decisão, em vez de se cortar.
 */
object BlackBars {
    /** Até isto acima do preto conta como preto (a compressão nunca dá o preto certinho). */
    const val DARK = 16

    /** Isto acima do preto já é imagem a sério (uma cena escura não tem quase nada assim). */
    const val BRIGHT = 48

    /** A fração de pontos pretos que faz uma linha ser barra. */
    const val BAR_FILL = 0.9f

    /** A linha logo a seguir à barra tem de ter imagem: pelo menos esta fração fora do preto. */
    const val EDGE_FILL = 0.25f

    /** Sem esta fração de pontos com imagem, a imagem é escura demais para decidir. */
    const val MIN_BRIGHT = 0.02f

    /** Barras mais finas do que isto não se cortam (a orla do SD analógico); mais largas do que [MAX_BAR], não é barra. */
    const val MIN_BAR = 0.02f
    const val MAX_BAR = 0.25f

    /** As barras de [luma] ([cols]×[rows]), ou null numa imagem escura demais (ou toda preta) para decidir. */
    fun measure(luma: ByteArray, cols: Int, rows: Int, black: Int = 0): BarMeasure? {
        if (cols <= 0 || rows <= 0 || luma.size < cols * rows) return null
        val dark = black + DARK
        val bright = black + BRIGHT
        val rowDark = IntArray(rows)
        var lit = 0
        for (r in 0 until rows) {
            var d = 0
            for (c in 0 until cols) {
                val v = luma[r * cols + c].toInt() and 0xFF
                if (v <= dark) d++
                if (v >= bright) lit++
            }
            rowDark[r] = d
        }
        if (lit < MIN_BRIGHT * cols * rows) return null
        val rowLimit = BAR_FILL * cols
        var top = 0
        while (top < rows && rowDark[top] >= rowLimit) top++
        if (top == rows) return null
        var bottom = 0
        while (bottom < rows && rowDark[rows - 1 - bottom] >= rowLimit) bottom++
        val letterbox = side(top, bottom, rows, cols - rowDark[top], cols - rowDark[rows - 1 - bottom], cols)

        // As colunas só dentro da imagem (sem as barras de cima e de baixo, que são pretas em todas).
        val from = top
        val to = rows - bottom
        val span = to - from
        val colDark = IntArray(cols)
        for (r in from until to) for (c in 0 until cols) if ((luma[r * cols + c].toInt() and 0xFF) <= dark) colDark[c]++
        val colLimit = BAR_FILL * span
        var left = 0
        while (left < cols && colDark[left] >= colLimit) left++
        var right = 0
        while (right < cols && colDark[cols - 1 - right] >= colLimit) right++
        val pillarbox = if (left == cols) -1f else side(left, right, cols, span - colDark[left], span - colDark[cols - 1 - right], span)
        return BarMeasure(letterbox, pillarbox, rows, cols)
    }

    /**
     * Uma barra de cada lado com [a] e [b] linhas (de [n]); [edgeA] e [edgeB]: os pontos com imagem na linha logo a
     * seguir a cada uma (de [width]). A fronteira fica entre a última linha preta e a primeira com imagem.
     */
    private fun side(a: Int, b: Int, n: Int, edgeA: Int, edgeB: Int, width: Int): Float {
        if (a == 0 && b == 0) return 0f
        if (abs(a - b) > max(1, n / 50)) return -1f
        if (edgeA < EDGE_FILL * width || edgeB < EDGE_FILL * width) return -1f
        val bar = min(a, b).toFloat() / n
        return when {
            bar < MIN_BAR -> 0f
            bar > MAX_BAR -> -1f
            else -> bar
        }
    }
}

/**
 * Quando mudar o corte: um por canal (novo, ou [reset], ao mudar de canal). [afd] e [bars] recebem o que se vai vendo
 * com a hora [nowMs] (o mesmo relógio nos dois) e dizem se a parte com imagem ([active]) mudou.
 *
 * - O AFD, quando diz que há barras, manda (depois de [AFD_STABLE_MS] igual; sem ele [AFD_GONE_MS], volta a imagem).
 * - Pela imagem, cada sentido à parte: um corte maior só depois de [STABLE_MS] a ver o mesmo, um mais pequeno (aparece
 *   imagem onde eram as barras) depois de [REVEAL_MS]. As imagens sem decisão (escuras, ou com uma legenda numa barra)
 *   não contam nem para um lado nem para o outro.
 * - A medida acerta numa das proporções habituais ([RATIOS]) se estiver a menos de um ponto da grelha dela.
 */
class AutoCrop {
    /** A parte do quadro com imagem (a imagem toda quando não há barras). */
    var active: CropRect = CropRect.FULL
        private set

    /** A proporção do quadro (largura ÷ altura, já com a do píxel). */
    var frameAspect: Float = 16f / 9f
        private set

    private var afdCode = Afd.NONE
    private var afdPending = Afd.NONE
    private var afdSince = Long.MIN_VALUE

    private val vertical = Axis()
    private val horizontal = Axis()

    private class Axis {
        var applied = 0f
        var candidate = 0f
        var since = Long.MIN_VALUE

        /** Um valor medido; true se o que vale mudou. */
        fun see(value: Float, tolerance: Float, nowMs: Long): Boolean {
            if (since == Long.MIN_VALUE || abs(value - candidate) > tolerance) {
                candidate = value
                since = nowMs
            }
            if (abs(candidate - applied) <= tolerance) return false
            val need = if (candidate < applied) REVEAL_MS else STABLE_MS
            if (nowMs - since < need) return false
            applied = candidate
            return true
        }

        fun reset() {
            applied = 0f
            candidate = 0f
            since = Long.MIN_VALUE
        }
    }

    /** O quadro tem [width]×[height] píxeis de proporção [pixelRatio]; true se [active] mudou. */
    fun frame(width: Int, height: Int, pixelRatio: Float = 1f): Boolean {
        if (width <= 0 || height <= 0) return false
        val aspect = width * (if (pixelRatio > 0f) pixelRatio else 1f) / height
        if (abs(aspect - frameAspect) < 0.001f) return false
        frameAspect = aspect
        return update()
    }

    /** O AFD da imagem desta hora ([Afd.NONE] sem ele); true se [active] mudou. */
    fun afd(code: Int, nowMs: Long): Boolean {
        if (code != afdPending || afdSince == Long.MIN_VALUE) {
            afdPending = code
            afdSince = nowMs
        }
        if (afdPending == afdCode) return false
        if (nowMs - afdSince < if (afdPending == Afd.NONE) AFD_GONE_MS else AFD_STABLE_MS) return false
        afdCode = afdPending
        return update()
    }

    /** A medida de [BlackBars.measure] desta hora (null: imagem escura); true se [active] mudou. */
    fun bars(m: BarMeasure?, nowMs: Long): Boolean {
        if (m == null) return false
        var changed = false
        if (m.letterbox >= 0f) changed = vertical.see(m.letterbox, 1.5f / m.rows, nowMs)
        if (m.pillarbox >= 0f) changed = horizontal.see(m.pillarbox, 1.5f / m.cols, nowMs) || changed
        if (changed) tolerance = max(1f / m.rows, 1f / m.cols)
        return changed && update()
    }

    /** O tamanho de um ponto da grelha da última medida (até onde se pode acertar numa proporção). */
    private var tolerance = 0f

    /** Outro canal: tudo de novo. */
    fun reset() {
        active = CropRect.FULL
        afdCode = Afd.NONE
        afdPending = Afd.NONE
        afdSince = Long.MIN_VALUE
        vertical.reset()
        horizontal.reset()
    }

    private fun update(): Boolean {
        val next = Afd.activeArea(afdCode, frameAspect) ?: fromBars()
        if (next == active) return false
        active = next
        return true
    }

    private fun fromBars(): CropRect {
        val lb = vertical.applied
        val pb = horizontal.applied
        if (lb <= 0f && pb <= 0f) return CropRect.FULL
        val rect = CropRect(pb, lb, 1f - pb, 1f - lb)
        // Só num sentido: acerta-se na proporção habitual mais perto, se ficar dentro da precisão da grelha.
        if (lb > 0f && pb > 0f) return rect
        val ratio = frameAspect * rect.width / rect.height
        val nearest = RATIOS.minByOrNull { abs(ratio / it - 1f) } ?: return rect
        if (abs(ratio / nearest - 1f) > SNAP) return rect
        val snapped = Afd.box(nearest, frameAspect)
        if (snapped.isFull) return rect
        val moved = if (lb > 0f) abs(snapped.top - lb) else abs(snapped.left - pb)
        return if (moved <= tolerance) snapped else rect
    }

    companion object {
        const val STABLE_MS = 5_000L
        const val REVEAL_MS = 1_000L
        const val AFD_STABLE_MS = 1_000L
        const val AFD_GONE_MS = 3_000L

        /** De quanto em quanto tempo vale a pena medir a imagem. */
        const val PROBE_MS = 1_000L

        /** O tamanho da grelha do brilho (9216 pontos: ~1 % da altura e 0,8 % da largura por ponto). */
        const val GRID_COLS = 128
        const val GRID_ROWS = 72

        /** As proporções habituais das imagens (4:3, 14:9, 16:9, 1,85, 2,00, 2,20, 2,35, 2,39) e quanto se acerta nelas. */
        val RATIOS = listOf(4f / 3f, 14f / 9f, 16f / 9f, 1.85f, 2f, 2.2f, 2.35f, 2.39f)
        const val SNAP = 0.03f

        /**
         * O que mostrar do quadro (proporção [frameAspect]) num ecrã com a proporção [screenAspect], para a parte com
         * imagem [active] encher o ecrã sem nada dela ficar de fora: o mínimo à volta dela com a proporção do ecrã,
         * dentro do quadro. Um 16:9 com barras de cinema num ecrã 16:9 fica como está (não há nada a ganhar); um 4:3
         * com um 16:9 dentro enche o ecrã.
         */
        fun window(active: CropRect, frameAspect: Float, screenAspect: Float): CropRect {
            if (active.isFull || frameAspect <= 0f || screenAspect <= 0f) return CropRect.FULL
            val w = min(1f, max(active.width, active.height * screenAspect / frameAspect))
            val h = min(1f, max(active.height, active.width * frameAspect / screenAspect))
            val l = ((active.left + active.right) / 2f - w / 2f).coerceIn(0f, 1f - w)
            val t = ((active.top + active.bottom) / 2f - h / 2f).coerceIn(0f, 1f - h)
            val r = CropRect(l, t, l + w, t + h)
            return if (r.isFull) CropRect.FULL else r
        }
    }
}

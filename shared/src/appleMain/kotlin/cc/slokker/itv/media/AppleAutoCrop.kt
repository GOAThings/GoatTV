package cc.slokker.itv.media

import platform.Foundation.NSData

/**
 * O [AutoCrop] para o Swift, com uma API plana: a grelha do brilho chega num NSData de uma só vez
 * ([AutoCrop.GRID_COLS]×[AutoCrop.GRID_ROWS] bytes, de cima para baixo), e a parte com imagem lê-se em quatro números.
 * Um por canal (ver GoatCore/AutoCrop.swift).
 */
class AppleAutoCrop {
    private val crop = AutoCrop()

    /** O quadro tem [width]×[height] (já com a proporção do píxel); true se a parte com imagem mudou. */
    fun frame(width: Int, height: Int): Boolean = crop.frame(width, height, 1f)

    /** O AFD da imagem desta hora ([Afd.NONE] = -1 sem ele); true se a parte com imagem mudou. */
    fun afd(code: Int, nowMs: Long): Boolean = crop.afd(code, nowMs)

    /** A grelha do brilho [grid] ([black]: o valor do preto, 16 na gama limitada); true se a parte com imagem mudou. */
    fun luma(grid: NSData, black: Int, nowMs: Long): Boolean =
        crop.bars(BlackBars.measure(AppleMedia.bytes(grid), AutoCrop.GRID_COLS, AutoCrop.GRID_ROWS, black), nowMs)

    val left: Float get() = crop.active.left
    val top: Float get() = crop.active.top
    val right: Float get() = crop.active.right
    val bottom: Float get() = crop.active.bottom

    val gridCols: Int get() = AutoCrop.GRID_COLS
    val gridRows: Int get() = AutoCrop.GRID_ROWS
    val probeMs: Long get() = AutoCrop.PROBE_MS

    /** O que mostrar do quadro para a parte com imagem ([left]…[bottom]) encher um ecrã com a proporção [screenAspect]. */
    fun window(left: Float, top: Float, right: Float, bottom: Float, frameAspect: Float, screenAspect: Float): CropRect =
        AutoCrop.window(CropRect(left, top, right, bottom), frameAspect, screenAspect)
}

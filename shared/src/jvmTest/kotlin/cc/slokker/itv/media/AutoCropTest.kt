package cc.slokker.itv.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tirar as barras pretas: o AFD, a medida das barras na grelha do brilho, quando mudar, e o que mostrar no ecrã. */
class AutoCropTest {
    private val cols = AutoCrop.GRID_COLS
    private val rows = AutoCrop.GRID_ROWS

    /** Uma grelha com imagem (um degradé claro) dentro de [l],[t],[r],[b] (pontos da grelha) e preto ([black]) fora. */
    private fun grid(l: Int = 0, t: Int = 0, r: Int = cols, b: Int = rows, black: Int = 0, inside: (Int, Int) -> Int = { x, y -> 80 + (x + y) % 120 }): ByteArray {
        val g = ByteArray(cols * rows) { black.toByte() }
        for (y in t until b) for (x in l until r) g[y * cols + x] = (black + inside(x, y)).coerceAtMost(255).toByte()
        return g
    }

    private fun near(expected: Float, actual: Float, eps: Float = 0.005f) =
        assertTrue(kotlin.math.abs(expected - actual) <= eps, "esperava $expected, deu $actual")

    @Test
    fun `afd is read from the H264 SEI and the MPEG-2 user data`() {
        // SEI (NAL 6), user_data_registered (4), tamanho, 0xB5 0x0031 "DTG1", flag, 1111 + 1001 (4:3 ao centro).
        val sei = byteArrayOf(0, 0, 0, 1, 0x06, 0x04, 0x09, 0xB5.toByte(), 0x00, 0x31, 'D'.code.toByte(), 'T'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte(), 0x41, 0xF9.toByte(), 0x80.toByte(), 0, 0, 0, 1, 0x65, 1, 2, 3)
        assertEquals(9, Afd.code(sei))
        val mpeg2 = byteArrayOf(0, 0, 1, 0x00, 0x11, 0x22, 0, 0, 1, 0xB2.toByte(), 'D'.code.toByte(), 'T'.code.toByte(), 'G'.code.toByte(), '1'.code.toByte(), 0x41, 0xFA.toByte(), 0, 0, 1, 0x01, 9, 9)
        assertEquals(10, Afd.code(mpeg2))
        // Sem o active_format_flag, ou sem o prefixo certo, não há AFD.
        val noFlag = sei.copyOf().also { it[14] = 0x01 }
        assertEquals(Afd.NONE, Afd.code(noFlag))
        val loose = sei.copyOf().also { it[7] = 0x26 }
        assertEquals(Afd.NONE, Afd.code(loose))
        assertEquals(Afd.NONE, Afd.code(ByteArray(64)))
    }

    @Test
    fun `afd codes become the area with the picture`() {
        val wide = 16f / 9f
        val narrow = 4f / 3f
        // 4:3 ao centro de um 16:9: barras dos lados, 1/8 de cada.
        val pillar = Afd.activeArea(9, wide)!!
        near(0.125f, pillar.left); near(0.875f, pillar.right); near(0f, pillar.top); near(1f, pillar.bottom)
        // 16:9 ao centro de um 4:3: barras em cima e em baixo.
        val letter = Afd.activeArea(10, narrow)!!
        near(0.125f, letter.top); near(0.875f, letter.bottom)
        // 16:9 em cima (código 2): a barra fica toda em baixo.
        val top = Afd.activeArea(2, narrow)!!
        near(0f, top.top); near(0.75f, top.bottom)
        // 14:9 num 16:9 e num 4:3.
        near(0.0625f, Afd.activeArea(11, wide)!!.left)
        near(1f / 14f, Afd.activeArea(11, narrow)!!.top)
        // A imagem toda, ou sem medida: decide a imagem.
        assertNull(Afd.activeArea(8, wide))
        assertNull(Afd.activeArea(10, wide))
        assertNull(Afd.activeArea(4, wide))
        assertNull(Afd.activeArea(Afd.NONE, wide))
        // Shoot and protect: a imagem 4:3 ou 16:9 inteira.
        near(0.125f, Afd.activeArea(13, wide)!!.left)
        assertNull(Afd.activeArea(15, wide))
    }

    @Test
    fun `bars are measured from the luma grid`() {
        // Um 16:9 com um filme 2,35 dentro: 9 linhas pretas em cima e em baixo (de 72).
        val film = BlackBars.measure(grid(t = 9, b = rows - 9), cols, rows)!!
        near(9f / rows, film.letterbox)
        near(0f, film.pillarbox)
        // Um 4:3 num 16:9: 16 colunas pretas de cada lado (de 128).
        val sd = BlackBars.measure(grid(l = 16, r = cols - 16), cols, rows)!!
        near(0f, sd.letterbox)
        near(16f / cols, sd.pillarbox)
        // A janela: as duas.
        val window = BlackBars.measure(grid(l = 16, r = cols - 16, t = 9, b = rows - 9), cols, rows)!!
        near(9f / rows, window.letterbox)
        near(16f / cols, window.pillarbox)
        // No Y da gama limitada, o preto é 16.
        near(9f / rows, BlackBars.measure(grid(t = 9, b = rows - 9, black = 16), cols, rows, black = 16)!!.letterbox)
        // Sem barras.
        val none = BlackBars.measure(grid(), cols, rows)!!
        near(0f, none.letterbox); near(0f, none.pillarbox)
    }

    @Test
    fun `dark frames, logos and subtitles in the bars give no wrong decision`() {
        // Toda preta, ou escura demais: sem decisão.
        assertNull(BlackBars.measure(grid(inside = { _, _ -> 0 }), cols, rows))
        assertNull(BlackBars.measure(grid(t = 9, b = rows - 9, inside = { _, _ -> 20 }), cols, rows))
        // Um logótipo pequeno na barra de cima (8 de 128 colunas): a barra continua a contar.
        val logo = grid(t = 9, b = rows - 9).also { g -> for (y in 2 until 6) for (x in 110 until 118) g[y * cols + x] = 200.toByte() }
        near(9f / rows, BlackBars.measure(logo, cols, rows)!!.letterbox)
        // Uma legenda na barra de baixo: as duas barras ficam diferentes, e esse sentido fica sem decisão.
        val subtitle = grid(t = 9, b = rows - 9).also { g -> for (y in rows - 6 until rows - 3) for (x in 30 until 98) g[y * cols + x] = (235).toByte() }
        assertEquals(-1f, BlackBars.measure(subtitle, cols, rows)!!.letterbox)
        // Uma cena escura em cima e em baixo, com a orla quase preta: sem decisão (a linha a seguir à "barra" não tem imagem).
        val gradient = grid(t = 9, b = rows - 9, inside = { x, y -> if (y < 12 || y >= rows - 12) (if (x % 8 == 0) 60 else 10) else 150 })
        assertEquals(-1f, BlackBars.measure(gradient, cols, rows)!!.letterbox)
        // Barras finíssimas (a orla do SD) não se cortam; enormes não são barras.
        near(0f, BlackBars.measure(grid(l = 1, r = cols - 1), cols, rows)!!.pillarbox)
        assertEquals(-1f, BlackBars.measure(grid(t = 24, b = rows - 24), cols, rows)!!.letterbox)
    }

    @Test
    fun `the crop only changes after the bars are stable`() {
        val crop = AutoCrop()
        crop.frame(1920, 1080)
        val film = BlackBars.measure(grid(t = 9, b = rows - 9), cols, rows)
        var t = 0L
        // Cinco segundos a ver o mesmo, e só então.
        while (t < AutoCrop.STABLE_MS) {
            assertFalse(crop.bars(film, t))
            t += 1_000
        }
        assertTrue(crop.bars(film, t))
        near(0.125f, crop.active.top, 0.01f)
        // Acertou na proporção habitual mais perto (a medida da grelha dá 2,37; fica 2,39).
        near(16f / 9f / 2.39f, crop.active.height, 0.001f)
        // As imagens escuras não mudam nada.
        repeat(10) { t += 1_000; assertFalse(crop.bars(null, t)) }
        // Imagem onde eram as barras: volta mais depressa.
        val full = BlackBars.measure(grid(), cols, rows)
        t += 1_000
        assertFalse(crop.bars(full, t))
        t += 1_000
        assertTrue(crop.bars(full, t))
        assertTrue(crop.active.isFull)
        // Uma piscadela de barras (uma imagem preta em cima e em baixo) não chega.
        t += 1_000
        assertFalse(crop.bars(film, t))
        t += 1_000
        assertFalse(crop.bars(full, t))
        assertTrue(crop.active.isFull)
    }

    @Test
    fun `afd comes first, and goes when it stops coming`() {
        val crop = AutoCrop()
        crop.frame(720, 576, 16f / 15f)
        assertFalse(crop.afd(10, 0))
        assertTrue(crop.afd(10, AutoCrop.AFD_STABLE_MS))
        near(0.125f, crop.active.top)
        // Com o AFD a mandar, as barras medidas não contam.
        val pillars = BlackBars.measure(grid(l = 20, r = cols - 20), cols, rows)
        var t = AutoCrop.AFD_STABLE_MS
        repeat(8) { t += 1_000; assertFalse(crop.bars(pillars, t)) }
        // Sem AFD três segundos, vale o que a imagem mostra.
        assertFalse(crop.afd(Afd.NONE, t))
        assertTrue(crop.afd(Afd.NONE, t + AutoCrop.AFD_GONE_MS))
        near(20f / cols, crop.active.left)
        crop.reset()
        assertTrue(crop.active.isFull)
    }

    @Test
    fun `the window only zooms as much as fills the screen`() {
        val wide = 16f / 9f
        // Um filme 2,35 num 16:9, num ecrã 16:9: não há nada a ganhar.
        assertTrue(AutoCrop.window(CropRect(0f, 0.12f, 1f, 0.88f), wide, wide).isFull)
        // Um 4:3 com barras dos lados num ecrã 16:9: também não.
        assertTrue(AutoCrop.window(CropRect(0.125f, 0f, 0.875f, 1f), wide, wide).isFull)
        // A janela (16:9 dentro de 4:3 dentro de 16:9): enche o ecrã.
        val w = AutoCrop.window(CropRect(0.125f, 0.125f, 0.875f, 0.875f), wide, wide)
        near(0.125f, w.left); near(0.125f, w.top); near(0.875f, w.right); near(0.875f, w.bottom)
        // Um 4:3 com um 16:9 dentro: corta só em cima e em baixo.
        val sd = AutoCrop.window(CropRect(0f, 0.125f, 1f, 0.875f), 4f / 3f, wide)
        near(0f, sd.left); near(0.125f, sd.top); near(1f, sd.right); near(0.875f, sd.bottom)
        // Um 4:3 com um 2,35 dentro num ecrã 16:9: só até encher a largura (as pontas das barras ficam).
        val scope = AutoCrop.window(CropRect(0f, 0.217f, 1f, 0.783f), 4f / 3f, wide)
        near(0f, scope.left); near(0.125f, scope.top); near(0.875f, scope.bottom)
        // A imagem em cima (AFD 2): a janela não sai do quadro.
        val top = AutoCrop.window(CropRect(0f, 0f, 1f, 0.75f), 4f / 3f, wide)
        near(0f, top.top); near(0.75f, top.bottom)
        // Num ecrã ao alto (o telemóvel), um 4:3 com barras dos lados num 16:9 perde-as: a imagem enche a largura.
        val portrait = AutoCrop.window(CropRect(0.125f, 0f, 0.875f, 1f), wide, 9f / 16f)
        near(0.125f, portrait.left); near(0.875f, portrait.right); near(0f, portrait.top); near(1f, portrait.bottom)
    }
}

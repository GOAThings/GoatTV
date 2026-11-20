package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** O que decide o aumento da imagem: o modo por vídeo e ecrã, o tamanho de saída, e a descida quando a GPU não aguenta. */
class UpscalingTest {
    private val ai = Prefs.Upscale.AI
    private val sharp = Prefs.Upscale.SHARP

    @Test
    fun `the mode follows the setting, the format and the sizes`() {
        assertEquals(Upscaling.OFF, Upscaling.mode(Prefs.Upscale.OFF, 1280, 720, 3840, 2160))
        assertEquals(Upscaling.AI, Upscaling.mode(ai, 1280, 720, 3840, 2160))
        assertEquals(Upscaling.SHARP, Upscaling.mode(sharp, 1280, 720, 3840, 2160))
        // Só o 8 bits SDR.
        assertEquals(Upscaling.OFF, Upscaling.mode(ai, 1280, 720, 3840, 2160, bitDepth = 10))
        assertEquals(Upscaling.OFF, Upscaling.mode(ai, 1280, 720, 3840, 2160, hdr = true))
        // Do tamanho do ecrã (ou quase), fica como vem.
        assertEquals(Upscaling.OFF, Upscaling.mode(ai, 1920, 1080, 1920, 1080))
        assertEquals(Upscaling.OFF, Upscaling.mode(sharp, 1920, 1080, 2200, 1238))
        assertEquals(Upscaling.OFF, Upscaling.mode(ai, 0, 0, 1920, 1080))
        // 720p num ecrã 1080p: 1,5×, a rede ainda compensa; 1600×900 num 1080p (1,2×) já só o nítido.
        assertEquals(Upscaling.AI, Upscaling.mode(ai, 1280, 720, 1920, 1080))
        assertEquals(Upscaling.SHARP, Upscaling.mode(ai, 1600, 900, 1920, 1080))
        // Sem a GPU para a rede, o nítido.
        assertEquals(Upscaling.SHARP, Upscaling.mode(ai, 1280, 720, 3840, 2160, aiCapable = false))
        // O que esta resolução já mostrou aguentar manda.
        assertEquals(Upscaling.SHARP, Upscaling.mode(ai, 1280, 720, 3840, 2160, ceiling = Upscaling.SHARP))
        assertEquals(Upscaling.OFF, Upscaling.mode(sharp, 1280, 720, 3840, 2160, ceiling = Upscaling.OFF))
        // O SD com píxeis não quadrados: conta o lado que cresce menos.
        assertEquals(Upscaling.AI, Upscaling.mode(ai, 720, 576, 1920, 1080))
    }

    @Test
    fun `the target fits the screen and never passes 4K`() {
        assertContentEquals(intArrayOf(3840, 2160), Upscaling.target(1280, 720, 3840, 2160))
        assertContentEquals(intArrayOf(1920, 1080), Upscaling.target(1280, 720, 1920, 1080))
        // Um ecrã maior do que o 4K fica no 4K.
        assertContentEquals(intArrayOf(3840, 2160), Upscaling.target(1920, 1080, 7680, 4320))
        // 4:3 num ecrã 16:9: a altura do ecrã, a largura pela proporção (par).
        assertContentEquals(intArrayOf(1440, 1080), Upscaling.target(640, 480, 1920, 1080))
        assertContentEquals(intArrayOf(0, 0), Upscaling.target(0, 0, 1920, 1080))
    }

    @Test
    fun `the setting is read from the account settings`() {
        fun read(json: String) = Prefs.from(kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject).upscale
        assertEquals(Prefs.Upscale.OFF, Prefs().upscale)
        assertEquals(ai, read("""{"upscale":"ai"}"""))
        assertEquals(sharp, read("""{"upscale":"sharp"}"""))
        assertEquals(Prefs.Upscale.OFF, read("""{"upscale":"max"}"""))
        assertEquals(Upscaling.AI, Upscaling.requested(ai))
        assertEquals("sharp", Upscaling.name(Upscaling.SHARP))
    }

    /** Imagens de 25 por segundo durante [seconds] a partir de [fromMs], cada uma a levar [busyUs]. */
    private fun UpscaleGovernor.run(fromMs: Long, seconds: Int, busyUs: (Int) -> Long): Long {
        var t = fromMs
        var dropped = -1L
        for (i in 0 until seconds * 25) {
            if (frame(t, busyUs(i), 40_000) && dropped < 0) dropped = t
            t += 40
        }
        return dropped
    }

    @Test
    fun `a GPU that keeps up stays in the mode`() {
        val g = UpscaleGovernor(Upscaling.AI)
        assertEquals(-1L, g.run(0, 10) { 20_000 })
        assertEquals(Upscaling.AI, g.mode)
        // Uma imagem atrasada de vez em quando (uma por segundo) não chega.
        assertEquals(-1L, g.run(10_000, 10) { if (it % 25 == 0) 60_000 else 20_000 })
        assertEquals(Upscaling.AI, g.mode)
    }

    @Test
    fun `a GPU that cannot keep up steps down after about two seconds, then turns off`() {
        val g = UpscaleGovernor(Upscaling.AI)
        // Tudo atrasado: o primeiro segundo não conta, depois duas janelas más.
        val first = g.run(0, 5) { 50_000 }
        assertTrue(first in 2_900..3_100, "desceu aos $first ms")
        assertEquals(Upscaling.SHARP, g.mode)
        assertEquals(25, g.lastLate)
        // O nítido também não aguenta: desliga, e desligado já não mede.
        assertTrue(g.run(10_000, 5) { 50_000 } > 0)
        assertEquals(Upscaling.OFF, g.mode)
        assertEquals(-1L, g.run(20_000, 5) { 50_000 })
        assertEquals(Upscaling.OFF, g.mode)
    }

    @Test
    fun `a bad window followed by a good one does not step down`() {
        val g = UpscaleGovernor(Upscaling.SHARP)
        // Do 1.º ao 2.º segundo atrasadas, do 2.º ao 3.º não, e assim por diante.
        assertEquals(-1L, g.run(0, 8) { i -> if ((i / 25) % 2 == 1) 50_000 else 10_000 })
        assertEquals(Upscaling.SHARP, g.mode)
    }

    @Test
    fun `missing GPU features step down at once`() {
        val g = UpscaleGovernor(Upscaling.AI)
        g.down()
        assertEquals(Upscaling.SHARP, g.mode)
        g.down()
        g.down()
        assertEquals(Upscaling.OFF, g.mode)
    }

    @Test
    fun `the session remembers what each resolution can take`() {
        val m = UpscaleMemory()
        assertEquals(Upscaling.AI, m.ceiling(1280, 720))
        m.lower(1280, 720, Upscaling.SHARP)
        assertEquals(Upscaling.SHARP, m.ceiling(1280, 720))
        // Nunca volta a subir na mesma sessão.
        m.lower(1280, 720, Upscaling.AI)
        assertEquals(Upscaling.SHARP, m.ceiling(1280, 720))
        assertEquals(Upscaling.AI, m.ceiling(720, 576))
        m.lower(720, 576, Upscaling.OFF)
        assertEquals(Upscaling.OFF, m.ceiling(720, 576))
    }

    @Test
    fun `the Apple bridge keeps the memory between videos`() {
        val memory = AppleUpscaleMemory()
        val a = AppleUpscale(ai, memory)
        assertEquals(Upscaling.AI, a.start(1280, 720, 3840, 2160, 8, false, supported = true))
        assertEquals(3840, a.targetWidth(3840, 2160))
        assertEquals(2160, a.targetHeight(3840, 2160))
        assertEquals(Upscaling.SHARP, a.fail())
        // O vídeo seguinte nesta resolução já começa no nítido; sem MetalFX, nada.
        val b = AppleUpscale(ai, memory)
        assertEquals(Upscaling.SHARP, b.start(1280, 720, 3840, 2160, 8, false, supported = true))
        assertEquals(Upscaling.OFF, b.start(1280, 720, 3840, 2160, 8, false, supported = false))
        assertFalse(b.frame(0, 0, 40_000))
    }
}

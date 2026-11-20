package cc.slokker.itv.media

import cc.slokker.itv.data.Prefs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PictureLookTest {
    private fun near(expected: Float, actual: Float, tolerance: Float = 0.0005f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected, got $actual")

    private fun near(expected: FloatArray, actual: FloatArray, tolerance: Float = 0.0005f) =
        expected.indices.forEach { near(expected[it], actual[it], tolerance) }

    @Test
    fun `everything at zero leaves the picture as it comes`() {
        val look = PictureLook()
        assertTrue(look.neutral)
        near(1f, look.gain)
        near(0f, look.bias)
        near(1f, look.saturation)
        near(0f, look.sharpen)
        near(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), look.rgbMatrix())
        near(0f, look.lumaBias(fullRange = false))
        near(1f, look.chromaGain)
        val pixel = floatArrayOf(0.2f, 0.6f, 0.9f)
        near(pixel, PictureLook.apply(look, pixel, floatArrayOf(0.5f, 0.5f, 0.5f)))
    }

    @Test
    fun `values outside the range are clamped`() {
        assertEquals(PictureLook(5, -5, 5, 5), PictureLook(99, -99, 7, 12))
        assertEquals(0, PictureLook(sharpness = -3).sharpness)
        assertTrue(PictureLook(sharpness = -3).neutral)
    }

    @Test
    fun `brightness adds and contrast turns around mid grey`() {
        near(floatArrayOf(0.59f, 0.59f, 0.59f), PictureLook.apply(PictureLook(brightness = 3), floatArrayOf(0.5f, 0.5f, 0.5f)))
        near(floatArrayOf(0.35f, 0.35f, 0.35f), PictureLook.apply(PictureLook(brightness = -5), floatArrayOf(0.5f, 0.5f, 0.5f)))
        // O cinzento médio não muda com o contraste; o resto afasta-se ou aproxima-se dele.
        near(floatArrayOf(0.5f, 0.5f, 0.5f), PictureLook.apply(PictureLook(contrast = 5), floatArrayOf(0.5f, 0.5f, 0.5f)))
        near(floatArrayOf(0.76f, 0.76f, 0.76f), PictureLook.apply(PictureLook(contrast = 5), floatArrayOf(0.7f, 0.7f, 0.7f)))
        near(floatArrayOf(0.36f, 0.36f, 0.36f), PictureLook.apply(PictureLook(contrast = -5), floatArrayOf(0.3f, 0.3f, 0.3f)))
        // Nunca sai do 0 a 1.
        near(floatArrayOf(1f, 1f, 1f), PictureLook.apply(PictureLook(brightness = 5, contrast = 5), floatArrayOf(0.95f, 0.95f, 0.95f)))
        near(floatArrayOf(0f, 0f, 0f), PictureLook.apply(PictureLook(brightness = -5, contrast = 5), floatArrayOf(0.05f, 0.05f, 0.05f)))
    }

    @Test
    fun `saturation keeps the greys and the brightness of a colour`() {
        val grey = floatArrayOf(0.4f, 0.4f, 0.4f)
        near(grey, PictureLook.apply(PictureLook(saturation = 5), grey))
        near(grey, PictureLook.apply(PictureLook(saturation = -5), grey))
        val colour = floatArrayOf(0.6f, 0.3f, 0.2f)
        fun luma(c: FloatArray) = c[0] * PictureLook.LUMA[0] + c[1] * PictureLook.LUMA[1] + c[2] * PictureLook.LUMA[2]
        val more = PictureLook.apply(PictureLook(saturation = 3), colour)
        val less = PictureLook.apply(PictureLook(saturation = -3), colour)
        near(luma(colour), luma(more))
        near(luma(colour), luma(less))
        assertTrue(more[0] - more[2] > colour[0] - colour[2])
        assertTrue(less[0] - less[2] < colour[0] - colour[2])
    }

    @Test
    fun `sharpness pushes a pixel away from its neighbours`() {
        val look = PictureLook(sharpness = 4)
        near(0.6f, look.sharpen)
        near(floatArrayOf(0.72f, 0.72f, 0.72f), PictureLook.apply(look, floatArrayOf(0.6f, 0.6f, 0.6f), floatArrayOf(0.4f, 0.4f, 0.4f)))
        // Uma zona lisa fica igual.
        near(floatArrayOf(0.6f, 0.6f, 0.6f), PictureLook.apply(look, floatArrayOf(0.6f, 0.6f, 0.6f), floatArrayOf(0.6f, 0.6f, 0.6f)))
    }

    @Test
    fun `the YCbCr numbers give the same picture as the RGB ones`() {
        // BT.709, gama limitada: RGB → YCbCr, os ajustes em YCbCr, e de volta; tem de dar o mesmo que em RGB.
        val look = PictureLook(brightness = 2, contrast = -3, saturation = 4)
        val rgb = floatArrayOf(0.55f, 0.35f, 0.45f)
        val kr = PictureLook.LUMA[0]
        val kb = PictureLook.LUMA[2]
        val y = kr * rgb[0] + PictureLook.LUMA[1] * rgb[1] + kb * rgb[2]
        val cb = (rgb[2] - y) / (2f * (1f - kb))
        val cr = (rgb[0] - y) / (2f * (1f - kr))
        val codedY = PictureLook.LIMITED_BLACK + y * PictureLook.LIMITED_SCALE
        val outY = (look.gain * codedY + look.lumaBias(fullRange = false) - PictureLook.LIMITED_BLACK) / PictureLook.LIMITED_SCALE
        val outCb = cb * look.chromaGain
        val outCr = cr * look.chromaGain
        val back = floatArrayOf(
            outY + 2f * (1f - kr) * outCr,
            outY - (2f * kr * (1f - kr) * outCr + 2f * kb * (1f - kb) * outCb) / PictureLook.LUMA[1],
            outY + 2f * (1f - kb) * outCb,
        )
        near(PictureLook.apply(look, rgb), back, 0.001f)
        // Na gama inteira o Y leva o mesmo que o RGB.
        near(look.bias, look.lumaBias(fullRange = true))
    }

    @Test
    fun `HDR and 10-bit video are left alone`() {
        val look = PictureLook(brightness = 1)
        assertTrue(look.applies(bitDepth = 8, hdr = false))
        assertFalse(look.applies(bitDepth = 10, hdr = false))
        assertFalse(look.applies(bitDepth = 8, hdr = true))
        assertFalse(PictureLook().applies(bitDepth = 8, hdr = false))
        // Na Apple, só até 1080 linhas.
        assertTrue(look.applies(8, false, height = 1080, maxHeight = PictureLook.APPLE_MAX_HEIGHT))
        assertFalse(look.applies(8, false, height = 2160, maxHeight = PictureLook.APPLE_MAX_HEIGHT))
    }

    @Test
    fun `the settings are read and clamped`() {
        val prefs = Prefs.from(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"picture_brightness":2,"picture_contrast":-9,"picture_saturation":"x","picture_sharpness":-2}""",
        ) as kotlinx.serialization.json.JsonObject)
        assertEquals(PictureLook(2, -5, 0, 0), PictureLook.of(prefs))
        assertTrue(PictureLook.of(Prefs()).neutral)
        assertEquals(4, Prefs.Picture.KEYS.size)
    }
}

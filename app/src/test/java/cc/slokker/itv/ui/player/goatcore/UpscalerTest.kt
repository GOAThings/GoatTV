package cc.slokker.itv.ui.player.goatcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A passagem do FSRCNNX (o ficheiro original, nos recursos) a shaders GLSL ES 3.00, sem GPU. */
class UpscalerTest {
    private val source = javaClass.getResourceAsStream("/goatcore/FSRCNNX_x2_8-0-4-1.glsl")!!.use { it.readBytes().decodeToString() }

    @Test
    fun oFicheiroTemOAvisoDoAutor() {
        assertTrue(source.contains("Copyright (C) 2017-2021 igv"))
        assertTrue(source.contains("GNU Lesser General Public"))
    }

    @Test
    fun passagensETexturas() {
        val passes = Upscaler.AiPass.parse(source)
        // 2 do mapa de características, 8 do mapeamento, 2 residuais, 1 da convolução de subpíxeis (a agregação é nossa).
        assertEquals(13, passes.size)
        assertEquals(listOf("FEATURE1", "FEATURE2"), passes.take(2).map { it.save })
        assertEquals("SUBCONV1", passes.last().save)
        // A imagem de fora (o LUMA) só nas duas primeiras.
        assertTrue(passes.take(2).all { p -> p.inputs.toList() == listOf(-1) })
        assertTrue(passes.drop(2).all { p -> p.inputs.all { it >= 0 } })
        // Nenhuma escreve numa textura que está a ler, e chegam 6 texturas.
        passes.forEach { p -> assertFalse(p.save, p.output in p.inputs.toList()) }
        assertEquals(6, passes.maxOf { it.output } + 1)
        // Cada valor lido é o último que se guardou com esse nome (a textura não foi reutilizada pelo meio).
        val holder = HashMap<Int, String>()
        passes.forEach { p ->
            p.binds.forEachIndexed { i, b -> if (b != "LUMA") assertEquals("${p.save} lê $b", b, holder[p.inputs[i]]) }
            holder[p.output] = p.save
        }
    }

    @Test
    fun osShadersFicamEmGlslEs3() {
        Upscaler.AiPass.parse(source).forEach { p ->
            val glsl = p.source()
            assertTrue(glsl.startsWith("#version 300 es\n"))
            assertFalse(p.save, glsl.contains("_texOff"))
            assertFalse(p.save, glsl.contains("//!"))
            assertTrue(glsl.contains("vec4 hook()"))
            p.binds.forEach { assertTrue(glsl.contains("uniform mediump sampler2D $it;")) }
        }
    }
}

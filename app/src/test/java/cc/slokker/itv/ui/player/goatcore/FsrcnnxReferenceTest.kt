package cc.slokker.itv.ui.player.goatcore

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * O FSRCNNX corrido no processador, a ler o ficheiro original (sem GPU): uma referência para o que os shaders têm de
 * dar. Imagens guardadas de cima para baixo (y a crescer para baixo, como no mpv).
 */
class FsrcnnxReferenceTest {
    private val source = javaClass.getResourceAsStream("/goatcore/FSRCNNX_x2_8-0-4-1.glsl")!!.use { it.readBytes().decodeToString() }

    private class Img(val w: Int, val h: Int, val c: Int) {
        val v = FloatArray(w * h * c)
        fun at(x: Int, y: Int, k: Int) = v[((y.coerceIn(0, h - 1)) * w + x.coerceIn(0, w - 1)) * c + k]
    }

    private val num = Regex("""-?\d+\.\d+(?:e-?\d+)?|-?\d+""")
    private fun nums(s: String) = num.findAll(s).map { it.value.toFloat() }.toList()

    /** As 13 passagens (até à SUBCONV1) sobre [luma] (w×h, um canal): devolve a SUBCONV1 (4 canais). */
    private fun ox(a: Float, @Suppress("UNUSED_PARAMETER") b: Float) = a.toInt()
    private fun oy(@Suppress("UNUSED_PARAMETER") a: Float, b: Float) = b.toInt()

    private fun network(luma: Img): Img {
        val saved = HashMap<String, Img>()
        val blocks = source.split("//!HOOK").drop(1)
        var last: Img? = null
        for (block in blocks) {
            val lines = block.lines()
            val save = lines.firstOrNull { it.startsWith("//!SAVE ") }?.substringAfter("//!SAVE ")?.trim() ?: continue
            val out = Img(luma.w, luma.h, 4)
            val body = lines.filter { !it.startsWith("//") }
            for (y in 0 until luma.h) for (x in 0 until luma.w) {
                val res = FloatArray(4)
                for (line in body) {
                    val l = line.trim()
                    when {
                        l.startsWith("vec4 res = vec4(") -> nums(l.substringAfter("vec4(")).forEachIndexed { i, f -> res[i] = f }
                        l.startsWith("res += vec4(") && "LUMA_texOff" in l -> {
                            val w = nums(l.substringAfter("vec4(").substringBefore(")"))
                            val off = nums(l.substringAfter("vec2(").substringBefore(")"))
                            val s = luma.at(x + ox(off[0], off[1]), y + oy(off[0], off[1]), 0)
                            for (i in 0..3) res[i] += w[i] * s
                        }
                        l.startsWith("res += mat4") -> {
                            val m = nums(l.substringAfter("(").substringBefore(")"))
                            val tex = Regex("""\* (\w+)_texOff\((.*)\);""").find(l)!!
                            val img = saved[tex.groupValues[1]]!!
                            val o = if (tex.groupValues[2] == "0") listOf(0f, 0f) else nums(tex.groupValues[2].substringAfter("vec2("))
                            val v = FloatArray(4) { img.at(x + ox(o[0], o[1]), y + oy(o[0], o[1]), it) }
                            // mat4 de 16 números: coluna a coluna; M * v.
                            for (r in 0..3) for (col in 0..3) res[r] += m[col * 4 + r] * v[col]
                        }
                        l.startsWith("res += ") && "_texOff(0)" in l -> {
                            val img = saved[l.substringAfter("res += ").substringBefore("_texOff")]!!
                            for (i in 0..3) res[i] += img.at(x, y, i)
                        }
                        l.startsWith("res = max(res") -> {
                            val a = nums(l.substringAfter("+ vec4(").substringBefore(")"))
                            for (i in 0..3) res[i] = max(res[i], 0f) + a[i] * min(res[i], 0f)
                        }
                    }
                }
                for (i in 0..3) out.v[(y * out.w + x) * 4 + i] = res[i]
            }
            saved[save] = out
            last = out
        }
        return last!!
    }

    /** A agregação do original: o subpíxel (sx, sy) do píxel (x, y) é o canal sx * 2 + sy (ou o trocado). */
    private fun aggregate(sub: Img, swapped: Boolean): Img {
        val out = Img(sub.w * 2, sub.h * 2, 1)
        for (y in 0 until out.h) for (x in 0 until out.w) {
            val sx = x and 1
            val sy = y and 1
            val i = if (swapped) sy * 2 + sx else sx * 2 + sy
            out.v[y * out.w + x] = sub.at(x / 2, y / 2, i)
        }
        return out
    }

    private fun bilinear(low: Img): Img {
        val out = Img(low.w * 2, low.h * 2, 1)
        for (y in 0 until out.h) for (x in 0 until out.w) {
            val fx = (x + 0.5f) / 2 - 0.5f
            val fy = (y + 0.5f) / 2 - 0.5f
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val ax = fx - x0
            val ay = fy - y0
            out.v[y * out.w + x] = (low.at(x0, y0, 0) * (1 - ax) + low.at(x0 + 1, y0, 0) * ax) * (1 - ay) +
                (low.at(x0, y0 + 1, 0) * (1 - ax) + low.at(x0 + 1, y0 + 1, 0) * ax) * ay
        }
        return out
    }

    private fun mse(a: Img, b: Img, border: Int = 6): Double {
        var s = 0.0
        var n = 0
        for (y in border until a.h - border) for (x in border until a.w - border) {
            val d = a.at(x, y, 0) - b.at(x, y, 0)
            s += d * d
            n++
        }
        return s / n
    }

    private fun scene(size: Int): Img {
        val hi = Img(size, size, 1)
        for (y in 0 until size) for (x in 0 until size) {
            val r = hypot(x - size / 2.0, y - size / 2.0)
            var v = 0.45 + 0.2 * sin(x * 0.21) * cos(y * 0.17)
            if (r < size * 0.3) v += 0.25
            if ((x / 8 + y / 8) % 2 == 0 && x > size * 0.7) v -= 0.2
            hi.v[y * size + x] = (16 / 255.0 + v.coerceIn(0.0, 1.0) * 219 / 255.0).toFloat()
        }
        return hi
    }

    private fun down(hi: Img): Img {
        val low = Img(hi.w / 2, hi.h / 2, 1)
        for (y in 0 until low.h) for (x in 0 until low.w) {
            low.v[y * low.w + x] = (hi.at(2 * x, 2 * y, 0) + hi.at(2 * x + 1, 2 * y, 0) + hi.at(2 * x, 2 * y + 1, 0) + hi.at(2 * x + 1, 2 * y + 1, 0)) / 4
        }
        return low
    }

    @Test
    fun referencia() {
        // Uma imagem plana sai igual (nos 4 subpíxeis), de 0 a 1.
        for (value in listOf(0f, 0.0627f, 0.5f, 0.92f, 1f)) {
            val sub = network(Img(16, 16, 1).also { it.v.fill(value) })
            for (i in 0..3) assertTrue("plana $value", abs(sub.at(8, 8, i) - value) < 0.003f)
        }
        // Rampas: o centro do píxel fica no sítio, e o subpíxel (x, y) é o canal x * 2 + y, com o y a crescer para baixo.
        val rx = Img(16, 16, 1).also { i -> for (y in 0 until 16) for (x in 0 until 16) i.v[y * 16 + x] = 0.2f + 0.04f * x }
        val ry = Img(16, 16, 1).also { i -> for (y in 0 until 16) for (x in 0 until 16) i.v[y * 16 + x] = 0.2f + 0.04f * y }
        val a = network(rx)
        val b = network(ry)
        assertTrue(a.at(8, 8, 0) < 0.52f && a.at(8, 8, 2) > 0.52f && abs(a.at(8, 8, 0) - a.at(8, 8, 1)) < 0.003f)
        assertTrue(b.at(8, 8, 0) < 0.52f && b.at(8, 8, 1) > 0.52f && abs(b.at(8, 8, 0) - b.at(8, 8, 2)) < 0.003f)
        // Numa imagem reduzida a metade, a rede aproxima-se mais do original do que o bilinear, e só com esta ordem.
        val hi = scene(96)
        val low = down(hi)
        val sub = network(low)
        val net = mse(aggregate(sub, false), hi)
        assertTrue("rede $net, bilinear ${mse(bilinear(low), hi)}", net < mse(bilinear(low), hi))
        assertTrue(net < mse(aggregate(sub, true), hi))
    }
}

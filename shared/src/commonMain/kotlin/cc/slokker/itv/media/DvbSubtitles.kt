package cc.slokker.itv.media

/**
 * Uma imagem de legenda DVB: onde fica ([x], [y], no ecrã de [DvbSubtitlePage.displayWidth] por
 * [DvbSubtitlePage.displayHeight]) e os pixels em RGBA pré-multiplicado, linha a linha (4 bytes por pixel).
 */
class DvbSubtitleImage(val x: Int, val y: Int, val width: Int, val height: Int, val rgba: ByteArray)

/**
 * O que mostrar a partir de agora: as imagens (vazio = limpar) no ecrã do emissor (720×576 se não disser outro) e
 * quanto tempo, no máximo ([timeoutSeconds], 0 = até à seguinte).
 */
class DvbSubtitlePage(val displayWidth: Int, val displayHeight: Int, val images: List<DvbSubtitleImage>, val timeoutSeconds: Int)

/**
 * Lê as legendas DVB (ETSI EN 300 743): as imagens que o emissor desenha por segmentos (página, regiões, paletas e
 * objetos com os pixels em códigos de 2, 4 ou 8 bits). [compositionPage] e [ancillaryPage] vêm do PMT (o descritor
 * 0x59); os segmentos de outras páginas não são desta faixa.
 *
 * [decode] recebe um PES (sem o data_identifier e o subtitle_stream_id) e devolve a página nova quando o emissor diz
 * que acabou (o segmento "end of display set", ou o PES seguinte sem ele).
 */
class DvbSubtitleDecoder(private val compositionPage: Int, private val ancillaryPage: Int) {
    private class Region(val id: Int) {
        var width = 0
        var height = 0
        var depth = 2
        var clutId = 0
        var pixels = ByteArray(0)
        var fill = false
        var background8 = 0
        var background4 = 0
        var background2 = 0
        /** Os objetos da região: o id e onde começam nela. */
        var objects: List<IntArray> = emptyList()

        fun reset(w: Int, h: Int) {
            if (w != width || h != height || pixels.size != w * h) {
                width = w
                height = h
                pixels = ByteArray(w * h)
            }
        }
    }

    private class Clut(var c2: IntArray = DEFAULT_2.copyOf(), var c4: IntArray = DEFAULT_4.copyOf(), var c8: IntArray = DEFAULT_8.copyOf())

    private var displayWidth = 720
    private var displayHeight = 576
    private var windowX = 0
    private var windowY = 0
    private var timeout = 0
    /** As regiões da página e onde ficam (id → x, y). */
    private var placements: List<IntArray> = emptyList()
    private val regions = HashMap<Int, Region>()
    private val cluts = HashMap<Int, Clut>()
    private var pending = false

    /** Um PES de legendas (os segmentos, a partir do sync byte 0x0F); a página nova se este fechou uma. */
    fun decode(data: ByteArray): DvbSubtitlePage? {
        var i = 0
        var result: DvbSubtitlePage? = null
        while (i + 6 <= data.size && (data[i].toInt() and 0xFF) == 0x0F) {
            val type = data[i + 1].toInt() and 0xFF
            val page = u16(data, i + 2)
            val length = u16(data, i + 4)
            val body = i + 6
            if (body + length > data.size) break
            i = body + length
            if (page != compositionPage && page != ancillaryPage) continue
            when (type) {
                0x10 -> if (page == compositionPage) {
                    // Uma página nova sem "end of display set" na anterior: a anterior fecha aqui.
                    if (pending) result = page()
                    pageComposition(data, body, body + length)
                    pending = true
                }
                0x11 -> if (page == compositionPage) regionComposition(data, body, body + length)
                0x12 -> clut(data, body, body + length)
                0x13 -> objectData(data, body, body + length)
                0x14 -> if (page == compositionPage) displayDefinition(data, body, body + length)
                0x80 -> if (page == compositionPage && pending) {
                    pending = false
                    result = page()
                }
            }
        }
        return result
    }

    private fun page(): DvbSubtitlePage {
        val images = ArrayList<DvbSubtitleImage>()
        for (p in placements) {
            val r = regions[p[0]] ?: continue
            if (r.width == 0 || r.height == 0) continue
            val clut = cluts[r.clutId] ?: DEFAULT_CLUT
            val colors = when (r.depth) {
                1 -> clut.c2
                2 -> clut.c4
                else -> clut.c8
            }
            val rgba = ByteArray(r.width * r.height * 4)
            var visible = false
            for (k in 0 until r.width * r.height) {
                val c = colors[(r.pixels[k].toInt() and 0xFF) % colors.size]
                val a = c ushr 24
                if (a == 0) continue
                visible = true
                // Pré-multiplicado (como o Core Graphics quer).
                rgba[k * 4] = ((c shr 16 and 0xFF) * a / 255).toByte()
                rgba[k * 4 + 1] = ((c shr 8 and 0xFF) * a / 255).toByte()
                rgba[k * 4 + 2] = ((c and 0xFF) * a / 255).toByte()
                rgba[k * 4 + 3] = a.toByte()
            }
            if (visible) images += DvbSubtitleImage(windowX + p[1], windowY + p[2], r.width, r.height, rgba)
        }
        return DvbSubtitlePage(displayWidth, displayHeight, images, timeout)
    }

    // ---- Segmentos ---------------------------------------------------------------------------------

    private fun displayDefinition(d: ByteArray, from: Int, to: Int) {
        if (to - from < 5) return
        val window = (d[from].toInt() and 0x08) != 0
        displayWidth = u16(d, from + 1) + 1
        displayHeight = u16(d, from + 3) + 1
        if (window && to - from >= 13) {
            windowX = u16(d, from + 5)
            windowY = u16(d, from + 9)
        } else {
            windowX = 0
            windowY = 0
        }
    }

    private fun pageComposition(d: ByteArray, from: Int, to: Int) {
        if (to - from < 2) return
        timeout = d[from].toInt() and 0xFF
        val state = (d[from + 1].toInt() shr 2) and 3
        // Um ponto de entrada ou uma mudança de modo: tudo de novo (regiões, paletas e objetos).
        if (state != 0) {
            regions.clear()
            cluts.clear()
        }
        val list = ArrayList<IntArray>()
        var i = from + 2
        while (i + 6 <= to) {
            list += intArrayOf(d[i].toInt() and 0xFF, u16(d, i + 2), u16(d, i + 4))
            i += 6
        }
        placements = list
    }

    private fun regionComposition(d: ByteArray, from: Int, to: Int) {
        if (to - from < 10) return
        val id = d[from].toInt() and 0xFF
        val r = regions.getOrPut(id) { Region(id) }
        r.fill = (d[from + 1].toInt() and 0x08) != 0
        r.reset(u16(d, from + 2), u16(d, from + 4))
        r.depth = (d[from + 6].toInt() shr 2) and 7
        r.clutId = d[from + 7].toInt() and 0xFF
        r.background8 = d[from + 8].toInt() and 0xFF
        r.background4 = (d[from + 9].toInt() shr 4) and 0x0F
        r.background2 = (d[from + 9].toInt() shr 2) and 3
        if (r.fill) {
            val bg = when (r.depth) {
                1 -> r.background2
                2 -> r.background4
                else -> r.background8
            }
            r.pixels.fill(bg.toByte())
        }
        val objects = ArrayList<IntArray>()
        var i = from + 10
        while (i + 6 <= to) {
            val objectId = u16(d, i)
            val objectType = (d[i + 2].toInt() shr 6) and 3
            val x = u16(d, i + 2) and 0x0FFF
            val y = u16(d, i + 4) and 0x0FFF
            objects += intArrayOf(objectId, x, y)
            i += if (objectType == 1 || objectType == 2) 8 else 6
        }
        r.objects = objects
    }

    private fun clut(d: ByteArray, from: Int, to: Int) {
        if (to - from < 2) return
        val c = cluts.getOrPut(d[from].toInt() and 0xFF) { Clut() }
        var i = from + 2
        while (i + 2 <= to) {
            val entry = d[i].toInt() and 0xFF
            val flags = d[i + 1].toInt() and 0xFF
            val y: Int
            val cr: Int
            val cb: Int
            val t: Int
            if (((flags and 1)) != 0) {
                if (i + 6 > to) break
                y = d[i + 2].toInt() and 0xFF
                cr = d[i + 3].toInt() and 0xFF
                cb = d[i + 4].toInt() and 0xFF
                t = d[i + 5].toInt() and 0xFF
                i += 6
            } else {
                if (i + 4 > to) break
                val v = u16(d, i + 2)
                y = (v shr 10 and 0x3F) shl 2
                cr = (v shr 6 and 0x0F) shl 4
                cb = (v shr 2 and 0x0F) shl 4
                t = (v and 3) shl 6
                i += 4
            }
            val color = if (y == 0) 0 else fromYCrCb(255 - t, y, cr, cb)
            if ((flags and 0x80) != 0 && entry < 4) c.c2[entry] = color
            if ((flags and 0x40) != 0 && entry < 16) c.c4[entry] = color
            if (((flags and 0x20)) != 0) c.c8[entry] = color
        }
    }

    private fun objectData(d: ByteArray, from: Int, to: Int) {
        if (to - from < 3) return
        val id = u16(d, from)
        val coding = (d[from + 2].toInt() shr 2) and 3
        val nonModifying = (d[from + 2].toInt() and 0x02) != 0
        // Só pixels (os objetos de caracteres quase não existem).
        if (coding != 0 || to - from < 7) return
        val topLength = u16(d, from + 3)
        val bottomLength = u16(d, from + 5)
        val top = from + 7
        val bottom = top + topLength
        if (bottom + bottomLength > to) return
        for (r in regions.values) {
            for (o in r.objects) {
                if (o[0] != id) continue
                field(d, top, top + topLength, r, o[1], o[2], nonModifying)
                // Sem o campo de baixo, repete-se o de cima.
                if (bottomLength > 0) field(d, bottom, bottom + bottomLength, r, o[1], o[2] + 1, nonModifying)
                else field(d, top, top + topLength, r, o[1], o[2] + 1, nonModifying)
            }
        }
    }

    /** Um campo (linhas alternadas, a partir de [y0]) de um objeto na região [r], em [x0]. */
    private fun field(d: ByteArray, from: Int, to: Int, r: Region, x0: Int, y0: Int, nonModifying: Boolean) {
        var map2to4 = intArrayOf(0, 7, 8, 15)
        var map2to8 = intArrayOf(0x00, 0x77, 0x88, 0xFF)
        var map4to8 = IntArray(16) { it * 0x11 }
        var x = x0
        var y = y0
        val bits = Bits(d, from, to)
        while (bits.byte < to) {
            when (bits.read(8)) {
                0x10 -> {
                    val map = when (r.depth) {
                        2 -> map2to4
                        3 -> map2to8
                        else -> null
                    }
                    x = pixels2(bits, r, x, y, map, nonModifying)
                }
                0x11 -> x = pixels4(bits, r, x, y, if (r.depth == 3) map4to8 else null, nonModifying)
                0x12 -> x = pixels8(bits, r, x, y, nonModifying)
                0x20 -> map2to4 = IntArray(4) { bits.read(4) }
                0x21 -> map2to8 = IntArray(4) { bits.read(8) }
                0x22 -> map4to8 = IntArray(16) { bits.read(8) }
                0xF0 -> {
                    x = x0
                    y += 2
                }
                else -> return
            }
        }
    }

    private fun put(r: Region, x: Int, y: Int, run: Int, code: Int, nonModifying: Boolean) {
        if (nonModifying && code == 1) return
        if (y < 0 || y >= r.height) return
        val end = minOf(x + run, r.width)
        var at = y * r.width + x
        for (k in x until end) r.pixels[at++] = code.toByte()
    }

    private fun pixels2(b: Bits, r: Region, start: Int, y: Int, map: IntArray?, nm: Boolean): Int {
        var x = start
        while (true) {
            var run = 0
            var code = 0
            val c = b.read(2)
            if (c != 0) {
                run = 1
                code = c
            } else if (b.read(1) == 1) {
                run = 3 + b.read(3)
                code = b.read(2)
            } else if (b.read(1) == 1) {
                run = 1
            } else {
                when (b.read(2)) {
                    0 -> break
                    1 -> run = 2
                    2 -> {
                        run = 12 + b.read(4)
                        code = b.read(2)
                    }
                    3 -> {
                        run = 29 + b.read(8)
                        code = b.read(2)
                    }
                }
            }
            if (run > 0) put(r, x, y, run, map?.get(code) ?: code, nm)
            x += run
        }
        b.align()
        return x
    }

    private fun pixels4(b: Bits, r: Region, start: Int, y: Int, map: IntArray?, nm: Boolean): Int {
        var x = start
        while (true) {
            var run = 0
            var code = 0
            val c = b.read(4)
            if (c != 0) {
                run = 1
                code = c
            } else if (b.read(1) == 0) {
                val n = b.read(3)
                if (n == 0) break
                run = n + 2
            } else if (b.read(1) == 0) {
                run = 4 + b.read(2)
                code = b.read(4)
            } else {
                when (b.read(2)) {
                    0 -> run = 1
                    1 -> run = 2
                    2 -> {
                        run = 9 + b.read(4)
                        code = b.read(4)
                    }
                    3 -> {
                        run = 25 + b.read(8)
                        code = b.read(4)
                    }
                }
            }
            if (run > 0) put(r, x, y, run, map?.get(code) ?: code, nm)
            x += run
        }
        b.align()
        return x
    }

    private fun pixels8(b: Bits, r: Region, start: Int, y: Int, nm: Boolean): Int {
        var x = start
        while (true) {
            var run: Int
            var code = 0
            val c = b.read(8)
            if (c != 0) {
                run = 1
                code = c
            } else if (b.read(1) == 0) {
                run = b.read(7)
                if (run == 0) break
            } else {
                run = b.read(7)
                code = b.read(8)
            }
            put(r, x, y, run, code, nm)
            x += run
        }
        return x
    }

    /** Lê bits de [d] a partir de [from], sem passar de [to] (dá zeros depois do fim). */
    private class Bits(private val d: ByteArray, from: Int, private val to: Int) {
        var byte = from
        private var bit = 0

        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val b = if (byte < to) (d[byte].toInt() shr (7 - bit)) and 1 else 0
                v = (v shl 1) or b
                if (++bit == 8) {
                    bit = 0
                    byte++
                }
            }
            return v
        }

        fun align() {
            if (bit != 0) {
                bit = 0
                byte++
            }
        }
    }

    companion object {
        private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

        private fun argb(a: Int, r: Int, g: Int, b: Int) =
            (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        /** Y, Cr, Cb (BT.601, gama de estúdio como o DVB os manda) e a transparência em ARGB. */
        private fun fromYCrCb(a: Int, y: Int, cr: Int, cb: Int): Int {
            val yy = 1.164 * (y - 16)
            val r = yy + 1.596 * (cr - 128)
            val g = yy - 0.813 * (cr - 128) - 0.391 * (cb - 128)
            val b = yy + 2.018 * (cb - 128)
            return argb(a, r.toInt(), g.toInt(), b.toInt())
        }

        // As paletas por omissão (EN 300 743, secção 10).
        private val DEFAULT_2 = intArrayOf(0, argb(255, 255, 255, 255), argb(255, 0, 0, 0), argb(255, 127, 127, 127))
        private val DEFAULT_4 = IntArray(16) { i ->
            when {
                i == 0 -> 0
                i < 8 -> argb(255, if ((i and 1) != 0) 255 else 0, if ((i and 2) != 0) 255 else 0, if ((i and 4) != 0) 255 else 0)
                else -> argb(255, if ((i and 1) != 0) 127 else 0, if ((i and 2) != 0) 127 else 0, if ((i and 4) != 0) 127 else 0)
            }
        }
        private val DEFAULT_8 = IntArray(256) { i ->
            fun c(low: Int, high: Int, bit: Int) = (if ((i and bit) != 0) low else 0) + (if ((i and (bit shl 4)) != 0) high else 0)
            when {
                i == 0 -> 0
                i < 8 -> argb(63, if ((i and 1) != 0) 255 else 0, if ((i and 2) != 0) 255 else 0, if ((i and 4) != 0) 255 else 0)
                else -> when (i and 0x88) {
                    0x00 -> argb(255, c(85, 170, 1), c(85, 170, 2), c(85, 170, 4))
                    0x08 -> argb(127, c(85, 170, 1), c(85, 170, 2), c(85, 170, 4))
                    0x80 -> argb(255, 127 + c(43, 85, 1), 127 + c(43, 85, 2), 127 + c(43, 85, 4))
                    else -> argb(255, c(43, 85, 1), c(43, 85, 2), c(43, 85, 4))
                }
            }
        }
        private val DEFAULT_CLUT = Clut()
    }
}

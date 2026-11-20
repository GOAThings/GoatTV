package cc.slokker.itv.ui.player.goatcore

import android.opengl.GLES20
import android.opengl.GLES30
import cc.slokker.itv.media.PictureLook
import java.nio.ByteBuffer

/**
 * O aumento da imagem do [VideoGpuStage] (Definições → Imagem → Aumentar a imagem; o modo de cada vídeo decide-se em
 * media/Upscaling). Lê a imagem de fora do estágio (o vídeo já desentrelaçado, no tamanho dele, com o y 0 em baixo) e:
 *
 * - [drawSharp] ("Nítido", GLES2): uma passagem só, no tamanho do ecrã, ao jeito do AMD FSR 1 (EASU) e do Snapdragon
 *   GSR. Lê os 12 píxeis do vídeo à volta (o 4×4 sem os cantos), tira deles a direção e a força da aresta, e pesa-os
 *   com um núcleo que se estica ao longo dela (uma aproximação do Lanczos 2), preso ao mínimo e ao máximo dos quatro do
 *   meio (sem halos). Depois um realce que se adapta ao contraste (como o CAS: forte onde há pouco, fraco junto do
 *   preto e do branco) e a cor (os ajustes da imagem). São 13 leituras por píxel do ecrã. Para as GPUs sem highp nos
 *   fragment shaders (Mali-400/450) as coordenadas de cada leitura vêm todas do vertex shader, e a posição dentro do
 *   píxel do vídeo (a fração) vem de uma textura de 256×256 que se repete ([phase]): a conta faz-se na unidade de
 *   texturas, com a precisão dela.
 * - [runAi] ("IA", GLES 3.0 com texturas de meia precisão): o FSRCNNX 8-0-4-1 (igv, LGPL-3.0; ver o ficheiro
 *   goatcore/FSRCNNX_x2_8-0-4-1.glsl nos recursos), uma rede convolucional pequena treinada em fotografias, que faz o Y
 *   2× maior. As passagens leem-se do ficheiro original tal como vem (o formato dos shaders do mpv) e passam a GLSL ES
 *   3.00 aqui ([AiPass]). A cor aumenta-se como sempre (bilinear): no fim, a cada píxel do vídeo aumentado soma-se a
 *   diferença entre o Y da rede e o Y da imagem bilinear (mudar o Y por igual nos três canais não mexe no Cb e no Cr).
 *
 * Tudo na thread e no contexto do [VideoGpuStage].
 */
internal class Upscaler(private val es3: Boolean) {
    /** A rede pode correr (GLES 3.0 com render para meia precisão, e compilou). */
    var aiReady: Boolean = es3 && !broken
        private set

    private var phase = 0
    private val sharp = HashMap<Boolean, Int>()

    private var passes: List<AiPass>? = null
    private var combine = 0
    private var lowW = 0
    private var lowH = 0
    private var floats = IntArray(0)
    private var floatFbos = IntArray(0)
    private var out = 0
    private var outFbo = 0

    /**
     * Desenha [source] ([w]×[h], a imagem de fora) aumentada no framebuffer que está ligado (o ecrã), com o viewport já
     * posto, com o realce [strength] e a cor de [look]. Falha (o shader não compila) com uma exceção.
     */
    fun drawSharp(source: Int, w: Int, h: Int, look: PictureLook, strength: Float, quad: (Int) -> Unit) {
        if (phase == 0) phase = phaseTexture()
        val colour = !look.neutral
        val program = sharp.getOrPut(colour) { VideoGpuStage.program(VERTEX_SHARP, fragmentSharp(colour)) }
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        // Cada leitura dá um píxel inteiro (a interpolação é a do núcleo).
        filter(GLES20.GL_NEAREST)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, phase)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "phase"), 1)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "texel"), 1f / w, 1f / h)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "size"), w.toFloat(), h.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "sharp"), strength)
        if (colour) {
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "colour"), 1, false, look.rgbMatrix(), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "bias"), look.bias)
        }
        quad(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
        filter(GLES20.GL_LINEAR)
    }

    /**
     * A rede sobre [source] ([w]×[h]): devolve a textura com a imagem 2× maior (RGBA de 8 bits, LINEAR), que fica até à
     * imagem seguinte. Sem GLES 3.0, ou se algo falhar (fica [aiReady] false para a sessão), uma exceção.
     */
    fun runAi(source: Int, w: Int, h: Int, quad: (Int) -> Unit): Int {
        check(aiReady) { "ai not available" }
        try {
            // Os erros que já lá estavam não são da rede.
            var stale = 0
            while (stale++ < 8 && GLES20.glGetError() != GLES20.GL_NO_ERROR) continue
            val list = passes ?: build().also { passes = it }
            if (w != lowW || h != lowH) allocate(list, w, h)
            for (p in list) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, floatFbos[p.output])
                GLES20.glViewport(0, 0, w, h)
                GLES20.glUseProgram(p.program)
                p.inputs.forEachIndexed { unit, input ->
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (input < 0) source else floats[input])
                    GLES20.glUniform1i(p.samplers[unit], unit)
                }
                GLES30.glUniform2i(p.lim, w - 1, h - 1)
                quad(p.program)
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outFbo)
            GLES20.glViewport(0, 0, w * 2, h * 2)
            GLES20.glUseProgram(combine)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floats[list.last().output])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(combine, "sub"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(combine, "pic"), 1)
            quad(combine)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            val error = GLES20.glGetError()
            check(error == GLES20.GL_NO_ERROR) { "gl error $error" }
            return out
        } catch (e: Throwable) {
            aiReady = false
            broken = true
            throw e
        }
    }

    /** As passagens da rede, compiladas, com a textura onde cada uma escreve e as que lê (ver [AiPass]). */
    private fun build(): List<AiPass> {
        val source = javaClass.getResourceAsStream(NETWORK)?.use { it.readBytes().decodeToString() } ?: error("no $NETWORK")
        val parsed = AiPass.parse(source)
        check(parsed.isNotEmpty()) { "no passes in $NETWORK" }
        combine = VideoGpuStage.program(VERTEX_COMBINE, FRAGMENT_COMBINE)
        return parsed.map { it.compile() }
    }

    /** As texturas de meia precisão (as mínimas: cada uma volta a servir quando já ninguém a lê) e a de saída. */
    private fun allocate(list: List<AiPass>, w: Int, h: Int) {
        freeTextures()
        val count = list.maxOf { it.output } + 1
        floats = IntArray(count) { VideoGpuStage.texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST) }
        floatFbos = IntArray(count) { i ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floats[i])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            VideoGpuStage.framebuffer(floats[i])
        }
        out = VideoGpuStage.texture(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w * 2, h * 2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        outFbo = VideoGpuStage.framebuffer(out)
        lowW = w
        lowH = h
    }

    private fun freeTextures() {
        if (floatFbos.isNotEmpty()) GLES20.glDeleteFramebuffers(floatFbos.size, floatFbos, 0)
        if (floats.isNotEmpty()) GLES20.glDeleteTextures(floats.size, floats, 0)
        if (outFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(outFbo), 0)
        if (out != 0) GLES20.glDeleteTextures(1, intArrayOf(out), 0)
        floats = IntArray(0)
        floatFbos = IntArray(0)
        out = 0
        outFbo = 0
        lowW = 0
        lowH = 0
    }

    /**
     * Uma passagem da rede. [inputs]: as texturas que lê, pela ordem dos BIND (-1 é a imagem de fora, o "LUMA" do
     * original); [output]: a textura onde escreve (índices nas de meia precisão).
     */
    class AiPass(val binds: List<String>, val save: String, val body: String) {
        var inputs = IntArray(0)
        var output = 0
        var program = 0
        var samplers = IntArray(0)
        var lim = -1

        /** O shader em GLSL ES 3.00: cada píxel é um do vídeo (gl_FragCoord), e cada leitura um texelFetch preso à borda. */
        fun source(): String = buildString {
            append("#version 300 es\nprecision mediump float;\nprecision highp int;\n")
            binds.forEach { append("uniform mediump sampler2D $it;\n") }
            append("uniform ivec2 lim;\nout vec4 fragOut;\nivec2 P;\n")
            // O y do original cresce para baixo; nas texturas daqui, para cima.
            binds.forEach { b ->
                val fetch = "texelFetch($b, clamp(P + ivec2(x, -y), ivec2(0), lim), 0)"
                if (b == LUMA) {
                    append("float T_$b(int x, int y) { return dot($fetch.rgb, vec3(0.2126, 0.7152, 0.0722)); }\n")
                } else {
                    append("vec4 T_$b(int x, int y) { return $fetch; }\n")
                }
            }
            append(
                body.replace(OFFSET) { "T_${it.groupValues[1]}(${it.groupValues[2]}, ${it.groupValues[3]})" }
                    .replace(CENTRE) { "T_${it.groupValues[1]}(0, 0)" },
            )
            append("\nvoid main() {\nP = ivec2(gl_FragCoord.xy);\nfragOut = hook();\n}\n")
        }

        fun compile(): AiPass = apply {
            program = VideoGpuStage.program(VERTEX_PASS, source())
            samplers = IntArray(binds.size) { GLES20.glGetUniformLocation(program, binds[it]) }
            lim = GLES20.glGetUniformLocation(program, "lim")
        }

        companion object {
            const val LUMA = "LUMA"
            private val OFFSET = Regex("""(\w+)_texOff\(vec2\((-?\d+),\s*(-?\d+)\)\)""")
            private val CENTRE = Regex("""(\w+)_texOff\(0\)""")

            /**
             * As passagens de um shader do mpv (//!HOOK … //!BIND … //!SAVE …, e o vec4 hook()), já com as texturas
             * atribuídas. A última do original (a "aggregation", sem SAVE, que junta os 4 subpíxeis) faz-se no
             * [FRAGMENT_COMBINE], com a cor.
             */
            fun parse(source: String): List<AiPass> {
                val list = source.split("//!HOOK").drop(1).mapNotNull { block ->
                    val lines = block.lines().drop(1)
                    val save = lines.firstOrNull { it.startsWith("//!SAVE ") }?.substringAfter("//!SAVE ")?.trim() ?: return@mapNotNull null
                    val binds = lines.filter { it.startsWith("//!BIND ") }.map { it.substringAfter("//!BIND ").trim() }
                    AiPass(binds, save, lines.filterNot { it.startsWith("//") }.joinToString("\n"))
                }
                assign(list)
                return list
            }

            /**
             * Cada valor guardado (o mesmo nome pode guardar-se mais do que uma vez) numa textura livre: livre é a que
             * tem um valor que nenhuma passagem a seguir lê. O último valor fica até ao fim (é o que o combine lê).
             */
            private fun assign(list: List<AiPass>) {
                val current = HashMap<String, Int>()
                val reads = ArrayList<IntArray>()
                val lastRead = ArrayList<Int>()
                list.forEachIndexed { i, p ->
                    reads += IntArray(p.binds.size) { b -> if (p.binds[b] == LUMA) -1 else current[p.binds[b]] ?: error("${p.binds[b]} read before saved") }
                    reads[i].filter { it >= 0 }.forEach { lastRead[it] = i }
                    current[p.save] = lastRead.size
                    lastRead += if (i == list.lastIndex) Int.MAX_VALUE else -1
                }
                val textureOf = IntArray(lastRead.size)
                val busy = ArrayList<Int>()
                list.forEachIndexed { i, p ->
                    val value = i
                    p.inputs = IntArray(reads[i].size) { b -> reads[i][b].let { if (it < 0) -1 else textureOf[it] } }
                    val taken = (0 until value).filter { lastRead[it] >= i }.map { textureOf[it] }.toSet()
                    val free = busy.firstOrNull { it !in taken }
                    textureOf[value] = free ?: busy.size.also { busy += it }
                    p.output = textureOf[value]
                }
            }
        }
    }

    internal companion object {
        /** O ficheiro da rede, tal como vem do original (ver o cabeçalho dele). */
        private const val NETWORK = "/goatcore/FSRCNNX_x2_8-0-4-1.glsl"

        /** A rede falhou neste aparelho: não se volta a tentar até a app fechar. */
        @Volatile var broken = false

        /** O "Nítido" falhou neste aparelho (o shader não compila): fica desligado até a app fechar. */
        @Volatile var sharpBroken = false

        fun filter(f: Int) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
        }

        /** A fração da posição dentro do píxel: 256×256, o x no L e o y no A, a repetir-se (ver [drawSharp]). */
        fun phaseTexture(): Int {
            val id = VideoGpuStage.texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST, GLES20.GL_REPEAT)
            val bytes = ByteBuffer.allocateDirect(256 * 256 * 2)
            for (y in 0 until 256) for (x in 0 until 256) {
                bytes.put(x.toByte())
                bytes.put(y.toByte())
            }
            bytes.position(0)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE_ALPHA, 256, 256, 0, GLES20.GL_LUMINANCE_ALPHA, GLES20.GL_UNSIGNED_BYTE, bytes)
            return id
        }

        // Os 12 píxeis à volta: o de baixo à esquerda dos quatro do meio é f (floor(p − ½)); b, c em cima (y − 1),
        // e, f, g, h, i, j, k, l nas duas linhas do meio, n, o em baixo (y + 2). Ler (uv − ½ píxel + d) com NEAREST dá
        // exatamente o píxel floor(p − ½) + d. ph: a posição em píxeis do vídeo menos ½ (a fração lê-se na [phase]).
        const val VERTEX_SHARP = """
            attribute vec2 position;
            uniform vec2 texel;
            uniform vec2 size;
            varying vec2 tb;
            varying vec2 tc;
            varying vec2 te;
            varying vec2 tf;
            varying vec2 tg;
            varying vec2 th;
            varying vec2 ti;
            varying vec2 tj;
            varying vec2 tk;
            varying vec2 tl;
            varying vec2 tn;
            varying vec2 to;
            varying vec2 ph;
            void main() {
                vec2 uv = (position + 1.0) * 0.5;
                vec2 o = uv - 0.5 * texel;
                tb = o + vec2(0.0, -1.0) * texel;
                tc = o + vec2(1.0, -1.0) * texel;
                te = o + vec2(-1.0, 0.0) * texel;
                tf = o;
                tg = o + vec2(1.0, 0.0) * texel;
                th = o + vec2(2.0, 0.0) * texel;
                ti = o + vec2(-1.0, 1.0) * texel;
                tj = o + vec2(0.0, 1.0) * texel;
                tk = o + vec2(1.0, 1.0) * texel;
                tl = o + vec2(2.0, 1.0) * texel;
                tn = o + vec2(0.0, 2.0) * texel;
                to = o + vec2(1.0, 2.0) * texel;
                ph = uv * size - 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        /**
         * O "Nítido" (ver [drawSharp]), com ou sem a cor ([colour]). As contas seguem as do FsrEasuF do FSR 1 (a
         * direção e o comprimento da aresta pelos quatro do meio, pesados pela posição; o núcleo esticado e o anel
         * preso), em mediump.
         */
        fun fragmentSharp(colour: Boolean): String = buildString {
            append(
                """
                precision mediump float;
                #ifdef GL_FRAGMENT_PRECISION_HIGH
                #define TC highp
                #else
                #define TC mediump
                #endif
                uniform sampler2D tex;
                uniform sampler2D phase;
                uniform float sharp;
                varying TC vec2 tb;
                varying TC vec2 tc;
                varying TC vec2 te;
                varying TC vec2 tf;
                varying TC vec2 tg;
                varying TC vec2 th;
                varying TC vec2 ti;
                varying TC vec2 tj;
                varying TC vec2 tk;
                varying TC vec2 tl;
                varying TC vec2 tn;
                varying TC vec2 to;
                varying TC vec2 ph;
                """.trimIndent(),
            )
            append('\n')
            if (colour) append("uniform mat3 colour;\nuniform float bias;\n")
            append(
                """
                float lum(vec3 c) { return c.g + 0.5 * (c.r + c.b); }
                void edge(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
                    float dc = lD - lC;
                    float cb = lC - lB;
                    float dirX = lD - lB;
                    float lenX = clamp(abs(dirX) / max(max(abs(dc), abs(cb)), 0.001), 0.0, 1.0);
                    float ec = lE - lC;
                    float ca = lC - lA;
                    float dirY = lE - lA;
                    float lenY = clamp(abs(dirY) / max(max(abs(ec), abs(ca)), 0.001), 0.0, 1.0);
                    dir += vec2(dirX, dirY) * w;
                    len += (lenX * lenX + lenY * lenY) * w;
                }
                void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len2, float lob, float clp, vec3 c) {
                    vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.y * dir.x - off.x * dir.y) * len2;
                    float d2 = min(dot(v, v), clp);
                    float wB = 0.4 * d2 - 1.0;
                    float wA = lob * d2 - 1.0;
                    wB *= wB;
                    wA *= wA;
                    float w = (1.5625 * wB - 0.5625) * wA;
                    aC += c * w;
                    aW += w;
                }
                void main() {
                    vec4 fr = texture2D(phase, ph);
                    vec2 pp = vec2(fr.r, fr.a) * (255.0 / 256.0) + 0.5 / 256.0;
                    vec3 b = texture2D(tex, tb).rgb;
                    vec3 c = texture2D(tex, tc).rgb;
                    vec3 e = texture2D(tex, te).rgb;
                    vec3 f = texture2D(tex, tf).rgb;
                    vec3 g = texture2D(tex, tg).rgb;
                    vec3 h = texture2D(tex, th).rgb;
                    vec3 i = texture2D(tex, ti).rgb;
                    vec3 j = texture2D(tex, tj).rgb;
                    vec3 k = texture2D(tex, tk).rgb;
                    vec3 l = texture2D(tex, tl).rgb;
                    vec3 n = texture2D(tex, tn).rgb;
                    vec3 o = texture2D(tex, to).rgb;
                    float bL = lum(b); float cL = lum(c); float eL = lum(e); float fL = lum(f);
                    float gL = lum(g); float hL = lum(h); float iL = lum(i); float jL = lum(j);
                    float kL = lum(k); float lL = lum(l); float nL = lum(n); float oL = lum(o);
                    vec2 dir = vec2(0.0);
                    float len = 0.0;
                    edge(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
                    edge(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
                    edge(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
                    edge(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);
                    float dirR = dot(dir, dir);
                    bool zro = dirR < 0.0001;
                    dir = zro ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
                    len = len * 0.5;
                    len *= len;
                    float stretch = 1.0 / max(abs(dir.x), abs(dir.y));
                    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
                    float lob = 0.5 - 0.29 * len;
                    float clp = 1.0 / lob;
                    vec3 aC = vec3(0.0);
                    float aW = 0.0;
                    tap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
                    tap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
                    tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
                    tap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
                    tap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
                    tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
                    tap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
                    tap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
                    tap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
                    tap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
                    tap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
                    tap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);
                    vec3 mn = min(min(f, g), min(j, k));
                    vec3 mx = max(max(f, g), max(j, k));
                    vec3 p = clamp(aC / max(aW, 0.0001), mn, mx);
                    // O realce: a diferença para a média bilinear dos quatro do meio, mais forte onde o contraste é pouco
                    // e longe do preto e do branco (como o CAS), sem passar o mínimo e o máximo deles.
                    vec3 mean = mix(mix(f, g, pp.x), mix(j, k, pp.x), pp.y);
                    vec3 amp = sqrt(clamp(min(mn, 1.0 - mx) / max(mx, vec3(0.004)), 0.0, 1.0));
                    p = clamp(p + sharp * amp * (p - mean), mn, mx);
                """.trimIndent(),
            )
            append('\n')
            if (colour) append("p = colour * p + bias;\n")
            append("gl_FragColor = vec4(p, 1.0);\n}\n")
        }

        const val VERTEX_PASS = """
            #version 300 es
            in vec2 position;
            void main() {
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        const val VERTEX_COMBINE = """
            #version 300 es
            in vec2 position;
            out highp vec2 uv;
            void main() {
                uv = (position + 1.0) * 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        // No tamanho 2×: o Y da rede (um dos 4 subpíxeis do píxel do vídeo, pela ordem do original: x * 2 + y, com o y
        // a crescer para baixo) e a cor da imagem bilinear, com o Y trocado pelo da rede.
        const val FRAGMENT_COMBINE = """
            #version 300 es
            precision mediump float;
            precision highp int;
            uniform mediump sampler2D sub;
            uniform mediump sampler2D pic;
            in highp vec2 uv;
            out vec4 fragOut;
            void main() {
                ivec2 q = ivec2(gl_FragCoord.xy);
                vec4 s = texelFetch(sub, q / 2, 0);
                int i = (q.x & 1) * 2 + (1 - (q.y & 1));
                float y = s[i];
                vec3 rgb = texture(pic, uv).rgb;
                float yb = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                fragOut = vec4(clamp(rgb + (y - yb), 0.0, 1.0), 1.0);
            }
        """
    }
}

package cc.slokker.itv.ui.player.goatcore

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import cc.slokker.itv.media.Deinterlacing
import cc.slokker.itv.media.InterlaceVerdict
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Desentrelaça na GPU o vídeo entrelaçado (os canais SD de cabo e satélite, o 1080i): o descodificador desenha numa
 * textura ([input]) e cada imagem vai para o ecrã ([output]) já sem o "pente" (as duas metades, captadas com 1/50 s de
 * diferença, mostradas juntas).
 *
 * [mode] ([Deinterlacing]): FIELDS, cada campo passa a ser uma imagem (50 por segundo num canal de 25), com as linhas
 * do outro campo interpoladas na direção da aresta (ELA: das três direções, a de cima-esquerda/baixo-direita, a
 * vertical e a outra diagonal, a em que as linhas vizinhas mais se parecem); BLEND, as duas misturam-se numa (½ a
 * linha, ¼ cada vizinha: 25 por segundo, mais leve).
 *
 * Duas passagens: a primeira desentrelaça no tamanho do vídeo (uma imagem de fora com o tamanho dele), a segunda só
 * estica para o ecrã. Numa TV 4K o trabalho pesado fica no 1080 (ou no 576), e as caixas fracas (Mali-450) aguentam os
 * 50 por segundo. As coordenadas de cada leitura vêm todas do vertex shader (os "varying" usados tal como vêm): nas
 * GPUs sem highp nos fragment shaders (Mali-400/450), contas com elas lá perdiam a linha certa num 1080.
 *
 * O descodificador pode já ter desentrelaçado (ver [InterlaceVerdict]): uma imagem em cada [MEASURE_EVERY] mede-se o
 * pente numa grelha de [COMB_W]×[COMB_H] pontos, e sem pente nenhum durante muito tempo mostra-se como vem.
 *
 * A hora de cada imagem é a que o descodificador lhe deu (releaseOutputBuffer com a hora): vai para o ecrã com
 * eglPresentationTimeANDROID, e o segundo campo meia imagem depois.
 */
internal class Deinterlacer(
    private val output: Surface,
    private val mode: Int,
    private val verdict: InterlaceVerdict,
    /** Viu-se pente: o descodificador dá os campos juntos (para os canais seguintes já começarem a saber). */
    private val onWoven: () -> Unit = {},
) : SurfaceTexture.OnFrameAvailableListener {
    private val thread = HandlerThread("goatcore-deinterlace").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var external = 0
    private var parity = 0
    private var deinterlace = 0
    private var comb = 0
    private var blit = 0
    private var picture = 0
    private var pictureFbo = 0
    private var pictureW = 0
    private var pictureH = 0
    private var combTexture = 0
    private var combFbo = 0
    private lateinit var texture2d: SurfaceTexture

    /** Onde o descodificador desenha. */
    lateinit var input: Surface
        private set

    /** O tamanho da imagem que se vê (sem o recorte), pelo formato de saída do descodificador. */
    @Volatile private var width = 0
    @Volatile private var height = 0

    fun setSize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    /** Como foi captada cada imagem enviada para o ecrã, pela hora dela (ver [frame]). */
    private class Frame(val stampNs: Long, val progressive: Boolean, val bottomFirst: Boolean)
    private val frames = ArrayDeque<Frame>()

    /**
     * A imagem que vai sair com a hora [stampNs] (a dada a releaseOutputBuffer) é progressiva, ou tem o campo de baixo
     * primeiro. Da thread do vídeo, logo antes de a mandar.
     */
    fun frame(stampNs: Long, progressive: Boolean, bottomFirst: Boolean) {
        synchronized(frames) {
            frames.addLast(Frame(stampNs, progressive, bottomFirst))
            while (frames.size > 16) frames.removeFirst()
        }
    }

    /** A da imagem com a hora [stampNs]: a que tem essa hora, ou a mais antiga (as anteriores já não vêm). */
    private fun takeFrame(stampNs: Long): Frame? = synchronized(frames) {
        while (frames.size > 1 && frames.first().stampNs < stampNs - 1_000_000L) frames.removeFirst()
        frames.removeFirstOrNull()
    }

    private val matrix = FloatArray(16)
    private var lastStampNs = 0L
    private var fieldNs = 20_000_000L
    private var drawn = 0L
    private var released = false
    private val combPixels: ByteBuffer = ByteBuffer.allocateDirect(COMB_W * COMB_H * 4).order(ByteOrder.nativeOrder())

    /** Para o registo: imagens desentrelaçadas e mostradas como vinham. */
    @Volatile var deinterlaced = 0L
        private set
    @Volatile var passedThrough = 0L
        private set

    init {
        val ready = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                setUp()
            } catch (e: Throwable) {
                error = e
            }
            ready.countDown()
        }
        awaitUninterrupted(ready)
        error?.let {
            release()
            throw it
        }
    }

    private fun setUp() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "eglChooseConfig" }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent" }

        // A textura do descodificador: cada leitura dá uma linha inteira (NEAREST), nunca a mistura de duas (de campos
        // diferentes); a interpolação para o ecrã é na segunda passagem.
        external = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_NEAREST)
        // As linhas pares e ímpares: uma textura de 1×2 que se repete (0 numa linha, 1 na seguinte).
        parity = texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST, GLES20.GL_REPEAT)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 2, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(2).put(0, 0.toByte()).put(1, 0xFF.toByte()))
        combTexture = texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, COMB_W, COMB_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        combFbo = framebuffer(combTexture)

        deinterlace = program(VERTEX_DEINTERLACE, FRAGMENT_DEINTERLACE)
        comb = program(VERTEX_COMB, FRAGMENT_COMB)
        blit = program(VERTEX_BLIT, FRAGMENT_BLIT)

        texture2d = SurfaceTexture(external)
        texture2d.setOnFrameAvailableListener(this, handler)
        input = Surface(texture2d)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (released) return
        try {
            texture2d.updateTexImage()
            texture2d.getTransformMatrix(matrix)
            val stamp = texture2d.timestamp
            val frame = takeFrame(stamp)
            var at = stamp
            val now = System.nanoTime()
            // A primeira imagem sai já (vem com a hora do vídeo, não a do relógio).
            if (at <= 0 || at - now > 1_000_000_000L || now - at > 1_000_000_000L) at = now
            if (lastStampNs > 0) {
                val half = (at - lastStampNs) / 2
                if (half in 8_000_000L..30_000_000L) fieldNs = half
            }
            lastStampNs = at
            val w = width.takeIf { it > 0 } ?: return
            val h = height.takeIf { it > 0 } ?: return
            if (w != pictureW || h != pictureH) resize(w, h)
            if (verdict.measuring && drawn++ % MEASURE_EVERY == 0L) measure()
            val progressive = frame?.progressive == true
            if (!verdict.deinterlace || progressive) {
                passedThrough++
                draw(MODE_COPY, 0f, at)
                return
            }
            deinterlaced++
            if (mode == Deinterlacing.FIELDS) {
                val first = if (frame?.bottomFirst == true) 1f else 0f
                draw(MODE_FIELD, first, at)
                draw(MODE_FIELD, 1f - first, at + fieldNs)
            } else {
                draw(MODE_BLEND, 0f, at)
            }
        } catch (e: Exception) {
            Log.w(TAG, "deinterlace: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** A imagem de fora (o tamanho do vídeo) onde se desentrelaça. */
    private fun resize(w: Int, h: Int) {
        if (pictureFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(pictureFbo), 0)
        if (picture != 0) GLES20.glDeleteTextures(1, intArrayOf(picture), 0)
        picture = texture(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        pictureFbo = framebuffer(picture)
        pictureW = w
        pictureH = h
    }

    /** Mede o pente desta imagem (ver [InterlaceVerdict]): a grelha pequena desenha-se e lê-se de volta. */
    private fun measure() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, combFbo)
        GLES20.glViewport(0, 0, COMB_W, COMB_H)
        GLES20.glUseProgram(comb)
        bindExternal(comb)
        quad(comb)
        combPixels.clear()
        GLES20.glReadPixels(0, 0, COMB_W, COMB_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, combPixels)
        var combed = 0
        var detail = 0
        for (i in 0 until COMB_W * COMB_H) {
            if ((combPixels.get(i * 4).toInt() and 0xFF) > 127) combed++
            if ((combPixels.get(i * 4 + 1).toInt() and 0xFF) > 127) detail++
        }
        val before = verdict.deinterlace
        val wasWoven = verdict.woven
        verdict.sample(combed, detail, COMB_W * COMB_H)
        if (verdict.woven && !wasWoven) {
            Log.i(TAG, "deinterlace: the decoder output is interlaced (combed=$combed detail=$detail)")
            onWoven()
        } else if (before != verdict.deinterlace) {
            Log.i(TAG, "deinterlace: ${if (verdict.deinterlace) "combing seen, deinterlacing again" else "no combing for a long time, showing as is"}")
        }
    }

    /** [kind]: MODE_COPY, MODE_FIELD (o campo [field]: 0 = linhas pares, 1 = ímpares) ou MODE_BLEND; para o ecrã às [atNs]. */
    private fun draw(kind: Float, field: Float, atNs: Long) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, pictureFbo)
        GLES20.glViewport(0, 0, pictureW, pictureH)
        GLES20.glUseProgram(deinterlace)
        bindExternal(deinterlace)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, parity)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(deinterlace, "parity"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(deinterlace, "field"), field)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(deinterlace, "mode"), kind)
        quad(deinterlace)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val size = IntArray(2)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, size, 1)
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glUseProgram(blit)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, picture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blit, "picture"), 0)
        quad(blit)
        EGLExt.eglPresentationTimeANDROID(display, window, atNs)
        EGL14.eglSwapBuffers(display, window)
    }

    /** A textura do descodificador na unidade 0, com a transformação dela e o tamanho de uma linha e de uma coluna. */
    private fun bindExternal(program: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, external)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "st"), 1, false, matrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "texel"), 1f / pictureW, 1f / pictureH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "lines"), pictureH.toFloat())
    }

    private fun quad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "position")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    /** Larga tudo (a superfície do ecrã fica livre para o canal seguinte). Depois de parar o descodificador. */
    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            released = true
            runCatching { if (::input.isInitialized) input.release() }
            runCatching { if (::texture2d.isInitialized) texture2d.release() }
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            done.countDown()
        }
        // Quem fecha vem muitas vezes de uma thread interrompida (stop): espera-se na mesma, senão o descodificador do
        // canal seguinte ia ligar-se à superfície antes de o EGL a largar.
        awaitUninterrupted(done)
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "GoatCore"

        const val MODE_COPY = 0f
        const val MODE_FIELD = 1f
        const val MODE_BLEND = 2f

        /** A grelha onde se mede o pente: 2304 pontos. */
        const val COMB_W = 64
        const val COMB_H = 36
        const val MEASURE_EVERY = 8L

        fun awaitUninterrupted(latch: CountDownLatch) {
            var interrupted = false
            while (true) {
                try {
                    latch.await()
                    break
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }

        fun texture(target: Int, filter: Int, wrap: Int = GLES20.GL_CLAMP_TO_EDGE): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, wrap)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, wrap)
            return ids[0]
        }

        fun framebuffer(texture: Int): Int {
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[0])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "framebuffer $status" }
            return ids[0]
        }

        /** O ecrã inteiro (dois triângulos). */
        val QUAD: ByteBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        }

        // uv: 0..1 na imagem, com o y 0 em baixo (como no GL); a imagem de fora fica assim, direita. As coordenadas
        // na textura do descodificador passam pela transformação dela (o recorte e o y ao contrário). n/s: a linha
        // de cima e a de baixo (na imagem, r − 1 e r + 1); w/e: a coluna à esquerda e à direita.
        const val VERTEX_DEINTERLACE = """
            attribute vec2 position;
            uniform mat4 st;
            uniform vec2 texel;
            uniform float lines;
            varying vec2 c;
            varying vec2 n;
            varying vec2 s;
            varying vec2 nw;
            varying vec2 ne;
            varying vec2 sw;
            varying vec2 se;
            varying vec2 row;
            vec2 at(vec2 uv) { return (st * vec4(uv, 0.0, 1.0)).xy; }
            void main() {
                vec2 uv = (position + 1.0) * 0.5;
                c = at(uv);
                n = at(uv + vec2(0.0, texel.y));
                s = at(uv - vec2(0.0, texel.y));
                nw = at(uv + vec2(-texel.x, texel.y));
                ne = at(uv + vec2(texel.x, texel.y));
                sw = at(uv - vec2(texel.x, texel.y));
                se = at(uv + vec2(texel.x, -texel.y));
                // A linha da imagem (0 em cima) a dividir por 2: a textura de 1×2 diz se é par ou ímpar.
                row = vec2(0.5, (1.0 - uv.y) * lines * 0.5);
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        // mode 0: a linha como vem. 1: o campo [field] fica, as linhas do outro interpolam-se (ELA, presa entre a de
        // cima e a de baixo para não inventar). 2: a linha com as vizinhas (½ e ¼ cada).
        const val FRAGMENT_DEINTERLACE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES tex;
            uniform sampler2D parity;
            uniform float field;
            uniform float mode;
            varying vec2 c;
            varying vec2 n;
            varying vec2 s;
            varying vec2 nw;
            varying vec2 ne;
            varying vec2 sw;
            varying vec2 se;
            varying vec2 row;
            void main() {
                vec4 m = texture2D(tex, c);
                if (mode < 0.5) {
                    gl_FragColor = m;
                    return;
                }
                vec4 a = texture2D(tex, n);
                vec4 b = texture2D(tex, s);
                if (mode > 1.5) {
                    gl_FragColor = 0.5 * m + 0.25 * (a + b);
                    return;
                }
                if (abs(texture2D(parity, row).r - field) < 0.5) {
                    gl_FragColor = m;
                    return;
                }
                vec4 a0 = texture2D(tex, nw);
                vec4 a2 = texture2D(tex, ne);
                vec4 b0 = texture2D(tex, sw);
                vec4 b2 = texture2D(tex, se);
                vec3 l = vec3(0.299, 0.587, 0.114);
                float dl = abs(dot(a0.rgb - b2.rgb, l));
                float dc = abs(dot(a.rgb - b.rgb, l));
                float dr = abs(dot(a2.rgb - b0.rgb, l));
                vec4 o = 0.5 * (a + b);
                if (dl < dc && dl <= dr) {
                    o = 0.5 * (a0 + b2);
                } else if (dr < dc && dr < dl) {
                    o = 0.5 * (a2 + b0);
                }
                gl_FragColor = clamp(o, min(a, b), max(a, b));
            }
        """

        // A grelha do pente: cada ponto lê a linha, as duas vizinhas (do outro campo) e as duas a seguir (do mesmo).
        const val VERTEX_COMB = """
            attribute vec2 position;
            uniform mat4 st;
            uniform vec2 texel;
            varying vec2 c;
            varying vec2 n;
            varying vec2 s;
            varying vec2 n2;
            varying vec2 s2;
            vec2 at(vec2 uv) { return (st * vec4(uv, 0.0, 1.0)).xy; }
            void main() {
                vec2 uv = (position + 1.0) * 0.5;
                c = at(uv);
                n = at(uv + vec2(0.0, texel.y));
                s = at(uv - vec2(0.0, texel.y));
                n2 = at(uv + vec2(0.0, 2.0 * texel.y));
                s2 = at(uv - vec2(0.0, 2.0 * texel.y));
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        // Vermelho: pente (a linha foge às duas vizinhas para o mesmo lado e é igual às do mesmo campo). Verde:
        // detalhe na vertical (uma aresta), que conta tanto numa imagem entrelaçada como numa já desentrelaçada.
        const val FRAGMENT_COMB = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES tex;
            varying vec2 c;
            varying vec2 n;
            varying vec2 s;
            varying vec2 n2;
            varying vec2 s2;
            void main() {
                vec3 l = vec3(0.299, 0.587, 0.114);
                float y = dot(texture2D(tex, c).rgb, l);
                float d1 = y - dot(texture2D(tex, n).rgb, l);
                float d2 = y - dot(texture2D(tex, s).rgb, l);
                float same = abs(y - dot(texture2D(tex, n2).rgb, l)) + abs(y - dot(texture2D(tex, s2).rgb, l));
                float vertical = abs(d1) + abs(d2);
                float detail = step(0.12, vertical);
                float combed = step(0.0036, d1 * d2) * step(same, 0.5 * vertical) * detail;
                gl_FragColor = vec4(combed, detail, 0.0, 1.0);
            }
        """

        const val VERTEX_BLIT = """
            attribute vec2 position;
            varying vec2 uv;
            void main() {
                uv = (position + 1.0) * 0.5;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        const val FRAGMENT_BLIT = """
            precision mediump float;
            uniform sampler2D picture;
            varying vec2 uv;
            void main() {
                gl_FragColor = texture2D(picture, uv);
            }
        """

        fun program(vertex: String, fragment: String): Int {
            fun shader(type: Int, source: String): Int {
                val s = GLES20.glCreateShader(type)
                GLES20.glShaderSource(s, source.trimIndent())
                GLES20.glCompileShader(s)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: ${GLES20.glGetShaderInfoLog(s)}" }
                return s
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }
    }
}

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
import cc.slokker.itv.media.AutoCrop
import cc.slokker.itv.media.CropRect
import cc.slokker.itv.media.Deinterlacing
import cc.slokker.itv.media.InterlaceVerdict
import cc.slokker.itv.media.PictureLook
import cc.slokker.itv.media.ToneMapping
import cc.slokker.itv.media.UpscaleGovernor
import cc.slokker.itv.media.UpscaleMemory
import cc.slokker.itv.media.Upscaling
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * O vídeo que passa pela GPU antes do ecrã: o descodificador desenha numa textura ([input]) e cada imagem vai para o
 * ecrã ([output]) desentrelaçada e/ou com os ajustes da imagem. Só existe quando há alguma coisa a fazer (um canal
 * entrelaçado com Definições → Desentrelaçar ligado, ou algum ajuste de Definições → Imagem fora do 0): com tudo no
 * padrão o descodificador desenha direto no ecrã, como sempre. O HDR e os 10 bits só passam por aqui para o
 * [toneMap] (o resto é GLES2 de 8 bits).
 *
 * HDR numa TV SDR ([toneMap], ver media/ToneMapping): quando o descodificador não converte o HDR10/HLG para SDR, a
 * última passagem faz a conversão (uma passagem, do descodificador para o ecrã, em highp; sem highp nos fragment
 * shaders a compilação falha logo ao criar o estágio, e o vídeo vai direto como antes). Assume-se que a textura do
 * descodificador dá o RGB do BT.2020 ainda com a curva do sinal (PQ ou HLG): o amostrador externo só faz o YUV → RGB.
 * Só os ajustes de cor se somam (depois da conversão); sem desentrelaçar, sem aumentar e sem a nitidez.
 *
 * Desentrelaçar: os canais SD de cabo e satélite e o 1080i saem sem o "pente" (as duas metades, captadas com 1/50 s
 * de diferença, mostradas juntas).
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
 * Os ajustes da imagem ([pictureLook], lido a cada imagem: mudam sem reabrir o canal) fazem-se na última passagem, a que
 * desenha no ecrã: a nitidez (a diferença para a média dos quatro vizinhos, no tamanho do vídeo) e depois a cor (uma
 * matriz e uma soma, ver [PictureLook]). Sem nada a desentrelaçar ([mode] NONE) é a única passagem: a textura do
 * descodificador vai direta para o ecrã. Cada variante do shader só tem o que usa (sem ajustes, a passagem é a cópia
 * de sempre), e as coordenadas dos vizinhos vêm também do vertex shader.
 *
 * A hora de cada imagem é a que o descodificador lhe deu (releaseOutputBuffer com a hora): vai para o ecrã com
 * eglPresentationTimeANDROID, e o segundo campo meia imagem depois.
 *
 * Aumentar a imagem ([upscale], Definições → Imagem; ver [Upscaler] e media/Upscaling): pela ordem desentrelaçar →
 * aumentar → ajustes → ecrã, cada passagem no tamanho de saída dela. O vídeo passa sempre pela imagem de fora (a
 * cópia, se não houver nada a desentrelaçar), que se aumenta para o tamanho da superfície do ecrã (a EGL; o leitor
 * põe-na do tamanho do ecrã a sério, ver GoatCoreMediaPlayer). O modo de cada tamanho de vídeo e de superfície
 * decide-se aqui, e mede-se cada imagem (o tempo da GPU, com glFinish): se não acompanhar ([UpscaleGovernor]), desce
 * de "IA" para "Nítido" e de "Nítido" para nada, e a resolução fica assim para o resto da sessão ([upscaleMemory]).
 * A "IA" precisa de um contexto GLES 3.0 com render para meia precisão: pede-se um quando a definição a quer, e sem
 * ele fica o "Nítido".
 *
 * Cortar as barras pretas ([crop], Definições → Cortar barras pretas; ver media/AutoCrop): a última passagem lê só o
 * rectângulo pedido do quadro (as coordenadas, no vertex shader; não custa nada a mais), e quem mostra põe a vista com
 * a proporção desse rectângulo. Para se saber onde estão as barras, mais ou menos uma vez por segundo
 * ([AutoCrop.PROBE_MS]) a textura do descodificador desenha-se numa grelha de [AutoCrop.GRID_COLS]×[AutoCrop.GRID_ROWS]
 * só com o brilho, que se lê de volta ([onLuma]) depois de a imagem ir para o ecrã (36 KB por segundo).
 */
internal class VideoGpuStage(
    private val output: Surface,
    /** [Deinterlacing]: FIELDS, BLEND, ou NONE (só os ajustes da imagem). */
    private val mode: Int,
    private val verdict: InterlaceVerdict,
    /** Os ajustes da imagem neste momento (Definições → Imagem). */
    private val pictureLook: () -> PictureLook = { PictureLook() },
    /** Viu-se pente: o descodificador dá os campos juntos (para os canais seguintes já começarem a saber). */
    private val onWoven: () -> Unit = {},
    /** Aumentar a imagem (Prefs.Upscale): o que a definição pede para este vídeo (já de 8 bits SDR). */
    private val upscale: String = cc.slokker.itv.data.Prefs.Upscale.OFF,
    /** O que cada resolução aguentou nesta sessão (sincroniza-se nela). */
    private val upscaleMemory: UpscaleMemory = UpscaleMemory(),
    /** [ToneMapping]: PQ ou HLG para converter o HDR para SDR aqui, ou NONE. */
    private val toneMap: Int = ToneMapping.NONE,
    /** Cortar as barras pretas: o rectângulo do quadro a mostrar agora (lido a cada imagem); null = desligado. */
    private val crop: (() -> CropRect)? = null,
    /** A grelha do brilho (o Y de 0 a 255, de cima para baixo, [AutoCrop.GRID_COLS]×[AutoCrop.GRID_ROWS]), nesta thread. */
    private val onLuma: ((ByteArray) -> Unit)? = null,
) : SurfaceTexture.OnFrameAvailableListener {
    private val thread = HandlerThread("goatcore-video-gpu").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var external = 0
    private var parity = 0
    private var deinterlace = 0
    private var comb = 0
    /** As variantes da última passagem já compiladas (ver [finalProgram]). */
    private val finals = HashMap<Int, Int>()
    private var picture = 0
    private var pictureFbo = 0
    private var pictureW = 0
    private var pictureH = 0
    private var combTexture = 0
    private var combFbo = 0
    private var probe = 0
    private var probeTexture = 0
    private var probeFbo = 0
    private var lastProbeNs = 0L
    private val probePixels: ByteBuffer? = onLuma?.let { ByteBuffer.allocateDirect(AutoCrop.GRID_COLS * AutoCrop.GRID_ROWS * 4).order(ByteOrder.nativeOrder()) }
    private lateinit var texture2d: SurfaceTexture

    /** O aumento da imagem (só com a definição ligada); [es3]: o contexto é GLES 3.0. */
    private var upscaler: Upscaler? = null
    private var es3 = false
    private var governor: UpscaleGovernor? = null
    /** O vídeo e a superfície para que se decidiu o modo do [governor]. */
    private val upscaleFor = IntArray(4)
    /** O tempo da GPU na imagem que se está a desenhar (as duas, nos campos), e quando começou a passagem atual. */
    private var busyNs = 0L
    private var passStartNs = 0L
    private val surfaceSize = IntArray(2)

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
        // A "IA" do aumento precisa do GLES 3.0: pede-se esse contexto primeiro; sem ele, o de sempre (GLES2).
        var config: EGLConfig? = null
        if (upscale == cc.slokker.itv.data.Prefs.Upscale.AI && !Upscaler.broken) {
            config = chooseConfig(EGLExt.EGL_OPENGL_ES3_BIT_KHR)
            if (config != null) {
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                es3 = context != EGL14.EGL_NO_CONTEXT
            }
            if (!es3) Log.i(TAG, "upscale: no OpenGL ES 3.0 context, ai is not available (sharp instead)")
        }
        if (!es3) {
            config = chooseConfig(EGL14.EGL_OPENGL_ES2_BIT)
            checkNotNull(config) { "eglChooseConfig" }
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        }
        checkNotNull(config)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent" }

        // A textura do descodificador: cada leitura dá uma linha inteira (NEAREST), nunca a mistura de duas (de campos
        // diferentes); a interpolação para o ecrã é na segunda passagem. Sem desentrelaçar, vai direta para o ecrã e
        // é ela que se interpola.
        external = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, if (mode == Deinterlacing.NONE) GLES20.GL_LINEAR else GLES20.GL_NEAREST)
        // As linhas pares e ímpares: uma textura de 1×2 que se repete (0 numa linha, 1 na seguinte).
        parity = texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST, GLES20.GL_REPEAT)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 2, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(2).put(0, 0.toByte()).put(1, 0xFF.toByte()))
        combTexture = texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, COMB_W, COMB_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        combFbo = framebuffer(combTexture)

        deinterlace = program(VERTEX_DEINTERLACE, FRAGMENT_DEINTERLACE)
        // As duas variantes da conversão do HDR já aqui: se a GPU não as compilar (sem highp), não há estágio.
        if (toneMap != ToneMapping.NONE) {
            finalProgram(true, LEVEL_COPY)
            finalProgram(true, LEVEL_COLOUR)
        }
        comb = program(VERTEX_COMB, FRAGMENT_COMB)
        if (onLuma != null) {
            probeTexture = texture(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, AutoCrop.GRID_COLS, AutoCrop.GRID_ROWS, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            probeFbo = framebuffer(probeTexture)
            probe = program(VERTEX_PROBE, FRAGMENT_PROBE)
        }

        texture2d = SurfaceTexture(external)
        texture2d.setOnFrameAvailableListener(this, handler)
        input = Surface(texture2d)

        if (upscale != cc.slokker.itv.data.Prefs.Upscale.OFF) {
            // A rede escreve em texturas de meia precisão: o GLES 3.2 já o faz, o 3.0 e o 3.1 com a extensão.
            val version = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
            val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
            val halfFloat = "GL_EXT_color_buffer_half_float" in extensions || "GL_EXT_color_buffer_float" in extensions || "OpenGL ES 3.2" in version
            if (es3 && !halfFloat) Log.i(TAG, "upscale: $version has no half-float render targets, ai is not available (sharp instead)")
            upscaler = Upscaler(es3 && halfFloat)
        }
    }

    private fun chooseConfig(renderable: Int): EGLConfig? {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, renderable, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        return if (EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) configs[0] else null
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
            val look = pictureLook()
            EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, surfaceSize, 0)
            EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, surfaceSize, 1)
            val up = upscaleMode(w, h, surfaceSize[0], surfaceSize[1])
            busyNs = 0L
            if (mode == Deinterlacing.NONE && up == Upscaling.OFF) {
                // Só os ajustes: uma passagem, do descodificador para o ecrã.
                pictureW = w
                pictureH = h
                passedThrough++
                present(look, fromDecoder = true, at, Upscaling.OFF)
                probeIfDue()
                return
            }
            if (w != pictureW || h != pictureH) resize(w, h)
            if (mode != Deinterlacing.NONE && verdict.measuring && drawn++ % MEASURE_EVERY == 0L) measure()
            val progressive = frame?.progressive == true
            if (mode == Deinterlacing.NONE || !verdict.deinterlace || progressive) {
                passedThrough++
                draw(MODE_COPY, 0f, at, look, up)
            } else {
                deinterlaced++
                if (mode == Deinterlacing.FIELDS) {
                    val first = if (frame?.bottomFirst == true) 1f else 0f
                    draw(MODE_FIELD, first, at, look, up)
                    draw(MODE_FIELD, 1f - first, at + fieldNs, look, up)
                } else {
                    draw(MODE_BLEND, 0f, at, look, up)
                }
            }
            if (up != Upscaling.OFF) govern(w, h)
            probeIfDue()
        } catch (e: Exception) {
            Log.w(TAG, "video gpu: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * O modo do aumento para o vídeo [w]×[h] na superfície [sw]×[sh]: decide-se de novo quando um dos dois muda (ver
     * Upscaling.mode), e daí em diante é o do [governor].
     */
    private fun upscaleMode(w: Int, h: Int, sw: Int, sh: Int): Int {
        val u = upscaler ?: return Upscaling.OFF
        if (governor == null || upscaleFor[0] != w || upscaleFor[1] != h || upscaleFor[2] != sw || upscaleFor[3] != sh) {
            upscaleFor[0] = w
            upscaleFor[1] = h
            upscaleFor[2] = sw
            upscaleFor[3] = sh
            val ceiling = synchronized(upscaleMemory) { upscaleMemory.ceiling(w, h) }
            val m = Upscaling.mode(upscale, w, h, sw, sh, aiCapable = u.aiReady, ceiling = if (Upscaler.sharpBroken) Upscaling.OFF else ceiling)
            val why = when {
                m == Upscaling.requested(upscale) -> ""
                ceiling < Upscaling.requested(upscale) && m == ceiling -> " (this resolution could not keep up before)"
                Upscaling.scale(w, h, sw, sh) < Upscaling.MIN_SCALE -> " (the video is not smaller than the surface)"
                !u.aiReady -> " (no GPU support for ai)"
                else -> " (too small a difference for ai)"
            }
            Log.i(TAG, "upscale: ${Upscaling.name(m)} for ${w}x$h on a ${sw}x$sh surface$why")
            governor = UpscaleGovernor(m)
        }
        return governor?.mode ?: Upscaling.OFF
    }

    /** Conta a imagem que acabou de sair no [governor] (o tempo da GPU e o que tinha, o intervalo entre imagens). */
    private fun govern(w: Int, h: Int) {
        val g = governor ?: return
        val before = g.mode
        if (!g.frame(System.nanoTime() / 1_000_000, busyNs / 1000, fieldNs * 2 / 1000)) return
        synchronized(upscaleMemory) { upscaleMemory.lower(w, h, g.mode) }
        Log.i(TAG, "upscale: ${Upscaling.name(before)} cannot keep up with ${w}x$h (late ${g.lastLate}/${g.lastFrames} frames in a second), " +
            "${Upscaling.name(g.mode)} for this resolution from now on")
    }

    /** O aumento [up] falhou nesta GPU ([e]): desce um modo já (a rede não volta a tentar-se nesta sessão). */
    private fun upscaleFailed(up: Int, e: Throwable) {
        val g = governor ?: return
        if (up == Upscaling.SHARP) Upscaler.sharpBroken = true
        g.down()
        Log.w(TAG, "upscale: ${Upscaling.name(up)} failed on this GPU (${e.javaClass.simpleName} ${e.message}), ${Upscaling.name(g.mode)} instead")
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

    /**
     * A grelha do brilho desta imagem para o [onLuma], se já passou [AutoCrop.PROBE_MS] desde a última. Depois do
     * swap: a leitura espera só pela grelha (e pela imagem que já foi para o ecrã), não atrasa a imagem.
     */
    private fun probeIfDue() {
        val callback = onLuma ?: return
        val pixels = probePixels ?: return
        val now = System.nanoTime()
        if (now - lastProbeNs < AutoCrop.PROBE_MS * 1_000_000L) return
        lastProbeNs = now
        val cols = AutoCrop.GRID_COLS
        val rows = AutoCrop.GRID_ROWS
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, probeFbo)
        GLES20.glViewport(0, 0, cols, rows)
        GLES20.glUseProgram(probe)
        bindExternal(probe)
        quad(probe)
        pixels.clear()
        GLES20.glReadPixels(0, 0, cols, rows, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // A primeira linha lida é a de baixo da imagem (o y do GL): a grelha vai de cima para baixo.
        val luma = ByteArray(cols * rows)
        for (r in 0 until rows) {
            val from = (rows - 1 - r) * cols
            for (c in 0 until cols) luma[r * cols + c] = pixels.get((from + c) * 4)
        }
        callback(luma)
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

    /**
     * [kind]: MODE_COPY, MODE_FIELD (o campo [field]: 0 = linhas pares, 1 = ímpares) ou MODE_BLEND; para o ecrã às
     * [atNs], aumentada por [up].
     */
    private fun draw(kind: Float, field: Float, atNs: Long, look: PictureLook, up: Int) {
        passStartNs = System.nanoTime()
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

        present(look, fromDecoder = false, atNs, up)
    }

    /**
     * A última passagem: a imagem desentrelaçada (ou, com [fromDecoder], a textura do descodificador) para o ecrã às
     * [atNs], com os ajustes [look] (a nitidez e a cor). Com o aumento [up]: a "IA" faz primeiro a imagem 2× (que esta
     * passagem estica para o ecrã, com os ajustes), o "Nítido" é ele próprio esta passagem (com a cor, e a nitidez dos
     * ajustes somada ao realce dele).
     */
    private fun present(look: PictureLook, fromDecoder: Boolean, atNs: Long, up: Int) {
        var source = picture
        var sourceW = pictureW
        var sourceH = pictureH
        // O modo pode ter descido nesta mesma imagem (o primeiro campo falhou).
        var upMode = if (fromDecoder) Upscaling.OFF else minOf(up, governor?.mode ?: Upscaling.OFF)
        if (upMode == Upscaling.AI) {
            try {
                source = upscaler!!.runAi(picture, pictureW, pictureH, ::quad)
                sourceW = pictureW * 2
                sourceH = pictureH * 2
            } catch (e: Throwable) {
                upscaleFailed(Upscaling.AI, e)
                upMode = governor?.mode ?: Upscaling.OFF
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceSize[0], surfaceSize[1])
        val window = cropUniform()
        if (upMode == Upscaling.SHARP) {
            try {
                upscaler!!.drawSharp(picture, pictureW, pictureH, look, SHARP_STRENGTH + look.sharpen, window, ::quad)
                swap(atNs, timed = true)
                return
            } catch (e: Throwable) {
                upscaleFailed(Upscaling.SHARP, e)
                upMode = Upscaling.OFF
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(0, 0, surfaceSize[0], surfaceSize[1])
            }
        }
        val level = if (look.neutral) LEVEL_COPY else if (look.sharpness == 0 || toneMap != ToneMapping.NONE) LEVEL_COLOUR else LEVEL_SHARP
        val program = finalProgram(fromDecoder, level)
        GLES20.glUseProgram(program)
        if (fromDecoder) {
            bindExternal(program)
        } else {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, source)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "st"), 1, false, IDENTITY, 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "texel"), 1f / sourceW, 1f / sourceH)
        }
        if (level >= LEVEL_COLOUR) {
            GLES20.glUniformMatrix3fv(GLES20.glGetUniformLocation(program, "colour"), 1, false, look.rgbMatrix(), 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "bias"), look.bias)
        }
        if (level >= LEVEL_SHARP) GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "sharp"), look.sharpen)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(program, "crop"), 1, window, 0)
        quad(program)
        swap(atNs, timed = up != Upscaling.OFF)
    }

    /**
     * Para o ecrã às [atNs]. [timed]: com o aumento, espera-se pela GPU antes (glFinish) e conta-se o tempo desde o
     * início da passagem ([busyNs], ver [govern]): o eglSwapBuffers pode esperar pelo ecrã, e isso não é atraso da GPU.
     */
    private fun swap(atNs: Long, timed: Boolean) {
        if (timed) {
            GLES20.glFinish()
            busyNs += System.nanoTime() - passStartNs
        }
        EGLExt.eglPresentationTimeANDROID(display, window, atNs)
        EGL14.eglSwapBuffers(display, window)
    }

    private val cropValues = FloatArray(4)

    /**
     * O rectângulo a mostrar ([crop]) para o vertex shader da última passagem: (x, y, largura, altura) nas coordenadas
     * da textura, com o y 0 em baixo; sem corte, a imagem toda.
     */
    private fun cropUniform(): FloatArray {
        val r = crop?.invoke()?.takeUnless { it.isFull } ?: CropRect.FULL
        cropValues[0] = r.left
        cropValues[1] = 1f - r.bottom
        cropValues[2] = r.width
        cropValues[3] = r.height
        return cropValues
    }

    /** A variante da última passagem: de onde lê ([fromDecoder] ou a imagem de fora) e o que faz ([level]). */
    private fun finalProgram(fromDecoder: Boolean, level: Int): Int =
        finals.getOrPut(level * 2 + if (fromDecoder) 1 else 0) {
            program(VERTEX_FINAL, fragmentFinal(fromDecoder, level, if (fromDecoder) toneMap else ToneMapping.NONE))
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

    internal companion object {
        const val TAG = "GoatCore"
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

        /** O que a última passagem faz: só copia, a cor, ou a nitidez e a cor. */
        const val LEVEL_COPY = 0
        /** O realce do "Nítido" sem nitidez nos ajustes (ver Upscaler.drawSharp; a dos ajustes soma-se). */
        const val SHARP_STRENGTH = 0.4f
        const val LEVEL_COLOUR = 1
        const val LEVEL_SHARP = 2

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

        // A grelha do brilho para cortar as barras pretas: cada ponto lê um píxel da textura do descodificador e guarda o
        // Y (BT.601 sobre o RGB que o amostrador dá, com o preto em 0) no vermelho.
        const val VERTEX_PROBE = """
            attribute vec2 position;
            uniform mat4 st;
            varying vec2 c;
            void main() {
                c = (st * vec4((position + 1.0) * 0.5, 0.0, 1.0)).xy;
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        const val FRAGMENT_PROBE = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES tex;
            varying vec2 c;
            void main() {
                gl_FragColor = vec4(dot(texture2D(tex, c).rgb, vec3(0.299, 0.587, 0.114)), 0.0, 0.0, 1.0);
            }
        """

        // A última passagem. uv: 0..1 na imagem (só o rectângulo [crop], ao cortar as barras pretas); as coordenadas passam pela transformação da textura (a do
        // descodificador, ou nenhuma na imagem de fora). n/s/w/e: os quatro vizinhos, a um píxel do vídeo (texel), só
        // lidos pela variante com nitidez.
        const val VERTEX_FINAL = """
            attribute vec2 position;
            uniform mat4 st;
            uniform vec2 texel;
            uniform vec4 crop;
            varying vec2 c;
            varying vec2 n;
            varying vec2 s;
            varying vec2 w;
            varying vec2 e;
            vec2 at(vec2 uv) { return (st * vec4(uv, 0.0, 1.0)).xy; }
            void main() {
                vec2 uv = crop.xy + (position + 1.0) * 0.5 * crop.zw;
                c = at(uv);
                n = at(uv + vec2(0.0, texel.y));
                s = at(uv - vec2(0.0, texel.y));
                w = at(uv - vec2(texel.x, 0.0));
                e = at(uv + vec2(texel.x, 0.0));
                gl_Position = vec4(position, 0.0, 1.0);
            }
        """

        /**
         * O fragment shader da última passagem, só com o que a variante usa: a nitidez (a imagem mais [sharp] vezes a
         * diferença para a média dos quatro vizinhos) e a cor (`colour · rgb + bias`, ver [PictureLook.rgbMatrix]).
         * Com [toneMap] (PQ ou HLG; só da textura do descodificador, sem a nitidez): o HDR → SDR antes da cor, tudo em
         * highp (as curvas do PQ em mediump saíam erradas na Tegra).
         */
        fun fragmentFinal(fromDecoder: Boolean, level: Int, toneMap: Int = ToneMapping.NONE): String = buildString {
            if (fromDecoder) append("#extension GL_OES_EGL_image_external : require\n")
            append(if (toneMap != ToneMapping.NONE) "precision highp float;\n" else "precision mediump float;\n")
            if (toneMap != ToneMapping.NONE) append(ToneMapping.glsl(toneMap))
            // O amostrador externo é lowp por omissão: na conversão do HDR pede-se highp, para não perder os 10 bits.
            append(if (!fromDecoder) "uniform sampler2D tex;\n" else if (toneMap != ToneMapping.NONE) "uniform highp samplerExternalOES tex;\n" else "uniform samplerExternalOES tex;\n")
            if (level >= LEVEL_COLOUR) append("uniform mat3 colour;\nuniform float bias;\n")
            if (level >= LEVEL_SHARP) append("uniform float sharp;\nvarying vec2 n;\nvarying vec2 s;\nvarying vec2 w;\nvarying vec2 e;\n")
            append("varying vec2 c;\nvoid main() {\n")
            append("vec3 p = texture2D(tex, c).rgb;\n")
            if (toneMap != ToneMapping.NONE) append("p = toneMap(p);\n")
            if (level >= LEVEL_SHARP && toneMap == ToneMapping.NONE) {
                append("vec3 a = 0.25 * (texture2D(tex, n).rgb + texture2D(tex, s).rgb + texture2D(tex, w).rgb + texture2D(tex, e).rgb);\n")
                append("p = clamp(p + sharp * (p - a), 0.0, 1.0);\n")
            }
            if (level >= LEVEL_COLOUR) append("p = colour * p + bias;\n")
            append("gl_FragColor = vec4(p, 1.0);\n}\n")
        }

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

package cc.slokker.itv.lab

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import cc.slokker.itv.media.TsTrack
import cc.slokker.itv.ui.player.goatcore.GoatCorePlayer
import java.io.InputStream
import java.net.URL

/**
 * O laboratório do motor GoatCore (só no debug): toca canais em sequência, como quem faz zapping, e escreve no registo
 * (etiqueta GOATLAB) o tempo até à primeira imagem, as imagens mostradas e atrasadas, os blocos de som e os erros.
 *
 * `adb shell am start -n cc.slokker.itv.debug/cc.slokker.itv.lab.GoatCoreLabActivity --es seq "h264_aac,hevc_ac3" --el dwell 6000`
 *
 * Cada nome é um canal de assets/goatcore (feito com o ffmpeg, tocado em ciclo ao ritmo de um direto, com o relógio a
 * voltar atrás em cada volta) ou um endereço http(s).
 */
class GoatCoreLabActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private lateinit var label: TextView
    private val main = Handler(Looper.getMainLooper())
    private var player: GoatCorePlayer? = null
    private var seq = listOf<String>()
    private var index = 0
    private var dwell = 6000L
    private var rounds = 1
    private var current = ""
    private var firstMs = -1L
    private var errors = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        seq = (intent.getStringExtra("seq") ?: "h264_aac").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        dwell = intent.getLongExtra("dwell", 6000)
        rounds = intent.getIntExtra("rounds", 1)
        surfaceView = SurfaceView(this)
        label = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setBackgroundColor(0x88000000.toInt())
            textSize = 14f
            setPadding(16, 8, 16, 8)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
            addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        })
        surfaceView.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        index = 0
        playCurrent()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        main.removeCallbacksAndMessages(null)
        player?.stop()
        player = null
    }

    private var exo: androidx.media3.exoplayer.ExoPlayer? = null

    /** "exo:<endereço>": o mesmo canal no ExoPlayer, para comparar (o tempo até à primeira imagem e se a há). */
    private fun playExo(url: String) {
        val started = android.os.SystemClock.elapsedRealtime()
        val p = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        exo = p
        p.setVideoSurfaceView(surfaceView)
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                firstMs = android.os.SystemClock.elapsedRealtime() - started
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                synchronized(errors) { errors += error.errorCodeName }
            }
        })
        p.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        p.prepare()
        p.play()
        main.postDelayed({
            Log.i(TAG, "RESULT exo ch=$current first=$firstMs errors=$errors")
            p.release()
            exo = null
            index++
            playCurrent()
        }, dwell)
    }

    private fun playCurrent() {
        if (index >= seq.size * rounds) {
            Log.i(TAG, "DONE")
            label.text = "done"
            return
        }
        current = seq[index % seq.size]
        firstMs = -1
        errors = mutableListOf()
        if (current.startsWith("exo:")) return playExo(current.removePrefix("exo:"))
        val emulator = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && !intent.getBooleanExtra("hw", false)
        val p = GoatCorePlayer(surfaceView.holder.surface, softwareVideo = emulator, listener = object : GoatCorePlayer.Listener {
            override fun onFirstFrame(ms: Long) {
                firstMs = ms
            }

            override fun onTracks(tracks: List<TsTrack>) {
                Log.i(TAG, "tracks ch=$current ${tracks.joinToString { "${it.codec}/${it.language ?: "-"}" }}")
            }

            override fun onError(why: String, unsupported: Boolean, cause: Throwable?) {
                synchronized(errors) { errors += why }
            }

            override fun onEnded() {}
        })
        player = p
        val name = current
        p.open { if (name.startsWith("http")) URL(name).openStream() else PacedAsset(assets.open("goatcore/$name.ts").use { it.readBytes() }, 2.0) }
        main.postDelayed(::report, dwell)
        tick()
    }

    private fun tick() {
        val p = player ?: return
        val s = p.stats
        label.text = "$current  first=${firstMs}ms  shown=${s.framesRendered} late=${s.framesLate} audio=${s.audioFrames} jumps=${s.jumps} err=${errors.size}"
        main.postDelayed(::tick, 250)
    }

    private fun report() {
        main.removeCallbacksAndMessages(null)
        val p = player
        val s = p?.stats
        Log.i(
            TAG,
            "RESULT ch=$current first=$firstMs units=${s?.videoUnits} shown=${s?.framesRendered} late=${s?.framesLate} " +
                "audio=${s?.audioFrames} jumps=${s?.jumps} decoderErrors=${s?.decoderErrors} errors=${synchronized(errors) { errors.toList() }}",
        )
        p?.stop()
        player = null
        index++
        playCurrent()
    }

    /** Um canal de teste tocado em ciclo ao ritmo de um direto ([seconds] por volta), como o servidor de um fornecedor. */
    private class PacedAsset(private val bytes: ByteArray, seconds: Double) : InputStream() {
        private val bytesPerMs = bytes.size / (seconds * 1000)
        private val start = System.currentTimeMillis()
        private var sent = 0L

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            // O primeiro segundo vai de uma vez (os fornecedores mandam uma rajada ao começar), o resto ao ritmo.
            while (true) {
                val allowed = ((System.currentTimeMillis() - start + 1000) * bytesPerMs).toLong()
                if (sent < allowed) break
                Thread.sleep(5)
            }
            val at = (sent % bytes.size).toInt()
            val n = minOf(len, bytes.size - at, 16 * 1024)
            bytes.copyInto(b, off, at, at + n)
            sent += n
            return n
        }
    }

    companion object {
        private const val TAG = "GOATLAB"
    }
}

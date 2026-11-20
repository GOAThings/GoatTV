package cc.slokker.itv.ui.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.Player
import cc.slokker.itv.playback.PictureName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * "Guardar imagem" no leitor: a imagem do canal neste momento, sem nada da app por cima (os painéis, os botões e as
 * legendas são outras vistas; copia-se só a superfície do vídeo, ver [VideoView.capture]), num JPEG com o nome do
 * canal e a hora (ver [PictureName]).
 *
 * No Android 10+ vai para Imagens/GoatTV pelo MediaStore (sem pedir permissão nenhuma, e aparece na galeria); antes
 * disso, ou numa box sem o MediaStore das imagens, para a pasta de imagens da própria app
 * (Android/data/…/files/Pictures). Precisa do PixelCopy (Android 7+).
 */
internal object PictureSaver {
    private const val TAG = "itv"
    private const val FOLDER = "GoatTV"
    private const val QUALITY = 92

    /** O lado maior da imagem guardada (um canal 4K fica em 4K). */
    private const val MAX_SIDE = 3840

    /** Guarda a imagem que [view] mostra de [player], com o nome de [channel]. Devolve se ficou guardada. */
    suspend fun save(context: Context, view: VideoView?, player: Player?, channel: String): Boolean {
        view ?: return false
        val size = pictureSize(view, player) ?: return false
        val bitmap = view.capture(size.first, size.second, timeoutMs = 2_000) ?: return false
        val name = PictureName.of(channel, System.currentTimeMillis()) + ".jpg"
        val app = context.applicationContext
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && toGallery(app, bitmap, name)) true else toAppFolder(app, bitmap, name)
            } catch (e: Exception) {
                Log.w(TAG, "save picture: ${e.javaClass.simpleName} ${e.message}")
                false
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * O tamanho da imagem a guardar: o do vídeo, com os píxeis já quadrados (um 720×576 de 16:9 fica 1024×576); sem o
     * saber ainda, o da vista no ecrã.
     */
    private fun pictureSize(view: VideoView, player: Player?): Pair<Int, Int>? {
        val video = player?.videoSize
        var w = 0f
        var h = 0f
        if (video != null && video.width > 0 && video.height > 0) {
            w = video.width * video.pixelWidthHeightRatio.takeIf { it > 0f }.let { it ?: 1f }
            h = video.height.toFloat()
        } else {
            w = view.width.toFloat()
            h = view.height.toFloat()
        }
        if (w < 2f || h < 2f) return null
        val scale = minOf(1f, MAX_SIDE / maxOf(w, h))
        return (w * scale).roundToInt().coerceAtLeast(2) to (h * scale).roundToInt().coerceAtLeast(2)
    }

    /** Imagens/GoatTV, pelo MediaStore (Android 10+). false se a box não o tiver: fica a pasta da app. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun toGallery(context: Context, bitmap: Bitmap, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull() ?: return false
        return try {
            val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) } == true
            if (!written) error("no output")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "save picture (gallery): ${e.javaClass.simpleName} ${e.message}")
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    /** A pasta de imagens da app (não precisa de permissão em nenhuma versão). */
    private fun toAppFolder(context: Context, bitmap: Bitmap, name: String): Boolean {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(context.filesDir, "Pictures")
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val file = File(dir, name)
        val written = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (!written) file.delete()
        return written
    }
}

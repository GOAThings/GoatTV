package cc.slokker.itv.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import cc.slokker.itv.BuildConfig
import cc.slokker.itv.InstallResultReceiver
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Uma versão publicada da app. */
class AppRelease(val version: String, val notes: String, val apkUrl: String, val size: Long)

/**
 * Atualizações da app: as versões são publicadas como releases do repositório público
 * [RELEASES_REPO] (só os APKs; o código é privado). O APK descarregado vai para o instalador do Android,
 * que só o aceita se estiver assinado com a mesma chave da app instalada.
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val gson: Gson) {

    private val _installFailures = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Motivos de instalações falhadas (do instalador do Android), para a app os mostrar por extenso. */
    val installFailures: SharedFlow<String> = _installFailures

    fun reportInstallFailure(message: String) {
        _installFailures.tryEmit(message)
    }

    /** Só a app "a sério" se atualiza (as versões de teste têm outro id e não podem receber o APK release). */
    val enabled: Boolean = BuildConfig.APPLICATION_ID == RELEASE_APP_ID

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** A versão mais recente publicada, ou null se não houver nenhuma com APK. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$RELEASES_REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { res ->
            if (res.code == 404) return@withContext null
            if (!res.isSuccessful) throw IOException("GitHub HTTP ${res.code}")
            val release = gson.fromJson(res.body.charStream(), GithubRelease::class.java) ?: return@withContext null
            val apk = release.assets.orEmpty().firstOrNull { it.name?.endsWith(".apk") == true } ?: return@withContext null
            val url = apk.url ?: return@withContext null
            AppRelease(release.tag?.removePrefix("v")?.trim().orEmpty(), release.body.orEmpty().trim(), url, apk.size ?: 0)
        }
    }

    /** Descarrega o APK para a cache da app; [onProgress] recebe 0..1. */
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // só fica a versão a instalar
        val file = File(dir, "goattv-${release.version}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val total = res.body.contentLength().takeIf { it > 0 } ?: release.size
            res.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** O Android deixa esta app instalar APKs? (Android 8+: "Instalar apps desconhecidas" para a GoatTV.) */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Abre o ecrã do Android onde se autoriza a GoatTV a instalar apps. */
    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Entrega o APK ao instalador do Android (que mostra a confirmação; o resultado chega ao [InstallResultReceiver]). */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Por defeito uma sessão só instala na memória interna; se a app estiver num disco adotado
            // (ex.: SHIELD com USB como armazenamento), o Android recusa ("Cannot automatically move…").
            // AUTO deixa-a ficar onde está.
            setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }

    private class GithubRelease(
        @SerializedName("tag_name") val tag: String?,
        val body: String?,
        val assets: List<GithubAsset>?,
    )

    private class GithubAsset(
        val name: String?,
        val size: Long?,
        @SerializedName("browser_download_url") val url: String?,
    )

    companion object {
        /** Fica com o nome antigo: as versões já instaladas procuram atualizações aqui. */
        const val RELEASES_REPO = "GOAThings/GoatTV-Releases"
        private const val RELEASE_APP_ID = "cc.slokker.itv"

        /** "0.10.1" > "0.9.3": compara número a número (o que não é número conta como 0). */
        fun isNewer(remote: String, current: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(remote)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

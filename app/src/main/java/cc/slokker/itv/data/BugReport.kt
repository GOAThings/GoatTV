package cc.slokker.itv.data

import android.content.Context
import android.os.Build
import android.os.Process
import cc.slokker.itv.BuildConfig
import java.io.File
import java.util.Date

/**
 * Relatórios para a zona admin do site (`POST /api/reports`): o logcat desta app, o último crash e o que a pessoa
 * escreveu. Antes de sair da TV, as palavras-passe, os utilizadores, os tokens, os URLs e os servidores passam a `***`.
 */
object BugReport {
    private const val CRASH_FILE = "last_crash.txt"
    /** O servidor aceita até 64 KB de registo. */
    private const val MAX_BYTES = 60_000

    /** Guarda o crash num ficheiro (vai no próximo relatório) e deixa o Android tratar dele como sempre. */
    fun installCrashHandler(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { file.writeText("${Date()} · ${BuildConfig.VERSION_NAME} · ${thread.name}\n${e.stackTraceToString()}") }
            previous?.uncaughtException(thread, e)
        }
    }

    /** O aparelho, para o campo `device`. */
    val device: String get() = "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}".take(120)

    /** O registo a enviar: [header], o último crash e o logcat (as linhas mais recentes), sem os [secrets]. */
    fun collect(context: Context, header: String, secrets: Collection<String>): String {
        val crash = File(context.filesDir, CRASH_FILE).takeIf { it.exists() }?.let { runCatching { it.readText().take(15_000) }.getOrNull() }
        val head = buildString {
            append(header).append('\n')
            if (crash != null) append("\n--- Last crash ---\n").append(crash.trimEnd()).append('\n')
            append("\n--- Log ---\n")
        }
        return trim(redact(head, secrets), redact(logcat(), secrets))
    }

    /** As linhas desta app (sem a permissão READ_LOGS, o Android já só dá as da própria app). */
    private fun logcat(): String = runCatching {
        val command = mutableListOf("logcat", "-d", "-v", "time", "-t", "4000")
        if (Build.VERSION.SDK_INT >= 24) command += "--pid=${Process.myPid()}"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat: $it" }

    /** [head] inteiro e, do [log], as últimas linhas que cabem. */
    internal fun trim(head: String, log: String, max: Int = MAX_BYTES): String {
        val budget = max - head.toByteArray().size
        var tail = log
        while (tail.toByteArray().size > budget && tail.contains('\n')) tail = tail.substringAfter('\n')
        if (tail.toByteArray().size > budget) tail = ""
        return head + tail
    }

    /** O servidor de [value] (um URL, ou só o endereço), para sair do relatório mesmo quando aparece sem o resto. */
    fun hostOf(value: String): String? =
        value.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').takeIf { it.isNotEmpty() }

    /** Troca por `***` os [secrets] e o que tem cara de credencial (URLs inteiros, parâmetros, caminhos Xtream, tokens, servidores). */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (secret in secrets.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }) out = out.replace(secret, "***")
        // Os links dos canais podem ter credenciais em qualquer parte do caminho ou do servidor.
        out = URL.replace(out) { "${it.groupValues[1]}://***" }
        out = QUERY.replace(out) { "${it.groupValues[1]}=***" }
        out = XTREAM_PATH.replace(out) { "/${it.groupValues[1]}/***/***/" }
        out = STALKER_PATH.replace(out, "/stalker/***/***/")
        out = MAC.replace(out, "***")
        out = USER_INFO.replace(out) { "${it.groupValues[1]}***@" }
        out = AUTH.replace(out) { "${it.groupValues[1]} ***" }
        out = HOST.replace(out) { if (looksLikeCode(it.value)) it.value else "***" }
        out = IPV4.replace(out, "***")
        return out
    }

    /** Um nome com pontos que é código ou um ficheiro (fica, para o relatório servir) e não um servidor. Na dúvida, é servidor. */
    private fun looksLikeCode(name: String): Boolean {
        val labels = name.split('.')
        val last = labels.last().lowercase()
        if (last in FILE_ENDINGS) return true
        if (last.length == 2 || last in COMMON_TLDS || '-' in name) return false
        return name.any { it.isUpperCase() } || labels[0] in CODE_ROOTS || "${labels[0]}.${labels[1]}" in CODE_ROOTS
    }

    private val URL = Regex("\\b([a-zA-Z][a-zA-Z0-9+.-]*)://[^\\s\"'<>]+")
    private val QUERY = Regex("(?i)\\b(password|passwd|pass|pwd|username|user|token|access_token|auth|key|api_key|apikey|mac|sn|device_id|device_id2|signature|play_token)=([^&\\s\"']+)")
    private val XTREAM_PATH = Regex("/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/")
    private val STALKER_PATH = Regex("/stalker/[^/\\s]+/[^/\\s]+/")
    private val MAC = Regex("(?i)\\b[0-9a-f]{2}(?:(?::|%3A)[0-9a-f]{2}){5}\\b")
    private val USER_INFO = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@")
    private val AUTH = Regex("(?i)(authorization:?|bearer)\\s+[^\\s,]+")
    private val HOST = Regex("(?<![\\w.-])(?:[A-Za-z0-9_-]+\\.)+[A-Za-z][A-Za-z0-9-]*[A-Za-z0-9](?![\\w-])")
    private val IPV4 = Regex("(?<![\\w.])\\d{1,3}(?:\\.\\d{1,3}){3}(?![\\w.])")
    private val FILE_ENDINGS = setOf("kt", "java", "swift", "cpp", "dex", "jar", "apk", "ipa", "dylib", "xml", "json", "png", "jpg", "webp", "gz", "ts", "m3u", "m3u8", "mp4", "mkv", "php", "html", "js", "css", "txt", "log")
    private val COMMON_TLDS = setOf("com", "net", "org", "info", "biz", "xyz", "top", "site", "online", "club", "live", "pro", "stream", "app", "dev", "tech", "store", "shop", "cloud", "link", "icu", "vip", "fun", "space", "website", "one", "world", "today", "media", "example", "invalid", "test", "local", "lan")
    private val CODE_ROOTS = setOf("java", "javax", "kotlin", "kotlinx", "android", "androidx", "dalvik", "libcore", "sun", "okhttp3", "okio", "retrofit2", "coil", "coil3", "com.android", "com.google", "com.squareup", "com.apple", "org.videolan", "org.json", "cc.slokker")
}

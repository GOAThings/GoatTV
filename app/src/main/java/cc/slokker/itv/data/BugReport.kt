package cc.slokker.itv.data

import android.content.Context
import android.os.Build
import android.os.Process
import cc.slokker.itv.BuildConfig
import java.io.File
import java.util.Date

/**
 * Relatórios para a zona admin do site (`POST /api/reports`): o logcat desta app, o último crash e o que a pessoa
 * escreveu. Antes de sair da TV, as palavras-passe, os utilizadores, os tokens e os URLs das listas passam a `***`.
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

    /** Troca por `***` os [secrets] e o que tem cara de credencial (parâmetros, caminhos Xtream, user:pass@, tokens). */
    fun redact(text: String, secrets: Collection<String>): String {
        var out = text
        for (secret in secrets.filter { it.length >= 3 }.distinct().sortedByDescending { it.length }) out = out.replace(secret, "***")
        out = QUERY.replace(out) { "${it.groupValues[1]}=***" }
        out = XTREAM_PATH.replace(out) { "/${it.groupValues[1]}/***/***/" }
        out = USER_INFO.replace(out) { "${it.groupValues[1]}***@" }
        out = AUTH.replace(out) { "${it.groupValues[1]} ***" }
        return out
    }

    private val QUERY = Regex("(?i)\\b(password|passwd|pass|pwd|username|user|token|access_token|auth|key|api_key|apikey)=([^&\\s\"']+)")
    private val XTREAM_PATH = Regex("/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/")
    private val USER_INFO = Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s:@]+:[^/\\s@]+@")
    private val AUTH = Regex("(?i)(authorization:?|bearer)\\s+[^\\s,]+")
}

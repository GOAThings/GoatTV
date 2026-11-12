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
        return BugReportRules.report(head, logcat(), secrets.toList())
    }

    /** As linhas desta app (sem a permissão READ_LOGS, o Android já só dá as da própria app). */
    private fun logcat(): String = runCatching {
        val command = mutableListOf("logcat", "-d", "-v", "time", "-t", "4000")
        if (Build.VERSION.SDK_INT >= 24) command += "--pid=${Process.myPid()}"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat: $it" }

    /** [head] inteiro e, do [log], as últimas linhas que cabem. */
    internal fun trim(head: String, log: String, max: Int = BugReportRules.MAX_BYTES): String = BugReportRules.trim(head, log, max)

    /** O servidor de [value] (um URL, ou só o endereço), para sair do relatório mesmo quando aparece sem o resto. */
    fun hostOf(value: String): String? = BugReportRules.hostOf(value)

    /** Troca por `***` os [secrets] e o que tem cara de credencial (ver [BugReportRules.redact]). */
    fun redact(text: String, secrets: Collection<String>): String = BugReportRules.redact(text, secrets.toList())
}

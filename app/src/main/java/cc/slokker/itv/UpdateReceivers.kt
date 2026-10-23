package cc.slokker.itv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import cc.slokker.itv.ui.i18n.I18n

/** Resposta do instalador do Android a uma atualização: pede a confirmação ao utilizador, ou diz o que falhou. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // a app é substituída; o PackageReplacedReceiver volta a abri-la
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ItvUpdate", "Instalação falhou ($status): $message")
                // Cancelada pela pessoa: não é erro. O resto aparece por extenso nas Definições.
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    (context.applicationContext as ItvApp).container.updater.reportInstallFailure(message ?: status.toString())
                    Toast.makeText(context, I18n.s.updateFailed(message ?: status.toString()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Depois de uma atualização, volta a abrir a GoatTV (se o Android deixar abrir apps em segundo plano). */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }.onFailure { Log.i("ItvUpdate", "Não deu para reabrir: ${it.message}") }
    }
}

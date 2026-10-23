package cc.slokker.itv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.slokker.itv.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Arrancar com a box": abre a app quando o sistema acaba de arrancar.
 * Algumas versões do Android bloqueiam aberturas em segundo plano, por isso pode não funcionar em todas as boxes.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = SessionStore(context.applicationContext).autoStart()
                Log.i("ItvBoot", "BOOT_COMPLETED, arrancar=$enabled")
                if (enabled) {
                    context.startActivity(
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}

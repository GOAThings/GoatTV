package cc.slokker.itv.recording

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cc.slokker.itv.ItvApp

/**
 * Acorda a app para a próxima gravação marcada, também com ela fechada (o processo arranca para o alarme e o
 * [RecordingManager] decide). Depois de reiniciar a box, o alarme volta a pôr-se quando o processo arranca (ex.: o
 * "arrancar com a box" ou a primeira vez que se abre a app).
 *
 * Exato quando o Android deixa: até ao Android 11 (a SHIELD) sempre; no 12 a permissão vem dada; do 13 em diante só se
 * o utilizador a der ("Alarmes e lembretes"). Sem ela, o alarme é aproximado (pode chegar uns minutos depois) e, com
 * a app fechada, o Android pode não deixar o serviço da gravação começar: aí só grava com a app aberta.
 */
object RecordingAlarm {
    private const val TAG = "Recordings"

    fun canBeExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Põe o alarme para [at] (ms desde 1970), ou tira-o (null). */
    fun arm(context: Context, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(context, RecordingAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            am.cancel(intent)
            return
        }
        try {
            if (canBeExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (e: SecurityException) {
            // A permissão foi tirada entretanto: aproximado.
            Log.w(TAG, "Alarme exato recusado", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }
}

class RecordingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as ItvApp).container.recordings.onAlarm()
    }
}

package cc.slokker.itv.recording

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cc.slokker.itv.ItvApp
import cc.slokker.itv.MainActivity
import cc.slokker.itv.R
import cc.slokker.itv.data.recording.RecordingInfo
import cc.slokker.itv.ui.i18n.I18n

/**
 * O serviço em primeiro plano enquanto há uma gravação: com a app em segundo plano (HOME, ecrã desligado no
 * telemóvel) o Android deixa o processo, e a ligação, continuar. A notificação diz o que está a gravar e tem "Parar".
 * Na TV é o mesmo serviço (o Android TV mostra a notificação nas suas notificações, quando as tem).
 *
 * Tipo `specialUse` (Android 14+): gravar um stream para um ficheiro não cabe nos outros tipos, e o `dataSync` tem um
 * limite de 6 h por dia. Não pede nenhuma autorização ao utilizador (é uma permissão normal).
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    /** Com o ecrã desligado, o processador e o Wi-Fi continuam (senão a ligação parava de ler). */
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onDestroy() {
        wake?.takeIf { it.isHeld }?.release()
        wifi?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun keepAwake() {
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "goattv:recording")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_MAX_MS)
            }
        }
        if (wifi == null) {
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "goattv:recording")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = (application as ItvApp).container.recordings
        if (intent?.action == ACTION_STOP) {
            manager.stopAll()
            return START_NOT_STICKY
        }
        val shown = manager.active.value.map { it.info }.ifEmpty { pending }
        if (shown.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification(this, shown)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+: sem a app à vista nem um alarme exato, não deixa começar em primeiro plano.
            Log.w(TAG, "Sem serviço em primeiro plano", e)
        }
        keepAwake()
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        private const val TAG = "Recordings"
        private const val CHANNEL = "recordings"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "cc.slokker.itv.recording.STOP"

        /** Uma gravação nunca passa disto (4 h sem guia, ou o programa mais longo com as margens). */
        private const val WAKE_MAX_MS = 12 * 3600_000L

        /** O que mostrar enquanto a primeira gravação ainda não começou (a ligar). */
        @Volatile private var pending: List<RecordingInfo> = emptyList()

        /** Liga, atualiza ou desliga o serviço conforme [recording] (as gravações a decorrer). */
        fun update(context: Context, recording: List<RecordingInfo>) {
            pending = recording
            val intent = Intent(context, RecordingService::class.java)
            if (recording.isEmpty()) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "O Android não deixou o serviço começar em segundo plano: grava enquanto o processo durar", e)
                } else {
                    Log.w(TAG, "Sem serviço", e)
                }
            }
        }

        private fun notification(context: Context, recording: List<RecordingInfo>): android.app.Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            val s = I18n.s
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, s.recordings, NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                context, 1, Intent(context, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val first = recording.first()
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(s.recordingNow(first.title))
                .setContentText(first.channelName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, s.stopRecording, stop)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }
    }
}

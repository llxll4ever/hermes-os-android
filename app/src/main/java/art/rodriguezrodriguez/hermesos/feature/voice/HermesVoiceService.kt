package art.rodriguezrodriguez.hermesos.feature.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import art.rodriguezrodriguez.hermesos.MainActivity
import art.rodriguezrodriguez.hermesos.R

/**
 * Foreground Service keeping the Hermes Live Voice channel ready while the user
 * works across other Android apps or Samsung DeX windows.
 */
class HermesVoiceService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val targetScope = intent?.getStringExtra(EXTRA_TARGET_SCOPE) ?: "bot:orchestrator"
        val notification = buildNotification(targetScope)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(targetScope: String): Notification {
        val speakIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_START_VOICE, true)
            putExtra(EXTRA_TARGET_SCOPE, targetScope)
        }
        val speakPending = PendingIntent.getActivity(
            this,
            101,
            speakIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HermesVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hermes_logo)
            .setContentTitle("Hermes Live Voice Activo")
            .setContentText("Destino: $targetScope · Toca para hablar")
            .setContentIntent(speakPending)
            .setOngoing(true)
            .addAction(R.drawable.ic_hermes_logo, "Hablar", speakPending)
            .addAction(R.drawable.ic_hermes_logo, "Detener", stopPending)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Hermes Live Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal persistente para comandos de voz hacia Hermes y salas de bots"
            }
            mgr.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "hermes_voice_live_channel"
        private const val NOTIFICATION_ID = 9119
        const val ACTION_STOP = "art.rodriguezrodriguez.hermesos.VOICE_STOP"
        const val EXTRA_TARGET_SCOPE = "extra_voice_target_scope"
    }
}

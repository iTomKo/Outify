package cc.tomko.outify.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cc.tomko.outify.R
import cc.tomko.outify.core.AuthCallbackServerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class OAuthService : Service() {

    @Inject
    lateinit var serverManager: AuthCallbackServerManager

    companion object {
        const val CHANNEL_ID = "oauth_service_channel"
        const val NOTIFICATION_ID = 1001

        fun createNotification(context: Context): Notification {
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.notif_logging_in))
                .setContentText(context.getString(R.string.notif_oauth_complete))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }

        fun start(context: Context) {
            context.startService(Intent(context, OAuthService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OAuthService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_oauth_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_oauth_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification(this))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serverManager.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
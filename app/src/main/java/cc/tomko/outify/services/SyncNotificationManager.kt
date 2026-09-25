package cc.tomko.outify.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import cc.tomko.outify.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val CHANNEL_ID = "sync_progress_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_sync_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_sync_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showProgress(current: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_syncing_liked_tracks))
            .setContentText(context.getString(R.string.notif_sync_progress, current, total))
            .setProgress(total, current, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showIndeterminate() {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_syncing_liked_tracks))
            .setContentText(context.getString(R.string.notif_starting))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showComplete(count: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_complete))
            .setContentText(context.getString(R.string.notif_synced, count))
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun showError(message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_failed))
            .setContentText(message)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}

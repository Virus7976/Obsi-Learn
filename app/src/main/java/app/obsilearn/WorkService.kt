package app.obsilearn

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app running while it saves lessons or makes transcripts, so a long course
 * carries on with the screen off. It stops by itself when the work is done.
 */
class WorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (n <= 0) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Saving and transcripts", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (n == 1) "Working on 1 lesson" else "Working on $n lessons")
            .setContentText("Saving for offline, or making transcripts.")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, ID, note, type) }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 7301
        private const val EXTRA_COUNT = "count"

        /** Starts the service with the number of lessons in work, or stops it at 0. */
        fun update(context: Context, count: Int) {
            val i = Intent(context, WorkService::class.java).putExtra(EXTRA_COUNT, count)
            if (count <= 0) { context.stopService(i); return }
            runCatching { ContextCompat.startForegroundService(context, i) }
        }
    }
}

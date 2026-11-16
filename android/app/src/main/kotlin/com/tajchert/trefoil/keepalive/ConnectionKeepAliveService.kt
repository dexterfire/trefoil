package com.tajchert.trefoil.keepalive

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
import com.tajchert.trefoil.MainActivity
import com.tajchert.trefoil.R

/**
 * Foreground service (type specialUse) whose only job is to keep the process alive with an ongoing notification while the
 * view model holds a live connection. The notification carries the machine name only: no tokens, URLs or conversation text.
 */
class ConnectionKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> ConnectionKeepAlive.requestDisconnect()
            ACTION_START -> if (!enterForeground(intent.getStringExtra(EXTRA_MACHINE_NAME).orEmpty())) stopSelf()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Only called if the system ever times a specialUse service out (API 35+); the view model restarts it when needed. */
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    private fun enterForeground(machineName: String): Boolean = try {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, machineName), type)
        true
    } catch (e: IllegalStateException) { false } // includes ForegroundServiceStartNotAllowedException (API 31+)
    catch (e: SecurityException) { false }

    companion object {
        const val CHANNEL_ID = "connection_keepalive"
        const val NOTIFICATION_ID = 0x7e1f
        internal const val ACTION_START = "com.tajchert.trefoil.keepalive.START"
        internal const val ACTION_DISCONNECT = "com.tajchert.trefoil.keepalive.DISCONNECT"
        internal const val EXTRA_MACHINE_NAME = "machineName"

        internal fun baseIntent(context: Context) = Intent(context, ConnectionKeepAliveService::class.java)
        internal fun startIntent(context: Context, machineName: String) =
            baseIntent(context).setAction(ACTION_START).putExtra(EXTRA_MACHINE_NAME, machineName)

        internal fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        internal fun buildNotification(context: Context, machineName: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val disconnect = PendingIntent.getService(
                context, 1, baseIntent(context).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepalive)
                .setContentTitle("Connected to $machineName")
                .setContentText("Trefoil keeps this connection open while you use other apps.")
                .setContentIntent(open)
                .addAction(0, "Disconnect", disconnect)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}

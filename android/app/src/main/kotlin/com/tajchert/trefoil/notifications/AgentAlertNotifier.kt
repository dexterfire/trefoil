package com.tajchert.trefoil.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tajchert.trefoil.MainActivity
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.AgentAlert
import com.tajchert.trefoil.core.model.AgentAlertKind

/** Where the driver sends alerts; the notifier below is the real one, tests use a fake. */
internal interface AgentAlertSink {
    /** Notifications of this app are allowed (the permission on Android 13+ and the app-level switch). */
    fun canPost(): Boolean
    fun post(alert: AgentAlert, projectTitle: String?)
    fun cancel(environmentId: String, threadId: String)
    fun cancelAll()
}

/** The thread a notification tap asks the app to open. Untrusted when parsed from an intent: the app validates it against saved machines. */
data class OpenThreadRequest(val environmentId: String, val threadId: String)

/**
 * Local notifications for threads that need the user or ended. One notification per thread (tag [TAG], id from environment
 * and thread). Thread and project titles are conversation content: they show in the notification shade only, never on a locked
 * screen (the public version carries the status alone) and never in logs.
 */
internal class AgentAlertNotifier(private val context: Context) : AgentAlertSink {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun post(alert: AgentAlert, projectTitle: String?) {
        val manager = manager ?: return
        ensureChannel(context)
        try { manager.notify(TAG, notificationId(alert.environmentId, alert.threadId), build(context, alert, projectTitle)) }
        catch (_: RuntimeException) { /* A refused notification must not fail connection handling. */ }
    }

    override fun cancel(environmentId: String, threadId: String) {
        try { manager?.cancel(TAG, notificationId(environmentId, threadId)) } catch (_: RuntimeException) {}
    }

    override fun cancelAll() {
        val manager = manager ?: return
        try { manager.activeNotifications.filter { it.tag == TAG }.forEach { manager.cancel(TAG, it.id) } } catch (_: RuntimeException) {}
    }

    companion object {
        const val CHANNEL_ID = "agent_alerts"
        const val TAG = "agent-alert"
        const val ACTION_OPEN_THREAD = "com.tajchert.trefoil.action.OPEN_THREAD"
        const val EXTRA_ENVIRONMENT_ID = "environmentId"
        const val EXTRA_THREAD_ID = "threadId"
        const val UNTITLED = "Untitled conversation"
        const val MAX_TITLE_LENGTH = 120

        fun notificationId(environmentId: String, threadId: String): Int = (environmentId + "\n" + threadId).hashCode()

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Agent alerts", NotificationManager.IMPORTANCE_HIGH))
        }

        fun status(kind: AgentAlertKind): String = when (kind) {
            AgentAlertKind.Approval -> "Approval needed"
            AgentAlertKind.Input -> "Waiting for input"
            AgentAlertKind.Finished -> "Agent finished"
            AgentAlertKind.Failed -> "Agent failed"
        }

        fun title(raw: String): String = raw.trim().ifBlank { UNTITLED }.let { if (it.length > MAX_TITLE_LENGTH) it.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…" else it }

        fun openIntent(context: Context, environmentId: String, threadId: String): Intent =
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_THREAD)
                .putExtra(EXTRA_ENVIRONMENT_ID, environmentId).putExtra(EXTRA_THREAD_ID, threadId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun build(context: Context, alert: AgentAlert, projectTitle: String?): Notification {
            val status = status(alert.kind)
            val project = projectTitle?.trim()?.takeIf { it.isNotEmpty() }
            val open = PendingIntent.getActivity(
                context, notificationId(alert.environmentId, alert.threadId), openIntent(context, alert.environmentId, alert.threadId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val lockScreen = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepalive).setContentTitle("Trefoil").setContentText(status).build()
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepalive)
                .setContentTitle(title(alert.threadTitle))
                .setContentText(if (project != null) "$status · $project" else status)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(if (alert.kind == AgentAlertKind.Approval || alert.kind == AgentAlertKind.Input) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(lockScreen)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        }
    }
}

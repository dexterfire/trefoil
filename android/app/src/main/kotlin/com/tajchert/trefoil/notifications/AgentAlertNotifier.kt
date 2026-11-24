package com.tajchert.trefoil.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Local notifications for threads that need the user or ended. One notification per thread: its tag names the environment and
 * thread ([tag]) and the id is the fixed [NOTIFICATION_ID], so two threads never share a notification. Thread and project titles
 * are conversation content and never logged; the notification is `VISIBILITY_PRIVATE`, so where the device hides sensitive
 * notification content on the lock screen only the public version (the status alone) shows there.
 */
internal class AgentAlertNotifier(private val context: Context) : AgentAlertSink {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun post(alert: AgentAlert, projectTitle: String?) {
        val manager = manager ?: return
        ensureChannel(context)
        try { manager.notify(tag(alert.environmentId, alert.threadId), NOTIFICATION_ID, build(context, alert, projectTitle)) }
        catch (_: RuntimeException) { /* A refused notification must not fail connection handling. */ }
    }

    override fun cancel(environmentId: String, threadId: String) {
        try { manager?.cancel(tag(environmentId, threadId), NOTIFICATION_ID) } catch (_: RuntimeException) {}
    }

    override fun cancelAll() {
        val manager = manager ?: return
        try { manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }.forEach { manager.cancel(it.tag, it.id) } } catch (_: RuntimeException) {}
    }

    companion object {
        const val CHANNEL_ID = "agent_alerts"
        /** Every agent alert's tag starts with this; nothing else of the app uses it. */
        const val TAG_PREFIX = "agent-alert:"
        const val NOTIFICATION_ID = 1
        /** Scheme of the data URI that keeps each thread's PendingIntent distinct; the activity declares no filter for it. */
        const val URI_SCHEME = "trefoil-alert"
        const val ACTION_OPEN_THREAD = "com.tajchert.trefoil.action.OPEN_THREAD"
        const val EXTRA_ENVIRONMENT_ID = "environmentId"
        const val EXTRA_THREAD_ID = "threadId"
        /** Carries [tapToken]: MainActivity is exported, so only an intent with it comes from one of this app's notifications. */
        const val EXTRA_TAP_TOKEN = "tapToken"
        private const val TOKEN_PREFS = "trefoil.notifications"
        private const val TOKEN_KEY = "tapToken"
        @Volatile private var cachedTapToken: String? = null
        const val UNTITLED = "Untitled conversation"
        const val MAX_TITLE_LENGTH = 120

        /** Each part is URI-encoded, so the "/" between them is unambiguous: distinct (environment, thread) pairs never share a tag. */
        fun tag(environmentId: String, threadId: String): String = TAG_PREFIX + Uri.encode(environmentId) + "/" + Uri.encode(threadId)

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

        /**
         * A random secret of this installation, generated once and kept in app-private preferences (never logged, never exported).
         * Per install rather than per process, so a notification still opens its thread when the system killed the process that
         * posted it. Only the system and this app can read a PendingIntent's extras.
         */
        fun tapToken(context: Context): String = cachedTapToken ?: synchronized(this) {
            cachedTapToken ?: run {
                val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
                prefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotEmpty() } ?: run {
                    val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                    bytes.joinToString("") { "%02x".format(it) }.also { prefs.edit().putString(TOKEN_KEY, it).commit() }
                }
            }.also { cachedTapToken = it }
        }

        fun openIntent(context: Context, environmentId: String, threadId: String): Intent =
            // Explicit; the data URI only makes intents of different threads unequal (filterEquals), so their PendingIntents never merge.
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_THREAD)
                .setData(Uri.fromParts(URI_SCHEME, tag(environmentId, threadId), null))
                .putExtra(EXTRA_ENVIRONMENT_ID, environmentId).putExtra(EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_TAP_TOKEN, tapToken(context))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        fun build(context: Context, alert: AgentAlert, projectTitle: String?): Notification {
            val status = status(alert.kind)
            val project = projectTitle?.trim()?.takeIf { it.isNotEmpty() }
            val open = PendingIntent.getActivity(
                context, 0, openIntent(context, alert.environmentId, alert.threadId),
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

package com.tajchert.trefoil.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.test.core.app.ApplicationProvider
import com.tajchert.trefoil.MainActivity
import com.tajchert.trefoil.core.model.AgentAlert
import com.tajchert.trefoil.core.model.AgentAlertKind
import com.tajchert.trefoil.parseOpenThreadIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentAlertNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val notifier = AgentAlertNotifier(app)
    private fun alert(kind: AgentAlertKind, title: String = "Fix the login bug", env: String = "env-1", thread: String = "thread-1") =
        AgentAlert(env, thread, kind, title, "project-1")
    private fun active() = shadowOf(manager).allNotifications
    private fun text(n: Notification, key: String) = n.extras.getCharSequence(key)?.toString()

    @Test fun createsAHighImportanceChannel() {
        notifier.post(alert(AgentAlertKind.Approval), "Trefoil")
        val channel = manager.getNotificationChannel("agent_alerts")
        assertEquals("Agent alerts", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test fun contentIsTheThreadTitleAndStatusWithProject() {
        notifier.post(alert(AgentAlertKind.Approval), "Trefoil")
        val n = active().single()
        assertEquals("Fix the login bug", text(n, Notification.EXTRA_TITLE))
        assertEquals("Approval needed · Trefoil", text(n, Notification.EXTRA_TEXT))
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
    }

    @Test fun statusWordingPerKindAndCategory() {
        val expected = mapOf(
            AgentAlertKind.Approval to ("Approval needed" to Notification.CATEGORY_MESSAGE),
            AgentAlertKind.Input to ("Waiting for input" to Notification.CATEGORY_MESSAGE),
            AgentAlertKind.Finished to ("Agent finished" to Notification.CATEGORY_STATUS),
            AgentAlertKind.Failed to ("Agent failed" to Notification.CATEGORY_STATUS),
        )
        expected.forEach { (kind, wanted) ->
            val n = AgentAlertNotifier.build(app, alert(kind), null)
            assertEquals(wanted.first, text(n, Notification.EXTRA_TEXT))
            assertEquals(wanted.second, n.category)
        }
    }

    @Test fun unknownOrBlankProjectLeavesStatusOnly() {
        assertEquals("Agent failed", text(AgentAlertNotifier.build(app, alert(AgentAlertKind.Failed), null), Notification.EXTRA_TEXT))
        assertEquals("Agent failed", text(AgentAlertNotifier.build(app, alert(AgentAlertKind.Failed), "  "), Notification.EXTRA_TEXT))
    }

    @Test fun blankTitleIsUntitledAndLongTitlesAreCut() {
        assertEquals("Untitled conversation", text(AgentAlertNotifier.build(app, alert(AgentAlertKind.Input, title = "  "), null), Notification.EXTRA_TITLE))
        val long = text(AgentAlertNotifier.build(app, alert(AgentAlertKind.Input, title = "x".repeat(300)), null), Notification.EXTRA_TITLE)!!
        assertEquals(120, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun lockScreenVersionHidesTitleAndProject() {
        val n = AgentAlertNotifier.build(app, alert(AgentAlertKind.Approval, title = "Secret plans"), "Secret project")
        val public = n.publicVersion
        assertNotNull(public)
        assertEquals("Trefoil", text(public, Notification.EXTRA_TITLE))
        assertEquals("Approval needed", text(public, Notification.EXTRA_TEXT))
        assertNull(public.contentIntent)
    }

    @Test fun tapOpensMainActivityWithTheThreadAndNoNewExportedSurface() {
        val n = AgentAlertNotifier.build(app, alert(AgentAlertKind.Input), null)
        val pending = n.contentIntent
        val shadow = shadowOf(pending)
        assertTrue(shadow.isActivity)
        assertTrue(shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(shadow.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        val intent = shadow.savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("com.tajchert.trefoil.action.OPEN_THREAD", intent.action)
        assertEquals("env-1", intent.getStringExtra("environmentId"))
        assertEquals("thread-1", intent.getStringExtra("threadId"))
        assertEquals(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags and (android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        assertEquals(OpenThreadRequest("env-1", "thread-1"), parseOpenThreadIntent(intent))
        assertEquals("trefoil-alert", intent.data?.scheme)
    }

    @Test fun pendingIntentsOfDifferentThreadsNeverMerge() {
        fun intent(env: String, thread: String) = shadowOf(AgentAlertNotifier.build(app, alert(AgentAlertKind.Input, env = env, thread = thread), null).contentIntent).savedIntent
        val a = intent("a", "bc"); val b = intent("ab", "c"); val c = intent("env-1", "thread-2")
        assertTrue(!a.filterEquals(b) && !a.filterEquals(c) && !b.filterEquals(c))
        // Updating one thread's notification keeps the other thread's target.
        val first = AgentAlertNotifier.build(app, alert(AgentAlertKind.Input, thread = "thread-1"), null).contentIntent
        AgentAlertNotifier.build(app, alert(AgentAlertKind.Input, thread = "thread-2"), null)
        assertEquals("thread-1", shadowOf(first).savedIntent.getStringExtra("threadId"))
    }

    @Test fun oneNotificationPerThreadWithAPerThreadTagAndFixedId() {
        notifier.post(alert(AgentAlertKind.Approval), null)
        notifier.post(alert(AgentAlertKind.Input), null)
        notifier.post(alert(AgentAlertKind.Input, thread = "thread-2"), null)
        notifier.post(alert(AgentAlertKind.Input, env = "env-2"), null)
        val posted = manager.activeNotifications
        assertEquals(3, posted.size)
        assertTrue(posted.all { it.id == AgentAlertNotifier.NOTIFICATION_ID && it.tag.startsWith("agent-alert:") })
        assertEquals(setOf("agent-alert:env-1/thread-1", "agent-alert:env-1/thread-2", "agent-alert:env-2/thread-1"), posted.map { it.tag }.toSet())
        assertNotEquals(AgentAlertNotifier.tag("a", "b/c"), AgentAlertNotifier.tag("a/b", "c"))
    }

    @Test fun threadsWhoseIdsHashAlikeStaySeparate() {
        // "Aa" and "BB" share String.hashCode; the old hash-based id merged such threads.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        notifier.post(alert(AgentAlertKind.Approval, thread = "Aa"), null)
        notifier.post(alert(AgentAlertKind.Finished, thread = "BB"), null)
        assertEquals(2, manager.activeNotifications.size)
        notifier.cancel("env-1", "Aa")
        assertEquals("agent-alert:env-1/BB", manager.activeNotifications.single().tag)
    }

    @Test fun cancelRemovesOnlyThatThread() {
        notifier.post(alert(AgentAlertKind.Approval), null)
        notifier.post(alert(AgentAlertKind.Approval, thread = "thread-2"), null)
        notifier.cancel("env-1", "thread-1")
        assertEquals(AgentAlertNotifier.tag("env-1", "thread-2"), manager.activeNotifications.single().tag)
    }

    @Test fun cancelAllKeepsOtherNotifications() {
        notifier.post(alert(AgentAlertKind.Approval), null)
        notifier.post(alert(AgentAlertKind.Finished, thread = "thread-2"), null)
        manager.notify(0x7e1f, AgentAlertNotifier.build(app, alert(AgentAlertKind.Approval), null))
        manager.notify("other", AgentAlertNotifier.NOTIFICATION_ID, AgentAlertNotifier.build(app, alert(AgentAlertKind.Approval), null))
        notifier.cancelAll()
        val left = manager.activeNotifications
        assertEquals(2, left.size)
        assertEquals(setOf(null, "other"), left.map { it.tag }.toSet())
    }
}

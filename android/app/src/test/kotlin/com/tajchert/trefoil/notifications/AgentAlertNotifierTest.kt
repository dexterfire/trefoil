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
    }

    @Test fun oneNotificationPerThreadWithAStableTagAndId() {
        notifier.post(alert(AgentAlertKind.Approval), null)
        notifier.post(alert(AgentAlertKind.Input), null)
        notifier.post(alert(AgentAlertKind.Input, thread = "thread-2"), null)
        notifier.post(alert(AgentAlertKind.Input, env = "env-2"), null)
        val posted = manager.activeNotifications
        assertEquals(3, posted.size)
        assertTrue(posted.all { it.tag == "agent-alert" })
        assertTrue(AgentAlertNotifier.notificationId("env-1", "thread-1") in posted.map { it.id })
        assertEquals(3, posted.map { it.id }.toSet().size)
        assertNotEquals(AgentAlertNotifier.notificationId("a", "bc"), AgentAlertNotifier.notificationId("ab", "c"))
    }

    @Test fun cancelRemovesOnlyThatThread() {
        notifier.post(alert(AgentAlertKind.Approval), null)
        notifier.post(alert(AgentAlertKind.Approval, thread = "thread-2"), null)
        notifier.cancel("env-1", "thread-1")
        assertEquals(AgentAlertNotifier.notificationId("env-1", "thread-2"), manager.activeNotifications.single().id)
    }

    @Test fun cancelAllKeepsOtherNotifications() {
        notifier.post(alert(AgentAlertKind.Approval), null)
        notifier.post(alert(AgentAlertKind.Finished, thread = "thread-2"), null)
        manager.notify(0x7e1f, AgentAlertNotifier.build(app, alert(AgentAlertKind.Approval), null))
        notifier.cancelAll()
        val left = manager.activeNotifications
        assertEquals(1, left.size)
        assertEquals(0x7e1f, left.single().id)
        assertNull(left.single().tag)
    }
}

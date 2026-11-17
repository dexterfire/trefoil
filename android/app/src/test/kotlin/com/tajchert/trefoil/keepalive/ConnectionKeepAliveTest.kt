package com.tajchert.trefoil.keepalive

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionKeepAliveTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifications get() = app.getSystemService(NotificationManager::class.java)

    @Before fun setUp() { ConnectionKeepAlive.reset() }

    private fun requestStart(name: String, reconnecting: Boolean = false): Intent {
        assertTrue(ConnectionKeepAlive.start(app, name, reconnecting))
        return assertNotNullAnd(shadowOf(app).nextStartedService)
    }
    private fun run(intent: Intent, startId: Int = 1): ServiceController<ConnectionKeepAliveService> =
        Robolectric.buildService(ConnectionKeepAliveService::class.java, intent).create().startCommand(0, startId)
    private fun startedService(name: String): ConnectionKeepAliveService = run(requestStart(name)).get()
    private fun <T> assertNotNullAnd(value: T?): T { assertNotNull(value); return value!! }
    private fun title(notification: android.app.Notification) = notification.extras.getString("android.title")

    @Test fun startPostsForegroundNotificationWithMachineNameOnly() {
        val service = startedService("Workstation")
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Connected to Workstation", title(notification))
        assertEquals("Trefoil keeps this connection open while you use other apps.", notification.extras.getString("android.text"))
        assertEquals("Disconnect", notification.actions.single().title.toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val channel = notifications.getNotificationChannel("connection_keepalive")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.canShowBadge())
    }

    @Test fun startWhileReconnectingSaysSo() {
        val service = run(requestStart("Workstation", reconnecting = true)).get()
        assertEquals("Reconnecting to Workstation", title(shadowOf(service).lastForegroundNotification))
    }

    @Test fun startWhileRunningUpdatesTheNotificationWithoutAnotherForegroundStart() {
        startedService("One")
        assertTrue(ConnectionKeepAlive.running.value)
        assertTrue(ConnectionKeepAlive.start(app, "Two"))
        assertNull(shadowOf(app).nextStartedService)
        assertEquals("Connected to Two", title(shadowOf(notifications).getNotification(ConnectionKeepAliveService.NOTIFICATION_ID)))
        assertTrue(ConnectionKeepAlive.start(app, "Two", reconnecting = true))
        assertEquals("Reconnecting to Two", title(shadowOf(notifications).getNotification(ConnectionKeepAliveService.NOTIFICATION_ID)))
    }

    @Test fun disconnectActionEmitsOnDisconnectRequests() = runTest(UnconfinedTestDispatcher()) {
        val service = startedService("Workstation")
        val action = shadowOf(service).lastForegroundNotification.actions.single().actionIntent
        val received = mutableListOf<Unit>()
        val job = launch { ConnectionKeepAlive.disconnectRequests.first().also { received += it } }
        val intent = shadowOf(action).savedIntent
        Robolectric.buildService(ConnectionKeepAliveService::class.java, intent).create().startCommand(0, 2)
        job.join()
        assertEquals(1, received.size)
    }

    @Test fun stopStopsTheServiceAndIsIdempotent() {
        startedService("Workstation")
        ConnectionKeepAlive.stop(app)
        assertNotNull(shadowOf(app).nextStoppedService)
        assertFalse(ConnectionKeepAlive.running.value)
        ConnectionKeepAlive.stop(app)
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun stopBeforeTheServiceRanEntersTheForegroundFirstThenStopsItself() {
        val intent = requestStart("Workstation")
        assertTrue(ConnectionKeepAlive.starting.value)
        ConnectionKeepAlive.stop(app)
        // stopService now would kill the app ("startForegroundService() did not then call startForeground()").
        assertNull(shadowOf(app).nextStoppedService)
        val controller = run(intent)
        assertNotNull(shadowOf(controller.get()).lastForegroundNotification)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(ConnectionKeepAlive.starting.value)
        controller.destroy()
        assertFalse(ConnectionKeepAlive.running.value)
    }

    @Test fun aStartAfterAPendingStopWins() {
        val first = requestStart("One")
        ConnectionKeepAlive.stop(app)
        val second = requestStart("Two")
        val controller = run(first)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        controller.withIntent(second).startCommand(0, 2)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(ConnectionKeepAlive.running.value)
        assertFalse(ConnectionKeepAlive.starting.value)
    }

    @Test fun runningFollowsTheServiceLifecycle() {
        val intent = requestStart("Workstation")
        assertFalse(ConnectionKeepAlive.running.value)
        val controller = run(intent)
        assertTrue(ConnectionKeepAlive.running.value)
        assertFalse(ConnectionKeepAlive.starting.value)
        controller.destroy()
        assertFalse(ConnectionKeepAlive.running.value)
    }

    @Test fun refusedStartForegroundStopsTheServiceItself() {
        for (refusal in listOf(IllegalStateException("not allowed"), SecurityException("denied"))) {
            ConnectionKeepAlive.reset()
            val controller = Robolectric.buildService(ConnectionKeepAliveService::class.java, requestStart("Workstation")).create()
            shadowOf(controller.get()).setThrowInStartForeground(refusal)
            controller.startCommand(0, 1)
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertNull(shadowOf(controller.get()).lastForegroundNotification)
            assertFalse(ConnectionKeepAlive.running.value)
            assertFalse(ConnectionKeepAlive.starting.value)
            controller.destroy()
        }
    }

    @Test fun refusedStartForegroundKeepsTheServiceForANewerPendingStart() {
        val first = requestStart("One")
        val second = requestStart("Two")
        val controller = Robolectric.buildService(ConnectionKeepAliveService::class.java, first).create()
        shadowOf(controller.get()).setThrowInStartForeground(IllegalStateException("not allowed"))
        controller.startCommand(0, 1)
        // The newer start still needs its own startForeground; stopping now would kill the app.
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(ConnectionKeepAlive.starting.value)
        shadowOf(controller.get()).setThrowInStartForeground(null)
        controller.withIntent(second).startCommand(0, 2)
        assertFalse(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(ConnectionKeepAlive.running.value)
    }

    @Test fun startNeverThrowsWhenTheOsRefuses() {
        val refusing = object : android.content.ContextWrapper(app) {
            override fun startForegroundService(service: android.content.Intent?) = throw IllegalStateException("not allowed")
        }
        assertFalse(ConnectionKeepAlive.start(refusing, "Workstation"))
        val denied = object : android.content.ContextWrapper(app) {
            override fun startForegroundService(service: android.content.Intent?) = throw SecurityException("denied")
        }
        assertFalse(ConnectionKeepAlive.start(denied, "Workstation"))
        assertFalse(ConnectionKeepAlive.starting.value)
    }
}

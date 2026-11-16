package com.tajchert.trefoil.keepalive

import android.app.Application
import android.app.NotificationManager
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionKeepAliveTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun startedService(name: String): ConnectionKeepAliveService {
        assertTrue(ConnectionKeepAlive.start(app, name))
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        return Robolectric.buildService(ConnectionKeepAliveService::class.java, intent).create().startCommand(0, 1).get()
    }

    @Test fun startPostsForegroundNotificationWithMachineNameOnly() {
        val service = startedService("Workstation")
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals("Connected to Workstation", notification.extras.getString("android.title"))
        assertEquals("Trefoil keeps this connection open while you use other apps.", notification.extras.getString("android.text"))
        assertEquals("Disconnect", notification.actions.single().title.toString())
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel("connection_keepalive")
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.canShowBadge())
    }

    @Test fun startAgainUpdatesTheNotificationName() {
        startedService("One")
        val service = startedService("Two")
        assertEquals("Connected to Two", shadowOf(service).lastForegroundNotification.extras.getString("android.title"))
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
        ConnectionKeepAlive.stop(app)
        assertNull(shadowOf(app).nextStartedService)
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
    }
}

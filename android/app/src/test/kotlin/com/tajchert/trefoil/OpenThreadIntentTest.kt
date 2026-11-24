package com.tajchert.trefoil

import android.content.Intent
import com.tajchert.trefoil.notifications.AgentAlertNotifier
import com.tajchert.trefoil.notifications.OpenThreadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenThreadIntentTest {
    private val token = "fixture-token"
    private fun open(env: String? = "env-1", thread: String? = "thread-1", action: String? = "com.tajchert.trefoil.action.OPEN_THREAD", tap: String? = token) =
        Intent(action).apply { env?.let { putExtra("environmentId", it) }; thread?.let { putExtra("threadId", it) }; tap?.let { putExtra("tapToken", it) } }
    private fun parseOpenThreadIntent(intent: Intent?) = parseOpenThreadIntent(intent, token)

    @Test fun parsesEnvironmentAndThread() {
        assertEquals(OpenThreadRequest("env-1", "thread-1"), parseOpenThreadIntent(open()))
    }

    @Test fun ignoresOtherActionsAndMissingOrBlankExtras() {
        assertNull(parseOpenThreadIntent(null))
        assertNull(parseOpenThreadIntent(open(action = Intent.ACTION_MAIN)))
        assertNull(parseOpenThreadIntent(Intent("com.tajchert.trefoil.action.OPEN_THREAD")))
        assertNull(parseOpenThreadIntent(open(env = null)))
        assertNull(parseOpenThreadIntent(open(thread = null)))
        assertNull(parseOpenThreadIntent(open(env = " ")))
        assertNull(parseOpenThreadIntent(open(thread = "")))
    }

    @Test fun ignoresIntentsWithoutTheTapToken() {
        // Another app can send the exported action, but not the secret only this app's notifications carry.
        assertNull(parseOpenThreadIntent(open(tap = null)))
        assertNull(parseOpenThreadIntent(open(tap = "")))
        assertNull(parseOpenThreadIntent(open(tap = "guess")))
        assertNull(parseOpenThreadIntent(open(), ""))
    }

    @Test fun theNotificationsOwnIntentCarriesTheInstallationToken() {
        val app = RuntimeEnvironment.getApplication()
        val token = AgentAlertNotifier.tapToken(app)
        assertTrue(token.length >= 32)
        assertEquals(token, AgentAlertNotifier.tapToken(app))
        assertEquals(OpenThreadRequest("env-1", "thread-1"), parseOpenThreadIntent(AgentAlertNotifier.openIntent(app, "env-1", "thread-1"), token))
    }

    @Test fun oneUserActionLaunchesAtMostOnePermissionDialog() {
        var launched = 0
        val requests = SinglePermissionRequest { launched++ }
        // Turning the setting on asks from the toggle, and the setting flow then reaches the automatic first-connect request.
        assertTrue(requests.request())
        assertEquals(false, requests.request())
        assertEquals(1, launched)
        requests.finished()
        assertTrue("a later action may ask again", requests.request())
        assertEquals(2, launched)
    }

    @Test fun agentNotificationPermissionIsAskedOnceOnAndroid13PlusWhileConnectedAndOn() {
        assertEquals(true, shouldAskAgentNotificationPermission(33, connected = true, settingOn = true, granted = false, asked = false))
        assertEquals(false, shouldAskAgentNotificationPermission(32, connected = true, settingOn = true, granted = false, asked = false))
        assertEquals(false, shouldAskAgentNotificationPermission(33, connected = false, settingOn = true, granted = false, asked = false))
        assertEquals(false, shouldAskAgentNotificationPermission(33, connected = true, settingOn = false, granted = false, asked = false))
        assertEquals(false, shouldAskAgentNotificationPermission(33, connected = true, settingOn = true, granted = true, asked = false))
        assertEquals(false, shouldAskAgentNotificationPermission(33, connected = true, settingOn = true, granted = false, asked = true))
    }

    @Test fun ignoresWrongTypedExtrasOversizedIdsAndRecentsRedelivery() {
        assertNull(parseOpenThreadIntent(Intent("com.tajchert.trefoil.action.OPEN_THREAD").putExtra("environmentId", 5).putExtra("threadId", "t")))
        assertNull(parseOpenThreadIntent(open(thread = "t".repeat(513))))
        assertNull(parseOpenThreadIntent(open().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }
}

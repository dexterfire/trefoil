package com.tajchert.trefoil

import android.content.Intent
import com.tajchert.trefoil.notifications.OpenThreadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenThreadIntentTest {
    private fun open(env: String? = "env-1", thread: String? = "thread-1", action: String? = "com.tajchert.trefoil.action.OPEN_THREAD") =
        Intent(action).apply { env?.let { putExtra("environmentId", it) }; thread?.let { putExtra("threadId", it) } }

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

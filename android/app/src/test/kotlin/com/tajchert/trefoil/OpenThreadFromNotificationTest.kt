package com.tajchert.trefoil

import android.app.NotificationManager
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.AgentAlert
import com.tajchert.trefoil.core.model.AgentAlertKind
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.notifications.AgentAlertNotifier
import com.tajchert.trefoil.notifications.OpenThreadRequest
import com.tajchert.trefoil.security.EnvironmentStore
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch

/** A notification tap held by the view model (fixtures only, no real machines or threads). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class OpenThreadFromNotificationTest {
    /** Saved machines whose first read waits for [loaded], like a slow keystore at cold start. */
    private class SlowStore(private val saved: List<SavedEnvironment>) : EnvironmentStore {
        val loaded = CountDownLatch(1)
        override var lastKnown: List<SavedEnvironment>? = null
        override fun read(): List<SavedEnvironment> { loaded.await(); return saved.also { lastKnown = it } }
        override fun readHostKeys(): List<SshHostKey> = emptyList()
        override fun writeHostKeys(keys: List<SshHostKey>) = Unit
        override fun write(environments: List<SavedEnvironment>) = Unit
        override fun <T> locked(block: () -> T): T = synchronized(this) { block() }
    }

    private val alpha = SavedEnvironment("alpha", "Alpha", "https://alpha.invalid", "fixture")
    private val beta = SavedEnvironment("beta", "Beta", "https://beta.invalid", "fixture")
    private val store = SlowStore(listOf(alpha, beta))
    private lateinit var model: TrefoilViewModel
    private lateinit var client: T3Client
    private lateinit var mutable: MutableStateFlow<ClientState>
    private val request = OpenThreadRequest("alpha", "t1")

    @Before fun setUp() {
        model = TrefoilViewModel(RuntimeEnvironment.getApplication(), store)
        client = TrefoilViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.get(model) as T3Client
        @Suppress("UNCHECKED_CAST")
        mutable = T3Client::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(client) as MutableStateFlow<ClientState>
        val socketType = Class.forName("okhttp3.WebSocket")
        val socket = Proxy.newProxyInstance(socketType.classLoader, arrayOf(socketType)) { _, method, _ ->
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Long.TYPE -> 0L; else -> null }
        }
        T3Client::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
        live(alpha)
    }

    @After fun tearDown() { store.loaded.countDown(); model.viewModelScope.cancel(); client.close() }

    private fun live(environment: SavedEnvironment, vararg threads: String) {
        mutable.value = ClientState(environment = environment, connection = ConnectionStatus.Connected, shellLoaded = true, shellLive = true,
            threads = threads.map { ThreadSummary(it, "p", it) })
    }
    /** Rows retained (or cached) while the connection comes back: listed, but not synchronized with the server. */
    private fun retained(environment: SavedEnvironment, connection: ConnectionStatus, vararg threads: String) {
        mutable.value = ClientState(environment = environment, connection = connection, shellLoaded = true, shellLive = false,
            threads = threads.map { ThreadSummary(it, "p", it) })
    }
    private fun attemptEnvironmentId() =
        TrefoilViewModel::class.java.getDeclaredField("attemptEnvironmentId").apply { isAccessible = true }.get(model) as String?
    private fun idle(times: Int = 20) = repeat(times) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met in time" }
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
    }

    @Test fun aColdStartTapWaitsForTheSavedMachinesThenOpensOnce() {
        live(alpha, "t1")
        model.openThreadFromNotification(request)
        idle()
        assertNull("nothing opens before the saved machines are known", model.openedThread.value)
        assertNull(model.state.value.selectedThreadId)
        store.loaded.countDown()
        awaitTrue { model.openedThread.value == request }
        assertEquals("t1", model.state.value.selectedThreadId)
        model.consumeOpenedThread()
        // Consumed: later changes never open it again.
        live(alpha, "t1", "t2")
        idle()
        assertNull(model.openedThread.value)
    }

    @Test fun waitsForTheSynchronizedShellAndRetriesAfterAReconnect() {
        store.loaded.countDown()
        // The background connection expired: the retained rows still list the thread.
        retained(alpha, ConnectionStatus.Reconnecting, "t1")
        model.openThreadFromNotification(request)
        idle()
        assertNull("never selected against retained rows", model.openedThread.value)
        assertNull(model.state.value.selectedThreadId)
        retained(alpha, ConnectionStatus.Connected, "t1")
        idle()
        assertNull("connected, but the shell is still catching up", model.openedThread.value)
        live(alpha, "t1")
        awaitTrue { model.openedThread.value == request }
        assertEquals("t1", model.state.value.selectedThreadId)
    }

    @Test fun aSynchronizedShellWithoutTheThreadDropsTheRequest() {
        store.loaded.countDown()
        retained(alpha, ConnectionStatus.Reconnecting, "t1")
        model.openThreadFromNotification(request)
        idle()
        live(alpha, "other")
        idle()
        live(alpha, "other", "t1")
        idle()
        assertNull(model.openedThread.value)
        assertNull(model.state.value.selectedThreadId)
    }

    @Test fun aTapDeliveredInTheBackgroundWaitsForTheForegroundAndWinsOverResume() {
        store.loaded.countDown()
        idle()
        model.pause()
        model.openThreadFromNotification(OpenThreadRequest("beta", "t1"))
        idle()
        assertNull("no connect is started in the background", attemptEnvironmentId())
        model.resume()
        // Resume brings back the tapped machine, not the one shown before the background.
        awaitTrue { attemptEnvironmentId() == "beta" }
    }

    @Test fun anUnknownMachineIsDropped() {
        store.loaded.countDown()
        model.openThreadFromNotification(OpenThreadRequest("gamma", "t1"))
        idle()
        live(alpha, "t1")
        idle()
        assertNull(model.openedThread.value)
        assertNull(model.state.value.selectedThreadId)
    }

    @Test fun anotherMachineListingTheSameThreadIdNeverOpensIt() {
        store.loaded.countDown()
        model.openThreadFromNotification(request)
        idle()
        live(beta, "t1")
        idle()
        assertNull(model.openedThread.value)
        assertNull(model.state.value.selectedThreadId)
        // The connection moved elsewhere, so the request is gone even when the machine comes back.
        live(alpha, "t1")
        idle()
        assertNull(model.openedThread.value)
    }

    @Test fun pickingAnotherThreadDropsTheRequest() {
        store.loaded.countDown()
        live(alpha, "other")
        model.openThreadFromNotification(request)
        idle()
        model.selectThread("other")
        idle()
        live(alpha, "other", "t1")
        idle()
        assertNull(model.openedThread.value)
    }

    @Test fun clearingTheModelCancelsAgentAlerts() {
        store.loaded.countDown()
        val app = RuntimeEnvironment.getApplication()
        AgentAlertNotifier(app).post(AgentAlert("alpha", "t1", AgentAlertKind.Approval, "Fixture", "p"), null)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(1, manager.activeNotifications.size)
        TrefoilViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
        assertTrue(manager.activeNotifications.isEmpty())
    }
}

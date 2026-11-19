package com.tajchert.trefoil

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class TrefoilViewModelLifecycleTest {
    private lateinit var model: TrefoilViewModel
    private lateinit var client: T3Client
    private lateinit var mutable: MutableStateFlow<ClientState>
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val sent = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        model = TrefoilViewModel(RuntimeEnvironment.getApplication())
        client = TrefoilViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.get(model) as T3Client
        // Seed an established session without touching a real server or weakening production constructors.
        @Suppress("UNCHECKED_CAST")
        mutable = T3Client::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(client) as MutableStateFlow<ClientState>
        mutable.value = ClientState(environment = environment, connection = ConnectionStatus.Connected,
            selectedThreadId = "thread", canWrite = true)
        val socketType = Class.forName("okhttp3.WebSocket")
        val socket = Proxy.newProxyInstance(socketType.classLoader, arrayOf(socketType)) { _, method, args ->
            if (method.name == "send" && args?.singleOrNull() is String) sent += args.single() as String
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Long.TYPE -> 0L; else -> null }
        }
        T3Client::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
        setPongAge(0)
    }

    @After fun tearDown() { model.viewModelScope.cancel(); client.close() }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun setPongAge(age: Long) = T3Client::class.java.getDeclaredField("pongAt").apply { isAccessible = true }.setLong(client, System.currentTimeMillis() - age)

    @Test fun stalePongReconnectsEvenWhenStateIsConnected() {
        model.pause()
        setPongAge(20_001)
        model.resume()
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
    }

    @Test fun resumeWithinGraceKeepsLiveSessionAndDoesNotStartAConnectionAttempt() {
        model.pause()
        advance(59_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        model.resume()
        advance(2_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertTrue(model.state.value.canWrite)
        assertEquals("thread", model.state.value.selectedThreadId)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun graceExpiresAndRetainsSelectionWithWritesDisabled() {
        model.pause()
        advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        assertFalse(model.state.value.canWrite)
        assertEquals("thread", model.state.value.selectedThreadId)
    }

    @Test fun nextPauseGetsANewGracePeriod() {
        model.pause(); advance(40_000); model.resume()
        model.pause(); advance(40_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(20_001)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun socketLostDuringGraceStartsNormalReconnectOnResume() {
        model.pause(); advance(1_000)
        mutable.value = mutable.value.copy(connection = ConnectionStatus.Error, canWrite = false, error = "Connection lost")
        model.resume()
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
    }

    @Test fun repeatedPauseDoesNotExtendGrace() {
        model.pause(); advance(40_000); model.pause(); advance(20_001)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    private fun field(name: String) = TrefoilViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    /** As after a successful connect: the machine is saved and the view model owns the live connection. */
    private fun armLiveConnection() {
        val initialized = field("initialized").get(model) as kotlinx.coroutines.Deferred<*>
        val deadline = System.currentTimeMillis() + 5_000
        while (!initialized.isCompleted && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(initialized.isCompleted)
        @Suppress("UNCHECKED_CAST")
        (field("_environments").get(model) as MutableStateFlow<List<SavedEnvironment>>).value = listOf(environment)
        field("liveEnvironment").set(model, environment)
    }
    private fun dropConnection() { mutable.value = mutable.value.copy(connection = ConnectionStatus.Error, canWrite = false, error = "Connection lost") }

    @Test fun unexpectedDropReconnectsThroughThePlanAfterABackoff() {
        armLiveConnection()
        dropConnection(); advance(1_999)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        advance(1)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
    }

    @Test fun reselectingTheLiveMachineFromTheListReusesTheSession() {
        armLiveConnection()
        // Back to the machine list leaves the session alone; tapping the same machine again must not reconnect.
        model.selectMachine(environment); advance(1_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertTrue(model.state.value.canWrite)
        assertEquals("thread", model.state.value.selectedThreadId)
        assertNotNull(client.millisSinceLastPong())
        assertEquals(0, pings())
    }

    @Test fun reselectingAStaleLiveMachineReconnects() {
        armLiveConnection()
        setPongAge(RETAINED_CONNECTION_FRESHNESS_MS + 1)
        model.selectMachine(environment)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
    }

    @Test fun selectingADifferentMachineClosesTheCurrentSession() {
        armLiveConnection()
        val other = SavedEnvironment("other", "Other", "https://other.invalid", "other")
        @Suppress("UNCHECKED_CAST")
        (field("_environments").get(model) as MutableStateFlow<List<SavedEnvironment>>).value = listOf(environment, other)
        model.selectMachine(other)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        val deadline = System.currentTimeMillis() + 5_000
        while (client.millisSinceLastPong() != null && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        // The previous machine's socket is gone before the new machine is tried.
        assertNull(client.millisSinceLastPong())
        assertNotEquals(ConnectionStatus.Connected, model.state.value.connection)
        // Reselecting the machine being connected joins that attempt instead of starting another.
        val attempt = model.connectionAttempt.value.id
        model.selectMachine(other)
        if (model.connectionAttempt.value.phase == ConnectionPhase.Testing) assertEquals(attempt, model.connectionAttempt.value.id)
    }

    @Test fun userDisconnectNeverAutoReconnects() {
        armLiveConnection()
        model.disconnect(); advance(100)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        advance(30_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun dropWhilePausedLeavesReconnectingToResume() {
        armLiveConnection()
        model.pause()
        dropConnection(); advance(30_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun dropWhileOfflineReconnectsWhenANetworkReturns() {
        model.onDefaultNetwork(1); model.onDefaultNetworkLost(1)
        armLiveConnection()
        dropConnection(); advance(30_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        model.onDefaultNetwork(2); advance(2_000)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
    }

    private fun pings() = sent.count { it.contains("\"Ping\"") }

    @Test fun unchangedDefaultNetworkDoesNotCheckTheLink() {
        armLiveConnection()
        model.onDefaultNetwork(1); model.onDefaultNetwork(1); advance(30_000)
        assertEquals(0, pings())
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun networkChangePingsTheLinkAndASilentLinkReconnectsWithoutBackoff() {
        armLiveConnection()
        model.onDefaultNetwork(1); model.onDefaultNetwork(2); advance(0)
        assertEquals(1, pings())
        advance(LINK_CHECK_TIMEOUT_MS - 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        advance(1)
        // Dropped and already walking the plan: no initial 2 s backoff.
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
    }

    @Test fun answeredLinkCheckKeepsTheConnectionAndLaterDropsWaitTheBackoff() {
        armLiveConnection()
        model.onDefaultNetwork(1); model.onDefaultNetwork(2); advance(0)
        assertEquals(1, pings())
        T3Client::class.java.getDeclaredMethod("receive", kotlinx.serialization.json.JsonObject::class.java).apply { isAccessible = true }
            .invoke(client, kotlinx.serialization.json.buildJsonObject { put("_tag", kotlinx.serialization.json.JsonPrimitive("Pong")) })
        advance(LINK_CHECK_TIMEOUT_MS + 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        dropConnection(); advance(1_999)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        advance(1)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
    }

    @Test fun filePickerKeepsSshLoginWhileGraceStillExpires() {
        val login = SshConnectionInput("Fixture", "fixture.invalid", "fixture")
        @Suppress("UNCHECKED_CAST")
        val pending = TrefoilViewModel::class.java.getDeclaredField("_sshLogin").apply { isAccessible = true }.get(model) as MutableStateFlow<SshConnectionInput?>
        pending.value = login
        model.sshFilePickerActive = true
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(login, model.sshLogin.value)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    private fun shellSubscribes() = sent.mapNotNull { Regex(""""id":"(\d+)","tag":"orchestration.subscribeShell"""").find(it)?.groupValues?.get(1) }
    private fun seedShellStream() {
        T3Client::class.java.getDeclaredField("shellSubscription").apply { isAccessible = true }.set(client, "shell-0")
        T3Client::class.java.getDeclaredField("shellReady").apply { isAccessible = true }.setBoolean(client, true)
    }
    private fun receive(frame: String) = T3Client::class.java.getDeclaredMethod("receive", kotlinx.serialization.json.JsonObject::class.java).apply { isAccessible = true }
        .invoke(client, kotlinx.serialization.json.Json.parseToJsonElement(frame) as kotlinx.serialization.json.JsonObject)

    @Test fun pullToRefreshResubscribesTheShellAndReportsRefreshingUntilSynchronized() {
        seedShellStream()
        mutable.value = mutable.value.copy(threads = listOf(ThreadSummary("t", "p", "Fixture", "completed")))
        model.refresh(); advance(0)
        assertTrue(model.isRefreshing.value)
        val id = shellSubscribes().single()
        assertTrue(sent.any { it.contains("\"Interrupt\"") && it.contains("shell-0") })
        model.refresh(); advance(0)
        assertEquals("A refresh in flight is not restarted", 1, shellSubscribes().size)
        receive("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[{"id":"t","projectId":"p","title":"Fixture","status":"completed","pendingBackgroundTasks":[{"taskId":"a","kind":"subagent"}]}],"archivedThreads":[]}},{"kind":"synchronized"}]}""")
        advance(0)
        assertFalse(model.isRefreshing.value)
        assertTrue(model.state.value.threads.single().completionHeldBackgroundWork)
        assertTrue("Refresh is read-only", sent.none { it.contains("dispatchCommand") })
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun pullToRefreshWithoutALiveConnectionReconnects() {
        mutable.value = mutable.value.copy(connection = ConnectionStatus.Error)
        model.refresh(); advance(0)
        assertTrue(model.isRefreshing.value)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
    }

    @Test fun resumeOnARetainedSessionSilentlyRereadsTheShell() {
        seedShellStream()
        // A quick app switch keeps the live stream as is.
        model.pause(); advance(5_000)
        model.resume(); advance(0)
        assertEquals(0, shellSubscribes().size)
        // A longer stay in the background re-reads the shell once.
        model.pause(); advance(RESUME_SHELL_REFRESH_MIN_BACKGROUND_MS + 1_000)
        model.resume(); advance(0)
        assertEquals(1, shellSubscribes().size)
        assertFalse(model.isRefreshing.value)
        // A repeat while that re-read is still pending does not stack another one.
        model.pause(); advance(RESUME_SHELL_REFRESH_MIN_BACKGROUND_MS + 1_000)
        model.resume(); advance(0)
        assertEquals(1, shellSubscribes().size)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }
}

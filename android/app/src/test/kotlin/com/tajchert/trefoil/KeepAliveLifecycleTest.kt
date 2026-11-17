package com.tajchert.trefoil

import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.keepalive.ConnectionKeepAlive
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
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class KeepAliveLifecycleTest {
    private class FakeControl(var accept: Boolean = true) : KeepAliveControl {
        val starts = mutableListOf<String>()
        val reconnecting = mutableListOf<Boolean>()
        var stops = 0
        override val running = MutableStateFlow(false)
        override val starting = MutableStateFlow(false)
        override fun start(context: Context, machineName: String, reconnecting: Boolean): Boolean {
            starts += machineName; this.reconnecting += reconnecting
            if (accept) running.value = true
            return accept
        }
        override fun stop(context: Context) { stops++; running.value = false }
    }

    private lateinit var model: TrefoilViewModel
    private lateinit var client: T3Client
    private lateinit var mutable: MutableStateFlow<ClientState>
    private val control = FakeControl()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")

    @Before fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("trefoil.ui", Context.MODE_PRIVATE).edit().clear().commit()
        model = TrefoilViewModel(RuntimeEnvironment.getApplication())
        model.keepAliveControl = control
        client = field("client").get(model) as T3Client
        // Seed an established session without touching a real server or weakening production constructors.
        @Suppress("UNCHECKED_CAST")
        mutable = T3Client::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(client) as MutableStateFlow<ClientState>
        mutable.value = ClientState(environment = environment, connection = ConnectionStatus.Connected, selectedThreadId = "thread", canWrite = true)
        val socketType = Class.forName("okhttp3.WebSocket")
        val socket = Proxy.newProxyInstance(socketType.classLoader, arrayOf(socketType)) { _, method, _ ->
            when (method.returnType) { java.lang.Boolean.TYPE -> true; java.lang.Long.TYPE -> 0L; else -> null }
        }
        T3Client::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(client, socket)
        T3Client::class.java.getDeclaredField("pongAt").apply { isAccessible = true }.setLong(client, System.currentTimeMillis())
    }

    @After fun tearDown() { model.viewModelScope.cancel(); client.close() }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
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
    private fun enableKeepAlive() { model.setKeepConnected(true); advance(0) }

    @Test fun settingIsPersistedAndOffByDefault() {
        assertFalse(model.keepConnected.value)
        model.setKeepConnected(true)
        assertTrue(model.keepConnected.value)
        assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("trefoil.ui", Context.MODE_PRIVATE)
            .getBoolean("keepConnectedInBackground", false))
    }

    @Test fun keepAliveStartsInTheForegroundForTheLiveMachine() {
        enableKeepAlive()
        assertEquals(listOf("Fixture"), control.starts)
        assertTrue(model.keepAliveActive.value)
    }

    @Test fun activeKeepAliveKeepsTheConnectionPastTheGrace() {
        enableKeepAlive()
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertTrue(model.state.value.canWrite)
        assertEquals(0, control.stops)
    }

    @Test fun settingOffKeepsTheGraceDisconnect() {
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        assertTrue(control.starts.isEmpty())
    }

    @Test fun refusedStartBehavesLikeTheSettingIsOff() {
        control.accept = false
        enableKeepAlive()
        assertFalse(model.keepAliveActive.value)
        // One attempt per session: no retry while the refused session lasts.
        model.pause(); advance(1_000); model.resume(); advance(0)
        assertEquals(1, control.starts.size)
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun neverStartsInTheBackground() {
        model.pause()
        model.setKeepConnected(true); advance(0)
        assertTrue(control.starts.isEmpty())
        assertFalse(model.keepAliveActive.value)
    }

    @Test fun dropWhileBackgroundedWithKeepAliveSchedulesAReconnect() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        dropConnection(); advance(1_999)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertTrue(model.keepAliveActive.value)
        advance(1)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.id > 0)
        assertTrue(model.keepAliveActive.value)
    }

    @Test fun dropWhileOfflineInBackgroundKeepsTheServiceUntilTheNetworkReturns() {
        model.onDefaultNetwork(1); model.onDefaultNetworkLost(1)
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        dropConnection(); advance(30_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertTrue(model.keepAliveActive.value)
        model.onDefaultNetwork(2); advance(0)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
    }

    @Test fun settingOffWhileBackgroundedRestoresTheGraceDisconnect() {
        enableKeepAlive()
        model.pause(); advance(30_000)
        model.setKeepConnected(false); advance(0)
        assertFalse(model.keepAliveActive.value)
        assertEquals(1, control.stops)
        advance(BACKGROUND_CONNECTION_GRACE_MS - 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun connectionEndingWithoutAReconnectStopsTheService() {
        enableKeepAlive()
        dropConnection(); advance(0)
        assertFalse(model.keepAliveActive.value)
        assertEquals(1, control.stops)
    }

    @Test fun explicitDisconnectStopsTheServiceAndNeverReconnects() {
        armLiveConnection()
        enableKeepAlive()
        model.disconnect(); advance(100)
        assertFalse(model.keepAliveActive.value)
        assertEquals(1, control.stops)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        advance(30_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertEquals(1, control.starts.size)
    }

    @Test fun notificationDisconnectInTheBackgroundDisconnects() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        ConnectionKeepAlive.requestDisconnect(); advance(100)
        assertFalse(model.keepAliveActive.value)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        advance(30_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun wantedOnlyWhenEnabledAndLiveOrReconnecting() {
        for (status in listOf(ConnectionStatus.Connected, ConnectionStatus.Reconnecting)) {
            assertTrue(keepAliveWanted(true, status, reconnectPending = false))
            assertFalse(keepAliveWanted(false, status, reconnectPending = true))
        }
        // A plain first connect does not start it; an attempt continuing a kept connection counts as a pending reconnect.
        for (status in listOf(ConnectionStatus.Connecting, ConnectionStatus.Disconnected, ConnectionStatus.Error)) {
            assertFalse(keepAliveWanted(true, status, reconnectPending = false))
            assertTrue(keepAliveWanted(true, status, reconnectPending = true))
        }
    }

    @Test fun stepStartsOnlyInTheForegroundUpdatesWhenChangedAndStopsWhenUnwanted() {
        assertEquals(KeepAliveStep.Start, keepAliveStep(wanted = true, active = false, foreground = true, refused = false, changed = true))
        assertEquals(KeepAliveStep.None, keepAliveStep(wanted = true, active = false, foreground = false, refused = false, changed = true))
        assertEquals(KeepAliveStep.None, keepAliveStep(wanted = true, active = false, foreground = true, refused = true, changed = true))
        assertEquals(KeepAliveStep.None, keepAliveStep(wanted = true, active = true, foreground = true, refused = false, changed = false))
        assertEquals(KeepAliveStep.Update, keepAliveStep(wanted = true, active = true, foreground = true, refused = false, changed = true))
        assertEquals(KeepAliveStep.Update, keepAliveStep(wanted = true, active = true, foreground = false, refused = false, changed = true))
        assertEquals(KeepAliveStep.Stop, keepAliveStep(wanted = false, active = true, foreground = false, refused = false, changed = false))
        assertEquals(KeepAliveStep.None, keepAliveStep(wanted = false, active = false, foreground = true, refused = false, changed = false))
    }

    @Test fun firstConnectDoesNotStartTheService() {
        mutable.value = mutable.value.copy(connection = ConnectionStatus.Connecting, canWrite = false)
        enableKeepAlive()
        assertTrue(control.starts.isEmpty())
        mutable.value = mutable.value.copy(connection = ConnectionStatus.Connected, canWrite = true); advance(0)
        assertEquals(listOf("Fixture"), control.starts)
    }

    @Test fun serviceEndingOnItsOwnInTheBackgroundRestoresTheGraceDisconnect() {
        enableKeepAlive()
        model.pause(); advance(30_000)
        control.running.value = false; advance(0)
        assertFalse(model.keepAliveActive.value)
        advance(BACKGROUND_CONNECTION_GRACE_MS - 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun serviceEndingOnItsOwnIsNotRestartedForThisConnection() {
        enableKeepAlive()
        control.running.value = false; advance(0)
        assertFalse(model.keepAliveActive.value)
        model.pause(); advance(1_000); model.resume(); advance(0)
        assertEquals(1, control.starts.size)
        // A pending start is not a dead service.
        model.setKeepConnected(false); advance(0); model.setKeepConnected(true); advance(0)
        assertTrue(model.keepAliveActive.value)
        control.starting.value = true; control.running.value = false; advance(0)
        assertTrue(model.keepAliveActive.value)
    }

    @Test fun disconnectWhileTheConnectionLockIsHeldNeverRestartsTheService() {
        armLiveConnection()
        enableKeepAlive()
        val mutex = field("connectionMutex").get(model) as kotlinx.coroutines.sync.Mutex
        assertTrue(mutex.tryLock())
        model.disconnect(); advance(100)
        // The state is still live while the disconnect waits for the lock; the posted check must not start it again.
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertFalse(model.keepAliveActive.value)
        mutex.unlock(); advance(100)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        assertEquals(1, control.starts.size)
    }

    @Test fun resumeDuringARunningBackgroundReconnectAttemptLetsItContinue() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        // The held lock keeps the episode's first attempt running.
        val mutex = field("connectionMutex").get(model) as kotlinx.coroutines.sync.Mutex
        assertTrue(mutex.tryLock())
        dropConnection(); advance(2_000)
        val attempt = model.connectionAttempt.value
        assertEquals(ConnectionPhase.Testing, attempt.phase)
        assertTrue(attempt.id > 0)
        model.resume(); advance(0)
        // Connecting here would cancel the running attempt.
        assertEquals(attempt, model.connectionAttempt.value)
        mutex.unlock(); advance(0)
    }

    @Test fun resumeDuringABackgroundReconnectDelayConnectsOnceRightAway() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        dropConnection(); advance(1_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        model.resume(); advance(0)
        // Testing, or already Failed: the fixture host never resolves.
        val attempt = model.connectionAttempt.value
        assertTrue(attempt.id > 0)
        assertNotEquals(ConnectionPhase.Idle, attempt.phase)
        assertNull(field("autoReconnectJob").get(model))
        // The cancelled episode never adds a second connect when its backoff would have ended.
        advance(30_000)
        assertEquals(attempt.id, model.connectionAttempt.value.id)
    }

    @Test fun resumeDuringAForegroundDropBackoffConnectsTheEpisodeMachine() {
        armLiveConnection()
        enableKeepAlive()
        // The drop happens in the foreground, so the pause records no machine to resume.
        dropConnection(); advance(500)
        model.pause(); advance(500)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        model.resume(); advance(0)
        assertTrue(model.connectionAttempt.value.id > 0)
        assertNotEquals(ConnectionPhase.Idle, model.connectionAttempt.value.phase)
    }

    @Test fun resumeDuringABackgroundReconnectDelayWhileOfflineKeepsWaiting() {
        model.onDefaultNetwork(1)
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        dropConnection(); advance(1_000)
        model.onDefaultNetworkLost(1)
        model.resume(); advance(1_000)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        // The episode handed over to the network watch, which connects once a network returns.
        model.onDefaultNetwork(2); advance(0)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
    }

    @Test fun notificationShowsReconnectingInTheBackgroundWithoutFlapping() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        dropConnection(); advance(0)
        assertEquals(listOf("Fixture", "Fixture"), control.starts)
        assertEquals(listOf(false, true), control.reconnecting)
        mutable.value = mutable.value.copy(error = "Still lost"); advance(0)
        assertEquals(2, control.starts.size)
    }

    private fun hostKeyFailure(changed: Boolean) {
        val method = TrefoilViewModel::class.java.getDeclaredMethod("connectionAction", String::class.java, ConnectionKind::class.java,
            kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        val key = SshHostKey("fixture.invalid", 22, "ssh-ed25519", "SHA256:fixture")
        val block: suspend () -> Unit = { throw com.tajchert.trefoil.core.ssh.SshHostKeyException(key, changed) }
        method.invoke(model, "failed", ConnectionKind.Ssh, block)
        advance(0)
    }

    @Test fun unknownHostKeyInABackgroundAttemptFailsWithoutAPrompt() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        hostKeyFailure(changed = false)
        assertNull(model.sshHostKey.value)
        assertEquals(ConnectionPhase.Failed, model.connectionAttempt.value.phase)
    }

    @Test fun unknownHostKeyInTheForegroundStillAsksForTrust() {
        armLiveConnection()
        hostKeyFailure(changed = false)
        assertNotNull(model.sshHostKey.value)
        assertEquals(ConnectionPhase.AwaitingTrust, model.connectionAttempt.value.phase)
    }

    @Test fun changedHostKeyInABackgroundAttemptStillFailsClosed() {
        armLiveConnection()
        enableKeepAlive()
        model.pause()
        hostKeyFailure(changed = true)
        assertNull(model.sshHostKey.value)
        assertEquals(ConnectionPhase.Failed, model.connectionAttempt.value.phase)
        assertTrue(model.connectionAttempt.value.message.contains("changed"))
    }
}

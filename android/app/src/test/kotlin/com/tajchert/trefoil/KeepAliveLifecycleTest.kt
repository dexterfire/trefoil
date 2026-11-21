package com.tajchert.trefoil

import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.T3Client
import com.tajchert.trefoil.keepalive.ConnectionKeepAlive
import com.tajchert.trefoil.ui.ports.EXTERNAL_FORWARD_HOLD_MS
import com.tajchert.trefoil.ui.ports.ExternalForward
import com.tajchert.trefoil.ui.ports.ExternalOpenRequest
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
    /** [async]: like the real service, an accepted first start is only `starting` until [finishStart] reports startForeground. */
    private class FakeControl(var accept: Boolean = true, var async: Boolean = false) : KeepAliveControl {
        val starts = mutableListOf<String>()
        val reconnecting = mutableListOf<Boolean>()
        var stops = 0
        override val running = MutableStateFlow(false)
        override val starting = MutableStateFlow(false)
        override fun start(context: Context, machineName: String, reconnecting: Boolean): Boolean {
            starts += machineName; this.reconnecting += reconnecting
            if (accept) { if (async && !running.value) starting.value = true else running.value = true }
            return accept
        }
        override fun stop(context: Context) { stops++; running.value = false }
        fun finishStart(inForeground: Boolean) {
            check(starting.value)
            running.value = inForeground; starting.value = false
        }
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
    /** Idles the main looper until [done], for work that hops through IO (initialization, disconnect). */
    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!done() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        assertTrue(done())
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
        // The held lock keeps the reconnect in Testing, however fast the fixture host fails to resolve.
        val mutex = field("connectionMutex").get(model) as kotlinx.coroutines.sync.Mutex
        assertTrue(mutex.tryLock())
        try {
            model.onDefaultNetwork(2); advance(0)
            assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        } finally { mutex.unlock(); advance(0) }
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

    private val forward = PortForward("fixture", 5173, localPort = 25173, status = PortForwardStatus.Active, instance = 7)
    /** As after "Open in browser" once the browser took the URL (what MainActivity reports after startActivity). */
    private fun openForwardExternally() {
        mutable.value = mutable.value.copy(portForwards = listOf(forward))
        model.portForwards.externalOpened(ExternalOpenRequest(forward.localUrl!!, ExternalForward("fixture", 5173, 7))); advance(0)
    }

    @Test fun anExternallyOpenedForwardKeepsTheConnectionPastTheGraceWithTheSettingOff() {
        openForwardExternally()
        assertEquals(listOf("Fixture"), control.starts)
        assertTrue(model.keepAliveActive.value)
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertEquals(listOf(forward), model.state.value.portForwards)
        assertEquals(0, control.stops)
    }

    @Test fun aForwardUsedOnlyInTheAppDoesNotHoldTheConnection() {
        mutable.value = mutable.value.copy(portForwards = listOf(forward)); advance(0)
        assertTrue(control.starts.isEmpty())
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun stoppingTheLastExternalForwardEndsTheHoldAndRestartsTheFullGrace() {
        openForwardExternally()
        model.pause(); advance(30_000)
        model.portForwards.stop(5173); advance(0)
        assertFalse(model.keepAliveActive.value)
        assertEquals(1, control.stops)
        advance(BACKGROUND_CONNECTION_GRACE_MS - 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun theHoldEndsAtTheCapWithOpenConnectionsAndTheGraceRunsOnce() {
        openForwardExternally()
        model.pause()
        mutable.value = mutable.value.copy(portForwards = listOf(forward.copy(activeConnections = 2))); advance(0)
        advance(EXTERNAL_FORWARD_HOLD_MS - 1)
        assertTrue(model.keepAliveActive.value)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(1)
        assertFalse(model.keepAliveActive.value)
        assertEquals(1, control.stops)
        // The forward stays until the normal grace, scheduled once from the cap, disconnects.
        advance(BACKGROUND_CONNECTION_GRACE_MS - 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        assertEquals(1, control.stops)
        // Read before the reconnect that resume starts can report its own outcome.
        model.resume()
        val message = model.notice.value!!
        assertTrue(message, message.startsWith("30-min browser limit ended port 5173") && message.contains("Keep connected in background"))
        assertTrue(model.portForwards.stoppedNotices.value.getValue("fixture").message.contains("30 minutes"))
    }

    /** Deep sleep: Handler time stood still, elapsed real time did not; the return ends the hold. */
    @Test fun aHoldPastItsDeadlineAfterDeepSleepEndsWhenTheAppNextRuns() {
        var now = 5_000_000L
        model.forwardClock = { now }
        openForwardExternally()
        model.pause(); advance(1_000)
        now += EXTERNAL_FORWARD_HOLD_MS
        assertTrue(model.keepAliveActive.value)
        model.resume()
        assertTrue(model.portForwards.externalForwards.value.isEmpty())
        assertFalse(model.keepAliveActive.value)
    }

    @Test fun keepAliveUsersAreNeverToldToTurnItOnAfterTheCap() {
        enableKeepAlive()
        openForwardExternally()
        model.pause()
        advance(EXTERNAL_FORWARD_HOLD_MS + 1)
        assertTrue(model.portForwards.externalForwards.value.isEmpty())
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        dropConnection(); mutable.value = mutable.value.copy(portForwards = emptyList()); advance(0)
        model.resume()
        val message = model.notice.value!!
        assertTrue(message, message.contains("port 5173") && !message.contains("Keep connected"))
        assertFalse(model.portForwards.stoppedNotices.value.getValue("fixture").message.contains("Keep connected"))
    }

    @Test fun pairingAnotherMachineEndsTheHoldWithoutANotice() {
        armLiveConnection()
        openForwardExternally()
        model.pair("https://other.invalid/pair#token=fixture", "Other", false)
        idleUntil { model.portForwards.externalForwards.value.isEmpty() }
        idleUntil { mutable.value.portForwards.isEmpty() }
        assertTrue(model.portForwards.stoppedNotices.value.isEmpty())
    }

    @Test fun forgettingTheShownMachineEndsTheHoldWithoutANotice() {
        armLiveConnection()
        openForwardExternally()
        model.forget(environment)
        idleUntil { mutable.value.portForwards.isEmpty() }
        assertTrue(model.portForwards.externalForwards.value.isEmpty())
        assertTrue(model.portForwards.stoppedNotices.value.isEmpty())
    }

    /** The login prompt can still be cancelled: until a connection attempt really replaces this one, the page keeps its hold. */
    @Test fun selectingAnSshMachineThatAsksForALoginKeepsTheHold() {
        openForwardExternally()
        val ssh = SavedEnvironment("ssh", "Home", "", "fixture", ssh = SshProfile("home.invalid", 22, "dev", "SHA256:fixture", "ssh-ed25519"))
        model.connect(ssh); advance(0)
        assertNotNull(model.sshLogin.value)
        assertEquals(setOf(ExternalForward("fixture", 5173, 7)), model.portForwards.externalForwards.value)
        model.cancelSsh(); advance(0)
        assertEquals(setOf(ExternalForward("fixture", 5173, 7)), model.portForwards.externalForwards.value)
    }

    /** A Reconnect tap or a connection failure never swallows the lost-forward announcement. */
    @Test fun aConnectionAttemptOrFailureDoesNotConsumeTheAnnouncement() {
        openForwardExternally()
        model.pause(); advance(1_000)
        mutable.value = mutable.value.copy(portForwards = listOf(forward.copy(status = PortForwardStatus.Failed, localPort = null))); advance(0)
        model.resume()
        val message = model.notice.value!!
        model.reconnect()
        assertEquals(message, model.notice.value)
        TrefoilViewModel::class.java.getDeclaredMethod("connectionFailed", java.lang.Long.TYPE, ConnectionKind::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(model, 99L, ConnectionKind.Saved, "Could not reconnect.")
        assertEquals("Could not reconnect.", model.notice.value)
        model.reconnect()
        assertEquals(message, model.notice.value)
    }

    /** A worker publishes the failure while the app returns: the collector runs only after resume. */
    @Test fun aLossQueuedJustBeforeResumeIsStillAnnounced() {
        openForwardExternally()
        model.pause(); advance(1_000)
        val failed = forward.copy(status = PortForwardStatus.Failed, localPort = null, error = PortForwardError.Unauthorized)
        Thread { mutable.value = mutable.value.copy(portForwards = listOf(failed)) }.apply { start(); join() }
        model.resume()
        advance(0)
        assertTrue(model.portForwards.externalForwards.value.isEmpty())
        val message = model.notice.value!!
        assertTrue(message, message.startsWith("Forward failed: port 5173"))
        assertTrue(model.portForwards.stoppedNotices.value.getValue("fixture").message.contains("port 5173 failed"))
    }

    @Test fun aForwardLostInTheBackgroundIsAnnouncedOnceForItsMachineAndClearedEverywhere() {
        openForwardExternally()
        model.pause(); advance(30_000)
        dropConnection(); mutable.value = mutable.value.copy(portForwards = emptyList()); advance(0)
        assertFalse(model.keepAliveActive.value)
        model.resume()
        val message = model.notice.value!!
        assertTrue(message, message.startsWith("Connection ended: port 5173"))
        assertTrue(model.portForwards.stoppedNotices.value.getValue("fixture").message.contains("port 5173"))
        // Dismissing on the ports screen clears the banner that announced it too.
        model.dismissStoppedForwards("fixture")
        assertNull(model.notice.value)
        assertTrue(model.portForwards.stoppedNotices.value.isEmpty())
    }

    @Test fun clearingTheNoticeByANewOpenClearsTheBannerToo() {
        openForwardExternally()
        model.pause(); advance(1_000)
        mutable.value = mutable.value.copy(portForwards = listOf(forward.copy(status = PortForwardStatus.Failed, localPort = null))); advance(0)
        model.resume()
        assertNotNull(model.notice.value)
        // What starting a forward does on the ports screen.
        model.portForwards.dismissStoppedNotice("fixture"); advance(0)
        assertNull(model.notice.value)
    }

    @Test fun aLaterLossIsAnnouncedAloneAndAnEarlierOneNeverAgain() {
        openForwardExternally()
        model.pause(); advance(1_000)
        val failedVite = forward.copy(status = PortForwardStatus.Failed, localPort = null)
        mutable.value = mutable.value.copy(portForwards = listOf(failedVite)); advance(0)
        model.resume()
        assertTrue(model.notice.value!!.contains("port 5173"))
        // Not dismissed; another forward opened in the browser and lost on a later visit.
        val api = PortForward("fixture", 3000, localPort = 23000, status = PortForwardStatus.Active, instance = 8)
        mutable.value = mutable.value.copy(portForwards = listOf(failedVite, api))
        model.portForwards.externalOpened(ExternalOpenRequest(api.localUrl!!, ExternalForward("fixture", 3000, 8))); advance(0)
        model.pause(); advance(1_000)
        mutable.value = mutable.value.copy(portForwards = listOf(failedVite, api.copy(status = PortForwardStatus.Failed, localPort = null))); advance(0)
        model.resume()
        val second = model.notice.value!!
        assertTrue(second, second.contains("port 3000") && !second.contains("5173"))
        assertEquals(setOf(3000, 5173), model.portForwards.stoppedNotices.value.getValue("fixture").lost.keys)
        model.pause(); model.resume()
        assertEquals(second, model.notice.value)
        // One dismissal clears the ports notice and the banner.
        model.dismissStoppedForwards("fixture")
        assertNull(model.notice.value)
    }

    @Test fun aLossOnAnotherMachineIsNotAnnouncedHere() {
        openForwardExternally()
        model.pause()
        mutable.value = mutable.value.copy(environment = environment.copy(id = "other", name = "Other"), portForwards = emptyList()); advance(0)
        model.resume()
        assertNull(model.notice.value)
        assertNotNull(model.portForwards.stoppedNotices.value["fixture"])
    }

    @Test fun theNotificationDisconnectEndsTheHoldWithoutALostForwardNotice() {
        openForwardExternally()
        model.pause(); advance(30_000)
        ConnectionKeepAlive.requestDisconnect(); advance(0)
        assertFalse(model.keepAliveActive.value)
        assertTrue(model.portForwards.externalForwards.value.isEmpty())
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
        assertTrue(model.portForwards.stoppedNotices.value.isEmpty())
    }

    @Test fun keepAliveUsersSeeNoChangeWhenTheirExternalForwardStops() {
        enableKeepAlive()
        openForwardExternally()
        model.portForwards.stop(5173); advance(0)
        assertTrue(model.keepAliveActive.value)
        assertEquals(listOf("Fixture"), control.starts)
        assertEquals(0, control.stops)
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

    @Test fun startAcceptedThenForegroundedKeepsTheService() {
        control.async = true
        enableKeepAlive()
        assertTrue(model.keepAliveActive.value)
        assertTrue(control.starting.value)
        control.finishStart(inForeground = true); advance(0)
        assertTrue(model.keepAliveActive.value)
        model.pause(); advance(BACKGROUND_CONNECTION_GRACE_MS + 1_000)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        assertEquals(1, control.starts.size)
    }

    @Test fun startRefusedAfterItWasAcceptedCountsAsRefused() {
        control.async = true
        enableKeepAlive()
        assertTrue(model.keepAliveActive.value)
        // startForeground fails inside the service: the start was accepted, but the service ends without running.
        control.finishStart(inForeground = false); advance(0)
        assertFalse(model.keepAliveActive.value)
        // Not retried while this connection lasts.
        model.pause(); advance(1_000); model.resume(); advance(0)
        assertEquals(1, control.starts.size)
        assertFalse(model.keepAliveActive.value)
    }

    @Test fun startRefusedAfterItWasAcceptedInTheBackgroundRestoresTheGraceDisconnect() {
        control.async = true
        enableKeepAlive()
        model.pause(); advance(30_000)
        // A pending start keeps the connection like a running service.
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        control.finishStart(inForeground = false); advance(0)
        assertFalse(model.keepAliveActive.value)
        advance(BACKGROUND_CONNECTION_GRACE_MS - 1)
        assertEquals(ConnectionStatus.Connected, model.state.value.connection)
        advance(1)
        assertEquals(ConnectionStatus.Disconnected, model.state.value.connection)
    }

    @Test fun clearingTheViewModelStopsARunningService() {
        enableKeepAlive()
        assertTrue(model.keepAliveActive.value)
        TrefoilViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
        assertEquals(1, control.stops)
        assertFalse(model.keepAliveActive.value)
    }

    @Test fun clearingTheViewModelWithoutTheServiceStopsNothing() {
        TrefoilViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
        assertEquals(0, control.stops)
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

    @Test fun resumeNeverTakesOverAnEpisodeMadeObsoleteByALaterConnectionAction() {
        armLiveConnection()
        enableKeepAlive()
        dropConnection(); advance(500)
        // A later connection action that already ended moved the epoch on; the episode only notices when its delay ends.
        field("connectionEpoch").let { it.setLong(model, it.getLong(model) + 1) }
        model.pause(); advance(500)
        model.resume(); advance(0)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
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
        // The episode handed over to the network watch, which connects once a network returns. The held lock keeps
        // that attempt in Testing, so the assertion does not depend on how fast the fixture host fails to resolve.
        val mutex = field("connectionMutex").get(model) as kotlinx.coroutines.sync.Mutex
        assertTrue(mutex.tryLock())
        model.onDefaultNetwork(2); advance(0)
        assertEquals(ConnectionPhase.Testing, model.connectionAttempt.value.phase)
        mutex.unlock(); advance(0)
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

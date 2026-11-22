package com.tajchert.trefoil

import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.AccessTokenType
import com.tajchert.trefoil.core.model.ConnectionAttempt
import com.tajchert.trefoil.core.model.ConnectionPhase
import com.tajchert.trefoil.core.model.EnvironmentRoute
import com.tajchert.trefoil.core.model.RouteKind
import com.tajchert.trefoil.core.model.RouteSource
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.core.model.SshProfile
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadAction
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.ThreadTarget
import com.tajchert.trefoil.core.session.CachedShell
import com.tajchert.trefoil.core.session.CachedShellCursor
import com.tajchert.trefoil.core.session.ShellCacheSequencer
import com.tajchert.trefoil.core.session.ShellCacheStore
import com.tajchert.trefoil.core.session.ShellCacheTicket
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import com.tajchert.trefoil.security.EnvironmentStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The cold-start connect of the last-used machine, against loopback fixtures only (no real machines). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ColdStartConnectTest {
    private class MemoryStore(@Volatile var saved: List<SavedEnvironment>) : EnvironmentStore {
        override var lastKnown: List<SavedEnvironment>? = null
        override fun read() = synchronized(this) { saved.also { lastKnown = it } }
        override fun readHostKeys(): List<SshHostKey> = emptyList()
        override fun writeHostKeys(keys: List<SshHostKey>) = Unit
        override fun write(environments: List<SavedEnvironment>) = synchronized(this) { saved = environments; lastKnown = environments }
        override fun <T> locked(block: () -> T): T = synchronized(this) { block() }
    }

    /** The encrypted shell cache, in memory. */
    private class MemoryShellCache(vararg initial: CachedShell) : ShellCacheStore {
        private val sequencer = ShellCacheSequencer()
        val entries = ConcurrentHashMap(initial.associateBy { it.environmentId })
        val reads = CopyOnWriteArrayList<String>()
        /** When set, reads wait for it: a stalled file or Keystore call. */
        @Volatile var readGate: CompletableDeferred<Unit>? = null
        override fun ticket(environmentId: String) = sequencer.ticket(environmentId)
        override fun valid(environmentId: String, ticket: ShellCacheTicket) = sequencer.valid(environmentId, ticket)
        override suspend fun read(environmentId: String): CachedShell? { reads += environmentId; readGate?.await(); return entries[environmentId] }
        override suspend fun write(shell: CachedShell, ticket: ShellCacheTicket): Boolean = synchronized(this) {
            if (!sequencer.mayCommit(shell.environmentId, ticket)) return false
            entries[shell.environmentId] = shell; sequencer.committed(shell.environmentId, ticket); true
        }
        override suspend fun delete(environmentId: String) = synchronized(this) { sequencer.invalidate(environmentId); entries.remove(environmentId); Unit }
        override suspend fun retainOnly(environmentIds: Set<String>) = synchronized(this) { sequencer.invalidateAllExcept(environmentIds); entries.keys.retainAll(environmentIds); Unit }
    }

    private fun cached(id: String) = CachedShell(id, listOf(Project("p", "Project")),
        listOf(ThreadSummary("t1", "p", "First"), ThreadSummary("t2", "p", "Second")), CachedShellCursor("1.0.0", null, 9, System.currentTimeMillis()), 1)

    /** Accepts connections and never answers until [dropAll] closes them, which fails the client's TLS handshake. */
    private inner class HangingServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        val port get() = socket.localPort
        val accepted = AtomicInteger()
        @Volatile private var refusing = false
        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    clients += client; accepted.incrementAndGet()
                    if (refusing) runCatching { client.close() }
                }
            }
        }
        fun dropAll() = clients.forEach { runCatching { it.close() } }
        /** Closes every connection, now and from here on: the machine is down. */
        fun refuse() { refusing = true; dropAll() }
        override fun close() { dropAll(); socket.close() }
    }

    /** The view model's private last-used key (lastEnvironmentId). */
    private val LAST_ENVIRONMENT = "lastEnvironmentId"
    private val servers = mutableListOf<HangingServer>()
    private lateinit var model: TrefoilViewModel
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("trefoil.ui", Context.MODE_PRIVATE)

    @After fun tearDown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        servers.forEach { it.close() }
    }

    private fun machine(id: String, server: HangingServer) =
        SavedEnvironment(id, id.replaceFirstChar { it.uppercase() }, "https://127.0.0.1:${server.port}", "$id-token", tokenType = AccessTokenType.Bearer)

    /** Machines each probe saw, in order; filled when [start] is given probe results. */
    private val probed = CopyOnWriteArrayList<String>()

    /** With [probes], machine probes answer from it (missing: Unreachable) instead of reaching the fixtures. */
    private fun start(store: MemoryStore, lastUsed: String?, probes: Map<String, MachineProbe>? = null, shellCache: ShellCacheStore = MemoryShellCache()) {
        prefs.edit().apply { if (lastUsed == null) remove(LAST_ENVIRONMENT) else putString(LAST_ENVIRONMENT, lastUsed) }.commit()
        model = TrefoilViewModel(RuntimeEnvironment.getApplication(), store, shellCache = shellCache)
        if (probes != null) model.machineProbe = { environment, _ -> probed += environment.id; probes[environment.id] ?: MachineProbe.Unreachable }
        awaitTrue { model.environments.value == store.saved }
        model.resume()
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met in time" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** Counts connects started (each user or automatic connect moves the view model's private epoch on once). */
    private fun connects() = TrefoilViewModel::class.java.getDeclaredField("connectionEpoch").apply { isAccessible = true }.getLong(model)

    /** Lets the code under test run for [ms]; for asserting that something did not happen. */
    private fun idleFor(ms: Long) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }

    @Test fun theLastUsedMachineConnectsAtOnceAndTheOthersAreProbedOnlyAfterItSettles() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        val alpha = machine("alpha", alphaServer); val beta = machine("beta", betaServer)
        start(MemoryStore(listOf(alpha, beta)), lastUsed = "alpha")
        awaitTrue { alphaServer.accepted.get() >= 1 }
        assertTrue("The attempt runs without waiting for a probe", model.working.value)
        idleFor(300)
        assertEquals("No other machine is probed while the last-used one connects", 0, betaServer.accepted.get())
        assertTrue(model.machineProbes.value.isEmpty())

        // The attempt fails: quietly, then every machine is probed (the failed one too, so its card shows why).
        alphaServer.dropAll()
        awaitTrue { betaServer.accepted.get() >= 1 }
        assertNull("A failed cold-start connect raises no notice", model.notice.value)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertTrue("alpha" in model.machineProbes.value && "beta" in model.machineProbes.value)
        alphaServer.dropAll(); betaServer.dropAll()
        awaitTrue { model.machineProbes.value.values.none { it == MachineProbe.Checking } }
        // The failed machine is not connected again (no retry storm), and nothing is left running.
        alphaServer.dropAll(); betaServer.dropAll()
        idleFor(500)
        assertFalse(model.working.value)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertTrue("One connect and one probe at most", alphaServer.accepted.get() <= 3)
    }

    @Test fun aForgottenLastUsedMachineNeverConnectsAndTheProbesRunAsBefore() {
        val alphaServer = HangingServer()
        val alpha = machine("alpha", alphaServer)
        start(MemoryStore(listOf(alpha)), lastUsed = "gone")
        awaitTrue { model.machineProbes.value["alpha"] == MachineProbe.Checking }
        assertFalse(model.working.value)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun withoutALastUsedMachineNothingConnectsBeforeTheProbes() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        start(MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer))), lastUsed = null)
        awaitTrue { model.machineProbes.value.keys == setOf("alpha", "beta") }
        assertFalse(model.working.value)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun anSshOnlyLastUsedMachineWithoutASavedLoginNeverPromptsAtStart() {
        val sshOnly = SavedEnvironment("ssh", "Home", "", "ssh-token", ssh = SshProfile("127.0.0.1", 22, "fixture", "SHA256:fixture", "ssh-ed25519"))
        start(MemoryStore(listOf(sshOnly)), lastUsed = "ssh")
        idleFor(300)
        assertNull(model.sshLogin.value)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
    }

    @Test fun aMachineTheUserPicksDuringTheColdStartConnectWinsAndNothingAutoConnectsAfterIt() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        val alpha = machine("alpha", alphaServer); val beta = machine("beta", betaServer)
        start(MemoryStore(listOf(alpha, beta)), lastUsed = "alpha")
        awaitTrue { alphaServer.accepted.get() >= 1 }
        model.connect(beta)
        awaitTrue { betaServer.accepted.get() >= 1 }
        val attempt = model.connectionAttempt.value.id
        // The superseded attempt settles; its probes run, but neither it nor they start another connection.
        alphaServer.dropAll()
        idleFor(500)
        assertTrue(model.working.value)
        assertEquals("The user's attempt is the one running", attempt, model.connectionAttempt.value.id)
    }

    @Test fun anUnreachableLastUsedMachineFailsQuietlyAndReturnsToThePicker() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        start(MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer))), lastUsed = "alpha", probes = emptyMap())
        awaitTrue { alphaServer.accepted.get() >= 1 }
        assertEquals("Only the connecting machine is probed, alongside its attempt", listOf("alpha"), probed.toList())
        alphaServer.refuse()
        awaitTrue { "beta" in probed }
        assertNull(model.notice.value)
        assertNull("The failed selection is cleared, so the picker shows", model.state.value.environment)
        assertNull(model.state.value.error)
        assertEquals(ConnectionAttempt(), model.connectionAttempt.value)
        assertEquals(MachineProbe.Unreachable, model.machineProbes.value["alpha"])
        idleFor(500)
        val acceptedAfterSettling = alphaServer.accepted.get()
        idleFor(500)
        assertEquals("alpha is probed once and not connected again", listOf("alpha", "beta"), probed.toList())
        assertEquals(acceptedAfterSettling, alphaServer.accepted.get())
        assertEquals(1L, connects())
        assertFalse(model.working.value)
    }

    @Test fun aFailureTheProbeCannotExplainStaysLoudAndNothingAutoConnects() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        // The machine answers but rejects the saved sign-in: the user has to pair again, so the failure is shown.
        start(MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer))), lastUsed = "alpha",
            probes = mapOf("alpha" to MachineProbe.AuthFailed, "beta" to MachineProbe.Online))
        awaitTrue { alphaServer.accepted.get() >= 1 }
        alphaServer.refuse()
        awaitTrue { model.connectionAttempt.value.phase == ConnectionPhase.Failed }
        assertNotNull(model.notice.value ?: model.state.value.error)
        idleFor(500)
        assertEquals("No other machine is connected in its place", 1L, connects())
        assertEquals(0, betaServer.accepted.get())
    }

    @Test fun aUrlPolicyRefusalAtColdStartIsShown() {
        // Public plain HTTP is blocked by the shared URL policy; the real prober reports it as needing attention.
        val blocked = SavedEnvironment("alpha", "Alpha", "http://203.0.113.7:3773", "alpha-token", tokenType = AccessTokenType.Bearer)
        start(MemoryStore(listOf(blocked)), lastUsed = "alpha")
        awaitTrue { model.connectionAttempt.value.phase == ConnectionPhase.Failed }
        assertNotNull(model.notice.value ?: model.state.value.error)
    }

    @Test fun whenTheLastUsedMachineIsDownAndAnotherIsOnlineExactlyOneFollowUpConnectRuns() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        start(MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer))), lastUsed = "alpha",
            probes = mapOf("beta" to MachineProbe.Online))
        awaitTrue { alphaServer.accepted.get() >= 1 }
        assertEquals(1L, connects())
        alphaServer.refuse()
        awaitTrue { betaServer.accepted.get() >= 1 }
        assertEquals("One automatic connect, to the online machine", 2L, connects())
        val alphaAccepted = alphaServer.accepted.get()
        // beta fails as well: nothing connects again, neither alpha nor beta.
        betaServer.refuse()
        awaitTrue { !model.working.value }
        idleFor(1_000)
        assertEquals(2L, connects())
        assertEquals("alpha is not retried", alphaAccepted, alphaServer.accepted.get())
        assertEquals(listOf("alpha", "beta"), probed.toList())
    }

    private fun sshWithLanRoute(lan: HangingServer) = SavedEnvironment("ssh", "Home", "", "ssh-token",
        ssh = SshProfile("127.0.0.1", 22, "fixture", "SHA256:fixture", "ssh-ed25519"),
        routes = listOf(EnvironmentRoute(RouteKind.Lan, "https://127.0.0.1:${lan.port}", RouteSource.Learned)))

    @Test fun tappingTheMachineTheColdStartConnectIsReachingMakesItTheUsersAttempt() {
        val lanServer = HangingServer()
        val home = sshWithLanRoute(lanServer)
        start(MemoryStore(listOf(home)), lastUsed = "ssh", probes = emptyMap())
        awaitTrue { lanServer.accepted.get() >= 1 }
        val attempt = model.connectionAttempt.value.id
        model.selectMachine(home)
        idleFor(100)
        assertEquals("The running attempt is kept, not restarted", attempt, model.connectionAttempt.value.id)
        assertEquals(1L, connects())
        // The LAN address fails; SSH has no saved login, and the attempt is the user's now: it asks for one.
        lanServer.refuse()
        awaitTrue { model.sshLogin.value != null }
        assertEquals(1L, connects())
    }

    @Test fun withoutATapTheColdStartConnectNeverAsksForAnSshLogin() {
        val lanServer = HangingServer()
        start(MemoryStore(listOf(sshWithLanRoute(lanServer))), lastUsed = "ssh", probes = emptyMap())
        awaitTrue { lanServer.accepted.get() >= 1 }
        lanServer.refuse()
        awaitTrue { !model.working.value }
        idleFor(200)
        assertNull(model.sshLogin.value)
        assertEquals("An SSH machine's failure is shown", ConnectionPhase.Failed, model.connectionAttempt.value.phase)
    }

    @Test fun theColdStartShowsTheCachedRowsNotLiveAndAQuietFailureKeepsTheCache() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        val cache = MemoryShellCache(cached("alpha"), cached("beta"))
        start(MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer))), lastUsed = "alpha", probes = emptyMap(), shellCache = cache)
        awaitTrue { alphaServer.accepted.get() >= 1 }
        val shown = model.state.value
        assertEquals("alpha", shown.environment?.id)
        assertEquals("The cached rows show while connecting", listOf("t1", "t2"), shown.threads.map { it.id })
        assertTrue(shown.shellLoaded)
        assertFalse("Cached rows are never live", shown.shellLive)
        assertFalse(shown.canWrite); assertFalse(shown.canWriteThreads); assertFalse(shown.canRead)
        assertEquals(ConnectionStatus.Connecting, shown.connection)
        assertEquals("Only the machine shown at start is read", listOf("alpha"), cache.reads.toList())

        // Nothing can be written from cached rows: the action fails locally.
        model.threadAction(ThreadTarget("alpha", "t1"), ThreadAction.Settle, null)
        awaitTrue { model.notice.value != null }

        alphaServer.refuse()
        awaitTrue { "beta" in probed }
        assertNull("The failed selection is cleared", model.state.value.environment)
        idleFor(300)
        assertEquals("A quiet cold-start failure deselects and never wipes", setOf("alpha", "beta"), cache.entries.keys)
        assertEquals(listOf("t1", "t2"), cache.entries["alpha"]?.threads?.map { it.id })
    }

    @Test fun aStalledCacheReadNeverHoldsUpTheColdStartConnect() {
        val alphaServer = HangingServer()
        val cache = MemoryShellCache(cached("alpha")).apply { readGate = CompletableDeferred() }
        start(MemoryStore(listOf(machine("alpha", alphaServer))), lastUsed = "alpha", probes = emptyMap(), shellCache = cache)
        awaitTrue { alphaServer.accepted.get() >= 1 }
        assertEquals("The connect started while the read is stalled", listOf("alpha"), cache.reads.toList())
        assertEquals(ConnectionStatus.Connecting, model.state.value.connection)
        assertTrue(model.state.value.threads.isEmpty())
        // The read finishes while the connect is still before its shell subscription: the rows show, never live.
        cache.readGate!!.complete(Unit)
        awaitTrue { model.state.value.threads.isNotEmpty() }
        assertFalse(model.state.value.shellLive); assertFalse(model.state.value.canWriteThreads)
        assertEquals(ConnectionStatus.Connecting, model.state.value.connection)
    }

    @Test fun aCacheReadOverlappingAForgetIsNeverRestored() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        val cache = MemoryShellCache(cached("alpha"), cached("beta")).apply { readGate = CompletableDeferred() }
        val store = MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer)))
        start(store, lastUsed = null, probes = emptyMap(), shellCache = cache)
        model.connect(store.saved.first { it.id == "alpha" })
        awaitTrue { "alpha" in cache.reads }
        model.forget(store.saved.first { it.id == "alpha" })
        awaitTrue { store.saved.none { it.id == "alpha" } }
        cache.readGate!!.complete(Unit)
        idleFor(300)
        assertTrue(model.state.value.threads.isEmpty())
        assertFalse(cache.entries.containsKey("alpha"))
    }

    @Test fun forgettingAMachineWipesItsCacheEntryOnly() {
        val alphaServer = HangingServer(); val betaServer = HangingServer()
        val cache = MemoryShellCache(cached("alpha"), cached("beta"))
        val store = MemoryStore(listOf(machine("alpha", alphaServer), machine("beta", betaServer)))
        start(store, lastUsed = null, probes = emptyMap(), shellCache = cache)
        model.forget(store.saved.first { it.id == "alpha" })
        awaitTrue { !cache.entries.containsKey("alpha") && store.saved.none { it.id == "alpha" } }
        idleFor(200)
        assertEquals(setOf("beta"), cache.entries.keys)
    }

    @Test fun entriesOfMachinesNoLongerSavedAreRemovedAtStart() {
        val alphaServer = HangingServer()
        val cache = MemoryShellCache(cached("alpha"), cached("gone"))
        start(MemoryStore(listOf(machine("alpha", alphaServer))), lastUsed = "gone", probes = emptyMap(), shellCache = cache)
        awaitTrue { !cache.entries.containsKey("gone") }
        assertEquals(setOf("alpha"), cache.entries.keys)
        assertTrue("A machine no longer saved shows nothing", model.state.value.threads.isEmpty())
    }
}

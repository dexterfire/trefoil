package com.tajchert.trefoil

import android.content.Context
import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.AccessTokenType
import com.tajchert.trefoil.core.model.ConnectionAttempt
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.core.model.SshProfile
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

    /** Accepts connections and never answers until [dropAll] closes them, which fails the client's TLS handshake. */
    private inner class HangingServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val clients = CopyOnWriteArrayList<Socket>()
        val port get() = socket.localPort
        val accepted = AtomicInteger()
        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    clients += client; accepted.incrementAndGet()
                }
            }
        }
        fun dropAll() = clients.forEach { runCatching { it.close() } }
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

    private fun start(store: MemoryStore, lastUsed: String?) {
        prefs.edit().apply { if (lastUsed == null) remove(LAST_ENVIRONMENT) else putString(LAST_ENVIRONMENT, lastUsed) }.commit()
        model = TrefoilViewModel(RuntimeEnvironment.getApplication(), store)
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
}

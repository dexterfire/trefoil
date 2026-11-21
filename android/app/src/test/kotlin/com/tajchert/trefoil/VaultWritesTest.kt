package com.tajchert.trefoil

import android.os.Looper
import androidx.lifecycle.viewModelScope
import com.tajchert.trefoil.core.model.AccessTokenType
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.security.EnvironmentStore
import com.tajchert.trefoil.core.model.ConnectionPhase
import com.tajchert.trefoil.core.model.SshConnectionInput
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** View-model writes against a vault that the usage widget also writes (fixtures only, no real machines). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class VaultWritesTest {
    /** In-memory stand-in for one process's encrypted vault, with the same single lock and known list for every caller. */
    private class MemoryStore(var saved: List<SavedEnvironment>) : EnvironmentStore {
        /** The next this many reads of the machine list fail, as a keystore hiccup would. */
        @Volatile var failingReads = 0
        @Volatile var hostKeysUnreadable = false
        override var lastKnown: List<SavedEnvironment>? = null
        /** When set, the next write waits for it (after [writeEntered] opens), as a slow keystore write would. */
        @Volatile var writeGate: CountDownLatch? = null
        val writeEntered = CountDownLatch(1)
        override fun read() = synchronized(this) {
            if (failingReads > 0) { failingReads--; throw IllegalStateException("unreadable") }
            saved.also { lastKnown = it }
        }
        override fun readHostKeys(): List<SshHostKey> = if (hostKeysUnreadable) throw IllegalStateException("unreadable") else emptyList()
        override fun writeHostKeys(keys: List<SshHostKey>) = Unit
        override fun write(environments: List<SavedEnvironment>) = synchronized(this) {
            writeGate?.let { gate -> writeGate = null; writeEntered.countDown(); gate.await() }
            saved = environments; lastKnown = environments
        }
        override fun <T> locked(block: () -> T): T = synchronized(this) { block() }
    }

    private val alpha = SavedEnvironment("alpha", "Alpha", "https://alpha.invalid", "alpha-old",
        tokenType = AccessTokenType.DPoP, accessTokenExpiresAtMillis = 1_000)
    private val beta = SavedEnvironment("beta", "Beta", "https://beta.invalid", "beta-token")
    private val store = MemoryStore(listOf(alpha, beta))
    private lateinit var model: TrefoilViewModel

    private fun start() {
        model = TrefoilViewModel(RuntimeEnvironment.getApplication(), store)
        awaitTrue { model.environments.value == listOf(alpha, beta) }
    }

    private val servers = mutableListOf<HangingServer>()
    @After fun tearDown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        servers.forEach { it.close() }
    }

    /** Accepts connections and never answers, so a connection to it stays in progress until the client closes it. */
    private inner class HangingServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port get() = socket.localPort
        val accepted = AtomicInteger()
        val open = AtomicInteger()
        init {
            servers += this
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    accepted.incrementAndGet(); open.incrementAndGet()
                    // Reads until the client closes its end.
                    thread(isDaemon = true) { client.use { try { while (it.getInputStream().read() >= 0) Unit } catch (_: Exception) {} }; open.decrementAndGet() }
                }
            }
        }
        override fun close() = socket.close()
    }

    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met in time" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    /** Gives the code under test up to [ms] to make [condition] true; returns either way (not an assertion). */
    private fun idleUpTo(ms: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!condition() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
    }

    /** The usage widget renews alpha's session in the vault after the view model read it. */
    private fun widgetRenewsAlpha() = store.update { saved ->
        saved.map { if (it.id == "alpha") it.copy(accessToken = "alpha-new", accessTokenExpiresAtMillis = 9_000) else it }
    }

    @Test fun settingChangeOnAnotherMachineKeepsTheWidgetsRenewal() {
        start()
        widgetRenewsAlpha()
        model.allowLocalNetworkHttp("beta", true)
        awaitTrue { model.environments.value.first { it.id == "beta" }.allowInsecureHttp }
        val stored = store.read().first { it.id == "alpha" }
        assertEquals("alpha-new", stored.accessToken)
        assertEquals(9_000L, stored.accessTokenExpiresAtMillis)
        assertEquals("The view model shows what was stored", store.read(), model.environments.value)
    }

    @Test fun settingChangeOnTheRenewedMachineKeepsItsNewToken() {
        start()
        widgetRenewsAlpha()
        model.allowLocalNetworkHttp("alpha", true)
        awaitTrue { model.environments.value.first { it.id == "alpha" }.allowInsecureHttp }
        assertEquals("alpha-new", store.read().first { it.id == "alpha" }.accessToken)
        assertTrue(model.environments.value.first { it.id == "alpha" }.let { it.allowInsecureHttp && it.accessToken == "alpha-new" })
    }

    @Test fun unreadableHostKeysAtStartNeverLetAFailedReadEmptyTheMachines() {
        store.hostKeysUnreadable = true
        start()
        store.failingReads = 2
        model.allowLocalNetworkHttp("beta", true)
        awaitTrue { model.environments.value.first { it.id == "beta" }.allowInsecureHttp }
        // The vault stayed unreadable through the retry: the change applies to the list loaded at start.
        assertEquals(listOf(alpha, beta.copy(allowInsecureHttp = true)), store.read())
        assertEquals("The machines stay listed", store.read(), model.environments.value)
    }

    @Test fun aSecondViewModelInTheSameProcessKeepsTheMachinesAfterAFailedStartUpRead() {
        start()
        model.viewModelScope.cancel()
        // The activity is reopened in the same process; this model's own start-up read fails.
        store.failingReads = 1
        val second = TrefoilViewModel(RuntimeEnvironment.getApplication(), store)
        awaitTrue { second.notice.value == "Saved credentials could not be unlocked. Pair this device again." }
        model = second
        store.failingReads = 2
        second.forget(beta)
        awaitTrue { store.saved != listOf(alpha, beta) || second.notice.value == "Could not remove saved credentials." }
        assertEquals("The change applies to the list this process knows", listOf(alpha), store.saved)
    }

    @Test fun forgetDuringAConnectClosesItAndAQueuedRequestNeverConnects() {
        val server = HangingServer()
        val live = alpha.copy(baseUrl = "https://127.0.0.1:${server.port}", tokenType = AccessTokenType.Bearer, accessTokenExpiresAtMillis = null)
        store.saved = listOf(live, beta)
        model = TrefoilViewModel(RuntimeEnvironment.getApplication(), store)
        awaitTrue { model.environments.value == listOf(live, beta) }
        model.connect(live)
        awaitTrue { server.accepted.get() == 1 }
        model.forget(live)
        awaitTrue { store.saved == listOf(beta) && !model.working.value }
        awaitTrue { server.open.get() == 0 }
        // A request made with the old copy (a stale list row, a queued reconnect) stops without connecting.
        model.connect(live)
        awaitTrue { model.connectionAttempt.value.phase == ConnectionPhase.Failed }
        assertEquals(1, server.accepted.get())
        assertEquals(listOf(beta), store.saved)
    }

    @Test fun aTapWhileForgetIsDeletingTheMachineNeverConnectsIt() {
        val server = HangingServer()
        val live = alpha.copy(baseUrl = "https://127.0.0.1:${server.port}", tokenType = AccessTokenType.Bearer, accessTokenExpiresAtMillis = null)
        store.saved = listOf(live, beta)
        model = TrefoilViewModel(RuntimeEnvironment.getApplication(), store)
        awaitTrue { model.environments.value == listOf(live, beta) }
        val gate = CountDownLatch(1)
        store.writeGate = gate
        model.forget(live)
        awaitTrue { store.writeEntered.count == 0L }
        // The machine is still listed while its deletion is being written; the user taps it.
        model.connect(live)
        idleUpTo(2_000) { server.accepted.get() > 0 }
        gate.countDown()
        awaitTrue { store.saved == listOf(beta) && model.connectionAttempt.value.phase == ConnectionPhase.Failed }
        assertEquals("No connection was opened to the forgotten machine", 0, server.accepted.get())
    }

    @Test fun anSshReconnectOfAForgottenMachineNeverOpensSshOrPairsAnew() {
        val sshServer = HangingServer()
        start()
        model.forget(alpha)
        awaitTrue { store.saved == listOf(beta) && !model.working.value }
        model.connectSsh(SshConnectionInput("Alpha", "127.0.0.1", "user", port = sshServer.port, password = "fixture", savedEnvironmentId = "alpha"))
        awaitTrue { model.connectionAttempt.value.phase == ConnectionPhase.Failed }
        assertEquals(0, sshServer.accepted.get())
        assertEquals(listOf(beta), store.saved)
    }

    @Test fun savedSshReconnectTakesConsentAndExistenceFromTheVault() {
        // The copy the reconnect started from still has LAN consent; the user revoked it while SSH was opening.
        val configured = alpha.copy(name = "Renamed", allowInsecureHttp = true)
        val stored = listOf(alpha.copy(accessToken = "alpha-new", allowInsecureHttp = false), beta)
        val merged = stored.withReconnected(configured).first { it.id == "alpha" }
        assertEquals(false, merged.allowInsecureHttp)
        assertEquals("Renamed", merged.name)
        assertEquals("alpha-new", merged.accessToken)
        // Forgotten while the reconnect was in flight: the row is not re-created.
        assertEquals(listOf(beta), listOf(beta).withReconnected(configured))
    }
}

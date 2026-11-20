package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.model.RouteKind.*
import com.tajchert.trefoil.core.model.RouteSource.*
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConnectionPlanTest {
    private val lan = EnvironmentRoute(Lan, "https://192.168.1.5/", Learned)
    private val tailnet = EnvironmentRoute(Tailnet, "https://mac.tail1234.ts.net/", Learned)
    private val ssh = EnvironmentRoute(Ssh, "", Paired)
    private val tunnel = EnvironmentRoute(Tunnel, "https://prod-abc.t3coderelay.com/", Learned)

    private class Walk {
        val calls = mutableListOf<String>()
        val stages = mutableListOf<String>()
    }

    private suspend fun Walk.run(plan: List<EnvironmentRoute>, sshLoginSaved: Boolean = true, connectsOn: (List<EnvironmentRoute>?) -> Boolean): PlanOutcome =
        walkConnectionPlan(plan, sshLoginSaved, { stages += it },
            direct = { routes -> calls += routes.joinToString(",") { it.kind.name }; if (!connectsOn(routes)) throw PairingException("${routes.last().kind} failed") },
            ssh = { calls += "ssh"; if (!connectsOn(null)) throw SshConnectionException("SSH connection timed out.") },
        )

    @Test fun directRoutesRunTogetherAndSshKeepsItsSlot() = runBlocking {
        val walk = Walk()
        assertEquals(PlanOutcome.Connected, walk.run(listOf(lan, tailnet, ssh, tunnel)) { it == listOf(tunnel) })
        assertEquals(listOf("Lan,Tailnet", "ssh", "Tunnel"), walk.calls)
        assertEquals(listOf("Trying local network, then Tailscale…", "Trying T3 Connect tunnel…"), walk.stages)
    }

    @Test fun firstConnectingStepEndsTheWalk() = runBlocking {
        val walk = Walk()
        assertEquals(PlanOutcome.Connected, walk.run(listOf(lan, ssh, tunnel)) { it == null })
        assertEquals(listOf("Lan", "ssh"), walk.calls)
    }

    @Test fun sshWithoutSavedLoginIsSkippedBeforeTheTunnel() = runBlocking {
        val walk = Walk()
        assertEquals(PlanOutcome.Connected, walk.run(listOf(ssh, tunnel), sshLoginSaved = false) { true })
        assertEquals(listOf("Tunnel"), walk.calls)
    }

    @Test fun sshWithoutSavedLoginAsTheLastOptionAsksOnlyAfterEarlierRoutesFail() = runBlocking {
        val walk = Walk()
        assertEquals(PlanOutcome.NeedsSshLogin, walk.run(listOf(lan, ssh), sshLoginSaved = false) { false })
        assertEquals(listOf("Lan"), walk.calls)
        assertEquals(PlanOutcome.NeedsSshLogin, Walk().run(listOf(ssh), sshLoginSaved = false) { true })
    }

    @Test fun everyStepFailingThrowsTheLastFailure() = runBlocking {
        val walk = Walk()
        val error = runCatching { walk.run(listOf(lan, ssh, tunnel)) { false } }.exceptionOrNull()
        assertEquals("Tunnel failed", error?.message)
        assertEquals(listOf("Lan", "ssh", "Tunnel"), walk.calls)
        assertTrue(runCatching { Walk().run(emptyList()) { true } }.exceptionOrNull() is PairingException)
    }

    @Test fun sshHostKeyProblemsStopTheWalkForTheUser() = runBlocking {
        val calls = mutableListOf<String>()
        val key = SshHostKey("home.test", 22, "ssh-ed25519", "SHA256:other")
        val error = runCatching {
            walkConnectionPlan(listOf(ssh, tunnel), true, {}, direct = { calls += "direct" }, ssh = { throw SshHostKeyException(key, changed = true) })
        }.exceptionOrNull()
        assertTrue(error is SshHostKeyException)
        assertTrue(calls.isEmpty())
    }

    @Test fun hintsArePersistedOnlyWhenTheyChangeTheMachine() {
        val env = SavedEnvironment("env", "Mac", "https://mac.example/", "token")
        val other = SavedEnvironment("other", "Other", "https://other.example/", "token")
        val hints = RouteHints("env", listOf(DirectEndpointHint("lan", "https://192.168.1.5:3773")), TunnelHint.Active("prod-abc.t3coderelay.com"))
        val updated = applyRouteHints(listOf(env, other), hints)!!
        assertEquals(listOf(Lan, Tunnel), updated.first { it.id == "env" }.routes.map { it.kind })
        assertSame(other, updated.first { it.id == "other" })
        assertNull(applyRouteHints(updated, hints))
        assertNull(applyRouteHints(updated, RouteHints("env", null, TunnelHint.Unknown)))
        assertNull(applyRouteHints(updated, hints.copy(environmentId = "unsaved")))
        assertEquals(listOf(Lan), applyRouteHints(updated, RouteHints("env", null, TunnelHint.NotLinked))!!.first().routes.map { it.kind })
    }

    @Test fun rePairingKeepsTheVaultsLearnedRoutes() {
        val learned = listOf(lan, tunnel)
        val saved = SavedEnvironment("env", "Mac", "https://mac.example/", "old", routes = learned)
        val repaired = SavedEnvironment("env", "Mac", "https://prod-abc.t3coderelay.com/", "new")
        assertEquals(learned, repaired.withLearnedRoutesOf(listOf(saved)).routes)
        assertEquals("new", repaired.withLearnedRoutesOf(listOf(saved)).accessToken)
        val unrelated = SavedEnvironment("fresh", "New", "https://new.example/", "t")
        assertSame(unrelated, unrelated.withLearnedRoutesOf(listOf(saved)))
    }

    @Test fun sshLoginIsSavedOnlyWithItsSecret() {
        assertTrue(SshConnectionInput("Home", "home.test", "mike", password = "pw").hasSavedLogin())
        assertFalse(SshConnectionInput("Home", "home.test", "mike").hasSavedLogin())
        assertFalse(SshConnectionInput("Home", "home.test", "mike", authMethod = SshAuthMethod.PrivateKey, password = "pw").hasSavedLogin())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun draftMutationsAreCountedUntilTheySettle() = runTest {
        val inFlight = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val tracked = MutationTrackingClient(SendOnlyClient { gate.await() }, inFlight)
        val send = launch { tracked.sendMessage("fixture") }
        runCurrent()
        assertEquals(1, inFlight.get())
        gate.complete(Unit); send.join()
        assertEquals(0, inFlight.get())
        val failing = MutationTrackingClient(SendOnlyClient { error("boom") }, inFlight)
        assertTrue(runCatching { failing.sendMessage("fixture") }.isFailure)
        assertEquals(0, inFlight.get())
    }

    @Test fun composerReplyDraftClearsOnlyAfterAcceptedSend() = runBlocking {
        val state = MutableStateFlow(ClientState(environment = SavedEnvironment("env", "Mac", "https://mac.example/", "token"), selectedThreadId = "t1"))
        val cleared = mutableListOf<ComposerDraftKey>()
        val failing = ComposerDraftSettlingClient(SendOnlyClient(state) { error("boom") }, onSent = { cleared += it }, onLaunched = { cleared += it })
        assertTrue(runCatching { failing.sendMessage("fixture") }.isFailure)
        assertTrue(cleared.isEmpty())
        val ok = ComposerDraftSettlingClient(SendOnlyClient(state) { state.value = state.value.copy(selectedThreadId = "t2") }, onSent = { cleared += it }, onLaunched = { cleared += it })
        ok.sendMessage("fixture")
        assertEquals("the thread the message went to", listOf<ComposerDraftKey>(ComposerDraftKey.Reply("env", "t1")), cleared)
    }

    private class SendOnlyClient(override val state: StateFlow<ClientState> = MutableStateFlow(ClientState()), private val onSend: suspend () -> Unit) : TrefoilClient {
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean) = error("unused")
        override suspend fun connect(environment: SavedEnvironment) = error("unused")
        override suspend fun disconnect() = error("unused")
        override suspend fun selectThread(threadId: String) = error("unused")
        override suspend fun sendMessage(text: String) = onSend()
        override suspend fun interruptRun() = error("unused")
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = error("unused")
        override suspend fun reconnect() = error("unused")
        override fun close() = Unit
    }
}

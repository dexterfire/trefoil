package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.model.RouteKind.*
import com.tajchert.trefoil.core.model.RouteSource.*
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.core.session.ComposerDraftPersistence
import com.tajchert.trefoil.core.session.ComposerDraftStore
import com.tajchert.trefoil.core.session.PendingLaunch
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

    @Test fun aMachineRemovedWhileConnectingStopsTheWholePlan() = runBlocking {
        val walk = Walk()
        // Removed while SSH was opening: the tunnel route after it must not connect for a machine no longer stored.
        val error = runCatching {
            walkConnectionPlan(listOf(ssh, tunnel), true, {}, direct = { walk.calls += "direct" }, ssh = { throw MachineRemovedException() })
        }.exceptionOrNull()
        assertTrue(error is MachineRemovedException)
        assertTrue(walk.calls.isEmpty())
        // Removed during a direct run: the SSH step after it does not open.
        val second = runCatching {
            walkConnectionPlan(listOf(lan, ssh), true, {}, direct = { throw MachineRemovedException() }, ssh = { walk.calls += "ssh" })
        }.exceptionOrNull()
        assertTrue(second is MachineRemovedException)
        assertTrue(walk.calls.isEmpty())
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

    private class MemoryDrafts : ComposerDraftPersistence {
        var stored: String? = null
        override suspend fun load(): String? = stored
        override suspend fun save(json: String?) { stored = json }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun composerReplyDraftClearsOnlyAfterAcceptedSendAndKeepsNewerText() = runTest {
        val state = MutableStateFlow(ClientState(environment = SavedEnvironment("env", "Mac", "https://mac.example/", "token"), selectedThreadId = "t1"))
        val store = ComposerDraftStore(MemoryDrafts(), backgroundScope).also { runCurrent() }
        val key = ComposerDraftKey.Reply("env", "t1")
        store.update(key, "first")
        val failing = ComposerDraftSettlingClient(SendOnlyClient(state) { error("boom") }, store)
        assertTrue(runCatching { failing.sendMessage("first") }.isFailure)
        assertEquals("a failed send keeps the draft", "first", store.get(key)?.text)
        val gate = CompletableDeferred<Unit>()
        val slow = ComposerDraftSettlingClient(SendOnlyClient(state) { gate.await() }, store)
        val send = launch { slow.sendMessage("first") }
        runCurrent()
        store.update(key, "follow-up")
        gate.complete(Unit); send.join()
        assertEquals("text typed while the send was pending survives its ack", "follow-up", store.get(key)?.text)
        val ok = ComposerDraftSettlingClient(SendOnlyClient(state) { state.value = state.value.copy(selectedThreadId = "t2") }, store)
        ok.sendMessage("follow-up")
        assertNull("the thread the message went to", store.get(key))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun launchPersistsItsIdentityBeforeDispatchAndClearsTheDraftOnlyWhenAccepted() = runTest {
        val state = MutableStateFlow(ClientState(environment = SavedEnvironment("env", "Mac", "https://mac.example/", "token"), connection = ConnectionStatus.Connected))
        val disk = MemoryDrafts()
        val store = ComposerDraftStore(disk, backgroundScope).also { runCurrent() }
        val key = ComposerDraftKey.NewThread("env", "p1")
        store.update(key, "prompt")
        val draft = ThreadDraft("p1", ModelOption("codex", "gpt"))
        var onDiskAtDispatch: Boolean? = null
        val uncertain = ComposerDraftSettlingClient(SendOnlyClient(state, onLaunch = {
            onDiskAtDispatch = disk.stored?.let { draft.commandId in it && draft.threadId in it }
            throw UncertainOutcomeException("lost")
        }) {}, store)
        assertTrue(runCatching { uncertain.launchThread(draft, "prompt") }.isFailure)
        assertEquals("on disk before the launch went out", true, onDiskAtDispatch)
        assertEquals(PendingLaunch(draft.threadId, draft.commandId, "prompt"), store.get(key)?.pendingLaunch)
        // A fresh session (other ids) relaunching the same text reuses the recorded ids.
        var dispatched: ThreadDraft? = null
        val accepted = ComposerDraftSettlingClient(SendOnlyClient(state, onLaunch = { dispatched = it; it.threadId }) {}, store)
        accepted.launchThread(ThreadDraft("p1", ModelOption("codex", "gpt")), "prompt")
        assertEquals(draft.threadId, dispatched?.threadId); assertEquals(draft.commandId, dispatched?.commandId)
        assertNull(store.get(key))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun editedRetryNeverReusesTheIdsOfADifferentPrompt() = runTest {
        val state = MutableStateFlow(ClientState(environment = SavedEnvironment("env", "Mac", "https://mac.example/", "token"), connection = ConnectionStatus.Connected, shellLive = true))
        val store = ComposerDraftStore(MemoryDrafts(), backgroundScope).also { runCurrent() }
        val key = ComposerDraftKey.NewThread("env", "p1")
        store.update(key, "A")
        store.recordLaunch(key, "thread-A", "command-A", "A")
        store.update(key, "B")
        var dispatched: ThreadDraft? = null
        val client = ComposerDraftSettlingClient(SendOnlyClient(state, onLaunch = { dispatched = it; it.threadId }) {}, store)
        val retry = ThreadDraft("p1", ModelOption("codex", "gpt"), threadId = "thread-A", commandId = "command-A")
        assertTrue("unconfirmed: fails without dispatching", runCatching { client.launchThread(retry, "B") }.isFailure)
        assertNull(dispatched)
        assertEquals("B", store.get(key)?.text)
        state.value = state.value.copy(threads = listOf(ThreadSummary("thread-A", "p1", "A")))
        client.launchThread(retry, "B")
        assertNotEquals("A was accepted: B launches as a new thread", "thread-A", dispatched?.threadId)
        assertNotEquals("command-A", dispatched?.commandId)
        assertNull("B was accepted too", store.get(key))
    }

    @Test(timeout = 30_000) fun aResendNeverSettlesAPendingLaunchOnCachedRows() = runTest {
        // Connected, but the rows are cached ones still waiting for the catch-up: they list A's thread, which the server may
        // have deleted since.
        val state = MutableStateFlow(ClientState(environment = SavedEnvironment("env", "Mac", "https://mac.example/", "token"), connection = ConnectionStatus.Connected,
            shellLive = false, threads = listOf(ThreadSummary("thread-A", "p1", "A"))))
        val store = ComposerDraftStore(MemoryDrafts(), backgroundScope).also { runCurrent() }
        val key = ComposerDraftKey.NewThread("env", "p1")
        store.update(key, "A")
        store.recordLaunch(key, "thread-A", "command-A", "A")
        store.update(key, "B")
        var dispatched: ThreadDraft? = null
        val client = ComposerDraftSettlingClient(SendOnlyClient(state, onLaunch = { dispatched = it; it.threadId }) {}, store)
        val retry = ThreadDraft("p1", ModelOption("codex", "gpt"), threadId = "thread-A", commandId = "command-A")
        assertTrue("Stale rows are no evidence: fails without dispatching", runCatching { client.launchThread(retry, "B") }.isFailure)
        assertNull(dispatched)
        assertEquals("The draft and its pending launch are kept", "B", store.get(key)?.text)
        assertEquals("command-A", store.get(key)?.pendingLaunch?.commandId)
        // The connection's own shell is synchronized and lists it: now it settles and B launches anew.
        state.value = state.value.copy(shellLive = true)
        client.launchThread(retry, "B")
        assertNotEquals("thread-A", dispatched?.threadId)
    }

    @Test fun launchEvidenceIsOnlyTheLiveShell() {
        val environment = SavedEnvironment("env", "Mac", "https://mac.example/", "token")
        val rows = listOf(ThreadSummary("t1", "p1", "One"))
        val cached = ClientState(environment = environment, connection = ConnectionStatus.Connected, shellLoaded = true, shellLive = false, threads = rows)
        assertNull("Connected with cached rows is no evidence", launchEvidence(cached))
        assertNull(launchEvidence(cached.copy(connection = ConnectionStatus.Connecting, shellLive = true)))
        assertEquals("env" to setOf("t1"), launchEvidence(cached.copy(shellLive = true)))
    }

    private class SendOnlyClient(
        override val state: StateFlow<ClientState> = MutableStateFlow(ClientState()),
        private val onLaunch: suspend (ThreadDraft) -> String = { error("unused") },
        private val onSend: suspend () -> Unit,
    ) : TrefoilClient {
        override suspend fun launchThread(draft: ThreadDraft, firstMessage: String): String = onLaunch(draft)
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean) = error("unused")
        override suspend fun connect(environment: SavedEnvironment) = error("unused")
        override suspend fun disconnect() = error("unused")
        override suspend fun selectThread(threadId: String) = error("unused")
        override suspend fun sendMessage(text: String) = onSend()
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = error("unused")
        override suspend fun reconnect() = error("unused")
        override fun close() = Unit
    }
}

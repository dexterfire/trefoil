package com.tajchert.trefoil.ui.ports

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PortForwardCoordinatorTest {
    private class FakeClient : TrefoilClient {
        val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")
        override val state = MutableStateFlow(ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true,
            localServers = LocalServerCatalog(listOf(DiscoveredLocalServer("127.0.0.1", 5173, "http://127.0.0.1:5173", processName = "vite")), live = true)))
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        private var instances = 0L
        var next: (Int) -> PortForward = { PortForward(environment.id, it, localPort = it + 20_000, status = PortForwardStatus.Active, instance = ++instances) }
        var failure: Exception? = null
        override suspend fun startPortForward(remotePort: Int): PortForward {
            started += remotePort
            failure?.let { throw it }
            return next(remotePort).also { forward -> state.update { it.copy(portForwards = it.portForwards.filter { f -> f.remotePort != remotePort } + forward) } }
        }
        override suspend fun stopPortForward(remotePort: Int) { stopped += remotePort; state.update { it.copy(portForwards = it.portForwards.filter { f -> f.remotePort != remotePort }) } }
        override suspend fun pair(pairingUrl: String, name: String, allowInsecureHttp: Boolean): SavedEnvironment = error("unused")
        override suspend fun connect(environment: SavedEnvironment) {}
        override suspend fun disconnect() {}
        override suspend fun selectThread(threadId: String) {}
        override suspend fun sendMessage(text: String) = error("No writes expected")
        override suspend fun interruptRun() = error("No writes expected")
        override suspend fun respond(requestId: String, decision: String, answers: Map<String, List<String>>) = error("No writes expected")
        override suspend fun reconnect() {}
        override fun close() {}
    }

    private var holdChanges = 0

    private fun run(block: suspend CoroutineScope.(FakeClient, PortForwardCoordinator, MutableList<String>) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = FakeClient(); val errors = mutableListOf<String>()
        val coordinator = PortForwardCoordinator(scope, client, onExternalForwardsChanged = { holdChanges++ }) { errors += it }
        try { block(client, coordinator, errors) } finally { scope.cancel() }
    }

    /** Opens [port] externally with a UI collecting requests, and confirms that a browser took the URL. */
    private suspend fun CoroutineScope.openInBrowser(coordinator: PortForwardCoordinator, port: Int): ExternalOpenRequest {
        val requests = mutableListOf<ExternalOpenRequest>()
        val ui = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.externalUrls.toList(requests) }
        coordinator.openExternal(port)
        yield(); ui.cancel()
        return requests.single().also { coordinator.externalOpened(it) }
    }

    @Test fun aForwardIsHeldOnlyOnceABrowserTookItsUrlAndUntilTheUserStopsIt() = run { client, coordinator, _ ->
        val requests = mutableListOf<ExternalOpenRequest>()
        val ui = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.externalUrls.toList(requests) }
        coordinator.openExternal(5173)
        yield(); ui.cancel()
        val request = requests.single()
        assertEquals("http://localhost:25173/", request.url)
        // Requested, not yet taken by a browser.
        assertTrue(coordinator.externalForwards.value.isEmpty())
        coordinator.externalOpened(request)
        assertEquals(setOf(request.forward), coordinator.externalForwards.value)
        assertEquals(1, holdChanges)
        coordinator.inBackground = true
        coordinator.stop(5173)
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertEquals(2, holdChanges)
        // The user stopped it: nothing to explain on return.
        assertTrue(coordinator.stoppedNotices.value.isEmpty())
    }

    /** "Open in browser", then Home before the bind finished: the UI stopped collecting and the request is lost. */
    @Test fun aRequestNoUiReceivedHoldsNothing() = run { client, coordinator, _ ->
        coordinator.openExternal(5173)
        assertEquals(listOf(5173), client.started)
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertEquals(0, holdChanges)
    }

    @Test fun anInAppOpenNeverHolds() = run { _, coordinator, _ ->
        coordinator.openInApp(5173)
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertEquals(0, holdChanges)
    }

    @Test fun aReplacementForwardOnTheSamePortNeverInheritsTheHold() = run { client, coordinator, _ ->
        val first = openInBrowser(coordinator, 5173)
        // The failed listener was replaced (a retry from the app) before the collector saw the failure: first Starting...
        client.state.update { it.copy(portForwards = listOf(PortForward("fixture", 5173, status = PortForwardStatus.Starting, instance = first.forward.instance + 1))) }
        assertTrue(coordinator.externalForwards.value.isEmpty())

        // ...or already Active on the same local port, with every intermediate state conflated away.
        client.state.update { it.copy(portForwards = emptyList()) }
        val second = openInBrowser(coordinator, 5173)
        client.state.update { it.copy(portForwards = listOf(PortForward("fixture", 5173, 25173, PortForwardStatus.Active, instance = second.forward.instance + 1))) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
    }

    @Test fun aRequestForAListenerThatWasReplacedMeanwhileHoldsNothing() = run { client, coordinator, _ ->
        val requests = mutableListOf<ExternalOpenRequest>()
        val ui = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.externalUrls.toList(requests) }
        coordinator.openExternal(5173)
        yield(); ui.cancel()
        client.state.update { it.copy(portForwards = listOf(PortForward("fixture", 5173, 25173, PortForwardStatus.Active, instance = requests.single().forward.instance + 1))) }
        coordinator.externalOpened(requests.single())
        assertTrue(coordinator.externalForwards.value.isEmpty())
    }

    @Test fun lossesAreExplainedOnlyInTheBackgroundAndAccumulate() = run { client, coordinator, _ ->
        openInBrowser(coordinator, 5173)
        client.state.update { it.copy(connection = ConnectionStatus.Disconnected, portForwards = emptyList()) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertTrue(coordinator.stoppedNotices.value.isEmpty())

        client.state.update { it.copy(connection = ConnectionStatus.Connected) }
        val vite = openInBrowser(coordinator, 5173)
        val api = openInBrowser(coordinator, 3000)
        coordinator.inBackground = true
        // 3000 fails first; 5173 goes later with the connection. Both are reported.
        client.state.update { state -> state.copy(portForwards = state.portForwards.map {
            if (it.remotePort == 3000) it.copy(status = PortForwardStatus.Failed, localPort = null, error = PortForwardError.Unauthorized) else it }) }
        assertEquals(setOf(vite.forward), coordinator.externalForwards.value)
        client.state.update { it.copy(connection = ConnectionStatus.Disconnected, portForwards = emptyList()) }
        val notice = coordinator.stoppedNotices.value.getValue("fixture")
        assertEquals(mapOf(3000 to ForwardLoss.Failed, 5173 to ForwardLoss.ConnectionEnded), notice.lost)
        assertTrue(notice.message.contains("port 3000 failed") && notice.message.contains("port 5173 stopped because the connection ended"))
        assertFalse(notice.message.contains("localhost"))
        assertEquals(api.forward.environmentId, notice.environmentId)

        // Announced app-wide once; dismissing clears it.
        assertEquals(notice.message, coordinator.announceStopped("fixture"))
        assertNull(coordinator.announceStopped("fixture"))
        assertNull(coordinator.announceStopped("other"))
        coordinator.dismissStoppedNotice("fixture")
        assertTrue(coordinator.stoppedNotices.value.isEmpty())
    }

    @Test fun anEnvironmentSwitchEndsTheHoldAndANewOpenClearsTheNotice() = run { client, coordinator, _ ->
        openInBrowser(coordinator, 5173)
        coordinator.inBackground = true
        client.state.update { it.copy(environment = client.environment.copy(id = "other")) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertNotNull(coordinator.stoppedNotices.value["fixture"])
        client.state.update { it.copy(environment = client.environment, portForwards = emptyList()) }
        coordinator.inBackground = false
        openInBrowser(coordinator, 5173)
        assertTrue(coordinator.stoppedNotices.value.isEmpty())
    }

    @Test fun releaseAllEndsEveryHoldWithoutANotice() = run { client, coordinator, _ ->
        openInBrowser(coordinator, 5173)
        coordinator.inBackground = true
        coordinator.releaseAll()
        assertTrue(coordinator.externalForwards.value.isEmpty())
        client.state.update { it.copy(connection = ConnectionStatus.Disconnected, portForwards = emptyList()) }
        assertTrue(coordinator.stoppedNotices.value.isEmpty())
    }

    @Test fun anIdleHeldForwardStopsHoldingAfterTheLimit() = kotlinx.coroutines.test.runTest {
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val client = FakeClient()
        val coordinator = PortForwardCoordinator(scope, client, onExternalForwardsChanged = { holdChanges++ }) {}
        try {
            val request = openInBrowser(coordinator, 5173)
            coordinator.inBackground = true
            // An open page keeps it held however long it stays open.
            client.state.update { s -> s.copy(portForwards = s.portForwards.map { it.copy(activeConnections = 1) }) }
            testScheduler.advanceTimeBy(EXTERNAL_FORWARD_IDLE_MS * 3); testScheduler.runCurrent()
            assertEquals(setOf(request.forward), coordinator.externalForwards.value)
            // The tab closed: the clock starts now.
            client.state.update { s -> s.copy(portForwards = s.portForwards.map { it.copy(activeConnections = 0) }) }
            testScheduler.advanceTimeBy(EXTERNAL_FORWARD_IDLE_MS - 1); testScheduler.runCurrent()
            assertEquals(setOf(request.forward), coordinator.externalForwards.value)
            testScheduler.advanceTimeBy(1); testScheduler.runCurrent()
            assertTrue(coordinator.externalForwards.value.isEmpty())
            // The forward itself stays; only the hold ended, and the return explains why.
            assertEquals(1, client.state.value.portForwards.size)
            assertEquals(mapOf(5173 to ForwardLoss.Idle), coordinator.stoppedNotices.value.getValue("fixture").lost)
            assertTrue(coordinator.stoppedNotices.value.getValue("fixture").message.contains("10 minutes"))
        } finally { scope.cancel() }
    }

    @Test fun openInAppForwardsThenShowsLoopbackUrlTitledByProcessName() = run { client, coordinator, errors ->
        coordinator.openInApp(5173)
        assertEquals(listOf(5173), client.started)
        assertEquals(PortBrowserState("fixture", 5173, "http://localhost:25173/", "vite"), coordinator.browser.value)
        assertTrue(errors.isEmpty())
    }

    @Test fun titleFallsBackToThePortAndManualForwardOpensInApp() = run { client, coordinator, _ ->
        coordinator.forwardManual(8080)
        assertEquals(":8080", coordinator.browser.value?.title)
        assertEquals("http://localhost:28080/", coordinator.browser.value?.url)
    }

    @Test fun openExternalEmitsTheUrlOnceAndLeavesTheBrowserClosed() = run { client, coordinator, _ ->
        val urls = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.externalUrls.collect { urls += it.url } }
        coordinator.openExternal(5173)
        yield()
        assertEquals(listOf("http://localhost:25173/"), urls)
        assertNull(coordinator.browser.value)
        job.cancel()
    }

    @Test fun readOnlyOrDisconnectedStartsNothingAndReportsAnError() = run { client, coordinator, errors ->
        client.state.update { it.copy(canWrite = false) }
        coordinator.openInApp(5173)
        client.state.update { it.copy(canWrite = true, connection = ConnectionStatus.Disconnected) }
        coordinator.openExternal(5173)
        assertTrue(client.started.isEmpty())
        assertEquals(2, errors.size)
        assertNull(coordinator.browser.value)
    }

    /** The capability only says the server honours the setting (T3 `previewTunnelSetting`); it never says the tunnel is off. */
    @Test fun theCapabilityAloneNeverBlocksForwarding() = run { client, coordinator, errors ->
        client.state.update { it.copy(previewTunnelSetting = false) }
        coordinator.openInApp(5173)
        client.state.update { it.copy(previewTunnelSetting = true) }
        coordinator.openExternal(5173)
        client.state.update { it.copy(previewTunnelSetting = null) }
        coordinator.openInApp(5173)
        assertEquals(listOf(5173, 5173, 5173), client.started)
        assertTrue(errors.isEmpty())
    }

    @Test fun aServerConfigThatTurnsTunnelsOffAndInvalidPortsAreRejectedBeforeAnyRequest() = run { client, coordinator, errors ->
        client.state.update { it.copy(previewTunnelSetting = true, previewTunnelDisabled = true) }
        coordinator.openInApp(5173)
        client.state.update { it.copy(previewTunnelDisabled = false) }
        coordinator.openInApp(0); coordinator.openInApp(70_000)
        assertTrue(client.started.isEmpty())
        assertEquals(listOf("Browser tunnels are turned off on this T3 server.", "Choose a port from 1 to 65535.", "Choose a port from 1 to 65535."), errors)
    }

    @Test fun failedForwardReportsItsErrorAndDoesNotOpenTheBrowser() = run { client, coordinator, errors ->
        client.next = { PortForward("fixture", it, status = PortForwardStatus.Failed, error = PortForwardError.TunnelDisabled) }
        coordinator.openInApp(5173)
        assertNull(coordinator.browser.value)
        assertEquals(listOf("Browser tunnels are turned off on this T3 server."), errors)
    }

    @Test fun startFailuresBecomeUserFacingNoticesWithoutLeakingDetails() = run { client, coordinator, errors ->
        client.failure = IllegalStateException("Connection changed; forward the port again http://x/?wsTicket=secret")
        coordinator.openInApp(5173)
        assertEquals(1, errors.size)
        assertFalse(errors.single().contains("secret"))
        // The port is free to try again after a failure.
        client.failure = null
        coordinator.openInApp(5173)
        assertNotNull(coordinator.browser.value)
    }

    @Test fun browserClosesWhenItsForwardDisappearsOrFails() = run { client, coordinator, _ ->
        coordinator.openInApp(5173)
        assertNotNull(coordinator.browser.value)
        client.state.update { it.copy(portForwards = emptyList()) }
        assertNull(coordinator.browser.value)

        coordinator.openInApp(5173)
        assertNotNull(coordinator.browser.value)
        client.state.update { it.copy(portForwards = listOf(PortForward("fixture", 5173, status = PortForwardStatus.Failed, error = PortForwardError.Unauthorized))) }
        assertNull(coordinator.browser.value)
    }

    @Test fun browserClosesOnEnvironmentSwitchAndOtherForwardsLeaveItOpen() = run { client, coordinator, _ ->
        coordinator.openInApp(5173)
        client.state.update { it.copy(portForwards = it.portForwards + PortForward("fixture", 3000, 3000, PortForwardStatus.Active)) }
        assertNotNull(coordinator.browser.value)
        client.state.update { it.copy(environment = client.environment.copy(id = "other")) }
        assertNull(coordinator.browser.value)
    }

    @Test fun stopClosesTheForwardAndItsBrowserEvenWhenReadOnly() = run { client, coordinator, errors ->
        coordinator.openInApp(5173)
        client.state.update { it.copy(canWrite = false) }
        coordinator.stop(5173)
        assertEquals(listOf(5173), client.stopped)
        assertNull(coordinator.browser.value)
        assertTrue(errors.isEmpty())
    }

    @Test fun repeatedOpensOfOnePortWhileStartingRunOneRequest() = run { client, coordinator, _ ->
        val gate = CompletableDeferred<Unit>()
        val slow = object : TrefoilClient by client {
            override suspend fun startPortForward(remotePort: Int): PortForward { gate.await(); return client.startPortForward(remotePort) }
        }
        val local = PortForwardCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), slow) {}
        local.openInApp(5173); local.openInApp(5173); local.openInApp(5173)
        gate.complete(Unit)
        assertEquals(listOf(5173), client.started)
    }
}

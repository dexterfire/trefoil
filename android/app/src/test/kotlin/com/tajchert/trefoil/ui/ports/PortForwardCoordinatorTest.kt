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
        var next: (Int) -> PortForward = { PortForward(environment.id, it, localPort = it + 20_000, status = PortForwardStatus.Active) }
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

    private val held = setOf(ExternalForward("fixture", 5173))

    @Test fun anExternalOpenHoldsItsForwardUntilTheUserStopsIt() = run { client, coordinator, _ ->
        coordinator.openExternal(5173)
        assertEquals(held, coordinator.externalForwards.value)
        assertEquals(1, holdChanges)
        coordinator.inBackground = true
        coordinator.stop(5173)
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertEquals(2, holdChanges)
        // The user stopped it: nothing to explain on return.
        assertNull(coordinator.stoppedNotice.value)
    }

    @Test fun anInAppOpenNeverHolds() = run { _, coordinator, _ ->
        coordinator.openInApp(5173)
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertEquals(0, holdChanges)
    }

    @Test fun theHoldEndsWhenTheForwardGoesAwayAndExplainsItOnlyInTheBackground() = run { client, coordinator, _ ->
        coordinator.openExternal(5173)
        client.state.update { it.copy(connection = ConnectionStatus.Disconnected, portForwards = emptyList()) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertNull(coordinator.stoppedNotice.value)

        client.state.update { it.copy(connection = ConnectionStatus.Connected) }
        coordinator.openExternal(5173)
        coordinator.inBackground = true
        client.state.update { it.copy(connection = ConnectionStatus.Disconnected, portForwards = emptyList()) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
        val notice = coordinator.stoppedNotice.value!!
        assertEquals("fixture", notice.environmentId)
        assertTrue(notice.message.contains("port 5173") && notice.message.contains("connection ended"))
        assertFalse(notice.message.contains("localhost"))
        coordinator.dismissStoppedNotice()
        assertNull(coordinator.stoppedNotice.value)
    }

    @Test fun theHoldEndsOnAFailedForwardAndOnAnEnvironmentSwitch() = run { client, coordinator, _ ->
        coordinator.openExternal(5173)
        coordinator.inBackground = true
        client.state.update { it.copy(portForwards = listOf(PortForward("fixture", 5173, status = PortForwardStatus.Failed, error = PortForwardError.Unauthorized))) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
        assertTrue(coordinator.stoppedNotice.value!!.message.contains("failed"))

        coordinator.inBackground = false
        client.state.update { it.copy(portForwards = emptyList()) }
        coordinator.openExternal(5173)
        // A new open clears the old notice.
        assertNull(coordinator.stoppedNotice.value)
        assertEquals(held, coordinator.externalForwards.value)
        client.state.update { it.copy(environment = client.environment.copy(id = "other")) }
        assertTrue(coordinator.externalForwards.value.isEmpty())
    }

    @Test fun noBrowserForTheUrlReleasesTheHold() = run { _, coordinator, _ ->
        coordinator.openExternal(5173)
        coordinator.externalOpenFailed("http://localhost:9/")
        assertEquals(held, coordinator.externalForwards.value)
        coordinator.externalOpenFailed("http://localhost:25173/")
        assertTrue(coordinator.externalForwards.value.isEmpty())
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
        val job = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.externalUrls.toList(urls) }
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

package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.DiscoveredLocalServer
import com.tajchert.trefoil.core.model.PortForward
import com.tajchert.trefoil.core.model.PortForwardError
import com.tajchert.trefoil.core.model.PortForwardStatus
import com.tajchert.trefoil.ui.ports.PortsActions
import com.tajchert.trefoil.ui.ports.PortsUiState

/** Fake dev servers and forwards for the ports screenshots. Thread ids match [ScreenshotFixtures.threads]. */
object PortsFixtures {
    private const val ENV = "env-workstation"

    val vite = DiscoveredLocalServer("127.0.0.1", 5173, "http://127.0.0.1:5173/", "node", 4121, agentThreadId = "t-approval", terminalThreadId = "t-approval", terminalId = "term-1")
    val api = DiscoveredLocalServer("127.0.0.1", 3000, "http://127.0.0.1:3000/", "bun", 4188, terminalThreadId = "t-approval", terminalId = "term-2")
    val docs = DiscoveredLocalServer("127.0.0.1", 8080, "http://127.0.0.1:8080/", "python3", 5230, terminalThreadId = "t-recent", terminalId = "term-3")
    val unknown = DiscoveredLocalServer("::1", 9229, "http://[::1]:9229/")

    val titles = mapOf("t-approval" to "Add SSH host key check", "t-recent" to "Fix dark mode contrast")

    val activeVite = PortForward(ENV, 5173, localPort = 5173, status = PortForwardStatus.Active, activeConnections = 2)
    val startingApi = PortForward(ENV, 3000, status = PortForwardStatus.Starting)
    val failedDocs = PortForward(ENV, 8080, status = PortForwardStatus.Failed, error = PortForwardError.PortRefused)
    val manual = PortForward(ENV, 4000, localPort = 41000, status = PortForwardStatus.Active)

    val mixed = PortsUiState(
        servers = listOf(docs, unknown, api, vite), forwards = listOf(activeVite, startingApi, failedDocs, manual),
        threadTitles = titles, live = true, selectedThreadId = "t-approval",
    )
    val empty = PortsUiState(live = true)
    val scanning = PortsUiState(live = false)
    val unsupported = PortsUiState(supported = false)
    val disabled = PortsUiState(live = true, tunnelsDisabled = true)
    val failed = PortsUiState(
        servers = listOf(docs), forwards = listOf(failedDocs), threadTitles = titles, live = true,
    )
    val stoppedInBackground = PortsUiState(
        servers = listOf(api, vite), threadTitles = titles, live = true, selectedThreadId = "t-approval",
        stoppedNotice = "The forward for port 5173 stopped because the connection to the machine ended. Open it again to keep using it in your browser.",
    )
    val readOnly = PortsUiState(servers = listOf(vite), threadTitles = titles, live = true, canWrite = false, selectedThreadId = "t-approval")

    val actions = PortsActions(onBack = {}, onOpenInApp = {}, onOpenExternal = {}, onStopForward = {}, onForwardManual = {})
}

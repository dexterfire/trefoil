package com.tajchert.trefoil.ui.ports

import androidx.compose.runtime.Immutable
import com.tajchert.trefoil.core.model.DiscoveredLocalServer
import com.tajchert.trefoil.core.model.PortForward

/** Everything [PortsScreen] renders. Built by the view model from `ClientState`; the screen holds no other state. */
@Immutable
data class PortsUiState(
    val servers: List<DiscoveredLocalServer> = emptyList(),
    val forwards: List<PortForward> = emptyList(),
    /** Thread id to title, for the "Agent · title" owner line. A missing id shows the bare owner kind. */
    val threadTitles: Map<String, String> = emptyMap(),
    /** The discovery subscription is live and has delivered a snapshot. */
    val live: Boolean = false,
    /** False when the server rejected the discovery subscription (older T3). */
    val supported: Boolean = true,
    /** The server config says tunnels are off (`ClientState.previewTunnelDisabled`); unknown is not off. */
    val tunnelsDisabled: Boolean = false,
    val canWrite: Boolean = true,
    val selectedThreadId: String? = null,
) {
    /** Opening or forwarding is possible: supported, tunnels not off, and the user may write. */
    val canForward: Boolean get() = supported && !tunnelsDisabled && canWrite
}

/** Every user intent [PortsScreen] raises. Ports are remote ports on the connected environment. */
class PortsActions(
    val onBack: () -> Unit,
    /** Forward the port if needed, then open it in the in-app browser. */
    val onOpenInApp: (port: Int) -> Unit,
    /** Forward the port if needed, then open it in the device's external browser. */
    val onOpenExternal: (port: Int) -> Unit,
    val onStopForward: (port: Int) -> Unit,
    /** A port typed by hand, already validated to 1..65535. Forward it and open it in-app. */
    val onForwardManual: (port: Int) -> Unit,
)

enum class PortOwnerKind { Agent, Terminal, None }

/** One list row: a discovered server, a manual forward with no matching server, or both. */
@Immutable
data class PortRow(
    val port: Int,
    val server: DiscoveredLocalServer?,
    val forward: PortForward?,
    val ownerKind: PortOwnerKind,
    val ownerThreadId: String?,
    /** The row belongs to the selected thread. */
    val inSelectedThread: Boolean,
)

/**
 * Rows for [state]: one per remote port (the first discovered server wins when several hosts share it), plus a row
 * for each forward that discovery did not list. The selected thread's servers come first, then by port.
 */
fun portRows(state: PortsUiState): List<PortRow> {
    val selected = state.selectedThreadId
    val forwardsByPort = state.forwards.associateBy { it.remotePort }
    val discovered = state.servers.distinctBy { it.port }.map { server ->
        val kind = when {
            server.agentThreadId != null -> PortOwnerKind.Agent
            server.terminalThreadId != null -> PortOwnerKind.Terminal
            else -> PortOwnerKind.None
        }
        PortRow(server.port, server, forwardsByPort[server.port], kind, server.threadId, selected != null && server.threadId == selected)
    }
    val known = discovered.mapTo(HashSet()) { it.port }
    val manual = state.forwards.filter { it.remotePort !in known }.distinctBy { it.remotePort }
        .map { PortRow(it.remotePort, null, it, PortOwnerKind.None, null, false) }
    return (discovered + manual).sortedWith(compareByDescending<PortRow> { it.inSelectedThread }.thenBy { it.port })
}

/** A port typed in the manual entry: digits only, 1..65535. Null otherwise. */
fun parsePortInput(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 5 || !trimmed.all { it in '0'..'9' }) return null
    return trimmed.toInt().takeIf { it in 1..65535 }
}

/** Ports shown by the thread chip: distinct and ascending. */
internal fun chipPorts(servers: List<DiscoveredLocalServer>): List<Int> = servers.map { it.port }.distinct().sorted()

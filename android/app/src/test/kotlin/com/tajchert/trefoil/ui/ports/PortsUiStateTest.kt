package com.tajchert.trefoil.ui.ports

import com.tajchert.trefoil.core.model.DiscoveredLocalServer
import com.tajchert.trefoil.core.model.PortForward
import com.tajchert.trefoil.core.model.PortForwardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortsUiStateTest {
    private fun server(port: Int, host: String = "127.0.0.1", agent: String? = null, terminal: String? = null) =
        DiscoveredLocalServer(host, port, "http://$host:$port/", agentThreadId = agent, terminalThreadId = terminal, terminalId = terminal?.let { "t" })

    @Test fun selectedThreadServersSortFirstThenByPort() {
        val state = PortsUiState(
            servers = listOf(server(9000), server(8000, terminal = "other"), server(7000, agent = "mine"), server(6000, terminal = "mine")),
            selectedThreadId = "mine",
        )
        val rows = portRows(state)
        assertEquals(listOf(6000, 7000, 8000, 9000), rows.map { it.port })
        assertEquals(listOf(true, true, false, false), rows.map { it.inSelectedThread })
        assertEquals(PortOwnerKind.Agent, rows[1].ownerKind)
        assertEquals(PortOwnerKind.Terminal, rows[0].ownerKind)
        assertEquals(PortOwnerKind.None, rows[3].ownerKind)
    }

    @Test fun agentOwnerWinsOverTerminalAndSharedPortsCollapse() {
        val state = PortsUiState(servers = listOf(server(5173, agent = "a", terminal = "t"), server(5173, host = "::1")))
        val rows = portRows(state)
        assertEquals(1, rows.size)
        assertEquals(PortOwnerKind.Agent, rows[0].ownerKind)
        assertEquals("a", rows[0].ownerThreadId)
    }

    @Test fun manualForwardsGetRowsWithoutServerAndMatchedForwardsAttach() {
        val matched = PortForward("e", 3000, 3000, PortForwardStatus.Active)
        val manual = PortForward("e", 4000, 41000, PortForwardStatus.Active)
        val rows = portRows(PortsUiState(servers = listOf(server(3000)), forwards = listOf(manual, matched)))
        assertEquals(listOf(3000, 4000), rows.map { it.port })
        assertEquals(matched, rows[0].forward)
        assertNull(rows[1].server)
        assertEquals(manual, rows[1].forward)
    }

    @Test fun noSelectedThreadLeavesNothingPinned() {
        val rows = portRows(PortsUiState(servers = listOf(server(2), server(1, terminal = "x"))))
        assertEquals(listOf(1, 2), rows.map { it.port })
        assertTrue(rows.none { it.inSelectedThread })
    }

    @Test fun parsePortAcceptsOnlyValidRange() {
        assertEquals(1, parsePortInput("1"))
        assertEquals(65535, parsePortInput(" 65535 "))
        assertNull(parsePortInput("0"))
        assertNull(parsePortInput("65536"))
        assertNull(parsePortInput(""))
        assertNull(parsePortInput("80a"))
        assertNull(parsePortInput("-1"))
        assertNull(parsePortInput("000001234567"))
    }

    @Test fun forwardingNeedsSupportWritesAndTunnelsNotOff() {
        assertTrue(PortsUiState().canForward)
        assertFalse(PortsUiState(tunnelsDisabled = true).canForward)
        assertFalse(PortsUiState(canWrite = false).canForward)
        assertFalse(PortsUiState(supported = false).canForward)
    }

    @Test fun chipPortsAreDistinctAndSorted() {
        assertEquals(listOf(3000, 5173), chipPorts(listOf(server(5173), server(3000), server(5173, host = "::1"))))
        assertEquals(emptyList<Int>(), chipPorts(emptyList()))
    }
}

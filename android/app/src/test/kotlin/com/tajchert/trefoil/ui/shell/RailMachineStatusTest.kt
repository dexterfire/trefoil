package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.MachineStatus
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.TrefoilDark
import com.tajchert.trefoil.ui.TrefoilLight
import com.tajchert.trefoil.ui.color
import com.tajchert.trefoil.ui.machineStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rail's machine avatar dot follows the live connection, with the Machines screen's states and colours. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class RailMachineStatusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private fun stateWith(connection: ConnectionStatus) = ClientState(environment = environment, connection = connection, canRead = true,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread")), selectedThreadId = "t")

    private fun railStatus(connection: ConnectionStatus): String? {
        compose.setContent {
            TrefoilApp(stateWith(connection), listOf(environment), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
                start = ShellStart(showThread = true))
        }
        val node = compose.onNode(hasContentDescription("Fixture") and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
        return node.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    }

    @Test fun aConnectedMachineIsOnline() = assertEquals("Connected", railStatus(ConnectionStatus.Connected))
    @Test fun aDisconnectedMachineIsOffline() = assertEquals("Offline", railStatus(ConnectionStatus.Disconnected))
    @Test fun aConnectingMachineIsConnecting() = assertEquals("Connecting", railStatus(ConnectionStatus.Connecting))
    @Test fun aReconnectingMachineIsConnecting() = assertEquals("Connecting", railStatus(ConnectionStatus.Reconnecting))
    @Test fun aFailedConnectionIsAnError() = assertEquals("Connection error", railStatus(ConnectionStatus.Error))

    @Test fun eachStateHasItsOwnDotColourAndOfflineIsNotGreen() {
        listOf(TrefoilLight, TrefoilDark).forEach { c ->
            val of = { connection: ConnectionStatus -> machineStatus(stateWith(connection)) }
            assertEquals(MachineStatus.Offline, of(ConnectionStatus.Disconnected))
            assertEquals(c.green, of(ConnectionStatus.Connected).color(c))
            assertEquals(c.amber, of(ConnectionStatus.Connecting).color(c))
            assertEquals(c.amber, of(ConnectionStatus.Reconnecting).color(c))
            assertEquals(c.red, of(ConnectionStatus.Error).color(c))
            assertEquals(c.muted, of(ConnectionStatus.Disconnected).color(c))
            assertNotEquals(c.green, of(ConnectionStatus.Disconnected).color(c))
        }
    }
}

package com.tajchert.trefoil.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.tajchert.trefoil.R
import com.tajchert.trefoil.testString
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class ConnectionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val fixture = ClientState(environment = environment, connection = ConnectionStatus.Connected,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread")))

    private val limited = fixture.copy(canReadUsage = true, providers = listOf(ProviderInstance("a", "codex", "Codex",
        usageLimits = ProviderUsageLimits("", listOf(UsageLimitWindow("w", "weekly", "Weekly", 62.0))))))
    private fun setApp(state: ClientState, onOpenUsage: () -> Unit) = compose.setContent {
        TrefoilApp(state, listOf(environment), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, onOpenUsage = onOpenUsage)
    }
    private fun limitChip() = compose.onNode(hasContentDescription(testString(R.string.menu_limits_usage), substring = true) and hasClickAction())

    @Test fun limitsAndUsageOpensFromOverflowMenu() {
        var opened = 0
        setApp(limited) { opened++ }
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText(testString(R.string.menu_limits_usage)).performClick()
        compose.onNodeWithText("Offline · showing last known data").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun limitChipShowsTightestLimitAndOpensTheScreen() {
        var opened = 0
        setApp(limited) { opened++ }
        compose.onNodeWithContentDescription(testString(R.string.menu_limits_usage) + " · " + testString(R.string.usage_limit_left, 38)).assertHasClickAction()
        compose.onNodeWithText("38%").assertExists()
        limitChip().performClick()
        compose.onNodeWithText(testString(R.string.menu_limits_usage)).assertExists()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test @Config(sdk = [35], qualifiers = "w320dp-h800dp") fun limitChipDropsItsNumberOnNarrowHeaders() {
        setApp(limited) {}
        compose.onNodeWithContentDescription(testString(R.string.menu_limits_usage) + " · " + testString(R.string.usage_limit_left, 38)).assertHasClickAction()
        compose.onNodeWithText("38%").assertDoesNotExist()
    }

    @Test fun limitChipWithoutDataIsIconOnly() {
        setApp(fixture.copy(canReadUsage = true)) {}
        compose.onNodeWithContentDescription(testString(R.string.menu_limits_usage)).assertHasClickAction()
        compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)
    }

    @Test fun disconnectedOpensCachedLimitsWithoutFetching() {
        var opened = 0
        val state = mutableStateOf(limited.copy(connection = ConnectionStatus.Disconnected, canReadUsage = false))
        compose.setContent {
            TrefoilApp(state.value, listOf(environment), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, onOpenUsage = { opened++ })
        }
        limitChip().performClick()
        compose.onNodeWithText("Offline · showing last known data").assertExists()
        compose.runOnIdle { assertEquals(0, opened) }
        // The fetch happens once the connection can read usage.
        compose.runOnIdle { state.value = limited }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun disconnectedOverflowEntryOpensCachedLimitsWithoutFetching() {
        var opened = 0
        setApp(limited.copy(connection = ConnectionStatus.Disconnected, canReadUsage = false)) { opened++ }
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText(testString(R.string.menu_limits_usage)).performClick()
        compose.onNodeWithText("Offline · showing last known data").assertExists()
        compose.runOnIdle { assertEquals(0, opened) }
    }

    @Test fun machinesScreenNoLongerRepeatsLimits() {
        compose.setContent { TrefoilTheme { MachinesScreen(limited, listOf(environment), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Machines").assertExists()
        compose.onAllNodesWithText("left", substring = true).assertCountEquals(0)
        compose.onNodeWithContentDescription("Open usage").assertDoesNotExist()
    }

    @Test fun restoredPhoneDetailWithoutSelectionReturnsToList() {
        val state = mutableStateOf(fixture)
        var restoring = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TrefoilApp(if (restoring) fixture else state.value, listOf(environment), { _, _, _ -> }, {},
                { state.value = state.value.copy(selectedThreadId = it) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {})
        }
        compose.onNodeWithText("Fixture thread").performClick()
        compose.onNodeWithContentDescription("Поиск").assertDoesNotExist()
        // Simulate process recreation: save the detail destination, but restore a fresh client state.
        restoring = true
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Pick a thread").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поиск").assertExists()
        compose.onNodeWithText("Fixture thread").assertExists()
    }

    @Test fun shortReconnectDoesNotFlashBannerAndBusyConversationHasNoBanner() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(fixture.copy(connection = ConnectionStatus.Reconnecting))
        compose.setContent { TrefoilTheme { ConnectionBanner(state.value, {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()
        compose.runOnIdle { state.value = fixture.copy(busy = true) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Connecting…").assertDoesNotExist()
        compose.onNodeWithText("Reconnecting…").assertDoesNotExist()
    }

    @Test fun longReconnectShowsBannerAndErrorAppearsImmediatelyAtSameHeight() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(fixture.copy(connection = ConnectionStatus.Connecting))
        compose.setContent { TrefoilTheme { ConnectionBanner(state.value, {}) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Connecting…").assertDoesNotExist()
        // Connecting -> Reconnecting is one continuous attempt and must not restart the delay.
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Reconnecting) }
        compose.mainClock.advanceTimeBy(600)
        val height = compose.onRoot().fetchSemanticsNode().size.height
        compose.onNodeWithText("Reconnecting…").assertExists()
        compose.runOnIdle { state.value = fixture.copy(connection = ConnectionStatus.Error, error = "Connection lost") }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Connection lost").assertExists()
        assertEquals(height, compose.onRoot().fetchSemanticsNode().size.height)
    }

    @Test fun errorsDuringInitialConnectionDoNotWaitForDelay() {
        compose.mainClock.autoAdvance = false
        compose.setContent { TrefoilTheme { ConnectionBanner(fixture.copy(connection = ConnectionStatus.Connecting, error = "Failed"), {}) } }
        compose.onNodeWithText("Failed").assertExists()
    }
}

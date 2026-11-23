package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.LocalFold
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Policy -> rendered shell: rail, drawer or phone header per window; destination restore; Back; the hinge gap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class ShellLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val fixture = ClientState(environment = environment, connection = ConnectionStatus.Connected,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread")))

    private val app: @androidx.compose.runtime.Composable (ClientState, ShellStart) -> Unit = { state, start ->
        TrefoilApp(state, listOf(environment), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = start)
    }
    private fun setApp(state: ClientState = fixture, start: ShellStart = ShellStart()) = compose.setContent { app(state, start) }
    private fun rail() = compose.onNodeWithContentDescription("Trefoil, show threads")
    private fun railItem(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h800dp") fun phoneKeepsTheHeaderWithoutRailOrPanes() {
        setApp()
        rail().assertDoesNotExist()
        compose.onNodeWithText("Fixture thread").assertExists()
        compose.onNodeWithText("Pick a thread").assertDoesNotExist()
    }

    @Test @Config(sdk = [35], qualifiers = "w880dp-h400dp") fun phoneLandscapeHasNoRail() {
        setApp()
        rail().assertDoesNotExist()
    }

    @Test @Config(sdk = [35], qualifiers = "w673dp-h841dp") fun foldPortraitUsesTwoPanesAndTheDrawer() {
        setApp(start = ShellStart(navigationOpen = true))
        rail().assertDoesNotExist()
        compose.onNodeWithText("Fixture thread").assertExists()
        compose.onNodeWithText("Pick a thread").assertExists()
        compose.onNodeWithText("Manage machines & routes…").assertExists()
    }

    @Test @Config(sdk = [35], qualifiers = "w841dp-h673dp") fun foldLandscapeHasTheRail() { setApp(); rail().assertExists() }
    @Test @Config(sdk = [35], qualifiers = "w800dp-h1280dp") fun tabletPortraitHasTheRail() { setApp(); rail().assertExists() }
    @Test @Config(sdk = [35], qualifiers = "w1920dp-h1080dp") fun desktopHasTheRail() { setApp(); rail().assertExists() }

    @Test fun tabletLandscapeShowsRailListAndConversation() {
        setApp()
        rail().assertExists()
        compose.onNodeWithText("Fixture thread").assertExists()
        compose.onNodeWithText("Pick a thread").assertExists()
        railItem("Screens").assertIsEnabled()
        railItem("Usage").assertIsEnabled()
    }

    @Test fun notConnectedHidesTheRailAndShowsMachines() {
        setApp(ClientState())
        rail().assertDoesNotExist()
    }

    @Test fun reconnectingKeepsTheRailWithLiveEntriesDisabledAndOneBanner() {
        setApp(fixture.copy(connection = ConnectionStatus.Reconnecting))
        rail().assertExists()
        railItem("Threads").assertIsEnabled()
        railItem("Usage").assertIsEnabled()
        railItem("Screens").assertIsNotEnabled()
        // The rail's own New thread button, found inside the rail; whatever the thread list draws in its header is not asserted.
        compose.onNode(hasContentDescription("New thread") and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Navigation"))).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(2_000) // the banner waits 1.5 s before showing a reconnect
        compose.onAllNodesWithText("Reconnecting…").assertCountEquals(1)
    }

    @Test fun usageBesideTheRailReturnsToThreadsOnBack() {
        setApp()
        railItem("Usage").performClick()
        compose.onNodeWithText("Limits & usage").assertExists()
        rail().assertExists()
        systemBack()
        compose.onNodeWithText("Limits & usage").assertDoesNotExist()
        compose.onNodeWithText("Fixture thread").assertExists()
    }

    @Test fun destinationSurvivesRecreation() {
        val restore = StateRestorationTester(compose)
        restore.setContent { app(fixture, ShellStart()) }
        railItem("Usage").performClick()
        compose.onNodeWithText("Limits & usage").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Limits & usage").assertExists()
    }

    @Test @Config(sdk = [35], qualifiers = "w673dp-h841dp") fun separatingFoldLeavesTheHingeEmpty() {
        val fold = Fold(FoldOrientation.Vertical, 330f, 343f, separating = true, halfOpened = true)
        compose.setContent { CompositionLocalProvider(LocalFold provides fold) { app(fixture, ShellStart()) } }
        val list = compose.onNodeWithText("Fixture thread").getUnclippedBoundsInRoot()
        val detail = compose.onNodeWithText("Pick a thread").getUnclippedBoundsInRoot()
        assertTrue("list ends at ${list.right}", list.right <= 330.dp)
        assertTrue("conversation starts at ${detail.left}", detail.left >= 343.dp)
    }
}

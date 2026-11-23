package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The side panel beside the conversation: where it docks, what closes it, and the chips it takes over. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class SidePanelShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val other = SavedEnvironment("other", "Other", "https://other.invalid", "other")
    private val agent = Subagent("a", "t", origin = "app_owned", status = "running", driver = "codex", title = "Explore code", childThreadId = "child")
    private val server = DiscoveredLocalServer("127.0.0.1", 5173, "http://127.0.0.1:5173", agentThreadId = "t")
    private val base = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canRead = true,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread"), ThreadSummary("u", "p", "Other thread")),
        selectedThreadId = "t", localServers = LocalServerCatalog(supported = false))
    private val withAgents = base.copy(subagents = listOf(agent))
    private val withServers = withAgents.copy(localServers = LocalServerCatalog(servers = listOf(server), live = true))
    private val selected = mutableListOf<String>()
    private val stopped = mutableListOf<String>()
    private var screensClosed = 0

    private val state = mutableStateOf(base)
    private fun setApp(initial: ClientState = base, start: ShellStart = ShellStart(showThread = true), fold: Fold? = null) {
        state.value = initial
        compose.setContent { App(start, fold) }
    }

    @androidx.compose.runtime.Composable
    private fun App(start: ShellStart, fold: Fold?) {
        CompositionLocalProvider(LocalFold provides fold) {
            TrefoilApp(state.value, listOf(environment, other), { _, _, _ -> }, {}, { selected += it }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
                onStopSubagent = { stopped += it }, onCloseScreen = { screensClosed++ }, start = start)
        }
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
    private fun inList(text: String) = hasText(text) and hasAnyAncestor(pane("Thread list"))
    private fun panel(tab: String) = compose.onNode(pane("$tab side panel"))
    private fun toggle() = compose.onNode(hasContentDescription("Open side panel") and hasClickAction())
    private fun closeButton() = compose.onNode(hasContentDescription("Close side panel") and hasAnyAncestor(pane("Screens side panel") or pane("Agents side panel") or pane("Preview side panel")))
    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun theToggleOpensAndClosesThePanelAndTheListReturns() {
        setApp()
        compose.onNode(inList("Other thread")).assertIsDisplayed()
        toggle().performClick()
        panel("Screens").assertExists()
        // 840-1599dp: the panel takes the list's place while open.
        compose.onNode(inList("Other thread")).assertDoesNotExist()
        closeButton().performClick()
        panel("Screens").assertDoesNotExist()
        compose.onNode(inList("Other thread")).assertIsDisplayed()
        toggle().assertExists()
    }

    @Test fun aListCollapsedBeforeOpeningStaysCollapsedAfterClosing() {
        setApp(start = ShellStart(showThread = true, listCollapsed = true))
        compose.onNode(inList("Other thread")).assertDoesNotExist()
        toggle().performClick()
        panel("Screens").assertExists()
        closeButton().performClick()
        compose.onNode(inList("Other thread")).assertDoesNotExist()
        compose.onNodeWithContentDescription("Show thread list").assertExists()
    }

    @Test fun showThreadListClosesThePanelThatReplacedTheList() {
        setApp()
        toggle().performClick()
        compose.onNodeWithContentDescription("Show thread list").performClick()
        panel("Screens").assertDoesNotExist()
        compose.onNode(inList("Other thread")).assertIsDisplayed()
    }

    @Config(qualifiers = "w1920dp-h1080dp")
    @Test fun desktopShowsThreePanesWithTheListStillVisible() {
        setApp(withAgents)
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).performClick()
        panel("Agents").assertExists()
        compose.onNode(inList("Other thread")).assertIsDisplayed()
        val list = compose.onNode(pane("Thread list")).getUnclippedBoundsInRoot()
        val conversation = compose.onNode(pane("Fixture thread, conversation")).getUnclippedBoundsInRoot()
        val side = panel("Agents").getUnclippedBoundsInRoot()
        assertTrue(list.right <= conversation.left + 1.dp && conversation.right <= side.left + 1.dp)
        assertTrue("panel width ${side.right - side.left}", side.right - side.left >= 360.dp)
    }

    @Config(qualifiers = "w841dp-h673dp")
    @Test fun bookPostureKeepsTheConversationStartAndThePanelEndOfTheHinge() {
        val fold = Fold(FoldOrientation.Vertical, 420f, 433f, separating = true, halfOpened = true)
        setApp(fold = fold)
        toggle().performClick()
        val conversation = compose.onNode(pane("Fixture thread, conversation")).getUnclippedBoundsInRoot()
        val side = panel("Screens").getUnclippedBoundsInRoot()
        assertTrue("conversation ends at ${conversation.right}", conversation.right <= fold.start.dp)
        assertTrue("panel starts at ${side.left}", side.left >= fold.end.dp)
        val d = compose.density.density
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val b = node.boundsInRoot
            if (b.width <= 0f || b.width >= 841f * d - 1f) return@forEach
            val overlap = minOf(b.right, fold.end * d) - maxOf(b.left, fold.start * d)
            assertTrue("node ${node.id} $b crosses the hinge", overlap <= 1f)
        }
    }

    /** The header's toggle while the panel is open: the "Close side panel" control that is not inside the panel. */
    private fun headerCloseToggle() = compose.onNode(hasContentDescription("Close side panel") and hasClickAction() and
        !hasAnyAncestor(pane("Screens side panel") or pane("Agents side panel") or pane("Preview side panel")))

    @Test fun anOpenPanelKeepsTheHeaderToggleInAWideEnoughPane() {
        setApp()
        toggle().performClick()
        panel("Screens").assertExists()
        val conversation = compose.onNode(pane("Fixture thread, conversation")).getUnclippedBoundsInRoot()
        assertTrue("conversation is ${conversation.right - conversation.left}", conversation.right - conversation.left >= 380.dp)
        headerCloseToggle().assertExists()
        // Closing from the header toggle puts focus back on the toggle.
        headerCloseToggle().performClick()
        panel("Screens").assertDoesNotExist()
        compose.waitForIdle()
        toggle().assertIsFocused()
    }

    @Config(qualifiers = "w841dp-h673dp")
    @Test fun anOpenPanelLeavesTheToggleOutOfAPaneNarrowerThan380dp() {
        val fold = Fold(FoldOrientation.Vertical, 420f, 433f, separating = true, halfOpened = true)
        setApp(fold = fold)
        toggle().performClick()
        val conversation = compose.onNode(pane("Fixture thread, conversation")).getUnclippedBoundsInRoot()
        assertTrue("conversation is ${conversation.right - conversation.left}", conversation.right - conversation.left < 380.dp)
        headerCloseToggle().assertDoesNotExist()
        // The panel's own Close still closes it; focus has no toggle to return to while the panel is open, and nothing throws.
        closeButton().performClick()
        compose.waitForIdle()
        panel("Screens").assertDoesNotExist()
        toggle().assertExists()
    }

    @Config(qualifiers = "w673dp-h841dp")
    @Test fun noToggleOnAFoldPortrait() {
        setApp(withAgents)
        compose.onNode(pane("Fixture thread, conversation")).assertExists()
        toggle().assertDoesNotExist()
    }

    @Config(qualifiers = "w880dp-h400dp")
    @Test fun noToggleAtCompactHeight() {
        setApp(withAgents)
        toggle().assertDoesNotExist()
    }

    @Test fun theOpenTabSurvivesRecreation() {
        state.value = withAgents
        val restoration = StateRestorationTester(compose)
        restoration.setContent { App(ShellStart(showThread = true), null) }
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).performClick()
        panel("Agents").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        panel("Agents").assertExists()
    }

    @Test fun closingThePanelEndsAScreenViewerLeftOpenOnAnotherTab() {
        setApp(withAgents)
        toggle().performClick()
        panel("Screens").assertExists()
        compose.onNode(hasText("Agents") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        panel("Agents").assertExists()
        val before = screensClosed
        closeButton().performClick()
        panel("Agents").assertDoesNotExist()
        // The viewer belongs to the panel, not to the tab in front: nothing live stays open behind a closed panel.
        assertEquals(before + 1, screensClosed)
    }

    @Test fun switchingMachineClosesThePanel() {
        setApp()
        toggle().performClick()
        panel("Screens").assertExists()
        state.value = base.copy(environment = other, selectedThreadId = "t")
        compose.waitForIdle()
        panel("Screens").assertDoesNotExist()
    }

    @Test fun theDestinationSurvivesTheSameMachineComingBackButNotAnotherMachine() {
        val noThread = base.copy(selectedThreadId = null)
        fun screens() = compose.onAllNodes(hasText("Choose a conversation first", substring = true))
        setApp(noThread, ShellStart(showScreens = true))
        // Once: the list says what to do and the detail pane beside it does not repeat the sentence.
        screens().assertCountEquals(1)
        compose.onNodeWithText("No conversation selected").assertExists()
        // The environment is absent for a moment while a session restores or reconnects; the same machine coming back keeps Screens.
        state.value = ClientState()
        compose.waitForIdle()
        screens().assertCountEquals(0)
        state.value = noThread
        compose.waitForIdle()
        screens().assertCountEquals(1)
        // Another machine does not inherit it.
        state.value = ClientState()
        compose.waitForIdle()
        state.value = noThread.copy(environment = other)
        compose.waitForIdle()
        screens().assertCountEquals(0)
        compose.onNodeWithText("Fixture thread").assertExists()
    }

    @Test fun theAgentsTabGoesAwayWithTheRoster() {
        setApp(withAgents)
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).performClick()
        panel("Agents").assertExists()
        state.value = base
        compose.waitForIdle()
        panel("Agents").assertDoesNotExist()
        compose.onNodeWithText("Explore code").assertDoesNotExist()
    }

    @Test fun backClosesThePanelBeforeLeavingTheThread() {
        val child = base.copy(threads = base.threads + ThreadSummary("c", "p", "Child thread", parentThreadId = "t", relationshipToParent = "subagent"), selectedThreadId = "c")
        setApp(child)
        toggle().performClick()
        panel("Screens").assertExists()
        // Screens' catalog takes Back first and leaves through the panel: the panel closes, the child stays open.
        systemBack()
        compose.waitForIdle()
        panel("Screens").assertDoesNotExist()
        assertEquals(emptyList<String>(), selected)
        systemBack()
        compose.waitForIdle()
        assertEquals(listOf("t"), selected)
    }

    @Test fun backClosesTheAgentsTab() {
        setApp(withAgents)
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).performClick()
        panel("Agents").assertExists()
        systemBack()
        compose.waitForIdle()
        panel("Agents").assertDoesNotExist()
    }

    @Test fun stopFollowsTheSheetsWriteRule() {
        setApp(withAgents)
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Stop Explore code").assertExists()
        state.value = withAgents.copy(canWrite = false)
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        panel("Agents").assertExists()
        compose.onNodeWithContentDescription("Stop Explore code").assertDoesNotExist()
    }

    @Config(qualifiers = "w673dp-h841dp")
    @Test fun demotedChipsDoWhatTheChipsDo() {
        setApp(withServers)
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Thread actions for Fixture thread").performClick()
        compose.onNodeWithText("Agents (${subagentPillLabel()})").performClick()
        // No dock at this width: the Agents item opens the agents sheet, as the chip does today.
        compose.onNode(hasTestTag("agent-row-a")).assertExists()
        compose.onNodeWithContentDescription("Stop Explore code").assertExists()
    }

    @Config(qualifiers = "w673dp-h841dp")
    @Test fun demotedServersItemOpensServers() {
        setApp(withServers)
        compose.onNodeWithContentDescription("Thread actions for Fixture thread").performClick()
        compose.onNodeWithText("Dev servers (:5173)").performClick()
        compose.onNode(pane("Servers")).assertExists()
    }

    @Test fun theServersChipOpensThePreviewTabWhereThePanelDocks() {
        setApp(withServers)
        compose.onNode(hasClickAction() and hasContentDescription("5173", substring = true) and hasAnyAncestor(pane("Fixture thread, conversation"))).performClick()
        panel("Preview").assertExists()
    }

    // Item 5: the docked panel has one header. The tab's action sits in the 64dp tab bar, the hosted screen's title row is gone.
    private fun inPanel(tab: String, matcher: SemanticsMatcher) = compose.onNode(matcher and hasAnyAncestor(pane("$tab side panel")))
    private fun assertInTabBar(tab: String, description: String) {
        val node = inPanel(tab, hasContentDescription(description) and hasClickAction())
        node.assertExists()
        val tabBar = node.getUnclippedBoundsInRoot().bottom - panel(tab).getUnclippedBoundsInRoot().top
        assertTrue("$description bottom at $tabBar", tabBar <= 64.dp)
    }

    @Test fun thePreviewTabKeepsForwardAPortInTheTabBarWithoutATitleRow() {
        setApp(withServers)
        toggle().performClick()
        panel("Preview").assertExists()
        assertInTabBar("Preview", "Forward a port…")
        compose.onAllNodes(hasText("Dev servers") and hasAnyAncestor(pane("Preview side panel"))).assertCountEquals(0)
        compose.onAllNodes(hasText("Listening on the connected machine") and hasAnyAncestor(pane("Preview side panel"))).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Forward a port…").assertCountEquals(1)
        // Same enabled rule as the list's own button had: it needs the forward permission.
        inPanel("Preview", hasContentDescription("Forward a port…")).assertIsEnabled()
    }

    @Test fun theScreensTabKeepsRefreshInTheTabBarWithoutATitleRow() {
        setApp(withAgents)
        toggle().performClick()
        panel("Screens").assertExists()
        assertInTabBar("Screens", "Refresh screen sessions")
        compose.onAllNodes(hasText("Screen sessions") and hasAnyAncestor(pane("Screens side panel"))).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Refresh screen sessions").assertCountEquals(1)
    }

    @Test fun theAgentsTabHasNoSecondHeading() {
        setApp(withAgents)
        compose.onNode(hasContentDescription(subagentLabel()) and hasClickAction()).performClick()
        panel("Agents").assertExists()
        // The tab pill is the only "Agents" text in the panel; the rows follow directly.
        compose.onAllNodes(hasText("Agents") and hasAnyAncestor(pane("Agents side panel"))).assertCountEquals(1)
        inPanel("Agents", hasTestTag("agent-row-a")).assertExists()
    }

    @Test fun theTabBarActionLeavesWithItsTab() {
        setApp(withAgents)
        toggle().performClick()
        compose.onAllNodesWithContentDescription("Refresh screen sessions").assertCountEquals(1)
        compose.onNode(hasText("Agents") and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab)).performClick()
        compose.onAllNodesWithContentDescription("Refresh screen sessions").assertCountEquals(0)
    }

    @Config(qualifiers = "w673dp-h841dp")
    @Test fun aDestinationKeepsItsOwnHeader() {
        setApp(withServers)
        compose.onNodeWithContentDescription("Thread actions for Fixture thread").performClick()
        compose.onNodeWithText("Dev servers (:5173)").performClick()
        compose.onNode(pane("Servers")).assertExists()
        compose.onNode(hasText("Dev servers") and hasAnyAncestor(pane("Servers"))).assertExists()
        compose.onNode(hasContentDescription("Forward a port…") and hasAnyAncestor(pane("Servers"))).assertExists()
    }

    private fun subagentPillLabel(): String = com.tajchert.trefoil.ui.subagentPill(com.tajchert.trefoil.ui.subagentRoster(emptyList(), listOf(agent)))!!.label
    private fun subagentLabel(): String = com.tajchert.trefoil.ui.subagentPill(com.tajchert.trefoil.ui.subagentRoster(emptyList(), listOf(agent)))!!.accessibilityLabel
}

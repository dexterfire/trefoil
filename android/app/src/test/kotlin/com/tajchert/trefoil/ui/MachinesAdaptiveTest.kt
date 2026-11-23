package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Machines in a container wide enough for list-detail: the layout comes from the container width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1200dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MachinesAdaptiveTest {
    @get:Rule val compose = createComposeRule()

    private val mini = SavedEnvironment("mini", "Mac mini", "https://mini.example.test", "fixture")
    private val book = SavedEnvironment("book", "MacBook Pro", "https://book.example.test", "fixture")
    private val saved = listOf(mini, book)
    private val connected = ClientState(
        environment = mini, connection = ConnectionStatus.Connected, canWrite = true,
        threads = listOf(ThreadSummary("a", "p", "Working one", "running"), ThreadSummary("b", "p", "Needs approval", "running", pendingRequestKind = "approval")),
    )

    private fun show(state: ClientState = connected, machines: List<SavedEnvironment> = saved, onConnect: (SavedEnvironment) -> Unit = {}, onDisconnect: () -> Unit = {}, onBack: (() -> Unit)? = null, onOpenUsage: (() -> Unit)? = null) =
        compose.setContent { TrefoilTheme { MachinesScreen(state, machines, onConnect = onConnect, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = onDisconnect, onBack = onBack, onOpenUsage = onOpenUsage) } }

    @Test fun connectedMachineOpensInTheInspectorWithItsActionsAndKnownCounts() {
        show()
        compose.onNodeWithText("Open threads").assertExists()
        compose.onNodeWithText("Disconnect").assertExists()
        compose.onNodeWithText("Forget").assertExists()
        compose.onNodeWithText("Needs you").assertExists()
        compose.onNodeWithText("Working").assertExists()
        // No limit is known, so no tile pretends otherwise.
        compose.onNodeWithText("Tightest limit").assertDoesNotExist()
        compose.onAllNodesWithText("Mac mini").assertCountEquals(2)
    }

    @Test fun tightestLimitShowsOnlyWhenKnown() {
        val withLimit = connected.copy(providers = listOf(ProviderInstance("a", "codex", "Work", usageLimits = ProviderUsageLimits("2026-01-01T00:00:00Z", listOf(UsageLimitWindow("w", "session", "Session", 38.0))))))
        show(withLimit)
        compose.onNodeWithText("Tightest limit").assertExists()
        compose.onNodeWithText("62% left").assertExists()
    }

    @Test fun selectingAnotherMachineOffersConnectAndHidesLiveFiguresWithoutConnecting() {
        val connects = mutableListOf<String>()
        show(onConnect = { connects += it.id })
        compose.onAllNodesWithText("MacBook Pro")[0].performClick()
        assertEquals(emptyList<String>(), connects)
        compose.onNodeWithText("Open threads").assertDoesNotExist()
        compose.onNodeWithText("Needs you").assertDoesNotExist()
        compose.onNodeWithText("Disconnect").assertDoesNotExist()
        compose.onNode(hasText("Connect") and hasClickAction()).performClick()
        assertEquals(listOf("book"), connects)
    }

    @Test fun openThreadsAndDisconnectAreWiredToTheConnectedMachine() {
        val connects = mutableListOf<String>()
        var disconnects = 0
        show(onConnect = { connects += it.id }, onDisconnect = { disconnects++ })
        compose.onNodeWithText("Open threads").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals(listOf("mini"), connects)
        assertEquals(1, disconnects)
    }

    @Test fun forgetAsksForConfirmationFirst() {
        show()
        compose.onNodeWithText("Forget").performClick()
        compose.onNodeWithText("Forget Mac mini?").assertExists()
    }

    @Test fun selectionSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrefoilTheme { MachinesScreen(connected, saved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = null) } }
        compose.onAllNodesWithText("MacBook Pro")[0].performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Open threads").assertDoesNotExist()
        compose.onNode(hasText("Connect") and hasClickAction()).assertExists()
    }

    @Test fun selectionSurvivesTheContainerNarrowingBelowListDetailAndWideningAgain() {
        var width by mutableStateOf(1200.dp)
        compose.setContent { TrefoilTheme { Box(Modifier.width(width)) { MachinesScreen(connected, saved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = null) } } }
        compose.onAllNodesWithText("MacBook Pro")[0].performClick()
        width = 700.dp
        compose.waitForIdle()
        width = 1200.dp
        compose.waitForIdle()
        compose.onNodeWithText("Open threads").assertDoesNotExist()
        compose.onNode(hasText("Connect") and hasClickAction()).assertExists()
    }

    @Test fun forgettingTheSelectedMachineFallsBackToTheConnectedOne() {
        var machines by mutableStateOf(saved)
        compose.setContent { TrefoilTheme { MachinesScreen(connected, machines, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = null) } }
        compose.onAllNodesWithText("MacBook Pro")[0].performClick()
        compose.onNodeWithText("Open threads").assertDoesNotExist()
        machines = listOf(mini)
        compose.waitForIdle()
        compose.onNodeWithText("Open threads").assertExists()
        compose.onAllNodesWithText("MacBook Pro").assertCountEquals(0)
    }

    @Test fun backArrowOnlyWithAHandler() {
        show()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun backArrowShowsWithAHandler() {
        var backs = 0
        show(onBack = { backs++ })
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun nothingSavedShowsOneCardWithBothAddActions() {
        var pairs = 0
        var ssh = 0
        compose.setContent { TrefoilTheme { MachinesScreen(ClientState(), emptyList(), onConnect = {}, onForget = {}, onPair = { pairs++ }, onSsh = { ssh++ }, onDisconnect = {}, onBack = null) } }
        compose.onNodeWithText("Machines").assertExists()
        compose.onNodeWithContentDescription("Pair with link").performClick()
        compose.onNodeWithContentDescription("Connect over SSH").performClick()
        assertEquals(1, pairs)
        assertEquals(1, ssh)
        compose.onNodeWithText("SAVED").assertDoesNotExist()
    }

    private val four = saved + listOf(
        SavedEnvironment("lab", "Lab box", "https://lab.example.test", "fixture"),
        SavedEnvironment("nas", "Home NAS", "https://nas.example.test", "fixture"),
    )
    private val withLimit = connected.copy(providers = listOf(ProviderInstance("a", "codex", "Work", usageLimits = ProviderUsageLimits("2026-01-01T00:00:00Z", listOf(UsageLimitWindow("w", "session", "Session", 38.0))))))

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot()

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun mediumContainerShowsTwoCardsPerRowAndTheAddTilesInsideTheViewport() {
        show(machines = four)
        val mini = bounds("machine-card-mini")
        val book = bounds("machine-card-book")
        val lab = bounds("machine-card-lab")
        assertEquals("first two machines share a row", mini.top, book.top)
        assertTrue("second sits right of the first: $mini $book", book.left >= mini.right)
        assertTrue("third starts the next row: $mini $lab", lab.top >= mini.bottom && lab.left == mini.left)
        val cardWidth = (mini.right - mini.left).value
        assertTrue("cards are about half the 673dp column: $cardWidth", cardWidth in 290f..330f)
        val pair = compose.onNodeWithContentDescription("Pair with link").getBoundsInRoot()
        val ssh = compose.onNodeWithContentDescription("Connect over SSH").getBoundsInRoot()
        assertTrue("add tiles end inside the 841dp viewport: $pair $ssh", pair.bottom.value <= 841f && ssh.bottom.value <= 841f)
        compose.onNodeWithContentDescription("Pair with link").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect over SSH").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun theTwoCardsOfARowHaveTheSameHeightAndTheirFootersLineUp() {
        // The connected machine carries live counts that the other lacks, so its content is taller.
        show(machines = four)
        val mini = bounds("machine-card-mini")
        val book = bounds("machine-card-book")
        assertEquals("cards of one row share top and bottom: $mini $book", mini.top, book.top)
        assertEquals("cards of one row share top and bottom: $mini $book", mini.bottom, book.bottom)
        val footers = compose.onAllNodesWithText("route", substring = true).fetchSemanticsNodes().take(2).map { it.boundsInRoot.top }
        assertEquals("both cards show their routes footer", 2, footers.size)
        assertEquals("footers sit on one line: $footers", footers[0], footers[1], 0.5f)
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun addTilesStayInsideTheViewportWhenNotConnectedToo() {
        show(ClientState(), machines = four)
        compose.onNodeWithContentDescription("Pair with link").assertIsDisplayed()
        compose.onNodeWithContentDescription("Connect over SSH").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun aLoneSavedMachineIsOneCardOfAtMostFourHundredEightyDp() {
        show(machines = listOf(mini))
        val card = bounds("machine-card-mini")
        assertTrue("one card, at most 480dp: $card", (card.right - card.left).value <= 480f && (card.right - card.left).value > 300f)
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun cardTouchTargetsAreAtLeastFortyEightDpFromSixHundredDp() {
        show()
        val menu = compose.onAllNodes(hasContentDescription("Options for Mac mini")).onFirst().getBoundsInRoot()
        assertTrue("menu button $menu", (menu.right - menu.left).value >= 48f && (menu.bottom - menu.top).value >= 48f)
        val routes = compose.onAllNodes(hasText("1 route") and hasClickAction()).onFirst().getBoundsInRoot()
        assertTrue("routes footer $routes", (routes.bottom - routes.top).value >= 48f)
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun notConnectedWithoutBackKeepsTheBrandHeaderAndNoEmptyTopBar() {
        show(ClientState(), machines = four)
        compose.onNodeWithTag("machines-brand-header").assertExists()
        compose.onNodeWithTag("machines-pane-header").assertDoesNotExist()
        assertTrue("brand header starts near the top, not under a 64dp bar: ${bounds("machines-brand-header")}", bounds("machines-brand-header").top.value < 40f)
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun connectedWithoutBackIsOneSixtyFourDpTitleRow() {
        show(machines = four)
        compose.onNodeWithTag("machines-brand-header").assertDoesNotExist()
        val header = bounds("machines-pane-header")
        assertEquals(64f, (header.bottom - header.top).value, 0.5f)
        assertTrue("the title row opens the screen", header.top.value < 1f)
        compose.onNodeWithText("Machines").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onAllNodesWithText("Machines").assertCountEquals(1)
    }

    @Test @Config(qualifiers = "w673dp-h841dp")
    fun connectedWithBackSharesOneRowBetweenArrowAndTitle() {
        var backs = 0
        show(machines = four, onBack = { backs++ })
        val header = bounds("machines-pane-header")
        assertEquals(64f, (header.bottom - header.top).value, 0.5f)
        val back = compose.onNodeWithContentDescription("Back").getBoundsInRoot()
        val title = compose.onNodeWithText("Machines").getBoundsInRoot()
        assertTrue("arrow and title sit inside the one row: $header $back $title", back.top >= header.top && back.bottom <= header.bottom && title.top >= header.top && title.bottom <= header.bottom && title.left >= back.right - 16.dp)
        compose.onAllNodesWithText("Machines").assertCountEquals(1)
        compose.onNodeWithTag("machines-brand-header").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun listDetailUsesTheSameThreeHeaders() {
        show()
        compose.onNodeWithTag("machines-brand-header").assertDoesNotExist()
        assertEquals(64f, bounds("machines-pane-header").let { (it.bottom - it.top).value }, 0.5f)
        compose.onAllNodesWithText("Machines").assertCountEquals(1)
    }

    @Test
    fun listDetailWithBackSharesOneRow() {
        show(onBack = {})
        compose.onNodeWithTag("machines-brand-header").assertDoesNotExist()
        assertEquals(64f, bounds("machines-pane-header").let { (it.bottom - it.top).value }, 0.5f)
        compose.onNodeWithContentDescription("Back").assertExists()
    }

    @Test
    fun listDetailNotConnectedKeepsTheBrandHeader() {
        show(ClientState())
        compose.onNodeWithTag("machines-brand-header").assertExists()
        compose.onNodeWithTag("machines-pane-header").assertDoesNotExist()
        compose.onNodeWithText("Not connected").assertExists()
    }

    @Test @Config(qualifiers = "w1840dp-h900dp")
    fun inspectorSitsNextToTheListOnAVeryWideContainer() {
        show(machines = four)
        val list = bounds("machines-list")
        val inspector = bounds("machine-inspector")
        val gap = (inspector.left - list.right).value
        assertTrue("inspector content starts within 40dp of the list: gap $gap ($list $inspector)", gap in 0f..40f)
    }

    @Test
    fun tightestLimitOpensUsageWhenAHandlerIsGiven() {
        var opened = 0
        show(withLimit, onOpenUsage = { opened++ })
        compose.onNodeWithText("Tightest limit").assertHasClickAction().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun tightestLimitIsNotClickableWithoutAHandler() {
        show(withLimit)
        compose.onNodeWithText("Tightest limit").assertExists().assertHasNoClickAction()
        compose.onNodeWithText("62% left").assertExists()
    }

    @Test fun chooserSaysNotConnectedAndSelectsTheFirstMachine() {
        show(ClientState())
        compose.onNodeWithText("Not connected").assertExists()
        compose.onNode(hasText("Connect") and hasClickAction()).assertExists()
        compose.onNodeWithText("Open threads").assertDoesNotExist()
        assertNull(selectedMachine(emptyList(), null, null))
    }
}

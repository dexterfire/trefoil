package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.R
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

/**
 * Review fixes of the adaptive shell: every destination stays on its side of a separating hinge, a collapsed list belongs to one
 * machine, and a collapsed subagent child keeps both header controls. Assertions look at the rail, the drawer and the destinations
 * themselves, never at which buttons the thread list draws in its own header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp")
class ShellReviewFixesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val other = SavedEnvironment("other", "Other", "https://other.invalid", "other")
    private val fixture = ClientState(environment = environment, connection = ConnectionStatus.Connected,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread")))
    private val bookFold = Fold(FoldOrientation.Vertical, 330f, 343f, separating = true, halfOpened = true)
    private val selected = fixture.copy(selectedThreadId = "t")
    private val selectedThreads = mutableListOf<String>()

    private fun setApp(state: ClientState = fixture, start: ShellStart = ShellStart(), fold: Fold? = null) = compose.setContent {
        CompositionLocalProvider(LocalFold provides fold) {
            TrefoilApp(state, listOf(environment, other), { _, _, _ -> }, {}, { selectedThreads += it }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = start)
        }
    }

    private fun systemBack() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    private fun density() = compose.density.density

    /** No semantics node narrower than the window may overlap the hinge band. */
    private fun assertNothingUnderTheHinge(fold: Fold, windowWidth: Float) {
        val d = density()
        compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val b = node.boundsInRoot
            if (b.width <= 0f || b.width >= windowWidth * d - 1f) return@forEach
            val overlap = minOf(b.right, fold.end * d) - maxOf(b.left, fold.start * d)
            assertTrue("node ${node.id} ${b} crosses the hinge ${fold.start}..${fold.end}", overlap <= 1f)
        }
    }

    private fun assertSides(startMarker: SemanticsNodeInteraction, endMarker: SemanticsNodeInteraction, fold: Fold = bookFold) {
        val s = startMarker.getUnclippedBoundsInRoot()
        val e = endMarker.getUnclippedBoundsInRoot()
        assertTrue("start region content ends at ${s.right}", s.right <= fold.start.dp)
        assertTrue("end region content starts at ${e.left}", e.left >= fold.end.dp)
    }

    // ---- 1. Hinge on every destination ----

    @Test fun usageSitsInTheEndRegionBesideTheThreadList() {
        setApp(start = ShellStart(showUsage = true), fold = bookFold)
        assertSides(compose.onNodeWithText("Fixture thread"), compose.onNodeWithText("Лимиты и использование"))
        assertNothingUnderTheHinge(bookFold, 673f)
    }

    @Test fun machinesSitsInTheEndRegionBesideTheThreadList() {
        setApp(start = ShellStart(choosingEnvironment = true), fold = bookFold)
        assertSides(compose.onNodeWithText("Fixture thread"), compose.onNodeWithText("ADD"))
        assertNothingUnderTheHinge(bookFold, 673f)
    }

    @Test fun screensSitsInTheEndRegionBesideTheThreadList() {
        setApp(start = ShellStart(showScreens = true), fold = bookFold)
        assertSides(compose.onNodeWithText("Fixture thread"), compose.onNode(hasText("Choose a conversation first", substring = true)))
        assertNothingUnderTheHinge(bookFold, 673f)
    }

    @Test fun serversSitsInTheEndRegionBesideTheThreadList() {
        setApp(start = ShellStart(showPorts = true), fold = bookFold)
        val scanning = compose.activity.getString(R.string.ports_scanning_title)
        assertSides(compose.onNodeWithText("Fixture thread"), compose.onNodeWithText(scanning))
        assertNothingUnderTheHinge(bookFold, 673f)
    }

    @Test fun withoutAMachineABrandPanelFillsTheStartRegionAndMachinesTheEnd() {
        setApp(ClientState(), fold = bookFold)
        assertSides(compose.onNodeWithText("Connect a machine to start"), compose.onNodeWithText("ADD"))
        compose.onNodeWithText("Fixture thread").assertDoesNotExist()
        assertNothingUnderTheHinge(bookFold, 673f)
    }

    @Test @Config(sdk = [35], qualifiers = "w841dp-h673dp") fun besideARailTheDestinationHasNoBackArrowAndTheHingeStays() {
        val fold = Fold(FoldOrientation.Vertical, 420f, 433f, separating = true, halfOpened = true)
        setApp(start = ShellStart(showUsage = true), fold = fold)
        compose.onNodeWithContentDescription("Trefoil, show threads").assertExists()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        assertSides(compose.onNodeWithText("Fixture thread"), compose.onNodeWithText("Лимиты и использование"), fold)
        assertNothingUnderTheHinge(fold, 841f)
    }

    @Test fun withoutARailTheDestinationKeepsABackArrowToThreads() {
        setApp(start = ShellStart(showUsage = true), fold = bookFold)
        compose.onNodeWithContentDescription("Back").assertExists()
        systemBack()
        compose.onNodeWithText("Лимиты и использование").assertDoesNotExist()
        compose.onNodeWithText("Pick a thread").assertExists()
    }

    @Test fun pickingAThreadInTheStartRegionGoesToThreadsWithThatThread() {
        setApp(start = ShellStart(showUsage = true), fold = bookFold)
        compose.onNodeWithText("Fixture thread").performClick()
        compose.onNodeWithText("Лимиты и использование").assertDoesNotExist()
        assertEquals(listOf("t"), selectedThreads)
    }

    @Test fun theSnackbarSitsInTheEndRegionBesideAHinge() {
        setApp(fixture.copy(settlementUndo = SettlementUndo("token", ThreadTarget("fixture", "t"))), fold = bookFold)
        compose.waitForIdle()
        val bounds = compose.onNodeWithText("Thread settled").getUnclippedBoundsInRoot()
        assertTrue("snackbar starts at ${bounds.left}", bounds.left >= bookFold.end.dp)
    }

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp") fun aFlatWindowStillGivesTheDestinationTheWholeWidthBesideTheRail() {
        setApp(start = ShellStart(showUsage = true))
        compose.onNodeWithText("Fixture thread").assertDoesNotExist()
        val usage = compose.onNodeWithText("Лимиты и использование").getUnclippedBoundsInRoot()
        assertTrue("usage starts at ${usage.left}", usage.left < 200.dp)
    }

    // ---- 2. A collapsed list belongs to one machine ----

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp") fun aCollapsedListOpensAgainOnAnotherMachine() {
        var state by mutableStateOf(selected)
        compose.setContent { TrefoilApp(state, listOf(environment, other), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = ShellStart(listCollapsed = true)) }
        compose.onNodeWithContentDescription("Show thread list").assertExists()
        state = selected.copy(environment = other)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show thread list").assertDoesNotExist()
    }

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp") fun aCollapsedListSurvivesRecreationForTheSameMachine() {
        val restore = StateRestorationTester(compose)
        restore.setContent { TrefoilApp(selected, listOf(environment, other), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = ShellStart(listCollapsed = true)) }
        compose.onNodeWithContentDescription("Show thread list").assertExists()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Show thread list").assertExists()
    }

    // ---- 3. Collapsed subagent child ----

    private val parent = ThreadSummary("parent", "p", "Parent thread")
    private val child = ThreadSummary("child", "p", "Child thread", parentThreadId = "parent", relationshipToParent = "subagent")
    private val childState = fixture.copy(threads = listOf(parent, child), selectedThreadId = "child")

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp") fun aCollapsedChildKeepsShowThreadListAndTheParentArrow() {
        setApp(childState, ShellStart(listCollapsed = true))
        compose.onNodeWithContentDescription("Show thread list").assertExists()
        compose.onNodeWithContentDescription("Back to threads").assertExists()
        compose.onNodeWithContentDescription("Back to threads").performClick()
        assertEquals(listOf("parent"), selectedThreads)
    }

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp") fun aCollapsedTopLevelThreadShowsOnlyShowThreadList() {
        setApp(childState.copy(selectedThreadId = "parent"), ShellStart(listCollapsed = true))
        compose.onNodeWithContentDescription("Show thread list").assertExists()
        compose.onNodeWithContentDescription("Back to threads").assertDoesNotExist()
    }
}

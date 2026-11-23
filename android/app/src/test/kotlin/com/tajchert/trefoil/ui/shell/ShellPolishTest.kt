package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Polish of the adaptive shell: the empty detail pane, the TalkBack pane order and the compact-height keyboard layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class ShellPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val quiet = ClientState(environment = environment, connection = ConnectionStatus.Connected,
        projects = listOf(Project("p", "Project")), threads = listOf(ThreadSummary("t", "p", "Fixture thread")))
    private val waiting = quiet.copy(threads = listOf(
        ThreadSummary("a", "p", "Approve the migration", pendingRequestKind = "approval"),
        ThreadSummary("b", "p", "Name the release", pendingRequestKind = "user_input"),
        ThreadSummary("c", "p", "Third waiting", pendingRequestKind = "approval"),
        ThreadSummary("d", "p", "Fourth waiting", pendingRequestKind = "approval"),
        ThreadSummary("t", "p", "Fixture thread")))
    private val picked = mutableListOf<String>()

    private var imeState by mutableStateOf<Float?>(null)
    private fun setApp(state: ClientState, start: ShellStart = ShellStart(), ime: Float? = null) {
        imeState = ime
        compose.setContent {
            CompositionLocalProvider(LocalImeHeightOverride provides imeState) {
                TrefoilApp(state, listOf(environment), { _, _, _ -> }, {}, { picked += it }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = start)
            }
        }
    }

    // ---- 5. Empty detail ----

    private fun inDetail(text: String) = hasText(text) and hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Threads"))

    @Test fun headingFollowsTheNumberOfWaitingThreads() {
        assertEquals("Pick a thread", emptyDetailHeading(0))
        assertEquals("1 thread needs you", emptyDetailHeading(1))
        assertEquals("4 threads need you", emptyDetailHeading(4))
    }

    @Test fun nothingWaitingInvitesAPickAndOffersANewThread() {
        setApp(quiet)
        compose.onNodeWithText("Pick a thread").assertExists()
        compose.onNodeWithText("New thread").assertExists()
        compose.onNodeWithText("Approve the migration").assertDoesNotExist()
    }

    @Test fun waitingThreadsBecomeAtMostThreeCardsThatOpenTheThread() {
        setApp(waiting)
        compose.onNodeWithText("4 threads need you").assertExists()
        compose.onNode(inDetail("Third waiting")).assertExists()
        compose.onNode(inDetail("Fourth waiting")).assertDoesNotExist()
        compose.onNode(inDetail("Name the release") and hasClickAction()).performClick()
        assertEquals(listOf("b"), picked)
    }

    @Test fun theNewThreadButtonFollowsTheRailsRule() {
        setApp(waiting.copy(connection = ConnectionStatus.Reconnecting))
        compose.onNode(hasText("New thread") and hasClickAction()).assertIsNotEnabled()
        // Waiting threads are only offered while connected: reconnecting shows the plain invitation and no cards.
        compose.onNodeWithText("Pick a thread").assertExists()
        compose.onNode(inDetail("Approve the migration")).assertDoesNotExist()
    }

    @Test @Config(sdk = [35], qualifiers = "w841dp-h300dp") fun aShortWindowScrollsTheEmptyDetailInsteadOfClippingIt() {
        setApp(waiting)
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("4 threads need you"))).assertExists()
    }

    // ---- 7. TalkBack order ----

    private fun pane(title: String) = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title))
    private fun index(title: String) = pane(title).fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]

    @Test fun panesReadNavigationThenListThenDetail() {
        setApp(quiet.copy(selectedThreadId = "t"))
        assertEquals(0f, index("Navigation"))
        assertEquals(1f, index("Thread list"))
        assertEquals(2f, index("Fixture thread, conversation"))
        listOf("Navigation", "Thread list", "Fixture thread, conversation").forEach {
            assertTrue("$it is a traversal group", pane(it).fetchSemanticsNode().config[SemanticsProperties.IsTraversalGroup])
        }
    }

    @Test fun aDestinationBesideTheRailIsTheDetailPaneNamedAfterIt() {
        setApp(quiet, ShellStart(showUsage = true))
        assertEquals(2f, index("Usage"))
        assertEquals(0f, index("Navigation"))
    }

    @Test fun theEmptyDetailIsNamedAfterThreads() {
        setApp(quiet)
        assertEquals(2f, index("Threads"))
    }

    @Test @Config(sdk = [35], qualifiers = "w400dp-h800dp") fun aPhoneKeepsItsSemanticsUntouched() {
        setApp(quiet)
        pane("Thread list").assertDoesNotExist()
    }

    // ---- 8. Compact height with the keyboard ----

    private fun decide(compact: Boolean = true, ime: Boolean = true, room: Float = 200f, focus: PaneFocus, twoPane: Boolean = true, split: Boolean = false) =
        transientList(twoPane, split, compact, ime, room, focus)

    @Test fun focusInTheConversationHidesTheListAndFocusInTheListGivesItTheWidth() {
        assertEquals(TransientList.HideList, decide(focus = PaneFocus.Conversation))
        assertEquals(TransientList.ListOnly, decide(focus = PaneFocus.List))
        assertEquals(TransientList.None, decide(focus = PaneFocus.None))
    }

    @Test fun nothingChangesWithoutTheKeyboardOrWithRoomToSpare() {
        assertEquals(TransientList.None, decide(ime = false, focus = PaneFocus.Conversation))
        assertEquals(TransientList.None, decide(room = 240f, focus = PaneFocus.Conversation))
        assertEquals(TransientList.HideList, decide(room = 239f, focus = PaneFocus.Conversation))
    }

    @Test fun regularHeightPhonesAndHingesAreNeverAffected() {
        assertEquals(TransientList.None, decide(compact = false, focus = PaneFocus.Conversation))
        assertEquals(TransientList.None, decide(twoPane = false, focus = PaneFocus.Conversation))
        assertEquals(TransientList.None, decide(split = true, focus = PaneFocus.List))
    }

    private fun focusComposer() = compose.onNode(hasSetTextAction()).performClick()

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h400dp") fun focusInTheConversationHidesTheListOnlyWhileTheKeyboardIsUp() {
        setApp(quiet.copy(selectedThreadId = "t"), ime = 300f)
        pane("Thread list").assertExists()
        focusComposer()
        compose.waitForIdle()
        pane("Thread list").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show thread list").assertDoesNotExist()
        imeState = 0f
        compose.waitForIdle()
        pane("Thread list").assertExists()
    }

    @Test @Config(sdk = [35], qualifiers = "w1280dp-h400dp") fun withoutTheKeyboardFocusInTheConversationKeepsTheList() {
        setApp(quiet.copy(selectedThreadId = "t"))
        focusComposer()
        compose.waitForIdle()
        pane("Thread list").assertExists()
    }
}

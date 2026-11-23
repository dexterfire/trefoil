package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Round-3a items 3 (table column cap), 6 (48dp targets), 13 (request border) and 16 (header, copy button) in wide and narrow panes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w900dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationTargetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val environment = SavedEnvironment("env", "Fixture", "https://fixture.invalid", "fixture-token")
    private val writable = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, selectedRuntimeMode = RuntimeMode.ApprovalRequired)

    @Composable
    private fun Pane(widthDp: Int, content: @Composable () -> Unit) {
        TrefoilTheme {
            CompositionLocalProvider(LocalConversationLayout provides conversationLayoutFor(widthDp.toFloat())) {
                Box(Modifier.width(widthDp.dp)) { content() }
            }
        }
    }

    private fun showComposer(widthDp: Int, state: ClientState = writable) = compose.setContent {
        Pane(widthDp) {
            ConversationComposer(state, null, "hello", {}, waiting = false, stopPending = false, onSetRuntimeMode = {}, onSetModel = {},
                onSetModelOption = { _, _ -> }, stop = {}, onSend = {}, onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {})
        }
    }

    /** Layout size of the node: what the control looks like. */
    private fun SemanticsNodeInteraction.look() = getBoundsInRoot().let { it.width to it.height }

    /** Size of the area that reacts to a touch (Material grows it past the layout bounds). */
    private fun SemanticsNodeInteraction.touch(): Pair<Dp, Dp> = with(compose.density) {
        fetchSemanticsNode().touchBoundsInRoot.let { it.width.toDp() to it.height.toDp() }
    }

    private fun assertTouchAtLeast48(label: String, node: SemanticsNodeInteraction) {
        val (w, h) = node.touch()
        assertTrue("$label touch area ${w}x$h", w >= 48.dp && h >= 48.dp)
    }

    // ---- Item 3: table column cap ----

    @Test fun narrowTablesKeepTheTwoHundredEightyDpColumnCap() {
        assertEquals(280, tableMaxColumnPx(wide = false, availablePx = 2000, density = 1f))
        assertEquals(735, tableMaxColumnPx(wide = false, availablePx = 5000, density = 2.625f))
        assertEquals(280, tableMaxColumnPx(wide = false, availablePx = 100, density = 1f)) // the width plays no part below 600dp
    }

    @Test fun wideTablesLetOneColumnGrowToTheSmallerOf520dpAndSixtyFivePercent() {
        assertEquals(520, tableMaxColumnPx(wide = true, availablePx = 880, density = 1f))
        assertEquals(520, tableMaxColumnPx(wide = true, availablePx = 800, density = 1f)) // 65% of 800 = 520
        assertEquals(390, tableMaxColumnPx(wide = true, availablePx = 600, density = 1f))
        assertEquals(1040, tableMaxColumnPx(wide = true, availablePx = 3000, density = 2f))
        assertEquals(2 * 390, tableMaxColumnPx(wide = true, availablePx = 1200, density = 2f))
    }

    @Test fun anUnboundedTableWidthKeepsTheNarrowCap() {
        assertEquals(280, tableMaxColumnPx(wide = true, availablePx = Constraints.Infinity, density = 1f))
    }

    @Test fun aLongUnbrokenTokenIsNotSplitInAWideTable() {
        val token = "x".repeat(60) // about 450dp at the cell text size: wider than the 280dp cap, narrower than 520dp
        compose.setContent { Pane(859) { MarkdownContent("| Command |\n| --- |\n| $token |") } }
        val b = compose.onNodeWithText(token, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("token wrapped ($b)", b.height < 30.dp)
    }

    @Test fun aLongUnbrokenTokenStillWrapsInANarrowTable() {
        val token = "x".repeat(60)
        compose.setContent { Pane(400) { MarkdownContent("| Command |\n| --- |\n| $token |") } }
        val height = compose.onNodeWithText(token, useUnmergedTree = true).getBoundsInRoot().height
        assertTrue("token did not wrap (height $height)", height > 30.dp)
    }

    // ---- Item 6: touch targets ----

    @Test fun primaryComposerButtonIs48dpWide() {
        showComposer(859)
        assertEquals(48.dp to 48.dp, compose.onNodeWithContentDescription("Send message").look())
    }

    @Test fun primaryComposerButtonStays44dpInANarrowPane() {
        showComposer(400)
        assertEquals(44.dp to 44.dp, compose.onNodeWithContentDescription("Send message").look())
    }

    @Test fun composerControlsReach48dpWideAndKeepTheirLookInBothPanes() {
        listOf(859, 400).forEach { width ->
            showComposer(width)
            assertTouchAtLeast48("send@$width", compose.onNodeWithContentDescription("Send message"))
            assertTouchAtLeast48("mode pill@$width", compose.onNodeWithContentDescription("Runtime mode: Supervised"))
            // The pill keeps its 40x32 look: only the touch area is larger.
            assertEquals(40.dp to 32.dp, compose.onNodeWithContentDescription("Runtime mode: Supervised").look())
            compose.activityRule.scenario.recreate()
        }
    }

    @Test fun composerKeepsItsHeightWhenWide() {
        showComposer(859)
        val wide = compose.onNodeWithContentDescription("Send message").getBoundsInRoot().bottom
        assertTrue("composer grew: send button bottom at $wide", wide <= 800.dp)
    }

    @Test fun aDisabledPickerPillDoesNotOpenItsMenu() {
        showComposer(859, state = writable.copy(canWrite = false))
        compose.onNodeWithContentDescription("Runtime mode: Supervised").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Runtime mode: Supervised").performClick()
        compose.onNodeWithText("Ask before commands and file changes.").assertDoesNotExist()
    }

    private val approval = RuntimeRequest("r1", "Run ./gradlew test", detail = "./gradlew test")

    private fun showApproval(widthDp: Int, enabled: Boolean, request: RuntimeRequest = approval, onRespond: (String) -> Unit = {}) = compose.setContent {
        Pane(widthDp) { RequestCard(request, enabled) { _, decision, _ -> onRespond(decision) } }
    }

    @Test fun allowAndDenyReach48dpWideAndNarrow() {
        listOf(859, 400).forEach { width ->
            showApproval(width, enabled = true)
            assertTouchAtLeast48("allow@$width", compose.onNodeWithText("Allow"))
            assertTouchAtLeast48("deny@$width", compose.onNodeWithText("Deny"))
            compose.activityRule.scenario.recreate()
        }
    }

    @Test fun allowAndDenyRespondWhenEnabled() {
        val decisions = mutableListOf<String>()
        showApproval(859, enabled = true) { decisions += it }
        compose.onNodeWithText("Allow").performClick()
        compose.onNodeWithText("Deny").performClick()
        assertEquals(listOf("accept", "decline"), decisions)
    }

    @Test fun allowAndDenyDoNothingWhenDisabled() {
        listOf(859, 400).forEach { width ->
            val decisions = mutableListOf<String>()
            showApproval(width, enabled = false) { decisions += it }
            compose.onNodeWithText("Allow").assertIsNotEnabled()
            compose.onNodeWithText("Deny").assertIsNotEnabled()
            compose.onNodeWithText("Allow").performClick()
            compose.onNodeWithText("Deny").performClick()
            assertTrue("responded at $width: $decisions", decisions.isEmpty())
            compose.activityRule.scenario.recreate()
        }
    }

    @Test fun anUnsupportedRequestOffersNoButtonsWide() {
        showApproval(859, enabled = true, request = approval.copy(canApprove = false))
        compose.onNodeWithText("Allow").assertDoesNotExist()
        compose.onNodeWithText("Deny").assertDoesNotExist()
    }

    @Test fun codeCopyIs48dpWideAnd36dpNarrow() {
        val md = "```kotlin\nval x = 1\n```"
        compose.setContent { Pane(859) { MarkdownContent(md) } }
        assertEquals(48.dp to 48.dp, compose.onNodeWithContentDescription("Copy code").look())
        assertTouchAtLeast48("copy", compose.onNodeWithContentDescription("Copy code"))
    }

    @Test fun codeCopyStays36dpInANarrowPane() {
        compose.setContent { Pane(400) { MarkdownContent("```kotlin\nval x = 1\n```") } }
        assertEquals(36.dp to 36.dp, compose.onNodeWithContentDescription("Copy code").look())
    }

    private val threadState = writable.copy(
        threads = listOf(ThreadSummary("t1", "p1", "Polish tablets", "completed")), selectedThreadId = "t1",
        messages = listOf(ConversationMessage(id = "m1", role = "assistant", text = "Done.", ordinal = 1)),
    )

    private fun showConversation(widthDp: Int) = compose.setContent {
        TrefoilTheme { Box(Modifier.width(widthDp.dp)) { Conversation(threadState, send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}) } }
    }

    @Test fun headerBackIs48dpWide() {
        showConversation(859)
        assertEquals(48.dp to 48.dp, compose.onNodeWithContentDescription("Back to threads").look())
    }

    @Test fun headerBackStays44dpInANarrowPane() {
        showConversation(400)
        assertEquals(44.dp to 44.dp, compose.onNodeWithContentDescription("Back to threads").look())
        assertTouchAtLeast48("back", compose.onNodeWithContentDescription("Back to threads"))
    }

    // ---- Item 16: the copy button under an assistant message without a time ----

    @Test fun copyButtonHangsLeftOfTheProseEdgeOnlyWhenWide() {
        compose.setContent { Pane(859) { Column { MessageRow(ConversationMessage(id = "m", role = "assistant", text = "Hello there", ordinal = 1), stacked = false, showMetadata = true) } } }
        // The 40dp button sits 4dp into its 48dp touch area (glyph at 16.5dp): the 16dp shift puts the glyph on the prose edge.
        assertEquals(-12.dp, compose.onNodeWithContentDescription("Copy response").getBoundsInRoot().left)
        assertTouchAtLeast48("copy response", compose.onNodeWithContentDescription("Copy response"))
    }

    @Test fun copyButtonStaysInlineInANarrowPane() {
        compose.setContent { Pane(400) { Column { MessageRow(ConversationMessage(id = "m", role = "assistant", text = "Hello there", ordinal = 1), stacked = false, showMetadata = true) } } }
        assertEquals(4.dp, compose.onNodeWithContentDescription("Copy response").getBoundsInRoot().left)
    }
}

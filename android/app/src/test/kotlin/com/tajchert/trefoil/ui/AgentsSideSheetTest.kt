package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.adaptive.sideSheetWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Agents: bottom sheet below 600dp, trailing modal side sheet from 600dp, centred dialog in a short window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class AgentsSideSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val roster = SubagentRoster(runId = null, turnActive = false, latestTurn = true, subagents = emptyList(), liveCount = 0, settledCount = 0)
    private var dismissed = 0
    private val title = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Agents")

    private fun sheet(content: @Composable (@Composable () -> Unit) -> Unit = { it() }) = compose.setContent {
        TrefoilTheme { content { ThreadAgentsSheet(roster, emptyList(), emptyList(), true, { dismissed++ }, {}, {}) } }
    }

    @Test fun phoneKeepsTheBottomSheet() {
        sheet()
        compose.onNodeWithText("Agents").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
        compose.onNode(title).assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun fromSixHundredDpItIsA360DpSheetOnTheEndEdge() {
        sheet()
        val bounds = compose.onNode(title).getBoundsInRoot()
        assertEquals(1000.dp, bounds.right)
        assertEquals(360.dp, bounds.right - bounds.left)
        assertEquals(640.dp + 16.dp + 4.dp, compose.onNodeWithText("Agents").getBoundsInRoot().left)
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun rightToLeftOpensOnTheLeftEdge() {
        sheet { content -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { content() } }
        val bounds = compose.onNode(title).getBoundsInRoot()
        assertEquals(0.dp, bounds.left)
        assertEquals(360.dp, bounds.right - bounds.left)
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun aVerticalHingeKeepsItInTheEndHalf() {
        val hinge = Fold(FoldOrientation.Vertical, 400f, 420f, separating = true, halfOpened = true)
        sheet { content -> CompositionLocalProvider(LocalFold provides hinge) { content() } }
        val bounds = compose.onNode(title).getBoundsInRoot()
        assertEquals(1000.dp, bounds.right)
        assertEquals(580.dp, bounds.right - bounds.left)
    }

    @Test @Config(qualifiers = "w1000dp-h400dp") fun aShortWindowShowsA480DpDialogInstead() {
        sheet { content -> CompositionLocalProvider(LocalCompactHeight provides true) { content() } }
        compose.onNode(title).assertDoesNotExist()
        compose.onNodeWithText("Agents").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1000dp-h400dp") fun theShortWindowDialogIs480DpWide() {
        val live = SubagentRoster("r", true, true, listOf(Subagent("a", "t", runId = "r", origin = "app_owned", childThreadId = "c", status = "running", title = "Explore")), 1, 0)
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalCompactHeight provides true) { ThreadAgentsSheet(live, emptyList(), emptyList(), true, {}, {}, {}) } } }
        // Title starts 20dp inside the left edge; the stop button ends 52dp (20 padding + 20 chevron + 12 gap) inside the right edge.
        val stop = compose.onNodeWithContentDescription("Stop Explore").getBoundsInRoot().right
        assertEquals(480.dp - 20.dp - 52.dp, stop - compose.onNodeWithText("Agents").getBoundsInRoot().left)
    }

    @Test @Config(qualifiers = "w1000dp-h400dp") fun theShortWindowDialogHasA48DpCloseButtonAtTheHeaderEndThatDismisses() {
        val many = (1..8).map { Subagent("a$it", "t", runId = "r", origin = "app_owned", childThreadId = "c$it", status = "running", title = "Explore $it") }
        val live = SubagentRoster("r", true, true, many, 8, 0)
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalCompactHeight provides true) { ThreadAgentsSheet(live, emptyList(), emptyList(), true, { dismissed++ }, {}, {}) } } }
        val close = compose.onNodeWithContentDescription("Close")
        close.assertIsDisplayed()
        val b = close.getBoundsInRoot()
        val heading = compose.onNodeWithText("Agents").getBoundsInRoot()
        assertTrue("48dp target: $b", b.right - b.left >= 48.dp && b.bottom - b.top >= 48.dp)
        assertTrue("trailing end of the header, level with the heading: $b $heading", b.left >= heading.right && b.top <= heading.top && b.bottom >= heading.bottom)
        close.performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun theSideSheetKeepsItsOwnCloseButtonOnly() {
        sheet()
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close")).assertCountEquals(1)
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun paneTitleIsReported() {
        sheet()
        compose.onNode(title).assertExists()
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun scrimTapDismisses() {
        sheet()
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(100f, 400f)) }
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun tapInsideDoesNotDismiss() {
        sheet()
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(800f, 400f)) }
        compose.runOnIdle { assertEquals(0, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun escapeDismisses() {
        sheet()
        compose.onNodeWithContentDescription("Close").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun backDismisses() {
        sheet()
        compose.runOnIdle { (ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun closeButtonDismissesAndIsA48DpTarget() {
        sheet()
        val close = compose.onNodeWithContentDescription("Close")
        val b = close.getBoundsInRoot()
        assertTrue(b.right - b.left >= 48.dp && b.bottom - b.top >= 48.dp)
        // It sits over the top end corner of the sheet, 4dp in, not over the title.
        assertEquals(1000.dp - 4.dp, b.right)
        assertEquals(4.dp, b.top)
        close.performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun foldWidthMath() {
        val hinge = Fold(FoldOrientation.Vertical, 400f, 420f, separating = true, halfOpened = true)
        assertEquals(360f, sideSheetWidth(1000f, null, rtl = false))
        assertEquals(580f, sideSheetWidth(1000f, hinge, rtl = false))
        assertEquals(400f, sideSheetWidth(1000f, hinge, rtl = true))
        // A flat hinge, or a horizontal one, leaves the sheet at its own width.
        assertEquals(360f, sideSheetWidth(1000f, hinge.copy(separating = false, halfOpened = false), rtl = false))
        assertEquals(360f, sideSheetWidth(1000f, hinge.copy(orientation = FoldOrientation.Horizontal), rtl = false))
        assertEquals(300f, sideSheetWidth(300f, null, rtl = false))
    }
}

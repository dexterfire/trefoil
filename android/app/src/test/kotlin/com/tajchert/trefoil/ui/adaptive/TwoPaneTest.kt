package com.tajchert.trefoil.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp")
class TwoPaneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: TwoPaneState
    private var collapses = 0
    private val divider get() = compose.onNodeWithContentDescription("Resize panes")

    private fun setPanes(direction: LayoutDirection = LayoutDirection.Ltr, locked: Boolean = false, hinge: Dp = 0.dp) {
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    state = rememberTwoPaneState(340.dp)
                    TfTwoPane(state, 340.dp, start = { Box(Modifier.fillMaxSize().testTag("start")) }, end = { Box(Modifier.fillMaxSize().testTag("end")) },
                        Modifier.fillMaxSize(), locked = locked, hingeWidth = hinge, onCollapsedChange = { if (it) collapses++ })
                }
            }
        }
    }

    private fun startWidth() = compose.onNodeWithTag("start").getBoundsInRoot().let { it.right - it.left }
    private fun drag(byDp: Float) = divider.performTouchInput {
        val px = byDp * density
        val sign = if (px < 0) -1f else 1f
        // The first move crosses the touch slop and contributes only the 1px beyond it.
        down(center); moveBy(Offset(sign * (viewConfiguration.touchSlop + 1f), 0f)); moveBy(Offset(px - sign, 0f)); up()
    }

    @Test fun dragResizesAndClampsToTheRange() {
        setPanes()
        assertEquals(340.dp, startWidth())
        drag(60f)
        assertEquals(400f, state.startWidth.value, 2f)
        drag(400f)
        assertEquals(480.dp, state.startWidth); assertEquals(480.dp, startWidth())
        drag(-150f)
        assertEquals(330f, state.startWidth.value, 2f)
        drag(-40f)
        assertEquals(290f, state.startWidth.value, 2f)
        assertFalse(state.collapsed)
    }

    @Test fun releaseBelowTheThresholdCollapsesAndKeepsTheOldWidth() {
        setPanes()
        drag(-200f)
        assertTrue(state.collapsed); assertEquals(1, collapses)
        assertEquals(340.dp, state.startWidth)
        compose.onNodeWithTag("start").assertDoesNotExist()
        divider.assertDoesNotExist()
        compose.runOnIdle { state.collapsed = false }
        assertEquals(340.dp, startWidth())
    }

    @Test fun doubleTapResetsToTheDefault() {
        setPanes()
        drag(100f)
        divider.performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(340.dp, state.startWidth) }
    }

    @Test fun keyboardResizesInStepsAndEnterCollapses() {
        setPanes()
        divider.requestFocus()
        divider.performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(356.dp, state.startWidth) }
        divider.performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(324.dp, state.startWidth) }
        divider.performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertTrue(state.collapsed) }
    }

    @Test fun talkBackCanAdjustTheWidth() {
        setPanes()
        divider.assert(SemanticsMatcher("has range 280..480") { it.config[SemanticsProperties.ProgressBarRangeInfo].range == 280f..480f })
        divider.performSemanticsAction(SemanticsActions.SetProgress) { it(420f) }
        compose.runOnIdle { assertEquals(420.dp, state.startWidth) }
    }

    @Test fun widthAndCollapseSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TrefoilTheme {
                state = rememberTwoPaneState(340.dp)
                TfTwoPane(state, 340.dp, start = { Box(Modifier.fillMaxSize().testTag("start")) }, end = { Box(Modifier.fillMaxSize()) }, Modifier.fillMaxSize())
            }
        }
        compose.runOnIdle { state.startWidth = 412.dp }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(412.dp, state.startWidth); assertFalse(state.collapsed) }
        compose.runOnIdle { state.collapsed = true }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertTrue(state.collapsed) }
    }

    @Test fun rtlPutsTheStartPaneOnTheRightAndMirrorsDrag() {
        setPanes(LayoutDirection.Rtl)
        val bounds = compose.onNodeWithTag("start").getBoundsInRoot()
        assertEquals(1000.dp, bounds.right); assertEquals(660.dp, bounds.left)
        drag(-60f) // dragging left widens the right-hand start pane
        assertEquals(400f, state.startWidth.value, 2f)
        divider.requestFocus()
        divider.performKeyInput { pressKey(Key.DirectionLeft) }
        compose.runOnIdle { assertEquals(state.startWidth.value, 416f, 2f) }
    }

    @Test fun lockedDividerCannotBeDraggedOrFocused() {
        setPanes(locked = true)
        divider.assertDoesNotExist()
        compose.onNodeWithTag("start").performTouchInput { down(centerRight); moveBy(Offset(200f, 0f)); up() }
        assertEquals(340.dp, startWidth())
    }

    @Test fun hingeGapSeparatesThePanes() {
        setPanes(locked = true, hinge = 20.dp)
        assertEquals(360.dp, compose.onNodeWithTag("end").getBoundsInRoot().left)
        assertEquals(640.dp, compose.onNodeWithTag("end").getBoundsInRoot().let { it.right - it.left })
    }

    private val containerWidth = mutableStateOf(1000.dp)
    private var startFocused = false
    private val startFocus = FocusRequester()
    private val endFocus = FocusRequester()

    private fun setSizedPanes(width: Dp, startWidth: Dp = 340.dp) {
        containerWidth.value = width
        compose.setContent {
            TrefoilTheme {
                state = rememberTwoPaneState(startWidth)
                TfTwoPane(state, 340.dp,
                    start = { Box(Modifier.fillMaxSize().testTag("start").onFocusChanged { startFocused = it.isFocused }.focusRequester(startFocus).focusable()) },
                    end = { Box(Modifier.fillMaxSize().testTag("end").focusRequester(endFocus).focusable()) },
                    Modifier.width(containerWidth.value).fillMaxHeight())
            }
        }
    }

    private fun width(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().let { it.right - it.left }

    @Test fun startIsClampedSoTheEndPaneKeepsItsMinimum() {
        setSizedPanes(600.dp, startWidth = 400.dp)
        assertEquals(320.dp, width("start")); assertEquals(280.dp, width("end"))
        compose.runOnIdle { assertEquals(400.dp, state.startWidth) }
    }

    @Test fun containerTooNarrowForBothAutoCollapsesWithoutTouchingTheState() {
        setSizedPanes(300.dp)
        // 280 start + 280 end do not fit in 300: the end pane takes everything; state is untouched.
        assertEquals(300.dp, width("end"))
        compose.onNodeWithTag("start").assertDoesNotExist()
        divider.assertDoesNotExist()
        compose.runOnIdle { assertFalse(state.collapsed); assertEquals(340.dp, state.startWidth); startFocus.requestFocus() }
        compose.runOnIdle { assertFalse(startFocused) }
        containerWidth.value = 1000.dp
        compose.waitForIdle()
        assertEquals(340.dp, width("start")); assertEquals(660.dp, width("end"))
    }

    @Test fun hiddenStartPaneCannotTakeFocusAndLosesIt() {
        setSizedPanes(1000.dp)
        compose.runOnIdle { startFocus.requestFocus() }
        compose.runOnIdle { assertTrue(startFocused); state.collapsed = true }
        compose.runOnIdle { assertFalse(startFocused) }
        compose.runOnIdle { startFocus.requestFocus() }
        compose.runOnIdle { assertFalse(startFocused); endFocus.requestFocus() }
        compose.onNodeWithTag("end").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Tab); pressKey(Key.Tab) }
        compose.runOnIdle { assertFalse(startFocused) }
        compose.onNodeWithTag("start").assertDoesNotExist()
    }

    @Test fun unboundedWidthFailsFast() {
        val error = runCatching {
            compose.setContent { TrefoilTheme { Row(Modifier.horizontalScroll(rememberScrollState())) {
                TfTwoPane(rememberTwoPaneState(340.dp), 340.dp, start = { Box(Modifier.size(10.dp)) }, end = { Box(Modifier.size(10.dp)) })
            } } }
            compose.waitForIdle()
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue("$error", generateSequence(error) { it.cause }.any { it.message?.contains("TfTwoPane needs a bounded width") == true })
    }
}

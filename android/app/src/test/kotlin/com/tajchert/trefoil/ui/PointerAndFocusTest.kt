package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome
import com.tajchert.trefoil.ui.adaptive.focusRing
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Tooltips on icon-only controls, hover and right-click on rows and cards, and the keyboard-only focus ring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PointerAndFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val macMini = SavedEnvironment("mini", "Mac mini", "http://192.168.10.199:3773", "t")
    private val laptop = SavedEnvironment("book", "MacBook Pro", "https://macbook.example.com", "t")
    private val connected = ClientState(
        environment = macMini, connection = ConnectionStatus.Connected, canWrite = true, canWriteThreads = true, threadActionsConfigured = true,
        projects = listOf(Project("trefoil", "Trefoil")),
        threads = listOf(
            ThreadSummary("a", "trefoil", "Polish the Android thread list", "completed", latestMessage = "Looks good", branch = "feat/list"),
            ThreadSummary("b", "trefoil", "Review the connection flow", "completed"),
        ),
    )

    private fun texts(): Set<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }.toSet()

    private fun iconOnlyControls() = compose.onAllNodes(
        hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and !SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
    )

    /** Every clickable node that has a content description and no text is icon-only: long-pressing it must show a tooltip popup. */
    private fun assertEveryIconOnlyControlHasATooltip(screen: String) {
        val count = iconOnlyControls().fetchSemanticsNodes().size
        assertTrue("$screen has no icon-only controls", count > 0)
        for (i in 0 until count) {
            val before = texts()
            val description = iconOnlyControls()[i].fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
            iconOnlyControls()[i].performTouchInput { down(center); advanceEventTime(1_000); up() }
            compose.waitForIdle()
            assertTrue("$screen: '$description' shows no tooltip", (texts() - before).isNotEmpty())
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
        }
    }

    @Test fun threadListIconControlsHaveTooltips() {
        compose.setContent {
            TrefoilTheme { Box(Modifier.width(360.dp)) { ThreadList(connected, {}, {}, chrome = ThreadListChrome(railPresent = true, onCollapse = {}), shell = ShellActions({}, {}, {}, {}, {})) } }
        }
        assertEveryIconOnlyControlHasATooltip("thread list")
    }

    @Test fun machinesIconControlsHaveTooltips() {
        compose.setContent { TrefoilTheme { MachinesScreen(connected, listOf(macMini, laptop), {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize()) } }
        assertEveryIconOnlyControlHasATooltip("machines")
    }

    @Test fun newThreadSheetIconControlsHaveTooltips() {
        compose.setContent { TrefoilTheme { Box(Modifier.fillMaxSize()) { NewThreadSheetContent(connected, {}, { _, _ -> }) } } }
        assertEveryIconOnlyControlHasATooltip("new thread")
    }

    @Test fun threadRowHoverChangesAndRestoresTheBackground() {
        compose.setContent { TrefoilTheme { Box(Modifier.width(360.dp)) { ThreadList(connected, {}, {}, chrome = ThreadListChrome(railPresent = true)) } } }
        val row = compose.onNodeWithText("Review the connection flow")
        val b = row.getBoundsInRoot()
        // Inside the 12dp container (inset 8dp from the list edge) but left of the project tile.
        val x = (b.left.value - 8f).toInt().coerceAtLeast(0); val y = ((b.top.value + b.bottom.value) / 2f).toInt()
        fun pixel() = compose.onRoot().captureToImage().toPixelMap()[x, y]
        val rest = pixel()
        row.performMouseInput { enter(center) }
        compose.waitForIdle()
        val hovered = pixel()
        assertNotEquals(rest, hovered)
        row.performMouseInput { exit(center) }
        compose.waitForIdle()
        assertEquals(rest, pixel())
    }

    @Test fun machineCardHoverChangesAndRestoresTheBackground() {
        compose.setContent { TrefoilTheme { MachinesScreen(connected, listOf(macMini, laptop), {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize()) } }
        val name = compose.onNodeWithText("MacBook Pro")
        val b = name.getBoundsInRoot()
        // The card's main row spans the card, so a point 8dp inside its left edge is card background, not the icon tile.
        val x = (b.left.value + 8f).toInt(); val y = ((b.top.value + b.bottom.value) / 2f).toInt()
        fun pixel() = compose.onRoot().captureToImage().toPixelMap()[x, y]
        val rest = pixel()
        name.performMouseInput { enter(center) }
        compose.waitForIdle()
        assertNotEquals(rest, pixel())
        name.performMouseInput { exit(center) }
        compose.waitForIdle()
        assertEquals(rest, pixel())
    }

    @Test fun rightClickOnTheThreadRowOpensTheActionsMenu() {
        compose.setContent { TrefoilTheme { Box(Modifier.width(360.dp)) { ThreadList(connected, {}, {}, chrome = ThreadListChrome(railPresent = true)) } } }
        compose.onNodeWithText("Settle").assertDoesNotExist()
        compose.onNodeWithText("Polish the Android thread list").performMouseInput { rightClick() }
        compose.onNodeWithText("Settle").assertExists()
        compose.onNodeWithText("Copy thread ID").assertExists()
    }

    @Test fun longPressOnTheThreadRowOpensTheSameMenu() {
        compose.setContent { TrefoilTheme { Box(Modifier.width(360.dp)) { ThreadList(connected, {}, {}, chrome = ThreadListChrome(railPresent = true)) } } }
        compose.onNodeWithText("Polish the Android thread list").performTouchInput { longClick() }
        compose.onNodeWithText("Settle").assertExists()
        compose.onNodeWithText("Copy thread ID").assertExists()
    }

    @Test fun rightClickOnTheMachineCardOpensItsMenu() {
        var forgotten = 0
        compose.setContent { TrefoilTheme { MachinesScreen(connected, listOf(macMini, laptop), {}, { forgotten++ }, {}, {}, {}, {}, Modifier.fillMaxSize()) } }
        val before = texts()
        compose.onNodeWithText("Mac mini").performMouseInput { rightClick() }
        compose.waitForIdle()
        assertEquals(setOf("Disconnect", "Forget"), texts() - before)
        assertEquals(0, forgotten)
    }

    @Test fun longPressOnTheMachineCardOpensTheSameMenu() {
        compose.setContent { TrefoilTheme { MachinesScreen(connected, listOf(macMini, laptop), {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize()) } }
        val before = texts()
        compose.onNodeWithText("Mac mini").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(setOf("Disconnect", "Forget"), texts() - before)
    }

    @Composable private fun RingProbe() {
        Column {
            Box(Modifier.size(80.dp).testTag("a").focusRing(RoundedCornerShape(12.dp)).clickable {})
            Box(Modifier.size(80.dp).testTag("b").focusRing(RoundedCornerShape(12.dp)).clickable {})
        }
    }

    private fun ringVisible(tag: String): Boolean {
        val b = compose.onNodeWithTag(tag).getBoundsInRoot()
        val image = compose.onRoot().captureToImage().toPixelMap()
        // The ring is drawn in the accent colour on the element's edge; the element itself is transparent.
        val accent = TrefoilLight.accent
        val y = ((b.top.value + b.bottom.value) / 2f).toInt()
        return (0..1).any { image[b.left.value.toInt() + it, y] == accent }
    }

    @Test fun focusRingShowsAfterTabAndNotAfterATap() {
        var input: androidx.compose.ui.input.InputModeManager? = null
        compose.setContent { TrefoilTheme(darkTheme = false) { input = LocalInputModeManager.current; RingProbe() } }
        compose.onNodeWithTag("a").performClick()
        compose.waitForIdle()
        assertFalse("ring after a tap", ringVisible("a")); assertFalse(ringVisible("b"))
        // The platform switches to keyboard mode on a hardware key; the test injector skips that, so do it here.
        compose.runOnIdle { input!!.requestInputMode(InputMode.Keyboard) }
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        assertTrue("no ring after Tab", ringVisible("a") || ringVisible("b"))
    }
}

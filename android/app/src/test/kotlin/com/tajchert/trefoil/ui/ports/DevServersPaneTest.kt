package com.tajchert.trefoil.ui.ports

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.DiscoveredLocalServer
import com.tajchert.trefoil.core.model.PortForward
import com.tajchert.trefoil.core.model.PortForwardStatus
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The dev servers pane lays itself out from its own width: today's either/or below 840dp, list-detail from 840dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1200dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevServersPaneTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val vite = DiscoveredLocalServer("127.0.0.1", 5173, "http://127.0.0.1:5173/", "node", 1)
    private val api = DiscoveredLocalServer("127.0.0.1", 3000, "http://127.0.0.1:3000/", "bun", 2)
    private val state = PortsUiState(servers = listOf(vite, api), forwards = listOf(PortForward("e", 5173, 5173, PortForwardStatus.Active)), live = true)
    private val page = PortBrowserState("e", 5173, "http://localhost:5173/", "vite")

    private val opened = mutableListOf<Int>()
    private val external = mutableListOf<Int>()
    private val stopped = mutableListOf<Int>()
    private val forwarded = mutableListOf<Int>()
    private var backs = 0
    private var closes = 0
    private val actions = PortsActions({ backs++ }, { opened += it }, { external += it }, { stopped += it }, { forwarded += it })

    private var width by mutableStateOf(1000.dp)
    private var webViews = 0

    private fun show(ui: PortsUiState = state, browser: PortBrowserState? = null, hosted: Boolean = false, width: Int = 1000) {
        this.width = width.dp
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalWebViewFactory provides { context -> webViews++; WebView(context) }) {
                    Box(Modifier.width(this.width).fillMaxHeight()) {
                        DevServersPane(ui, actions, browser, onCloseBrowser = { closes++ }, onOpenExternal = {}, hosted = hosted)
                    }
                }
            }
        }
    }

    private val back = "Back to conversations"

    @Test fun belowTheBreakpointTheBrowserTakesTheListsPlace() {
        show(browser = page, width = 839)
        compose.onAllNodesWithText("Dev servers").assertCountEquals(0)
        compose.onNodeWithContentDescription("Close browser").assertExists()
        compose.onNodeWithContentDescription("Page back").assertExists()
        compose.onAllNodesWithText("Open").assertCountEquals(0)
    }

    @Test fun belowTheBreakpointWithNothingOpenIsTheList() {
        show(width = 839)
        compose.onNodeWithText("Dev servers").assertExists()
        compose.onAllNodesWithText("Open")[0].assertExists()
        compose.onNodeWithText("Open a dev server to see it here").assertDoesNotExist()
    }

    @Test fun fromTheBreakpointTheListAndTheBrowserShareThePane() {
        show(browser = page, width = 840)
        compose.onNodeWithText("Dev servers").assertExists()
        compose.onAllNodesWithText("Open")[0].assertExists()
        compose.onNodeWithContentDescription("Close browser").assertExists()
        // The single toolbar has no title row and no bottom controls: one set of page controls.
        compose.onAllNodesWithContentDescription("Page back").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Stop loading").assertCountEquals(1)
    }

    @Test fun nothingOpenShowsTheCentredEmptyStateWithTheForwardAction() {
        show(width = 1000)
        compose.onNodeWithText("Open a dev server to see it here").assertExists()
        // The list's own button and the empty state's; the header's plus has a description only.
        compose.onAllNodes(hasText("Forward a port…") and hasClickAction()).assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Forward a port…").assertCountEquals(1)
        compose.onAllNodes(hasText("Forward a port…") and hasClickAction())[1].performClick()
        compose.onNodeWithText("Forward a port").assertExists()
    }

    @Test fun theEmptyStateActionKeepsTheExistingEnabledRule() {
        show(ui = state.copy(canWrite = false), width = 1000)
        compose.onAllNodes(hasText("Forward a port…") and hasClickAction()).assertCountEquals(1)
        compose.onNode(hasText("Forward a port…") and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun anEmptyListOffersTheActionOnlyInItsOwnPane() {
        show(ui = PortsUiState(live = true), width = 1000)
        compose.onNodeWithText("No dev servers yet").assertExists()
        compose.onAllNodes(hasText("Forward a port…") and hasClickAction()).assertCountEquals(1)
    }

    @Test fun theRowWhoseForwardIsOpenIsSelected() {
        show(browser = page, width = 1000)
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(isSelected()).assert(hasAnyDescendant(hasText(":5173")))
    }

    @Test fun noRowIsSelectableWhenNothingIsOpenOrInTheEitherOrLayout() {
        show(width = 1000)
        compose.onAllNodes(isSelected()).assertCountEquals(0)
    }

    @Test fun hostedHidesTheBackArrowAndTheListDoesNotTakeBack() {
        show(hosted = true, width = 1000)
        compose.onAllNodesWithContentDescription(back).assertCountEquals(0)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backs)
    }

    @Test fun notHostedKeepsTheBackArrowAndTheListTakesBack() {
        show(hosted = false, width = 1000)
        compose.onNodeWithContentDescription(back).assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, backs)
    }

    private fun backClosesTheBrowser(hosted: Boolean, width: Int) {
        show(browser = page, hosted = hosted, width = width)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closes)
        assertEquals(0, backs)
    }

    @Test fun backClosesAnOpenBrowserBeforeAnythingElseHostedNarrow() = backClosesTheBrowser(hosted = true, width = 500)
    @Test fun backClosesAnOpenBrowserBeforeAnythingElseHostedWide() = backClosesTheBrowser(hosted = true, width = 1000)
    @Test fun backClosesAnOpenBrowserBeforeAnythingElseNarrow() = backClosesTheBrowser(hosted = false, width = 500)
    @Test fun backClosesAnOpenBrowserBeforeAnythingElseWide() = backClosesTheBrowser(hosted = false, width = 1000)

    @Test fun backStillClosesTheBrowserAfterThePaneWidens() {
        // The page was composed first, so the list that appears beside it registers its Back handler later; it must not win.
        show(browser = page, width = 600)
        compose.waitForIdle()
        width = 1000.dp
        compose.waitForIdle()
        compose.onNodeWithText("Dev servers").assertExists()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closes)
        assertEquals(0, backs)
    }

    @Test fun theListTakesBackAgainOnceThePageIsClosed() {
        var browser by mutableStateOf<PortBrowserState?>(page)
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalWebViewFactory provides { context -> WebView(context) }) {
                    Box(Modifier.width(1000.dp).fillMaxHeight()) {
                        DevServersPane(state, actions, browser, onCloseBrowser = { closes++; browser = null }, onOpenExternal = {})
                    }
                }
            }
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, closes)
        assertEquals(0, backs)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(1, closes)
        assertEquals(1, backs)
    }

    @Test fun aNarrowHostedPaneDropsItsOwnTitleAndKeepsTheStatusAndPlus() {
        show(hosted = true, width = 420)
        compose.onAllNodesWithText("Dev servers").assertCountEquals(0)
        compose.onNodeWithText("Listening on the connected machine").assertExists()
        compose.onNodeWithContentDescription("Forward a port…").assertExists()
    }

    @Test fun aMediumHostedPaneKeepsItsTitleWithoutTheArrow() {
        show(hosted = true, width = 700)
        compose.onNodeWithText("Dev servers").assertExists()
        compose.onAllNodesWithContentDescription(back).assertCountEquals(0)
    }

    @Test fun gridColumnsFollowThePaneWidth() {
        assertEquals(1, portGridColumns(360f))
        assertEquals(1, portGridColumns(599f))
        assertEquals(2, portGridColumns(600f))
        assertEquals(2, portGridColumns(839f))
        assertTrue(portGridColumns(1200f) >= 3)
    }

    @Test fun twoCardsShareARowAt600AndStackAt599() {
        val two = state.copy(forwards = emptyList())
        show(ui = two, width = 600)
        val a = compose.onNode(hasText(":3000")).getUnclippedBoundsInRoot()
        val b = compose.onNode(hasText(":5173")).getUnclippedBoundsInRoot()
        assertEquals(a.top, b.top)
        assertTrue(b.left > a.left)
        width = 599.dp
        compose.waitForIdle()
        val a2 = compose.onNode(hasText(":3000")).getUnclippedBoundsInRoot()
        val b2 = compose.onNode(hasText(":5173")).getUnclippedBoundsInRoot()
        assertEquals(a2.left, b2.left)
        assertTrue(b2.top > a2.top)
    }

    @Test fun actionsCallTheSameCallbacksWithTheSamePorts() {
        show(width = 1000)
        compose.onAllNodesWithText("Open")[0].performClick()
        compose.onAllNodesWithText("Open in browser")[0].performClick()
        compose.onAllNodesWithText("Open")[1].performClick()
        compose.onNodeWithText("Stop forward").performClick()
        // Sorted by port: 3000 first, 5173 second (the only one with a forward).
        assertEquals(listOf(3000, 5173), opened)
        assertEquals(listOf(3000), external)
        assertEquals(listOf(5173), stopped)
    }

    @Test fun manualForwardGoesThroughTheSameCallback() {
        show(width = 1000)
        compose.onNodeWithContentDescription("Forward a port…").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("8123")
        compose.onNodeWithText("Forward").performClick()
        assertEquals(listOf(8123), forwarded)
    }

    @Test fun theWebViewIsCreatedOnceAcrossAWidthChange() {
        show(browser = page, width = 600)
        compose.waitForIdle()
        assertEquals(1, webViews)
        width = 1000.dp
        compose.waitForIdle()
        compose.onNodeWithText("Dev servers").assertExists()
        assertEquals(1, webViews)
        width = 700.dp
        compose.waitForIdle()
        compose.onAllNodesWithText("Dev servers").assertCountEquals(0)
        assertEquals(1, webViews)
    }

    @Test fun openingAnotherServerInListDetailLoadsANewPage() {
        var browser by mutableStateOf<PortBrowserState?>(page)
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalWebViewFactory provides { context -> webViews++; WebView(context) }) {
                    Box(Modifier.width(1000.dp).fillMaxHeight()) { DevServersPane(state, actions, browser, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, webViews)
        browser = PortBrowserState("e", 3000, "http://localhost:3000/", "bun")
        compose.waitForIdle()
        assertEquals(2, webViews)
        compose.onNode(isSelected()).assert(hasAnyDescendant(hasText(":3000")))
    }
}

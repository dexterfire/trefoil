package com.tajchert.trefoil.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.testString
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.LocalFold
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1200dp-h800dp")
class ScreenSessionsAdaptiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid/", "fixture-token")
    private val device = ScreenTarget.Device("thread", "remote-mac", "device-1", "android", "Pixel 8")
    private val other = ScreenTarget.Device("thread", "another-mac", "device-2", "ios", "Another Phone")
    private val browser = ScreenTarget.Browser("thread", "tab", "Local web app", "http://localhost:5173", interactive = true)
    private val catalog = ScreenCatalog(browsers = listOf(browser), devices = listOf(device, other), loaded = true)
    private val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canRead = true, selectedThreadId = "thread", screens = catalog)
    private fun accessFor(target: ScreenTarget) = ScreenAccess("fixture", 1, target, "https://fixture.invalid/api/device-hub", "wss://fixture.invalid/api/device-hub", mapOf("wsTicket" to "fixture-media", "hostId" to "remote-mac"), interactive = true)

    private val fakeViewer: @Composable (StreamController, (StreamViewerStatus) -> Unit) -> Unit = { _, report ->
        LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true, controller = "you")) }
        Box(Modifier.fillMaxSize().background(Color.DarkGray))
    }

    private fun show(
        width: Int, open: ScreenTarget? = null, onLeave: (() -> Unit)? = {}, onOpen: (ScreenTarget) -> Unit = {}, onClose: () -> Unit = {}, onRefresh: () -> Unit = {},
        fold: Fold? = null, fullscreen: Boolean = false, onFullscreen: (Boolean) -> Unit = {}, height: Int = 700, viewer: @Composable (StreamController, (StreamViewerStatus) -> Unit) -> Unit = fakeViewer,
    ) {
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalFold provides fold) {
                    Box(Modifier.width(width.dp).height(height.dp)) {
                        ScreenSessionsScreen(state, if (open == null) ScreenViewerState() else ScreenViewerState(environment.id, open, accessFor(open)), fullscreen, onFullscreen, onRefresh, onOpen, onClose, onLeave,
                            isAccessCurrent = { true }, viewerContent = viewer)
                    }
                }
            }
        }
    }

    private val tabletopFold = Fold(FoldOrientation.Horizontal, 340f, 360f, separating = true, halfOpened = true)
    private val verticalFold = Fold(FoldOrientation.Vertical, 490f, 510f, separating = true, halfOpened = true)

    @Test fun belowTheBreakpointTheCatalogAndTheViewerReplaceEachOther() {
        show(800)
        compose.onNodeWithText("Local web app").assertExists()
        compose.onNodeWithText("Choose a browser tab or device to view its screen.").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to conversations").assertExists()
    }

    @Test fun belowTheBreakpointAnOpenTargetHidesTheCatalogAndKeepsTheBackArrow() {
        show(800, open = device)
        compose.onNodeWithText("Local web app").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to screen sessions").assertExists()
        compose.onNodeWithContentDescription("Device home").assertExists()
    }

    @Test fun mediumWidthCapsAndCentresTheCatalog() {
        show(800)
        // 800dp wide, list capped at 640dp: the first section starts past the 80dp margin plus the 16dp padding.
        val left = compose.onNodeWithText("BROWSERS").getUnclippedBoundsInRoot().left
        assertTrue("catalog should be centred, left=$left", left > 80.dp)
    }

    @Test fun phoneWidthKeepsTheFullWidthCatalog() {
        show(400)
        val left = compose.onNodeWithText("BROWSERS").getUnclippedBoundsInRoot().left
        assertTrue("phone catalog starts at the edge, left=$left", left < 40.dp)
    }

    @Test fun fromTheBreakpointTheCatalogStaysNextToTheViewerAndTheOpenRowIsSelected() {
        show(900, open = device)
        compose.onNodeWithText("Local web app").assertExists()
        compose.onNode(hasText("Pixel 8") and isSelected()).assertExists()
        compose.onNode(hasText("Another Phone")).assertIsNotSelected()
        compose.onNode(hasText("Local web app")).assertIsNotSelected()
        compose.onNodeWithContentDescription("Back to screen sessions").assertDoesNotExist()
        compose.onNodeWithContentDescription("Device home").assertExists()
        compose.onNodeWithContentDescription("Enter full screen").assertExists()
        compose.onNodeWithContentDescription("Reconnect stream").assertExists()
    }

    @Test fun exactlyAtTheBreakpointIsListDetail() {
        show(840, open = device)
        compose.onNodeWithText("Local web app").assertExists()
        compose.onNodeWithContentDescription("Back to screen sessions").assertDoesNotExist()
    }

    @Test fun justBelowTheBreakpointIsSinglePane() {
        show(839, open = device)
        compose.onNodeWithText("Local web app").assertDoesNotExist()
    }

    @Test fun nothingOpenShowsAnEmptyViewerWithTheRefreshAction() {
        var refreshes = 0
        show(900, onRefresh = { refreshes++ })
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithText("Choose a browser tab or device to view its screen.").assertExists()
        compose.onNodeWithText(testString(R.string.refresh_sessions)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test fun choosingAnotherRowLeavesThenOpensExactlyLikeToday() {
        val calls = mutableListOf<String>()
        show(900, open = device, onOpen = { calls += "open:${(it as? ScreenTarget.Device)?.name ?: (it as ScreenTarget.Browser).title}" }, onClose = { calls += "close" })
        compose.onNodeWithText("Another Phone").performClick()
        compose.runOnIdle { assertEquals(listOf("close", "open:Another Phone"), calls) }
    }

    @Test fun choosingTheOpenRowDoesNothing() {
        val calls = mutableListOf<String>()
        show(900, open = device, onOpen = { calls += "open" }, onClose = { calls += "close" })
        compose.onNode(hasText("Pixel 8") and isSelected()).performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test fun choosingARowWithNothingOpenOnlyOpens() {
        val calls = mutableListOf<String>()
        show(900, onOpen = { calls += "open" }, onClose = { calls += "close" })
        compose.onNodeWithText("Local web app").performClick()
        compose.runOnIdle { assertEquals(listOf("open"), calls) }
    }

    @Test fun withoutALeaveActionThereIsNoArrowAndBackIsNotClaimed() {
        show(900, onLeave = null)
        compose.onNodeWithContentDescription("Back to conversations").assertDoesNotExist()
        compose.runOnIdle { assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test fun withALeaveActionTheArrowLeavesAndBackIsClaimed() {
        var left = 0
        show(900, onLeave = { left++ })
        compose.runOnIdle { assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        compose.onNodeWithContentDescription("Back to conversations").performClick()
        compose.runOnIdle { assertEquals(1, left) }
    }

    @Test fun backClosesTheOpenTargetFirstEvenWithoutALeaveAction() {
        var closed = 0
        show(900, open = device, onLeave = null, onClose = { closed++ })
        compose.runOnIdle { assertTrue(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()); compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test fun viewerChromeKeepsTheRealStatusTextAndControlRules() {
        show(900, open = device)
        compose.onNodeWithText("Live · controls connected").assertExists()
        compose.onNodeWithContentDescription("Device home").assertIsEnabled()
        compose.onNodeWithContentDescription("Rotate remote device").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Type on remote device").assertIsEnabled()
    }

    @Test fun theViewerIsCreatedOnceAcrossAWidthChange() {
        var width by mutableStateOf(1000)
        var created = 0
        var disposed = 0
        compose.setContent {
            TrefoilTheme {
                Box(Modifier.width(width.dp).height(700.dp)) {
                    ScreenSessionsScreen(state, ScreenViewerState(environment.id, device, accessFor(device)), false, {}, {}, {}, {}, {}, isAccessCurrent = { true }, viewerContent = { _, report ->
                        DisposableEffect(Unit) { created++; onDispose { disposed++ } }
                        LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true)) }
                        Box(Modifier.fillMaxSize().background(Color.DarkGray))
                    })
                }
            }
        }
        compose.onNodeWithText("Local web app").assertExists()
        compose.runOnIdle { assertEquals(1, created); width = 700 }
        compose.onNodeWithText("Local web app").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed); width = 1100 }
        compose.onNodeWithText("Local web app").assertExists()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed) }
    }

    @Test fun controlsPerTargetTypeAreUnchanged() {
        val streaming = StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true)
        fun names(target: ScreenTarget, canInput: Boolean = true, status: StreamViewerStatus = streaming, ready: Boolean = true) =
            screenControlSpecs(target, ready, canInput, status).map { it.control.name to it.enabled }
        assertEquals(
            listOf("DeviceBack" to true, "DeviceHome" to true, "DeviceAppSwitcher" to true, "DeviceRotate" to false, "DeviceType" to true),
            names(device),
        )
        assertEquals(
            listOf("DeviceBack" to false, "DeviceHome" to true, "DeviceAppSwitcher" to true, "DeviceRotate" to true, "DeviceType" to false),
            names(ScreenTarget.Device("thread", "h", "d", "ios", "Phone")),
        )
        assertEquals("DeviceType" to false, names(device.copy(serveSimOnly = true)).last())
        assertEquals(
            listOf("BrowserBack" to true, "BrowserForward" to true, "BrowserReload" to true, "BrowserType" to true, "Release" to true),
            names(browser),
        )
        assertEquals(
            listOf("BrowserBack" to false, "BrowserForward" to false, "BrowserReload" to false, "BrowserType" to false, "TakeControl" to true),
            names(browser, canInput = false),
        )
        assertEquals("TakeControl" to false, names(browser, canInput = false, status = streaming.copy(canOperate = false)).last())
        assertEquals("TakeControl" to false, names(browser, canInput = false, ready = false).last())
    }

    // ---- hinge geometry (pure) ----

    @Test fun tabletopNeedsAHorizontalHalfOpenedOrSeparatingFoldCrossingTheViewer() {
        val fold = tabletopFold
        assertEquals(HingeSplit(340f, 20f, 340f), tabletopSplit(fold, 0f, 700f, hasTarget = true))
        assertNull(tabletopSplit(null, 0f, 700f, true))
        assertNull(tabletopSplit(fold, 0f, 700f, hasTarget = false))
        assertNull(tabletopSplit(fold.copy(separating = false, halfOpened = false), 0f, 700f, true))
        // A flat hinge that still separates two screens splits the same way: the stream is never drawn across it.
        assertNotNull(tabletopSplit(fold.copy(halfOpened = false), 0f, 700f, true))
        assertNotNull(tabletopSplit(fold.copy(separating = false), 0f, 700f, true))
        assertNull(tabletopSplit(fold.copy(orientation = FoldOrientation.Vertical), 0f, 700f, true))
        // The viewer starts below the hinge, or ends above it.
        assertNull(tabletopSplit(fold, 400f, 700f, true))
        assertNull(tabletopSplit(fold, 0f, 300f, true))
        // Either part under 160dp falls back to the normal layout.
        assertNull(tabletopSplit(fold, 250f, 700f, true))
        assertNull(tabletopSplit(fold, 0f, 500f, true))
        assertNotNull(tabletopSplit(fold, 180f, 520f, true))
    }

    @Test fun foldedFullscreenNeedsAVerticalSeparatingOrHalfOpenedFoldInFullscreen() {
        val fold = verticalFold
        assertEquals(HingeSplit(490f, 20f, 490f), foldedFullscreenSplit(fold, 0f, 1000f, hasTarget = true, fullscreen = true))
        assertNull(foldedFullscreenSplit(fold, 0f, 1000f, hasTarget = true, fullscreen = false))
        assertNull(foldedFullscreenSplit(fold, 0f, 1000f, hasTarget = false, fullscreen = true))
        assertNull(foldedFullscreenSplit(null, 0f, 1000f, true, true))
        assertNull(foldedFullscreenSplit(fold.copy(orientation = FoldOrientation.Horizontal), 0f, 1000f, true, true))
        assertNull(foldedFullscreenSplit(fold.copy(separating = false, halfOpened = false), 0f, 1000f, true, true))
        assertNotNull(foldedFullscreenSplit(fold.copy(separating = false, halfOpened = true), 0f, 1000f, true, true))
        assertNotNull(foldedFullscreenSplit(fold.copy(separating = true, halfOpened = false), 0f, 1000f, true, true))
        assertNull(foldedFullscreenSplit(fold, 400f, 1000f, true, true))
    }

    // ---- tabletop and folded fullscreen (UI) ----

    @Test fun tabletopShowsTheInlineTypingFieldTheControlsAndTheSessionChips() {
        show(700, open = device, fold = tabletopFold, height = 700)
        compose.onNodeWithText("Text").assertExists()
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Device home").assertIsEnabled()
        compose.onNodeWithContentDescription("Rotate remote device").assertIsNotEnabled()
        compose.onNodeWithText(testString(R.string.dest_screens).uppercase()).assertExists()
        compose.onNode(hasText("Another Phone") and hasClickAction()).assertExists()
        compose.onNodeWithContentDescription("Enter full screen").assertExists()
        // The modal opened by the Type button is replaced by the inline field.
        compose.onNodeWithContentDescription("Type on remote device").assertDoesNotExist()
    }

    @Test fun tabletopFallsBackToTheNormalLayoutWhenTheHingeMissesTheViewer() {
        show(700, open = device, fold = tabletopFold.copy(start = 20f, end = 40f))
        compose.onNodeWithText(testString(R.string.dest_screens).uppercase()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Type on remote device").assertExists()
    }

    @Test fun tabletopSendsTypedTextOnlyWhenInputIsAllowed() {
        show(700, open = device, fold = tabletopFold)
        compose.onNodeWithText("Text").performTextInput("hello")
        compose.onNodeWithText("Send").assertIsEnabled()
    }

    @Test fun choosingASessionChipLeavesThenOpens() {
        val calls = mutableListOf<String>()
        show(700, open = device, fold = tabletopFold, onOpen = { calls += "open" }, onClose = { calls += "close" })
        compose.onNode(hasText("Another Phone") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(listOf("close", "open"), calls) }
    }

    @Test fun foldedFullscreenPutsTheControlsInTheStartHalfAndTheStreamInTheEndHalf() {
        show(1000, open = device, fold = verticalFold, fullscreen = true)
        val home = compose.onNodeWithContentDescription("Device home").getUnclippedBoundsInRoot()
        assertTrue("controls in the start half, right=${home.right}", home.right <= 490.dp)
        compose.onNodeWithContentDescription("Exit full screen").assertExists()
        compose.onNodeWithText(testString(R.string.dest_screens).uppercase()).assertExists()
    }

    @Test fun compactHeightUsesTheShorterBars() {
        var compact by mutableStateOf(false)
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalCompactHeight provides compact) {
                    Box(Modifier.width(1000.dp).height(400.dp)) {
                        ScreenSessionsScreen(state, ScreenViewerState(environment.id, device, accessFor(device)), false, {}, {}, {}, {}, {}, isAccessCurrent = { true }, viewerContent = fakeViewer)
                    }
                }
            }
        }
        // The 48dp button is centred in the bar, so an 8dp shorter bar moves it up by 4dp.
        val regular = compose.onNodeWithContentDescription("Enter full screen").getUnclippedBoundsInRoot().top
        compose.runOnIdle { compact = true }
        val shorter = compose.onNodeWithContentDescription("Enter full screen").getUnclippedBoundsInRoot().top
        assertEquals(4.dp, regular - shorter)
    }

    @Test fun compactHeightSinglePaneKeepsOneBarAndTheControls() {
        var compact by mutableStateOf(false)
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalCompactHeight provides compact) {
                    Box(Modifier.width(700.dp).height(360.dp)) {
                        ScreenSessionsScreen(state, ScreenViewerState(environment.id, device, accessFor(device)), false, {}, {}, {}, {}, {}, isAccessCurrent = { true }, viewerContent = fakeViewer)
                    }
                }
            }
        }
        // Regular height: the status row with Reconnect sits under the stream, above the controls.
        val bar = compose.onNodeWithContentDescription("Enter full screen").getUnclippedBoundsInRoot()
        assertTrue(compose.onNodeWithContentDescription("Reconnect stream").getUnclippedBoundsInRoot().top > bar.bottom)
        compose.runOnIdle { compact = true }
        // Compact height: that row is gone and Reconnect is in the top bar, once, so the stream gets the height. The controls stay.
        val reconnect = compose.onNodeWithContentDescription("Reconnect stream").getUnclippedBoundsInRoot()
        assertEquals(compose.onNodeWithContentDescription("Enter full screen").getUnclippedBoundsInRoot().top, reconnect.top)
        compose.onNodeWithText("Live · controls connected").assertExists()
        compose.onNodeWithContentDescription("Device home").assertExists()
    }

    @Test fun theViewerIsCreatedOnceAcrossTabletopAndFullscreenTransitions() {
        var fold by mutableStateOf<Fold?>(null)
        var fullscreen by mutableStateOf(false)
        var created = 0
        var disposed = 0
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalFold provides fold) {
                    Box(Modifier.width(1000.dp).height(700.dp)) {
                        ScreenSessionsScreen(state, ScreenViewerState(environment.id, device, accessFor(device)), fullscreen, { fullscreen = it }, {}, {}, {}, {}, isAccessCurrent = { true }, viewerContent = { _, report ->
                            DisposableEffect(Unit) { created++; onDispose { disposed++ } }
                            LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true)) }
                            Box(Modifier.fillMaxSize().background(Color.DarkGray))
                        })
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(1, created); fold = tabletopFold }
        compose.onNodeWithText(testString(R.string.dest_screens).uppercase()).assertExists()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed); fold = verticalFold; fullscreen = true }
        compose.onNodeWithContentDescription("Exit full screen").assertExists()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed); fold = null; fullscreen = false }
        compose.onNodeWithText("Local web app").assertExists()
        compose.runOnIdle { assertEquals(1, created); assertEquals(0, disposed) }
    }

    @Test fun typedTextIsNeverOfferedToSavedState() {
        // Text for the remote screen may be a password: it lives in plain `remember`, so nothing reaches a Bundle.
        val registry = SaveableStateRegistry(null) { true }
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry, LocalFold provides tabletopFold) {
                    Box(Modifier.width(700.dp).height(700.dp)) {
                        ScreenSessionsScreen(state, ScreenViewerState(environment.id, device, accessFor(device)), false, {}, {}, {}, {}, {}, isAccessCurrent = { true }, viewerContent = fakeViewer)
                    }
                }
            }
        }
        compose.onNodeWithText("Text").performTextInput("s3cret-value")
        compose.runOnIdle {
            val saved = registry.performSave()
            assertFalse("typed text reached saved state: $saved".replace("s3cret-value", "***"), saved.values.flatten().any { it.toString().contains("s3cret-value") })
        }
    }
}

package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.adaptive.LocalFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun keyEvent(key: Key, down: Boolean = true, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false, repeat: Int = 0): KeyEvent {
    val state = (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) android.view.KeyEvent.META_ALT_ON else 0) or (if (meta) android.view.KeyEvent.META_META_ON else 0)
    return KeyEvent(android.view.KeyEvent(0L, 0L, if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP, key.nativeKeyCode, repeat, state))
}

/** The pure dispatch rules: what a key press does with and without a binding, and while something modal is open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutDispatchRulesTest {
    private val globals = ShortcutTable.filter { it.scope == ShortcutScope.Global }

    private fun eventOf(chord: Chord) = keyEvent(chord.key, true, chord.ctrl, chord.shift, chord.alt, chord.meta)

    @Test fun everyGlobalChordCallsItsBindingAndIsConsumed() {
        globals.forEach { entry ->
            entry.chords.forEach { chord ->
                val calls = mutableListOf<ShortcutId>()
                val consumed = dispatchShortcut(eventOf(chord), false, mapOf(entry.id to { calls += entry.id; true }))
                assertTrue("${entry.id} ${chord.label}", consumed)
                assertEquals("${entry.id} ${chord.label}", listOf(entry.id), calls)
            }
        }
    }

    @Test fun anAbsentBindingLeavesTheKeyToTheFocusedNode() {
        globals.forEach { entry -> entry.chords.forEach { assertFalse("${entry.id} ${it.label}", dispatchShortcut(eventOf(it), false, emptyMap())) } }
    }

    @Test fun aBindingThatDeclinesDoesNotConsume() {
        assertFalse(dispatchShortcut(keyEvent(Key.N, ctrl = true), false, mapOf(ShortcutId.NewThread to { false })))
    }

    @Test fun modifiersMustMatchExactly() {
        val bindings = ShortcutId.entries.associateWith { { true } }
        assertFalse(dispatchShortcut(keyEvent(Key.N, ctrl = true, shift = true), false, bindings))
        assertFalse(dispatchShortcut(keyEvent(Key.N, ctrl = true, alt = true), false, bindings))
        assertFalse(dispatchShortcut(keyEvent(Key.N), false, bindings))
        assertTrue(dispatchShortcut(keyEvent(Key.N, ctrl = true), false, bindings))
    }

    @Test fun onlyAFirstKeyDownCounts() {
        val bindings = ShortcutId.entries.associateWith { { true } }
        assertFalse(dispatchShortcut(keyEvent(Key.N, down = false, ctrl = true), false, bindings))
        assertFalse(dispatchShortcut(keyEvent(Key.N, ctrl = true, repeat = 1), false, bindings))
    }

    @Test fun plainTypingKeysAreNeverGlobal() {
        val bindings = ShortcutId.entries.associateWith { { true } }
        listOf(Key.A, Key.N, Key.U, Key.R, Key.Slash, Key.Period, Key.One, Key.Enter, Key.Spacebar, Key.DirectionDown, Key.Y, Key.Tab).forEach {
            assertFalse("$it", dispatchShortcut(keyEvent(it), false, bindings))
            assertFalse("shift $it", dispatchShortcut(keyEvent(it, shift = true), false, bindings))
        }
    }

    @Test fun whileSomethingModalIsOpenOnlyTheCheatSheetAndEscapeStayGlobal() {
        val calls = mutableListOf<ShortcutId>()
        val bindings = ShortcutId.entries.associateWith { id -> { calls += id; true } }
        globals.forEach { entry -> entry.chords.forEach { dispatchShortcut(eventOf(it), true, bindings) } }
        assertEquals(setOf(ShortcutId.ShowShortcuts, ShortcutId.Dismiss), calls.toSet())
    }

    @Test fun composerAndNewThreadKeysAreNotRootShortcuts() {
        val bindings = ShortcutId.entries.associateWith { { true } }
        assertFalse(dispatchShortcut(keyEvent(Key.Enter), false, bindings))
        assertFalse(dispatchShortcut(keyEvent(Key.Enter, ctrl = true), false, bindings))
        assertFalse(dispatchShortcut(keyEvent(Key.Enter, ctrl = true, shift = true), false, bindings))
    }
}

/** The root key handler of the app: shortcuts reach the same actions as the buttons, and typing is left alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class ShortcutDispatchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"))
    private val threads = listOf(ThreadSummary("t", "p", "Fixture thread"), ThreadSummary("u", "p", "Other thread"))
    private val base = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canRead = true,
        projects = listOf(Project("p", "Project")), models = models, threads = threads, selectedThreadId = "t", localServers = LocalServerCatalog(supported = false))
    private val state = mutableStateOf(base)
    private val selected = mutableListOf<String>()
    private val sent = mutableListOf<String>()
    private val refreshed = mutableListOf<String>()

    private fun setApp(initial: ClientState = base, start: ShellStart = ShellStart(showThread = true)) {
        state.value = initial
        compose.setContent {
            CompositionLocalProvider(LocalFold provides null) {
                TrefoilApp(state.value, listOf(environment), { _, _, _ -> }, {}, { selected += it }, { sent += it }, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
                    onRefreshScreens = { refreshed += "screens" }, onRefreshUsage = { refreshed += "usage" }, onRefresh = { refreshed += "threads" }, start = start)
            }
        }
        compose.onRoot().performKeyInput { keyDown(Key.F12); keyUp(Key.F12) }
        compose.waitForIdle()
    }

    /** Presses a chord at the app window's root, or at the topmost window (an open dialog) when [inDialog]. */
    private fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, inDialog: Boolean = false) {
        val roots = compose.onAllNodes(isRoot())
        (if (inDialog) roots.onLast() else roots.onFirst()).performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            if (alt) keyDown(Key.AltLeft)
            keyDown(key); keyUp(key)
            if (alt) keyUp(Key.AltLeft)
            if (shift) keyUp(Key.ShiftLeft)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
    private fun inList(text: String) = hasText(text) and hasAnyAncestor(pane("Thread list"))
    private fun newThreadSheet() = compose.onNodeWithText("What should the agent do?")
    private fun cheatSheetOpen() = compose.onAllNodesWithText("Keyboard shortcuts").fetchSemanticsNodes().isNotEmpty()

    @Test fun ctrlSlashAndF1OpenTheCheatSheetAndTheSameChordClosesIt() {
        setApp()
        assertFalse(cheatSheetOpen())
        press(Key.Slash, ctrl = true)
        assertTrue(cheatSheetOpen())
        compose.onNodeWithText("Next thread that needs you").assertExists()
        press(Key.Slash, ctrl = true, inDialog = true)
        assertFalse(cheatSheetOpen())
        press(Key.F1)
        assertTrue(cheatSheetOpen())
        press(Key.F1, inDialog = true)
        assertFalse(cheatSheetOpen())
    }

    @Test fun theRailOverflowMenuOpensTheCheatSheetToo() {
        setApp()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNode(hasText("Keyboard shortcuts") and hasClickAction()).performClick()
        compose.onNodeWithText("Next thread that needs you").assertExists()
    }

    @Test fun ctrlNOpensTheNewThreadSheetOnlyWhereTheButtonIsEnabled() {
        setApp(base.copy(models = emptyList()))
        press(Key.N, ctrl = true)
        newThreadSheet().assertDoesNotExist()
        state.value = base.copy(canWrite = false)
        press(Key.N, ctrl = true)
        newThreadSheet().assertDoesNotExist()
        state.value = base
        press(Key.N, ctrl = true)
        newThreadSheet().assertExists()
    }

    @Test fun globalShortcutsDoNothingWhileTheNewThreadSheetIsOpen() {
        setApp()
        press(Key.N, ctrl = true)
        newThreadSheet().assertExists()
        press(Key.DirectionDown, alt = true)
        press(Key.Four, ctrl = true)
        assertTrue(selected.isEmpty())
        compose.onNode(pane("Usage")).assertDoesNotExist()
    }

    @Test fun altDownAndUpStepThroughTheList() {
        setApp()
        press(Key.DirectionUp, alt = true)
        assertTrue("first thread has no previous", selected.isEmpty())
        press(Key.DirectionDown, alt = true)
        assertEquals(listOf("u"), selected)
        press(Key.RightBracket, ctrl = true)
        assertEquals(listOf("u", "u"), selected)
    }

    // A phone-width window (a split-screen tablet with a keyboard) shows the list alone: the steps open a thread from it.
    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test fun threadStepsWorkFromTheListOfANarrowWindowToo() {
        setApp(start = ShellStart(showThread = false))
        press(Key.DirectionDown, alt = true)
        assertEquals(listOf("u"), selected)
    }

    @Test fun threadStepsNeedAnOpenListOrConversation() {
        setApp(base.copy(connection = ConnectionStatus.Disconnected))
        press(Key.DirectionDown, alt = true)
        assertTrue(selected.isEmpty())
    }

    @Test fun ctrlShiftUOpensTheNextThreadThatNeedsYou() {
        val waiting = threads + ThreadSummary("w", "p", "Waiting", pendingRequestKind = "approval", pendingRequestId = "r")
        setApp(base.copy(threads = waiting))
        press(Key.U, ctrl = true, shift = true)
        assertEquals(listOf("w"), selected)
    }

    @Test fun ctrlShiftUDoesNothingWhenNothingElseNeedsYou() {
        setApp()
        press(Key.U, ctrl = true, shift = true)
        assertTrue(selected.isEmpty())
    }

    @Test fun ctrlBackslashHidesAndShowsTheThreadList() {
        setApp()
        compose.onNode(inList("Other thread")).assertIsDisplayed()
        press(Key.Backslash, ctrl = true)
        compose.onNode(inList("Other thread")).assertDoesNotExist()
        press(Key.Backslash, ctrl = true)
        compose.onNode(inList("Other thread")).assertIsDisplayed()
    }

    @Test fun ctrlPeriodTogglesTheSidePanelLikeTheHeaderToggle() {
        setApp()
        press(Key.Period, ctrl = true)
        compose.onNode(pane("Screens side panel")).assertExists()
        press(Key.Period, ctrl = true)
        compose.onNode(pane("Screens side panel")).assertDoesNotExist()
    }

    @Test fun ctrlDigitsGoToTheRailDestinations() {
        setApp()
        press(Key.Four, ctrl = true)
        compose.onNode(pane("Usage")).assertExists()
        press(Key.One, ctrl = true)
        compose.onNode(pane("Usage")).assertDoesNotExist()
        press(Key.Two, ctrl = true)
        compose.onNode(pane("Screens")).assertExists()
    }

    @Test fun disabledRailDestinationsIgnoreTheirKeys() {
        // Reconnecting keeps the rail but disables Screens; the key must not bypass that.
        setApp(base.copy(connection = ConnectionStatus.Reconnecting))
        press(Key.Two, ctrl = true)
        compose.onNode(pane("Screens")).assertDoesNotExist()
    }

    @Test fun refreshFollowsTheScreenInFront() {
        setApp(base.copy(canReadUsage = true))
        press(Key.R, ctrl = true)
        assertEquals(listOf("threads"), refreshed)
        press(Key.Four, ctrl = true)
        press(Key.F5)
        assertEquals(listOf("threads", "usage"), refreshed)
    }

    @Test fun plainLettersReachAFocusedTextFieldAndNothingElse() {
        setApp()
        // The conversation's composer is a text field: focus it and press plain keys. None of them may open anything, navigate or send.
        compose.onNode(hasSetTextAction()).performClick()
        press(Key.N); press(Key.U); press(Key.Four); press(Key.Slash); press(Key.R); press(Key.A)
        newThreadSheet().assertDoesNotExist()
        assertFalse(cheatSheetOpen())
        assertTrue(selected.isEmpty() && sent.isEmpty() && refreshed.isEmpty())
    }
}

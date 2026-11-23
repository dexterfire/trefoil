package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun downEvent(key: Key, ctrl: Boolean = false, shift: Boolean = false): KeyEvent {
    val state = (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0)
    return KeyEvent(android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, key.nativeKeyCode, 0, state))
}

/** The registry itself: what a screen registers is what the root can reach, and nothing outlives the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutRegistryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anUnregisteredShortcutHasNoAction() {
        assertNull(ShortcutRegistry().action(ShortcutId.SearchThreads))
    }

    @Test fun aLateDisposeNeverRemovesItsReplacement() {
        val registry = ShortcutRegistry()
        val first = registry.register(ShortcutId.SearchThreads) { true }
        val second = registry.register(ShortcutId.SearchThreads) { false }
        registry.unregister(ShortcutId.SearchThreads, first)
        assertNotNull(registry.action(ShortcutId.SearchThreads))
        assertFalse(registry.action(ShortcutId.SearchThreads)!!.invoke())
        registry.unregister(ShortcutId.SearchThreads, second)
        assertNull(registry.action(ShortcutId.SearchThreads))
    }

    @Test fun theActionIsRegisteredOnlyWhileTheScreenIsComposedAndEnabled() {
        val registry = ShortcutRegistry()
        val shown = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        var calls = 0
        compose.setContent {
            CompositionLocalProvider(LocalShortcutRegistry provides registry) {
                if (shown.value) RegisterShortcut(ShortcutId.SearchThreads, enabled.value) { calls++ }
            }
        }
        compose.waitForIdle()
        assertTrue(registry.action(ShortcutId.SearchThreads)!!.invoke())
        assertEquals(1, calls)
        enabled.value = false
        compose.waitForIdle()
        assertNull("disabled", registry.action(ShortcutId.SearchThreads))
        enabled.value = true
        compose.waitForIdle()
        assertNotNull("enabled again", registry.action(ShortcutId.SearchThreads))
        shown.value = false
        compose.waitForIdle()
        assertNull("the screen left the composition", registry.action(ShortcutId.SearchThreads))
    }

    @Test fun theLatestActionRunsNotTheOneOfTheFirstComposition() {
        val registry = ShortcutRegistry()
        val which = mutableStateOf("first")
        val ran = mutableListOf<String>()
        compose.setContent { CompositionLocalProvider(LocalShortcutRegistry provides registry) { val now = which.value; RegisterShortcut(ShortcutId.SearchThreads) { ran += now } } }
        compose.waitForIdle()
        which.value = "second"
        compose.waitForIdle()
        registry.action(ShortcutId.SearchThreads)!!.invoke()
        assertEquals(listOf("second"), ran)
    }

    @Test fun withoutARegistryRegisteringDoesNothing() {
        compose.setContent { RegisterShortcut(ShortcutId.SearchThreads) { error("must not run") } }
        compose.waitForIdle()
    }

    @Test fun theHostDispatchesRegisteredActionsUnlessTheRootSaysTheirPaneIsHidden() {
        val host = ShortcutHost()
        var calls = 0
        host.registry.register(ShortcutId.SearchThreads) { calls++; true }
        assertTrue(host.dispatch(downEvent(Key.K, ctrl = true)))
        assertTrue(host.dispatch(downEvent(Key.F, ctrl = true)))
        assertEquals(2, calls)
        host.visible = { false }
        assertFalse(host.dispatch(downEvent(Key.K, ctrl = true)))
        host.visible = { true }
        host.modalOpen = { true }
        assertFalse("a dialog is open", host.dispatch(downEvent(Key.K, ctrl = true)))
        assertEquals(2, calls)
    }

    @Test fun aRootBindingWinsOverARegisteredAction() {
        val host = ShortcutHost()
        val ran = mutableListOf<String>()
        host.registry.register(ShortcutId.SearchThreads) { ran += "registered"; true }
        host.bindings = mapOf(ShortcutId.SearchThreads to { ran += "root"; true })
        host.dispatch(downEvent(Key.K, ctrl = true))
        assertEquals(listOf("root"), ran)
    }

    @Test fun aRegisteredActionIsNotVisibleInAHiddenPane() {
        assertTrue(registeredShortcutVisible(ShortcutId.SearchThreads, listHidden = false, transient = TransientList.None))
        assertTrue(registeredShortcutVisible(ShortcutId.SearchThreads, listHidden = false, transient = TransientList.ListOnly))
        assertFalse(registeredShortcutVisible(ShortcutId.SearchThreads, listHidden = true, transient = TransientList.None))
        assertFalse(registeredShortcutVisible(ShortcutId.SearchThreads, listHidden = false, transient = TransientList.HideList))
        assertFalse(registeredShortcutVisible(ShortcutId.SearchThreads, listHidden = false, transient = TransientList.PanelOpen))
        // The conversation is out of sight only while the keyboard layout shows the list alone.
        assertTrue(registeredShortcutVisible(ShortcutId.ModelPicker, listHidden = true, transient = TransientList.HideList))
        assertFalse(registeredShortcutVisible(ShortcutId.ModelPicker, listHidden = false, transient = TransientList.ListOnly))
    }
}

/** The three chords whose state lives inside a screen, pressed through the app root. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class ShortcutRegisteredActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"))
    private val threads = listOf(ThreadSummary("t", "p", "Fixture thread"), ThreadSummary("u", "p", "Other thread"))
    private val base = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canRead = true,
        projects = listOf(Project("p", "Project")), models = models, threads = threads, selectedThreadId = "t", localServers = LocalServerCatalog(supported = false))
    private val state = mutableStateOf(base)

    private fun setApp(initial: ClientState = base, start: ShellStart = ShellStart(showThread = true)) {
        state.value = initial
        compose.setContent {
            CompositionLocalProvider(LocalFold provides null) {
                TrefoilApp(state.value, listOf(environment), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = start)
            }
        }
        compose.onRoot().performKeyInput { keyDown(Key.F12); keyUp(Key.F12) }
        compose.waitForIdle()
    }

    private fun press(key: Key, ctrl: Boolean = false, shift: Boolean = false, inDialog: Boolean = false) {
        val roots = compose.onAllNodes(isRoot())
        (if (inDialog) roots.onLast() else roots.onFirst()).performKeyInput {
            if (ctrl) keyDown(Key.CtrlLeft)
            if (shift) keyDown(Key.ShiftLeft)
            keyDown(key); keyUp(key)
            if (shift) keyUp(Key.ShiftLeft)
            if (ctrl) keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
    }

    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
    private fun searchField() = compose.onNode(hasSetTextAction() and hasAnyAncestor(pane("Thread list")))
    private fun searchOpen() = compose.onAllNodes(hasSetTextAction() and hasAnyAncestor(pane("Thread list"))).fetchSemanticsNodes().isNotEmpty()
    private fun newThreadSheet() = compose.onNodeWithText("What should the agent do?")

    // ---- Model picker ----

    // The picker's own title, inside its dialog window.
    private fun pickerOpen() = compose.onAllNodes(hasText("Model") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()

    @Test fun ctrlShiftLOpensThePickerOfTheConversationOnScreen() {
        setApp()
        assertFalse(pickerOpen())
        press(Key.L, ctrl = true, shift = true)
        assertTrue(pickerOpen())
    }

    @Test fun ctrlShiftLFollowsTheModelPillsEnabledRule() {
        setApp(base.copy(models = emptyList()))
        press(Key.L, ctrl = true, shift = true)
        assertFalse("no models: the pill is disabled", pickerOpen())
        state.value = base.copy(canWrite = false)
        press(Key.L, ctrl = true, shift = true)
        assertFalse("read-only: the pill is disabled", pickerOpen())
        state.value = base.copy(busy = true)
        press(Key.L, ctrl = true, shift = true)
        assertFalse("busy: the pill is disabled", pickerOpen())
        state.value = base
        press(Key.L, ctrl = true, shift = true)
        assertTrue(pickerOpen())
    }

    @Test fun ctrlShiftLDoesNothingWithoutAConversation() {
        setApp(base.copy(selectedThreadId = null))
        press(Key.L, ctrl = true, shift = true)
        assertFalse(pickerOpen())
    }

    @Test fun ctrlShiftLDoesNothingAfterTheConversationLeft() {
        setApp()
        state.value = base.copy(selectedThreadId = null)
        compose.waitForIdle()
        press(Key.L, ctrl = true, shift = true)
        assertFalse(pickerOpen())
    }

    @Test fun ctrlShiftLReachesTheNewConversationAfterAThreadSwitch() {
        setApp()
        state.value = base.copy(selectedThreadId = "u")
        compose.waitForIdle()
        press(Key.L, ctrl = true, shift = true)
        assertTrue(pickerOpen())
    }

    @Test fun ctrlShiftLDoesNothingWhileADialogIsOpen() {
        setApp()
        press(Key.N, ctrl = true)
        newThreadSheet().assertExists()
        press(Key.L, ctrl = true, shift = true, inDialog = true)
        press(Key.L, ctrl = true, shift = true)
        assertFalse(pickerOpen())
    }

    // The conversation of a narrow window is the only pane; with the list in front there is no composer to choose a model for.
    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test fun ctrlShiftLDoesNothingFromTheListOfANarrowWindow() {
        setApp(start = ShellStart(showThread = false))
        press(Key.L, ctrl = true, shift = true)
        assertFalse(pickerOpen())
    }

    // ---- Machine switcher ----

    // The menu's own popup window; the navigation drawer (composed but closed) holds the same items, so a plain text match would not tell them apart.
    private fun machineMenuOpen() = compose.onAllNodes(hasText("Manage machines & routes…") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty()

    @Test fun ctrlShiftMOpensTheRailsMachineMenu() {
        setApp()
        assertFalse(machineMenuOpen())
        press(Key.M, ctrl = true, shift = true)
        assertTrue(machineMenuOpen())
        compose.onNode(hasText("Fixture") and hasAnyAncestor(isPopup())).assertExists()
    }

    @Test fun ctrlShiftMDoesNothingWithoutTheRail() {
        // No machine yet: no rail and no switcher.
        setApp(base.copy(environment = null, connection = ConnectionStatus.Disconnected, selectedThreadId = null, threads = emptyList()))
        press(Key.M, ctrl = true, shift = true)
        assertFalse(machineMenuOpen())
    }

    @Config(sdk = [35], qualifiers = "w700dp-h800dp")
    @Test fun ctrlShiftMDoesNothingWhereTheDrawerReplacesTheRail() {
        setApp()
        press(Key.M, ctrl = true, shift = true)
        assertFalse(machineMenuOpen())
    }

    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test fun ctrlShiftMDoesNothingOnAPhone() {
        setApp()
        press(Key.M, ctrl = true, shift = true)
        assertFalse(machineMenuOpen())
    }

    @Test fun ctrlShiftMDoesNothingWhileADialogIsOpen() {
        setApp()
        press(Key.N, ctrl = true)
        newThreadSheet().assertExists()
        press(Key.M, ctrl = true, shift = true, inDialog = true)
        press(Key.M, ctrl = true, shift = true)
        assertFalse(machineMenuOpen())
    }

    // ---- Search threads ----

    @Test fun ctrlKAndCtrlFOpenTheSearchFieldAndFocusIt() {
        setApp()
        assertFalse(searchOpen())
        press(Key.K, ctrl = true)
        searchField().assertIsFocused()
        // Closing the field with its own button, then the second chord does the same.
        compose.onNodeWithContentDescription("Close search").performClick()
        assertFalse(searchOpen())
        press(Key.F, ctrl = true)
        searchField().assertIsFocused()
    }

    @Test fun theChordFocusesAnOpenFieldInsteadOfClosingIt() {
        setApp()
        press(Key.K, ctrl = true)
        searchField().performTextInput("Other")
        compose.onNode(hasSetTextAction() and !hasAnyAncestor(pane("Thread list"))).requestFocus()
        press(Key.K, ctrl = true)
        assertTrue("still open", searchOpen())
        searchField().assertIsFocused()
        searchField().assertTextContains("Other")
    }

    @Test fun searchDoesNothingWhileTheListIsCollapsed() {
        setApp()
        press(Key.Backslash, ctrl = true)
        press(Key.K, ctrl = true)
        press(Key.F, ctrl = true)
        assertFalse(searchOpen())
        press(Key.Backslash, ctrl = true)
        press(Key.K, ctrl = true)
        assertTrue("the list is back", searchOpen())
    }

    // A phone-width window shows one pane: with the conversation in front the list is not composed, so there is nothing to search.
    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test fun searchDoesNothingFromTheConversationOfANarrowWindowAndWorksFromItsList() {
        setApp(start = ShellStart(showThread = true))
        press(Key.K, ctrl = true)
        compose.onAllNodesWithText("Search threads").assertCountEquals(0)
    }

    @Config(sdk = [35], qualifiers = "w400dp-h800dp")
    @Test fun searchWorksFromTheListOfANarrowWindow() {
        setApp(start = ShellStart(showThread = false))
        press(Key.K, ctrl = true)
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test fun searchDoesNothingWhileADialogIsOpen() {
        setApp()
        press(Key.N, ctrl = true)
        newThreadSheet().assertExists()
        press(Key.K, ctrl = true, inDialog = true)
        press(Key.K, ctrl = true)
        assertFalse(searchOpen())
    }

    @Test fun searchActsOnTheMachineOnScreenAfterASwitch() {
        setApp()
        press(Key.K, ctrl = true)
        assertTrue(searchOpen())
        // The list keeps its search per machine: another machine's list starts closed, and the chord opens that one.
        val other = SavedEnvironment("other", "Other", "https://other.invalid", "other")
        state.value = base.copy(environment = other, threads = listOf(ThreadSummary("z", "p", "Elsewhere")), selectedThreadId = null)
        compose.waitForIdle()
        assertFalse("the new machine's list starts without a search", searchOpen())
        press(Key.K, ctrl = true)
        assertTrue(searchOpen())
    }
}

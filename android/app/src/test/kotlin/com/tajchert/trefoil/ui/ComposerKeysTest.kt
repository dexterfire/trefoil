package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import android.view.InputDevice
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.input.key.KeyEvent
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Hardware-keyboard keys in the message box and the New thread dialog: they do what the primary button does, under its enabled rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerKeysTest {
    @get:Rule val compose = createComposeRule()
    private val environment = SavedEnvironment("env", "Env", "https://e.test", "t")
    private val idle = ClientState(selectedThreadId = "fixture", canWrite = true, connection = ConnectionStatus.Connected, environment = environment)
    private val running = idle.copy(activeRunId = "run", canStop = true, messages = listOf(ConversationMessage("old", "user", "Start", runId = "run")))
    private val sent = mutableListOf<Pair<String, FollowUpMode?>>()

    private fun conversation(state: ClientState) =
        compose.setContent { MaterialTheme { Conversation(state, {}, {}, { _, _, _ -> }, submitMessage = { s, m -> sent += s.text to m }) } }

    private fun field() = compose.onNode(hasSetTextAction())
    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** A physical keyboard's Enter (the injector's own events report the virtual keyboard, which the composer rightly treats as the on-screen one). */
    private fun SemanticsNodeInteraction.pressEnter(ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false, hardware: Boolean = true, repeat: Int = 0) {
        val state = (if (ctrl) AndroidKeyEvent.META_CTRL_ON else 0) or (if (shift) AndroidKeyEvent.META_SHIFT_ON else 0) or
            (if (alt) AndroidKeyEvent.META_ALT_ON else 0) or (if (meta) AndroidKeyEvent.META_META_ON else 0)
        listOf(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.ACTION_UP).forEach { action ->
            val event = if (hardware) AndroidKeyEvent(0L, 0L, action, AndroidKeyEvent.KEYCODE_ENTER, repeat, state, 7, 0, 0, InputDevice.SOURCE_KEYBOARD)
                else AndroidKeyEvent(0L, 0L, action, AndroidKeyEvent.KEYCODE_ENTER, repeat, state)
            performKeyPress(KeyEvent(event))
        }
    }
    private fun enter(ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false) = field().pressEnter(ctrl, shift, alt, meta)

    @Test fun enterCtrlEnterAndMetaEnterSendWhatTheButtonSends() {
        conversation(idle)
        field().performTextInput("one")
        enter()
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("one" to null), sent) }
    }

    @Test fun ctrlEnterSends() {
        conversation(idle)
        field().performTextInput("two")
        enter(ctrl = true)
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("two" to null), sent) }
    }

    @Test fun metaEnterSends() {
        conversation(idle)
        field().performTextInput("three")
        enter(meta = true)
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("three" to null), sent) }
    }

    @Test fun shiftEnterAndAltEnterInsertALineBreakAndSendNothing() {
        conversation(idle)
        field().performTextInput("a")
        enter(shift = true)
        enter(alt = true)
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        assertEquals("a\n\n", fieldText())
    }

    @Test fun theOnScreenKeyboardsEnterIsStillALineBreak() {
        conversation(idle)
        field().performTextInput("soft")
        field().pressEnter(hardware = false)
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun enterDoesNothingWhileTheButtonIsDisabled() {
        conversation(idle.copy(canWrite = false))
        field().performTextInput("blocked")
        enter(); enter(ctrl = true)
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        assertEquals("blocked", fieldText())
    }

    @Test fun enterSteersAndCtrlShiftEnterQueuesWhileATurnRuns() {
        conversation(running.copy(canSteer = true))
        field().performTextInput("steer me")
        enter()
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("steer me" to FollowUpMode.Steer), sent) }
    }

    @Test fun ctrlShiftEnterSendsTheOtherWay() {
        conversation(running.copy(canSteer = true))
        field().performTextInput("queue me")
        enter(ctrl = true, shift = true)
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("queue me" to FollowUpMode.Queue), sent) }
    }

    @Test fun ctrlShiftEnterWithNoAlternateIsThePrimarySend() {
        conversation(running.copy(canSteer = false))
        field().performTextInput("later")
        enter(ctrl = true, shift = true)
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("later" to FollowUpMode.Queue), sent) }
    }

    // ---- New thread dialog ----

    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"))
    private val launchable = ClientState(projects = listOf(Project("p1", "Alpha")), models = models, canWrite = true)
    private val started = mutableListOf<String>()

    private fun newThread(state: ClientState) = compose.setContent { TrefoilTheme { NewThreadSheetContent(state, {}, { _, prompt -> started += prompt }) } }
    private fun prompt() = compose.onNode(hasSetTextAction())

    @Test fun ctrlEnterStartsTheThreadOnceEvenWhenPressedAgain() {
        newThread(launchable)
        prompt().performTextInput("Fix the build")
        prompt().pressEnter(ctrl = true); prompt().pressEnter(ctrl = true)
        compose.runOnIdle { assertEquals(listOf("Fix the build"), started) }
    }

    @Test fun metaEnterStartsTheThreadToo() {
        newThread(launchable)
        prompt().performTextInput("Meta")
        prompt().pressEnter(meta = true)
        compose.runOnIdle { assertEquals(listOf("Meta"), started) }
    }

    @Test fun ctrlEnterDoesNothingWhereTheStartButtonIsDisabled() {
        newThread(launchable.copy(canWrite = false))
        prompt().performTextInput("No")
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
        prompt().pressEnter(ctrl = true)
        compose.runOnIdle { assertTrue(started.isEmpty()) }
    }

    @Test fun ctrlEnterLeavesAnImeCompositionAloneAndStartsOnceItIsCommitted() {
        newThread(launchable)
        prompt().performClick()
        compose.waitForIdle()
        // The focused Compose host view gives the connection a real IME would type through.
        val host = (compose as AndroidComposeTestRule<*, *>).activity.window.decorView.findFocus()
        val ime = compose.runOnUiThread<InputConnection> { host.onCreateInputConnection(EditorInfo()) }
        compose.runOnUiThread { ime.setComposingText("ni", 1) }
        compose.waitForIdle()
        prompt().pressEnter(ctrl = true)
        compose.runOnIdle { assertTrue("nothing starts while the IME composes", started.isEmpty()) }
        // The key was left to the field (a real IME takes it first; here the field reads it as a line break). Once committed, the chord starts.
        compose.runOnUiThread { ime.finishComposingText() }
        compose.waitForIdle()
        prompt().pressEnter(ctrl = true)
        compose.runOnIdle { assertEquals(listOf("ni"), started.map { it.trim() }) }
    }

    @Test fun plainEnterInTheNewThreadPromptDoesNotStartIt() {
        newThread(launchable)
        prompt().performTextInput("Plain")
        prompt().pressEnter()
        compose.runOnIdle { assertTrue(started.isEmpty()) }
    }
}

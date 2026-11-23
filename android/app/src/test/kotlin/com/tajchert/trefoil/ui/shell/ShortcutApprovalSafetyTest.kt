package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.ShellStart
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.adaptive.LocalFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** No key and no chord of the app ever allows or denies a request; only Tab-focus plus Space or Enter on the button itself does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class ShortcutApprovalSafetyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val pending = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canRead = true, canReadUsage = true,
        projects = listOf(Project("p", "Project")), models = listOf(ModelOption("codex", "gpt-a", "Model A")),
        threads = listOf(ThreadSummary("t", "p", "Fixture thread", pendingRequestKind = "approval", pendingRequestId = "approval"), ThreadSummary("u", "p", "Other thread")),
        selectedThreadId = "t", localServers = LocalServerCatalog(supported = false),
        requests = listOf(RuntimeRequest("approval", "Run fixture command", "rm -rf build")))
    private val responses = mutableListOf<Triple<String, String, Map<String, List<String>>>>()

    private fun setApp() = compose.setContent {
        CompositionLocalProvider(LocalFold provides null) {
            TrefoilApp(pending, listOf(environment), { _, _, _ -> }, {}, {}, {}, {}, { id, decision, answers -> responses += Triple(id, decision, answers) }, null, {}, {}, {}, {}, {}, {}, {},
                start = ShellStart(showThread = true))
        }
    }

    private fun press(chord: Chord) {
        compose.onAllNodes(isRoot()).onFirst().performKeyInput {
            if (chord.ctrl) keyDown(Key.CtrlLeft); if (chord.shift) keyDown(Key.ShiftLeft); if (chord.alt) keyDown(Key.AltLeft); if (chord.meta) keyDown(Key.MetaLeft)
            keyDown(chord.key); keyUp(chord.key)
            if (chord.meta) keyUp(Key.MetaLeft); if (chord.alt) keyUp(Key.AltLeft); if (chord.shift) keyUp(Key.ShiftLeft); if (chord.ctrl) keyUp(Key.CtrlLeft)
        }
        compose.waitForIdle()
    }

    /** Closes whatever the chord opened (the cheat sheet) so the next one starts from the same screen. */
    private fun reset() {
        if (compose.onAllNodesWithText("Next thread that needs you").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Close").performClick()
            compose.waitForIdle()
        }
    }

    @Test fun theFixtureShowsAPendingApprovalWithAllowAndDeny() {
        setApp()
        compose.onNodeWithText("Allow").assertIsEnabled()
        compose.onNodeWithText("Deny").assertIsEnabled()
    }

    @Test fun noTableShortcutAnswersAPendingApproval() {
        setApp()
        ShortcutTable.flatMap { it.chords }.distinctBy { it.label }.forEach { chord ->
            press(chord)
            reset()
            assertTrue("${chord.label} answered a request: $responses", responses.isEmpty())
        }
        compose.onNodeWithText("Allow").assertExists()
    }

    @Test fun noBareKeyOrChordAnswersAPendingApproval() {
        setApp()
        val keys = listOf(Key.Enter, Key.Spacebar, Key.Y, Key.N, Key.A, Key.D, Key.One, Key.Two, Key.Escape, Key.NumPadEnter)
        keys.forEach { key -> press(Chord(key, "$key")); reset() }
        keys.forEach { key -> press(Chord(key, "$key", ctrl = true)); reset() }
        keys.forEach { key -> press(Chord(key, "$key", ctrl = true, shift = true)); reset() }
        keys.forEach { key -> press(Chord(key, "$key", alt = true)); reset() }
        keys.forEach { key -> press(Chord(key, "$key", meta = true)); reset() }
        assertTrue("responses: $responses", responses.isEmpty())
    }

    @Test fun chordsWhileAllowHasFocusStillDoNotAnswer() {
        setApp()
        compose.onNodeWithText("Allow").requestFocus()
        ShortcutTable.filter { it.scope == ShortcutScope.Global }.flatMap { it.chords }.forEach { chord ->
            compose.onNodeWithText("Allow").performKeyInput {
                if (chord.ctrl) keyDown(Key.CtrlLeft); if (chord.shift) keyDown(Key.ShiftLeft); if (chord.alt) keyDown(Key.AltLeft)
                keyDown(chord.key); keyUp(chord.key)
                if (chord.alt) keyUp(Key.AltLeft); if (chord.shift) keyUp(Key.ShiftLeft); if (chord.ctrl) keyUp(Key.CtrlLeft)
            }
            compose.waitForIdle()
            reset()
        }
        assertTrue("responses: $responses", responses.isEmpty())
    }

    @Test fun theButtonStillWorksFromTheKeyboardWhenItIsFocused() {
        setApp()
        compose.onNodeWithText("Deny").requestFocus()
        compose.onNodeWithText("Deny").performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle { assertEquals(1, responses.size); assertEquals("approval", responses.single().first) }
    }
}

package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
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

/** Escape is system Back: it walks the same stack (spec 2), one step per press, and does nothing where Back would leave the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class ShortcutEscapeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture")
    private val base = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canRead = true, canReadUsage = true,
        projects = listOf(Project("p", "Project")), models = listOf(ModelOption("codex", "gpt-a", "Model A")),
        threads = listOf(ThreadSummary("t", "p", "Fixture thread"), ThreadSummary("u", "p", "Other thread")), selectedThreadId = "t", localServers = LocalServerCatalog(supported = false))
    private val agent = Subagent("a", "t", origin = "app_owned", status = "running", driver = "codex", title = "Explore code", childThreadId = "child")

    private val state = mutableStateOf(base)
    private fun setApp(initial: ClientState = base, start: ShellStart = ShellStart(showThread = true)) {
        state.value = initial
        compose.setContent {
            CompositionLocalProvider(LocalFold provides null) {
                TrefoilApp(state.value, listOf(environment), { _, _, _ -> }, {}, { id -> state.value = state.value.copy(selectedThreadId = id) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}, start = start)
            }
        }
    }

    private fun escape() { compose.onAllNodes(isRoot()).onFirst().performKeyInput { keyDown(Key.Escape); keyUp(Key.Escape) }; compose.waitForIdle() }
    private fun chord(key: Key) { compose.onAllNodes(isRoot()).onFirst().performKeyInput { keyDown(Key.CtrlLeft); keyDown(key); keyUp(key); keyUp(Key.CtrlLeft) }; compose.waitForIdle() }
    private fun pane(title: String) = SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)

    @Test fun escapeLeavesUsageForThreads() {
        setApp()
        chord(Key.Four)
        compose.onNode(pane("Usage")).assertExists()
        escape()
        compose.onNode(pane("Usage")).assertDoesNotExist()
        compose.onNode(pane("Fixture thread, conversation")).assertExists()
    }

    @Test fun escapeClosesTheDockedSidePanelBeforeAnythingElse() {
        setApp()
        chord(Key.Period)
        compose.onNode(pane("Screens side panel")).assertExists()
        escape()
        compose.onNode(pane("Screens side panel")).assertDoesNotExist()
        compose.onNode(pane("Fixture thread, conversation")).assertExists()
    }

    @Test fun escapeAndSystemBackAgreeStepByStep() {
        // Usage over a docked panel is not reachable (navigating closes the panel), so compare the two paths over Screens then Usage.
        setApp()
        chord(Key.Two)
        compose.onNode(pane("Screens")).assertExists()
        escape()
        compose.onNode(pane("Screens")).assertDoesNotExist()
        chord(Key.Four)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(pane("Usage")).assertDoesNotExist()
    }

    @Config(qualifiers = "w390dp-h844dp")
    @Test fun escapeReturnsAPhoneConversationToTheList() {
        setApp()
        compose.onNodeWithText("Other thread").assertDoesNotExist()
        escape()
        compose.onNodeWithText("Other thread").assertExists()
    }

    @Config(qualifiers = "w390dp-h844dp")
    @Test fun escapeOnTheListOwnsNothingSoTheActivityStays() {
        setApp(start = ShellStart(showThread = false))
        compose.onNodeWithText("Other thread").assertExists()
        escape()
        compose.onNodeWithText("Other thread").assertExists()
        assertFalse(compose.activity.isFinishing)
    }

    @Config(qualifiers = "w390dp-h844dp")
    @Test fun escapeFromASubagentChildGoesToItsParentBeforeTheList() {
        val child = ThreadSummary("child", "p", "Child thread", parentThreadId = "t", relationshipToParent = "subagent")
        setApp(base.copy(threads = base.threads + child, selectedThreadId = "child", subagents = listOf(agent)))
        escape()
        compose.runOnIdle { assertEquals("t", state.value.selectedThreadId) }
        compose.onNodeWithText("Other thread").assertDoesNotExist()
        escape()
        compose.onNodeWithText("Other thread").assertExists()
    }
}

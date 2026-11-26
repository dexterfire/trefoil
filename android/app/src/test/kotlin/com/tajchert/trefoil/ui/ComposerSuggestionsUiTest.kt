package com.tajchert.trefoil.ui

import android.view.InputDevice
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `/` and `$` popover over the live composer: it opens on the sigil word only (not on a path or a price), the
 * keyboard picks and closes it, and a pick writes into the draft — it never sends a message by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ComposerSuggestionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val environment = SavedEnvironment("env", "Env", "https://e.test", "t")
    private val state = ClientState(
        selectedThreadId = "fixture", canWrite = true, connection = ConnectionStatus.Connected, environment = environment,
        providers = listOf(
            ProviderInstance(
                instanceId = "codex", driver = "codex",
                slashCommands = listOf(ProviderSlashCommand("review", "Review the working tree"), ProviderSlashCommand("code-review", "Review a pull request")),
                skills = listOf(ProviderSkill("frontend-design", "Frontend design", "Distinctive UI"), ProviderSkill("hidden", "Hidden", null, invocable = false)),
            ),
        ),
    )
    private val sent = mutableListOf<Pair<String, FollowUpMode?>>()

    private fun conversation(state: ClientState = this.state) =
        compose.setContent { MaterialTheme { Conversation(state, {}, {}, { _, _, _ -> }, submitMessage = { s, m -> sent += s.text to m }) } }

    private fun field() = compose.onNode(hasSetTextAction())
    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun popover() = compose.onNodeWithTag("composer-suggestions")

    /** Enter as a physical keyboard sends it (see ComposerKeysTest): the injector's own events read as the on-screen one. */
    private fun hardwareEnter() {
        listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).forEach { action ->
            field().performKeyPress(KeyEvent(android.view.KeyEvent(0L, 0L, action, android.view.KeyEvent.KEYCODE_ENTER, 0, 0, 7, 0, 0, InputDevice.SOURCE_KEYBOARD)))
        }
    }

    @Test fun aSlashOpensTheCommandListAndSendsNothing() {
        conversation()
        field().performTextInput("/")
        popover().assertExists()
        compose.onNodeWithText("/review").assertIsDisplayed()
        compose.onNodeWithText("/code-review").assertIsDisplayed()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun aDollarOpensTheSkillListAndSearchesMoreThanTheName() {
        conversation()
        field().performTextInput("\$distinct")
        popover().assertExists()
        compose.onNodeWithText("\$frontend-design").assertIsDisplayed()
        // An agent-only skill never appears, whatever the query.
        compose.onAllNodesWithText("\$hidden").fetchSemanticsNodes().isEmpty()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun aPathNeverOpensTheList() {
        conversation()
        field().performTextInput("/usr/bin")
        popover().assertDoesNotExist()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun aPriceNeverOpensTheList() {
        conversation()
        field().performTextInput("\$5")
        popover().assertDoesNotExist()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun arrowsMoveTheHighlightAndEnterPicksWithoutSending() {
        conversation()
        field().performTextInput("/re")
        popover().assertExists()
        // "re" sorts the prefix match first, so the highlight starts on /review; Down moves it onto /code-review.
        field().performKeyInput { pressKey(Key.DirectionDown) }
        hardwareEnter()
        compose.runOnIdle {
            assertEquals("/code-review ", fieldText())
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun escapeClosesTheListAndEnterGoesBackToSendingWhatIsTyped() {
        conversation()
        field().performTextInput("/re")
        popover().assertExists()
        field().performKeyInput { pressKey(Key.Escape) }
        popover().assertDoesNotExist()
        hardwareEnter()
        compose.runOnIdle { assertEquals(listOf<Pair<String, FollowUpMode?>>("/re" to null), sent) }
    }

    @Test fun noProviderListMeansAnOrdinaryWordAndAnEmptyFieldStaysQuiet() {
        conversation(state.copy(providers = emptyList()))
        field().performTextInput("/")
        popover().assertDoesNotExist()
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }
}

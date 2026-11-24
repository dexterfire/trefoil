package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class PromptSuggestionHintTest {
    private val environment = SavedEnvironment("env", "Fixture", "https://fixture.invalid", "fixture-token")
    private val suggestion = PromptSuggestion("run-1", "Now add tests for it.")
    private val completed = ConversationRun("run-1", "completed", startedAt = "2026-11-24T10:00:00Z", completedAt = "2026-11-24T10:01:00Z", ordinal = 1)
    private val idle = ClientState(
        environment = environment, selectedThreadId = "t", canWrite = true, connection = ConnectionStatus.Connected,
        threads = listOf(ThreadSummary("t", "p", "Thread", latestRunId = "run-1")), runs = listOf(completed), promptSuggestion = suggestion,
    )

    private fun candidate(
        state: ClientState = idle, isDraft: Boolean = false, text: String = "", waiting: Boolean = false, canWrite: Boolean = true, dismissed: Set<String> = emptySet(),
    ) = promptSuggestionCandidate(state, isDraft, text, waiting, canWrite, dismissed)

    @Test fun emptyWritableIdleComposerOffersTheSuggestion() {
        assertEquals(suggestion, candidate())
    }

    @Test fun noSuggestionNoCandidate() {
        assertNull(candidate(idle.copy(promptSuggestion = null)))
    }

    @Test fun anyTextInTheDraftHidesIt() {
        assertNull(candidate(text = "a"))
        assertNull(candidate(text = " "))
    }

    @Test fun hiddenWhenTheThreadCannotBeWrittenYet() {
        assertNull(candidate(canWrite = false))
        assertNull(candidate(waiting = true))
        assertNull(candidate(isDraft = true))
    }

    @Test fun hiddenWhileARunIsActive() {
        assertNull(candidate(idle.copy(activeRunId = "run-2")))
        assertNull(candidate(idle.copy(threads = listOf(ThreadSummary("t", "p", "Thread", latestRunId = "run-2")), runs = listOf(completed, ConversationRun(id = "run-2", status = "running", ordinal = 2)))))
    }

    @Test fun blankSuggestionIsNeverShown() {
        assertNull(candidate(idle.copy(promptSuggestion = PromptSuggestion("run-1", "  "))))
    }

    @Test fun dismissalIsPerEnvironmentAndRun() {
        val key = promptSuggestionKey("env", "run-1")
        assertNull(candidate(dismissed = setOf(key)))
        assertEquals(suggestion, candidate(dismissed = setOf(promptSuggestionKey("other", "run-1"))))
        // A newer run suggests again, even though the older one stays dismissed.
        val newer = PromptSuggestion("run-2", "Open the PR.")
        val afterNewerRun = idle.copy(
            threads = listOf(ThreadSummary("t", "p", "Thread", latestRunId = "run-2")), promptSuggestion = newer,
            runs = listOf(completed, completed.copy(id = "run-2", ordinal = 2, completedAt = "2026-11-24T10:05:00Z")),
        )
        assertEquals(newer, candidate(afterNewerRun, dismissed = setOf(key)))
    }

    @Test fun keyKeepsEnvironmentAndRunApart() {
        assertNotEquals(promptSuggestionKey("a", "b:c"), promptSuggestionKey("a:b", "c"))
        assertNotEquals(promptSuggestionKey(null, "run"), promptSuggestionKey("env", "run"))
        assertEquals(promptSuggestionKey("env", "run"), promptSuggestionKey("env", "run"))
    }

    @Test fun acceptOnlyInsertsIntoAnEmptyDraft() {
        assertEquals("Now add tests.", promptSuggestionToAccept("Now add tests.", ""))
        assertNull(promptSuggestionToAccept("Now add tests.", "x"))
        assertNull(promptSuggestionToAccept("Now add tests.", " "))
        assertNull(promptSuggestionToAccept(null, ""))
    }

    @Test fun swipeRightAcceptsLeftDismissesShortSpringsBack() {
        assertEquals(PromptSuggestionSwipe.Accept, promptSuggestionSwipe(120f, 100f))
        assertEquals(PromptSuggestionSwipe.Accept, promptSuggestionSwipe(100f, 100f))
        assertEquals(PromptSuggestionSwipe.Dismiss, promptSuggestionSwipe(-100f, 100f))
        assertEquals(PromptSuggestionSwipe.None, promptSuggestionSwipe(99f, 100f))
        assertEquals(PromptSuggestionSwipe.None, promptSuggestionSwipe(-99f, 100f))
        assertEquals(PromptSuggestionSwipe.None, promptSuggestionSwipe(0f, 100f))
    }

    @Test fun compactHeightShowsOneLine() {
        assertEquals(1, promptSuggestionMaxLines(true))
        assertEquals(2, promptSuggestionMaxLines(false))
    }
}

/** The hint inside the real composer: shown from the first frame (appearDelayMs = 0), accepted by the pill or a semantics action, never sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptSuggestionComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val environment = SavedEnvironment("env", "Fixture", "https://fixture.invalid", "fixture-token")
    private val state = ClientState(
        environment = environment, selectedThreadId = "t", canWrite = true, connection = ConnectionStatus.Connected,
        threads = listOf(ThreadSummary("t", "p", "Thread", latestRunId = "run-1")),
        runs = listOf(ConversationRun("run-1", "completed", startedAt = "2026-11-24T10:00:00Z", completedAt = "2026-11-24T10:01:00Z", ordinal = 1)),
        promptSuggestion = PromptSuggestion("run-1", "Now add tests for it."),
    )

    private var text by mutableStateOf("")
    private var dismissed by mutableStateOf(emptySet<String>())
    private val sent = mutableListOf<FollowUpMode?>()

    private fun show(s: ClientState = state) = compose.setContent {
        TrefoilTheme {
            ConversationComposer(s, null, text, { text = it }, waiting = false, stopPending = false, onSetRuntimeMode = {}, onSetModel = {},
                onSetModelOption = { _, _ -> }, stop = {}, onSend = { sent += it }, onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {},
                promptSuggestions = PromptSuggestionUi(dismissed, { dismissed = dismissed + it }, appearDelayMs = 0))
        }
    }

    @Test fun hintShowsWithTheSuggestionAndUseDescription() {
        show()
        compose.onNodeWithTag("prompt-suggestion").assertIsDisplayed()
        compose.onNodeWithContentDescription("Suggested message: Now add tests for it.", substring = true).assertIsDisplayed()
    }

    @Test fun pillAcceptsIntoTheDraftWithoutSending() {
        show()
        compose.onNodeWithContentDescription("Use suggestion", substring = true).performClick()
        compose.waitForIdle()
        assertEquals("Now add tests for it.", text)
        assertTrue(sent.isEmpty())
        compose.onNodeWithTag("prompt-suggestion").assertDoesNotExist()
    }

    @Test fun dismissActionHidesItAndRecordsTheRunKey() {
        show()
        val actions = compose.onNodeWithTag("prompt-suggestion").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Use suggestion", "Dismiss suggestion"), actions.map { it.label })
        compose.runOnUiThread { actions.first { it.label == "Dismiss suggestion" }.action() }
        compose.waitForIdle()
        assertEquals(setOf(promptSuggestionKey("env", "run-1")), dismissed)
        assertEquals("", text)
        compose.onNodeWithTag("prompt-suggestion").assertDoesNotExist()
    }

    @Test fun typingHidesItAndClearingBringsItBack() {
        show()
        text = "x"
        compose.waitForIdle()
        compose.onNodeWithTag("prompt-suggestion").assertDoesNotExist()
        text = ""
        compose.waitForIdle()
        compose.onNodeWithTag("prompt-suggestion").assertIsDisplayed()
    }

    @Test fun notShownWithoutASuggestionOrWhileARunIsActive() {
        show(state.copy(promptSuggestion = null))
        compose.onNodeWithTag("prompt-suggestion").assertDoesNotExist()
    }
}

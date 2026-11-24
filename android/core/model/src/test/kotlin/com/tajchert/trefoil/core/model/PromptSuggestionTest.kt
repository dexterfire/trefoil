package com.tajchert.trefoil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptSuggestionTest {
    private val suggestion = PromptSuggestion("run-1", "Run the tests")
    private val completed = ConversationRun("run-1", "completed", startedAt = "2026-11-24T10:00:00Z", completedAt = "2026-11-24T10:01:00Z", ordinal = 1)

    private fun state(latestRunId: String?, runs: List<ConversationRun>, value: PromptSuggestion? = suggestion, selected: String? = "t") = ClientState(
        selectedThreadId = selected,
        threads = listOf(ThreadSummary("t", "p", "Thread", latestRunId = latestRunId)),
        runs = runs,
        promptSuggestion = value,
    )

    @Test fun offersTheSuggestionOfTheLatestCompletedRun() {
        assertEquals(suggestion, state("run-1", listOf(completed)).offeredPromptSuggestion)
    }

    @Test fun offersNothingWithoutASuggestionOrSelectedThread() {
        assertNull(state("run-1", listOf(completed), value = null).offeredPromptSuggestion)
        assertNull(state("run-1", listOf(completed), selected = null).offeredPromptSuggestion)
        assertNull(state("run-1", listOf(completed), selected = "other").offeredPromptSuggestion)
        assertNull(state(null, listOf(completed)).offeredPromptSuggestion)
    }

    @Test fun aNewerRunOrUnfinishedLatestRunHidesIt() {
        val running = ConversationRun("run-2", "running", startedAt = "2026-11-24T10:02:00Z", ordinal = 2)
        assertNull(state("run-2", listOf(completed, running)).offeredPromptSuggestion)
        val queued = ConversationRun("run-2", "queued", ordinal = 2)
        assertNull(state("run-2", listOf(completed, queued)).offeredPromptSuggestion)
        for (status in listOf("failed", "interrupted", "running", "waiting")) {
            assertNull(status, state("run-1", listOf(completed.copy(status = status))).offeredPromptSuggestion)
        }
    }

    @Test fun aSteeredRunCancelledBeforeItStartedIsSkipped() {
        val steered = ConversationRun("run-2", "cancelled", ordinal = 2)
        assertEquals(suggestion, state("run-2", listOf(completed, steered)).offeredPromptSuggestion)
        // A run cancelled after it started ended the turn itself.
        assertNull(state("run-2", listOf(completed, steered.copy(startedAt = "2026-11-24T10:02:00Z"))).offeredPromptSuggestion)
    }

    @Test fun detailLaggingTheShellOffersNothing() {
        // The shell names a run the projection has not loaded yet (T3's skippedUnseenRun case included).
        assertNull(state("run-2", listOf(completed)).offeredPromptSuggestion)
    }

    @Test fun theLastRunThatRanIsChosenByCompletionThenOrdinal() {
        val older = ConversationRun("run-0", "completed", startedAt = "2026-11-24T09:00:00Z", completedAt = "2026-11-24T09:01:00Z", ordinal = 0)
        val steered = ConversationRun("run-2", "cancelled", ordinal = 2)
        assertEquals(suggestion, state("run-2", listOf(completed, older, steered)).offeredPromptSuggestion)
        assertNull(state("run-2", listOf(completed, older, steered), value = PromptSuggestion("run-0", "x")).offeredPromptSuggestion)
        val sameEnd = completed.copy(id = "run-3", ordinal = 3)
        assertEquals("run-3", latestExecutedRun(listOf(sameEnd, completed)).let { it?.id })
        assertNull(latestExecutedRun(listOf(steered, ConversationRun("q", "queued"))))
    }
}

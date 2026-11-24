package com.tajchert.trefoil.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptSuggestionTest {
    private val suggestion = PromptSuggestion("run-1", "Run the tests")
    private val completed = ConversationRun("run-1", "completed", startedAt = "2026-11-24T10:00:00Z", completedAt = "2026-11-24T10:01:00Z", ordinal = 1)

    /** The shell row is the source of the latest run: [latestRunId], its [status] and [latestRunStartedAt]. */
    private fun state(
        latestRunId: String?, runs: List<ConversationRun> = listOf(completed), status: String = "idle", latestRunStartedAt: String? = null,
        value: PromptSuggestion? = suggestion, selected: String? = "t",
    ) = ClientState(
        selectedThreadId = selected,
        threads = listOf(ThreadSummary("t", "p", "Thread", status = status, latestRunId = latestRunId, latestRunStartedAt = latestRunStartedAt)),
        runs = runs,
        promptSuggestion = value,
    )

    @Test fun offersTheSuggestionOfTheLatestCompletedRun() {
        assertEquals(suggestion, state("run-1").offeredPromptSuggestion)
        assertEquals(suggestion, state("run-1", status = "completed").offeredPromptSuggestion)
        // Like T3, the shell decides; the detail may still be loading.
        assertEquals(suggestion, state("run-1", runs = emptyList()).offeredPromptSuggestion)
    }

    @Test fun offersNothingWithoutASuggestionOrSelectedThread() {
        assertNull(state("run-1", value = null).offeredPromptSuggestion)
        assertNull(state("run-1", selected = null).offeredPromptSuggestion)
        assertNull(state("run-1", selected = "other").offeredPromptSuggestion)
        assertNull(state(null).offeredPromptSuggestion)
    }

    @Test fun aNewerOrUnfinishedLatestRunHidesIt() {
        assertNull(state("run-2").offeredPromptSuggestion)
        for (status in listOf("queued", "preparing", "starting", "running", "waiting", "failed", "interrupted")) {
            assertNull(status, state("run-1", status = status).offeredPromptSuggestion)
        }
        assertNull(state("run-1", status = "cancelled", latestRunStartedAt = "2026-11-24T10:00:00Z").offeredPromptSuggestion)
    }

    @Test fun aSteeredRunCancelledBeforeItStartedIsSkipped() {
        val steered = ConversationRun("run-2", "cancelled", ordinal = 2)
        assertEquals(suggestion, state("run-2", listOf(completed, steered), status = "cancelled").offeredPromptSuggestion)
        // The last run that ran must itself have completed.
        assertNull(state("run-2", listOf(completed.copy(status = "failed"), steered), status = "cancelled").offeredPromptSuggestion)
        assertNull(state("run-2", listOf(steered), status = "cancelled").offeredPromptSuggestion)
    }

    @Test fun detailLaggingTheShellOffersNothing() {
        // T3 skippedUnseenRun: the shell's cancelled run is not in the detail yet.
        assertNull(state("run-2", listOf(completed), status = "cancelled").offeredPromptSuggestion)
        // The detail already shows the queued run cancelled, but the shell still has it queued: hidden, like T3.
        val cancelledInDetail = ConversationRun("run-2", "cancelled", ordinal = 2)
        assertNull(state("run-2", listOf(completed, cancelledInDetail), status = "queued").offeredPromptSuggestion)
    }

    @Test fun theLastRunThatRanIsChosenByCompletionThenOrdinal() {
        val older = ConversationRun("run-0", "completed", startedAt = "2026-11-24T09:00:00Z", completedAt = "2026-11-24T09:01:00Z", ordinal = 0)
        val steered = ConversationRun("run-2", "cancelled", ordinal = 2)
        assertEquals(suggestion, state("run-2", listOf(completed, older, steered), status = "cancelled").offeredPromptSuggestion)
        assertNull(state("run-2", listOf(completed, older, steered), status = "cancelled", value = PromptSuggestion("run-0", "x")).offeredPromptSuggestion)
        val sameEnd = completed.copy(id = "run-3", ordinal = 3)
        assertEquals("run-3", latestExecutedRun(listOf(sameEnd, completed))?.id)
        assertNull(latestExecutedRun(listOf(steered, ConversationRun("q", "queued"))))
    }
}

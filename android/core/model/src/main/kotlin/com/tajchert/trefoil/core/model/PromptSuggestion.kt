package com.tajchert.trefoil.core.model

import java.time.Instant

/**
 * T3 `OrchestrationV2PromptSuggestion`: the provider's predicted next message for the run that produced it.
 * Unpersisted on the server and conversation content here: never logged, never stored.
 */
data class PromptSuggestion(val runId: String, val text: String)

/**
 * The suggestion to offer in the empty composer: [ClientState.promptSuggestion] while the run that produced it is the
 * selected thread's latest completed run. Null otherwise. Dismissal and the empty-draft rule belong to the UI.
 *
 * Port of T3 `offeredPromptSuggestion` + `announcedRun`: the shell's `latestRunId` names the latest run. A message
 * promoted to Steer leaves its queued run cancelled before it started, still the latest run, so the comparison skips
 * to the last run that ran. T3 reads the latest run's status from the shell; Trefoil's shell row carries no run
 * status, so the run must also be in the selected thread's [ClientState.runs]: while the detail lags the shell (the
 * run is missing), nothing is offered.
 */
val ClientState.offeredPromptSuggestion: PromptSuggestion? get() {
    val suggestion = promptSuggestion ?: return null
    val latestRunId = threads.firstOrNull { it.id == selectedThreadId }?.latestRunId ?: return null
    val latest = runs.firstOrNull { it.id == latestRunId } ?: return null
    val announced = if (latest.status == "cancelled" && latest.startedAt == null) latestExecutedRun(runs) else latest
    return suggestion.takeIf { announced?.id == it.runId && announced.status == "completed" }
}

/** T3 `latestExecutedRun`: the run that ran last, skipping queued runs and runs cancelled before they started. */
internal fun latestExecutedRun(runs: List<ConversationRun>): ConversationRun? {
    var latest: ConversationRun? = null
    for (run in runs) {
        if (run.status == "queued") continue
        if (run.status == "cancelled" && run.startedAt == null) continue
        if (latest == null || runRanAfter(run, latest)) latest = run
    }
    return latest
}

/** T3 `runRanAfter`: later completion wins (an unfinished run counts as latest); equal ends fall back to the ordinal. */
private fun runRanAfter(run: ConversationRun, other: ConversationRun): Boolean {
    fun end(candidate: ConversationRun): Long = candidate.completedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: Long.MAX_VALUE
    return if (end(run) == end(other)) (run.ordinal ?: 0) > (other.ordinal ?: 0) else end(run) > end(other)
}

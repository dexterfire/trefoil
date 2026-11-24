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
 * Port of T3 `offeredPromptSuggestion` + `announcedRun` over the shell's latest run (T3 `presentThreadShell`: the
 * row's `latestRunId` and `latestRunStartedAt`, with the shell `status`, `idle` read as `completed`). A message
 * promoted to Steer leaves its queued run cancelled before it started, still the latest run, so the comparison skips
 * to the last run in [ClientState.runs] that ran. Detail can lag the shell, so it skips only once the runs include
 * that cancelled run. Without a shell row for the selected thread nothing is offered.
 */
val ClientState.offeredPromptSuggestion: PromptSuggestion? get() {
    val suggestion = promptSuggestion ?: return null
    val shell = threads.firstOrNull { it.id == selectedThreadId } ?: return null
    val latestRunId = shell.latestRunId ?: return null
    val latestStatus = if (shell.status == "idle") "completed" else shell.status
    val skipsLatest = latestStatus == "cancelled" && shell.latestRunStartedAt == null
    val (announcedId, announcedStatus) = if (!skipsLatest) latestRunId to latestStatus else {
        // T3 `skippedUnseenRun`: the detail has not caught up with the cancelled run yet.
        if (runs.none { it.id == latestRunId && it.status == "cancelled" && it.startedAt == null }) return null
        val run = latestExecutedRun(runs) ?: return null
        run.id to run.status
    }
    return suggestion.takeIf { announcedId == it.runId && announcedStatus == "completed" }
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

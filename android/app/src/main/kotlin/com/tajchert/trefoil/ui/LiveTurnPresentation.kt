/* Live turn layout adapted from T3 Code web MessagesTimeline.logic.ts (working and thinking rows),
 * V2LifecycleRow.tsx (SubagentTimelineLink), AgentElapsed.tsx, TimelineWorkGroupRows.logic.ts and
 * client-runtime state/subagentRuntime.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.model.ToolActivity

/**
 * Where the rows of an active turn go. [headerIndex] is the entry index the "Working for" header is inserted before
 * (null while idle), [orphanSubagents] are this turn's agents with no inline item in the timeline (rendered after the
 * entries), and [thinkingLabel] is the trailing live indicator (null when something else already shows liveness).
 */
internal data class LiveTurnLayout(val headerIndex: Int?, val orphanSubagents: List<Subagent>, val thinkingLabel: String?)

internal fun liveTurnLayout(
    entries: List<ConversationEntry>, activeRunId: String?, roster: SubagentRoster?, subagents: List<Subagent>,
    streaming: Boolean, hasRequests: Boolean, turnEnded: Boolean = false,
): LiveTurnLayout = LiveTurnLayout(
    activeTurnHeaderIndex(entries, activeRunId),
    orphanSubagents(entries, roster, activeRunId != null),
    thinkingRowLabel(entries, activeRunId, subagents, streaming, hasRequests, turnEnded),
)

/** The live header's LazyColumn key: the key the run's fold takes once it settles (see [ConversationEntry.Work]). */
internal fun workingHeaderKey(activeRunId: String) = "run:$activeRunId"

/** T3 `timelineEntryStartsResponse`: a user prompt, or a notification that wakes the agent. */
private val ConversationEntry.startsResponse: Boolean get() = when (this) {
    is ConversationEntry.Message -> message.role == "user"
    is ConversationEntry.Activities -> activities.first().kind == "notification"
    else -> false
}

/** T3 `activeTurnHeaderIndex`: right after the latest response boundary, or the top when there is none. */
internal fun activeTurnHeaderIndex(entries: List<ConversationEntry>, activeRunId: String?): Int? =
    if (activeRunId == null) null else entries.indexOfLast { it.startsResponse } + 1

/** Subagent items render as agent rows, never as work groups. Grouping keeps them apart from other kinds. */
internal val ConversationEntry.Activities.isSubagentGroup: Boolean get() = activities.all { it.kind == "subagent" }

/**
 * The live record a `subagent` item reports on (by `subagentId`), else a stand-in built from the item itself so the row
 * still renders before the roster catches up.
 */
internal fun subagentForActivity(activity: ToolActivity, subagents: List<Subagent>): Subagent =
    activity.subagentId?.let { id -> subagents.firstOrNull { it.id == id } } ?: Subagent(
        id = activity.subagentId ?: activity.id, threadId = activity.sourceThreadId.orEmpty(), runId = activity.runId,
        title = activity.title.takeUnless { it.isBlank() || it.equals("subagent", ignoreCase = true) },
        prompt = activity.input.orEmpty(), status = activity.status, progress = activity.output,
        startedAt = activity.startedAt, completedAt = activity.completedAt,
    )

/** One row per agent, in item order; follow-up items for the same agent collapse onto its first row. */
internal fun subagentsForGroup(activities: List<ToolActivity>, subagents: List<Subagent>): List<Subagent> =
    activities.map { subagentForActivity(it, subagents) }.distinctBy { it.id }

/**
 * Roster agents the visible timeline does not show inline (no item yet, or only roster data), rendered as the same rows at
 * the end of the active turn. Without an active run only still-running agents stay.
 */
internal fun orphanSubagents(entries: List<ConversationEntry>, roster: SubagentRoster?, turnActive: Boolean): List<Subagent> {
    if (roster == null) return emptyList()
    val inline = entries.filterIsInstance<ConversationEntry.Activities>().filter { it.isSubagentGroup }
        .flatMap { group -> group.activities.map { it.subagentId ?: it.id } }.toSet()
    return roster.subagents.filter { it.id !in inline && (turnActive || it.isActive) }
}

/** T3 `waitingOnSubagentsLabel`. */
internal fun waitingOnSubagentsLabel(count: Int): String = if (count == 1) "Waiting on a subagent" else "Waiting on $count subagents"

/**
 * T3 thinking row: shown while a run is active, no message streams, no request waits on the user and the tail is not
 * already a live in-progress work row. Live agents at the very end of the run read "Waiting on a subagent" (T3
 * `runningSubagentsAtRunTail`), otherwise "Thinking". A run whose provider turn already ended (T3 `turnEnded`, for example
 * a `waiting` run draining checkpoints) is not thinking.
 */
internal fun thinkingRowLabel(
    entries: List<ConversationEntry>, activeRunId: String?, subagents: List<Subagent>, streaming: Boolean, hasRequests: Boolean,
    turnEnded: Boolean = false,
): String? {
    if (activeRunId == null || turnEnded || streaming || hasRequests) return null
    val last = entries.lastOrNull()
    if (last is ConversationEntry.Activities && !last.isSubagentGroup && last.activities.first().runId == activeRunId &&
        last.activities.any { it.visibleInGroup() && it.isRunning }) return null
    var waiting = 0
    for (entry in entries.asReversed()) {
        if (entry !is ConversationEntry.Activities || !entry.isSubagentGroup || entry.activities.first().runId != activeRunId) break
        waiting += subagentsForGroup(entry.activities, subagents).count { it.isActive }
    }
    return if (waiting > 0) waitingOnSubagentsLabel(waiting) else "Thinking"
}

/** T3 `formatWorkingTimerNow`: whole seconds under a minute, then `formatDuration` ("1m 11s"). */
internal fun workingTimerLabel(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    return if (seconds < 60) "${seconds}s" else formatSubagentDuration(seconds * 1000)
}

/** T3 `formatElapsedSeconds` (AgentElapsed): `"43s"`, `"2m 05s"`, `"1h 02m"`. */
internal fun formatAgentElapsed(elapsedMillis: Long): String {
    val seconds = elapsedMillis.coerceAtLeast(0) / 1000
    val minutes = seconds / 60
    if (minutes == 0L) return "${seconds}s"
    val hours = minutes / 60
    if (hours == 0L) return "${minutes}m ${(seconds % 60).toString().padStart(2, '0')}s"
    return "${hours}h ${(minutes % 60).toString().padStart(2, '0')}m"
}

/** T3 `STATUS_VISUALS` labels for the inline agent row. */
internal fun subagentTimelineStatusLabel(status: String): String = when (status) {
    "pending" -> "Queued"; "running" -> "Running"; "waiting" -> "Waiting"; "idle" -> "Idle · resumable"
    "completed" -> "Completed"; "failed" -> "Failed"; else -> "Stopped"
}

/** One inline agent row (T3 `SubagentTimelineLink`): the second line is the agent's activity, or its status when it has none. */
internal data class SubagentTimelineRowUi(
    val id: String, val title: String, val detail: String?, val statusLabel: String, val elapsed: String?,
    val tone: SubagentTone, val driver: String, val openThreadId: String?, val runsOn: String?,
) {
    /** The trailing status label; when there is no detail the status already fills the second line. */
    val showStatus: Boolean get() = detail != null
    val failed: Boolean get() = tone == SubagentTone.Failed
    val accessibilityLabel: String get() = listOfNotNull(title, statusLabel, elapsed, detail).joinToString(", ")
}

internal fun subagentTimelineRow(subagent: Subagent, now: Long): SubagentTimelineRowUi = SubagentTimelineRowUi(
    id = subagent.id, title = subagentRowTitle(subagent), detail = subagentWorkflowSummary(subagent) ?: subagentDetail(subagent),
    statusLabel = subagentTimelineStatusLabel(subagent.status), elapsed = subagentElapsedMs(subagent, now)?.let(::formatAgentElapsed),
    tone = subagentTone(subagent.status), driver = subagent.driver, openThreadId = subagentOpenThreadId(subagent),
    runsOn = subagent.remoteChild?.let { it.label.ifBlank { it.environmentId } },
)

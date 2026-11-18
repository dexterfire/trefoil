/* Pending background work presentation adapted from T3 Code client-runtime state/threadExecution.ts
 * (presentPendingBackgroundWork) and mobile features/threads/ThreadDetailScreen.tsx (background status) (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.PendingBackgroundTask
import com.tajchert.trefoil.core.model.backgroundWorkHoldsCompletion
import com.tajchert.trefoil.core.model.backgroundWorkKind

internal data class BackgroundWorkItemUi(val taskId: String, val kind: String, val label: String, val childThreadId: String?)

/** [waiting] is false when only unawaited commands or monitors run: the thread is done, they just keep going. */
internal data class BackgroundWorkUi(val title: String, val items: List<BackgroundWorkItemUi>, val waiting: Boolean) {
    val accessibilityLabel: String get() = "$title: ${items.joinToString(", ") { it.label }}"
    /** Details opens the Agents sheet, which lists only subagents. */
    val hasSubagents: Boolean get() = items.any { it.kind == "subagent" }
}

private data class BackgroundWorkKind(val order: Int, val singular: String, val plural: String)

private val BACKGROUND_WORK_KINDS = mapOf(
    "subagent" to BackgroundWorkKind(0, "subagent", "subagents"),
    "command" to BackgroundWorkKind(1, "command", "commands"),
    "monitor" to BackgroundWorkKind(2, "monitor", "monitors"),
    "background_task" to BackgroundWorkKind(3, "background task", "background tasks"),
)

private fun kindOf(kind: String) = BACKGROUND_WORK_KINDS.getValue(backgroundWorkKind(kind))

private fun joinWithAnd(parts: List<String>): String =
    if (parts.size <= 1) parts.joinToString("") else "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"

/** T3 `presentPendingBackgroundWork`: "Waiting on a subagent", "Waiting · <label>", "Waiting on 3 subagents and 2 commands", or "Running …". */
internal fun presentPendingBackgroundWork(tasks: List<PendingBackgroundTask>): BackgroundWorkUi? {
    if (tasks.isEmpty()) return null
    val waiting = backgroundWorkHoldsCompletion(tasks)
    val items = tasks.map { task ->
        val kind = backgroundWorkKind(task.kind)
        val description = task.description?.trim()
        val label = when {
            description == null -> null
            kind == "subagent" -> formatSubagentDisplayTitle(description).trim()
            kind == "command" || kind == "monitor" -> commandDisplayText(description)
            else -> description
        }
        BackgroundWorkItemUi(task.taskId, kind, label?.takeIf { it.isNotEmpty() } ?: kindOf(kind).singular,
            if (kind == "subagent") task.childThreadId else null)
    }.sortedBy { kindOf(it.kind).order }
    val only = items.singleOrNull()
    if (only != null) {
        val noun = kindOf(only.kind).singular
        val title = if (only.label != noun) "${if (waiting) "Waiting" else "Running"} · ${only.label}"
            else if (waiting) "Waiting on a $noun" else "Running a $noun"
        return BackgroundWorkUi(title, items, waiting)
    }
    val groups = items.groupingBy { it.kind }.eachCount().map { (kind, count) ->
        val names = kindOf(kind)
        "$count ${if (count == 1) names.singular else names.plural}"
    }
    return BackgroundWorkUi("${if (waiting) "Waiting on" else "Running"} ${joinWithAnd(groups)}", items, waiting)
}

/**
 * T3 mobile shows background work only when no active-work status does: here, when the live turn has no trailing
 * thinking row and no request waits on the user.
 */
internal fun backgroundWorkStatus(tasks: List<PendingBackgroundTask>, thinkingLabel: String?, hasRequests: Boolean): BackgroundWorkUi? =
    if (thinkingLabel != null || hasRequests) null else presentPendingBackgroundWork(tasks)

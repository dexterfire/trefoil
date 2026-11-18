/* Pending background work adapted from T3 Code contracts (OrchestrationV2PendingBackgroundTask) and
 * shared/orchestrationV2PendingBackgroundWork.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

/**
 * Work the provider still runs after the latest run ended (T3 `PendingBackgroundWorkTask`).
 * [kind] is `subagent`, `command`, `monitor` or `background_task`; absent and unknown kinds decode as `background_task`.
 */
data class PendingBackgroundTask(
    val taskId: String,
    val kind: String = BACKGROUND_TASK_KIND,
    val description: String? = null,
    /** Subagents only: the local child thread doing the work. */
    val childThreadId: String? = null,
    /** The agent declared it resumes when this work ends. */
    val awaited: Boolean = false,
)

const val BACKGROUND_TASK_KIND = "background_task"
private val BACKGROUND_WORK_KINDS = setOf("subagent", "command", "monitor", BACKGROUND_TASK_KIND)

/** T3's contract decodes absent and unknown kinds as `background_task`. */
fun backgroundWorkKind(kind: String?): String = kind?.takeIf { it in BACKGROUND_WORK_KINDS } ?: BACKGROUND_TASK_KIND

/**
 * T3 `backgroundWorkHoldsCompletion`: commands and monitors are as often a dev server or its watchdog as a wait,
 * so they hold only when the agent marks them awaited.
 */
fun backgroundWorkHoldsCompletion(tasks: List<PendingBackgroundTask>): Boolean =
    tasks.any { it.awaited || (it.kind != "command" && it.kind != "monitor") }

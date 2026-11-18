/* Pending background work derivation adapted from T3 Code shared/orchestrationV2PendingBackgroundWork.ts
 * (derivePendingBackgroundWork) and shared/orchestrationV2ThreadError.ts (latestUnheldRun) (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PendingBackgroundTask
import com.tajchert.trefoil.core.model.backgroundWorkKind
import com.tajchert.trefoil.core.protocol.obj
import com.tajchert.trefoil.core.protocol.objects
import com.tajchert.trefoil.core.protocol.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant

private val BACKGROUND_TURN_ITEM_TYPES = setOf("command_execution", "dynamic_tool", "subagent")
private val LIVE_RUN_STATUSES = setOf("preparing", "starting", "running")
// waiting is completed work awaiting checkpoints; rolled_back is excluded because provider threads are not run-tied.
private val SETTLED_FOR_BACKGROUND_WAIT_RUN_STATUSES = setOf("cancelled", "completed", "failed", "interrupted", "waiting")
private const val PULL_REQUEST_WATCH_TASK_ID = "pull-request-watch:"

private fun JsonObject.trimmed(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.runOrdinal(): Int = (get("ordinal") as? JsonPrimitive)?.intOrNull ?: 0
private fun instant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
private fun isWorkActive(status: String) = status == "pending" || status == "running" || status == "waiting"

/**
 * T3 `derivePendingBackgroundWork` for a selected-thread projection: nothing while a run is live or the latest run has not
 * settled; else the active provider thread's roster, then active background turn items, then pull request watches.
 */
internal fun pendingBackgroundWork(projection: JsonObject): List<PendingBackgroundTask> {
    val runs = projection.objects("runs")
    if (runs.any { it.string("status") in LIVE_RUN_STATUSES }) return emptyList()
    val thread = projection.obj("thread")
    val pullRequests = pullRequestWatchTasks(thread.objects("pullRequests"))
    val latestRun = latestUnheldRun(runs) ?: return pullRequests
    if (latestRun.string("status") !in SETTLED_FOR_BACKGROUND_WAIT_RUN_STATUSES) return emptyList()

    val byTaskId = LinkedHashMap<String, PendingBackgroundTask>()
    val activeProviderThreadId = thread.trimmed("activeProviderThreadId")
    val providerThreads = projection.objects("providerThreads")
        .filter { activeProviderThreadId == null || it.string("id") == activeProviderThreadId }
    for (providerThread in providerThreads) {
        for (task in providerThread.objects("pendingBackgroundTasks")) {
            val taskId = task.trimmed("taskId") ?: continue
            if (taskId in byTaskId) continue
            val kind = backgroundWorkKind(task.trimmed("kind"))
            byTaskId[taskId] = PendingBackgroundTask(
                taskId = taskId, kind = kind, description = task.trimmed("description"),
                childThreadId = if (kind == "subagent") task.trimmed("childThreadId") else null,
                awaited = task.flag("awaited") == true,
            )
        }
    }

    val rolledBack = runs.filter { it.string("status") == "rolled_back" }.map { it.string("id") }.toSet()
    for (item in projection.objects("turnItems")) {
        val type = item.string("type")
        if (type !in BACKGROUND_TURN_ITEM_TYPES || !isWorkActive(item.string("status"))) continue
        if (type == "dynamic_tool" && (item["input"] as? JsonObject)?.flag("persistent") == true) continue
        if (item.string("runId") in rolledBack) continue
        val taskId = item.obj("nativeItemRef").string("nativeId").ifEmpty { item.string("id") }
        if (taskId.isEmpty() || taskId in byTaskId) continue
        byTaskId[taskId] = pendingTaskFromTurnItem(taskId, type, item)
    }

    for (task in pullRequests) byTaskId[task.taskId] = task
    return byTaskId.values.toList()
}

private fun pendingTaskFromTurnItem(taskId: String, type: String, item: JsonObject): PendingBackgroundTask {
    val description = item.trimmed("title") ?: when (type) {
        "command_execution" -> item.trimmed("input")
        "dynamic_tool" -> item.trimmed("toolName")
        "subagent" -> item.trimmed("prompt")
        else -> null
    }
    return when (type) {
        "subagent" -> PendingBackgroundTask(taskId, "subagent", description, childThreadId = item.trimmed("childThreadId"))
        "command_execution" -> PendingBackgroundTask(taskId, if (item.flag("monitor") == true) "monitor" else "command", description)
        else -> PendingBackgroundTask(taskId, "background_task", description)
    }
}

/** T3 `latestUnheldRun`: the highest-ordinal run, skipping held queue entries and runs a steer cancelled before they started. */
internal fun latestUnheldRun(runs: List<JsonObject>): JsonObject? = runs
    .filterNot { it.string("status") == "queued" && it.flag("queueHeld") == true }
    .filterNot { outlivedBeforeStart(it, runs) }
    .fold<JsonObject, JsonObject?>(null) { latest, run -> if (latest == null || run.runOrdinal() > latest.runOrdinal()) run else latest }

private fun outlivedBeforeStart(run: JsonObject, runs: List<JsonObject>): Boolean {
    if (run.string("status") != "cancelled" || run.trimmed("startedAt") != null) return false
    val cancelledAt = run.trimmed("completedAt")?.let(::instant) ?: return false
    return runs.any { earlier ->
        earlier.runOrdinal() < run.runOrdinal() && earlier.string("status") != "queued" &&
            (earlier.trimmed("completedAt")?.let(::instant)?.let { it >= cancelledAt } ?: true)
    }
}

/** A merged or dismissed pull request's watch holds nothing; one whose checks all finished stays listed but not awaited. */
private fun pullRequestWatchTasks(pullRequests: List<JsonObject>): List<PendingBackgroundTask> = pullRequests.mapNotNull { link ->
    val watch = link["watch"] as? JsonObject ?: return@mapNotNull null
    if (link.string("source") == "stack-dismissed" || link.obj("snapshot").string("state") == "merged") return@mapNotNull null
    val number = (link["number"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    PendingBackgroundTask(
        taskId = "$PULL_REQUEST_WATCH_TASK_ID${link.string("host").lowercase()}/${link.string("repository").lowercase()}#$number",
        kind = "monitor", description = "Watching pull request #$number",
        awaited = watch.flag("checksPending") != false,
    )
}

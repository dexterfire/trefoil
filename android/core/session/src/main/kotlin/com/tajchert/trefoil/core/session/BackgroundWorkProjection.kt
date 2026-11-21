/* Pending background work derivation adapted from T3 Code shared/orchestrationV2PendingBackgroundWork.ts
 * (derivePendingBackgroundWork) and shared/orchestrationV2ThreadError.ts (latestUnheldRun,
 * usageLimitRunPresentedAsLatest) (MIT).
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
internal const val PULL_REQUEST_WATCH_TASK_ID = "pull-request-watch:"

private fun JsonObject.trimmed(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
internal fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.runOrdinal(): Int = (get("ordinal") as? JsonPrimitive)?.intOrNull ?: 0
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
    val pullRequests = pullRequestWatchTasks(thread)
    // As T3's shell: a usage-limited run stays latest while the runs queued after it never started.
    val latestRun = usageLimitRunPresentedAsLatest(runs, projection.objects("turnItems"), providerSessionError(projection))
        ?: latestUnheldRun(runs) ?: return pullRequests
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

/** The latest session error of the thread's selected provider instance, as T3 server shells read it. */
internal fun providerSessionError(projection: JsonObject): String? {
    val instanceId = projection.obj("thread").string("providerInstanceId")
    return projection.objects("providerSessions").filter { it.string("providerInstanceId") == instanceId }
        .sortedBy { it.trimmed("updatedAt")?.let(::instant) ?: Long.MIN_VALUE }.lastOrNull()?.trimmed("lastError")
}

private fun endMillis(run: JsonObject): Long = run.trimmed("completedAt")?.let(::instant) ?: Long.MAX_VALUE

/** T3 `latestExecutedRun` with `runRanAfter`: held queues can execute out of ordinal order; unfinished runs count as latest. */
private fun latestExecutedRun(runs: List<JsonObject>): JsonObject? = runs
    .filterNot { it.string("status") == "queued" || (it.string("status") == "cancelled" && it.trimmed("startedAt") == null) }
    .fold<JsonObject, JsonObject?>(null) { latest, run ->
        if (latest == null) run
        else if (endMillis(run) == endMillis(latest)) (if (run.runOrdinal() > latest.runOrdinal()) run else latest)
        else if (endMillis(run) > endMillis(latest)) run else latest
    }

/** T3 `latestRootProviderFailure`: the newest failed root `error` item of a failed run. */
private fun latestRootProviderFailure(run: JsonObject, turnItems: List<JsonObject>): JsonObject? {
    if (run.string("status") != "failed") return null
    return turnItems.filter {
        it.string("type") == "error" && it.string("status") == "failed" && it.string("runId") == run.string("id") &&
            it.string("nodeId") == run.string("rootNodeId")
    }.maxWithOrNull(compareBy<JsonObject>({ it.trimmed("updatedAt")?.let(::instant) ?: Long.MIN_VALUE }, { it.runOrdinal() }, { it.string("id") }))
        ?.get("failure") as? JsonObject
}

/** T3 `usageLimitRunPresentedAsLatest`: a run blocked on a usage limit, while higher-ordinal runs exist. */
internal fun usageLimitRunPresentedAsLatest(runs: List<JsonObject>, turnItems: List<JsonObject>, sessionError: String?): JsonObject? {
    val executed = latestExecutedRun(runs) ?: return null
    if (executed.string("status") != "failed") return null
    val failure = latestRootProviderFailure(executed, turnItems)
    // threadErrorSummary: a different live session error supersedes the recorded failure.
    val current = if (sessionError != null && sessionError != failure?.trimmed("message")) null else failure
    if (current?.string("class") != "usage_limit") return null
    return executed.takeIf { blocked -> runs.any { it.runOrdinal() > blocked.runOrdinal() } }
}

/**
 * T3 `pullRequestWatchTasks`: every visible link with a live watch (merged included). Once every check finished a person
 * moves the PR next, so it is listed but not awaited, unless the watch runs until merge; a merged PR's watch holds.
 */
private fun pullRequestWatchTasks(thread: JsonObject): List<PendingBackgroundTask> = watchedPullRequests(thread).mapNotNull { link ->
    val watch = link.obj("watch")
    val number = (link["number"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
    PendingBackgroundTask(
        taskId = "$PULL_REQUEST_WATCH_TASK_ID${link.string("host").lowercase()}/${link.string("repository").lowercase()}#$number",
        kind = "monitor", description = "Watching pull request #$number",
        awaited = watch.flag("checksPending") != false || watch.string("until") == "merged" || link.obj("snapshot").string("state") == "merged",
    )
}

/* Queue, steer and stop derivation adapted from T3 Code (MIT): packages/client-runtime/src/state/threadWorkflows.ts
 * (getUserQueuedThreadRuns, deriveThreadQueueWorkflowState), packages/client-runtime/src/state/composerDispatch.ts
 * (compactionBlocksSteering), apps/mobile/src/state/use-thread-composer-state.ts and
 * packages/client-runtime/src/operations/commands.ts (interruptThreadTurn).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PendingBackgroundTask
import com.tajchert.trefoil.core.model.QueuedMessage
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.protocol.obj
import com.tajchert.trefoil.core.protocol.objects
import com.tajchert.trefoil.core.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

private val ACTIVE_RUN_STATUSES = setOf("preparing", "starting", "running", "waiting")
private val TERMINAL_ITEM_STATUSES = setOf("completed", "failed", "interrupted", "cancelled")
private const val COMPACT_COMMAND = "/compact"
internal const val QUEUED_MESSAGE_FALLBACK = "Queued message"

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.present(key: String): Boolean = get(key).let { it != null && it != JsonNull }
private fun JsonObject.runOrdinal(): Int = (get("ordinal") as? JsonPrimitive)?.intOrNull ?: 0
private fun JsonObject.noAttachments(): Boolean = (get("attachments") as? JsonArray).isNullOrEmpty()

/** T3 `resolveActiveThreadRun`: the last run (wire order) still preparing, starting, running or waiting. */
internal fun activeThreadRun(projection: JsonObject): JsonObject? =
    projection.objects("runs").lastOrNull { it.string("status") in ACTIVE_RUN_STATUSES }

/**
 * T3 `getUserQueuedThreadRuns` in `deriveThreadQueueWorkflowState` order: `queued` runs, without delegated-completion
 * and notification wakes, sorted by `queuePosition ?? ordinal` then `ordinal`. The text is the queued message's (a guest
 * message shows the guest's words), else [QUEUED_MESSAGE_FALLBACK].
 */
internal fun queuedMessages(projection: JsonObject): List<QueuedMessage> {
    val runs = projection.objects("runs").filter { it.string("status") == "queued" }
    if (runs.isEmpty()) return emptyList()
    val messages = projection.objects("messages")
    val automatic = messages.filter { it.present("delegatedCompletion") || it.present("notification") }.map { it.string("id") }.toSet()
    return runs.filter { it.string("userMessageId") !in automatic }
        .sortedWith(compareBy<JsonObject>({ (it["queuePosition"] as? JsonPrimitive)?.intOrNull ?: it.runOrdinal() }, { it.runOrdinal() }))
        .map { run ->
            val messageId = run.string("userMessageId")
            val message = messages.firstOrNull { it.string("id") == messageId }
            val guest = message?.obj("context")?.obj("guest")?.get("text") as? JsonPrimitive
            val text = guest?.takeIf { it.isString }?.content ?: (message?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
            QueuedMessage(run.string("id"), messageId, text ?: QUEUED_MESSAGE_FALLBACK, held = run.flag("queueHeld") == true)
        }
}

/** T3 `isHeld`: any queued run (wakes included) is held by a stop or restart until `queue.resume`. */
internal fun queueHeld(projection: JsonObject): Boolean =
    projection.objects("runs").any { it.string("status") == "queued" && it.flag("queueHeld") == true }

private fun isCompactionCommand(message: JsonObject): Boolean = message.string("role") == "user" &&
    message.string("text").trim().lowercase() == COMPACT_COMMAND && message.noAttachments()

/**
 * T3 mobile `canSteerActiveTurn`: `canPromoteToSteer` (the provider session's `capabilities.turns`
 * `supportsActiveSteering` / `supportsSteeringByInterruptRestart`; a setup-phase run with no known session may steer)
 * and not blocked by a running `/compact` or compaction ([compactionBlocksSteering]).
 */
internal fun canSteer(projection: JsonObject): Boolean {
    val activeRun = activeThreadRun(projection) ?: return false
    val turns = providerSession(projection)?.obj("capabilities")?.get("turns") as? JsonObject
    val messages = projection.objects("messages")
    val activeMessage = messages.firstOrNull { it.string("id") == activeRun.string("userMessageId") }
    val promoteSkipsCompaction = activeMessage != null && isCompactionCommand(activeMessage)
    val attemptId = activeRun.string("activeAttemptId")
    val steerableTurn = activeRun.string("status") == "running" && attemptId.isNotEmpty() &&
        projection.objects("providerTurns").any { it.string("runAttemptId") == attemptId && it.string("status") == "running" }
    val steers = turns?.flag("supportsActiveSteering") == true || turns?.flag("supportsSteeringByInterruptRestart") == true
    val canPromote = promoteSkipsCompaction ||
        (activeRun.string("status") in setOf("preparing", "starting") && (turns == null || steers)) ||
        (steerableTurn && steers)
    return canPromote && !compactionBlocksSteering(projection, activeRun, turns?.flag("activeSteeringDuringCompaction") == true)
}

/** T3 `compactionBlocksSteering` with mobile's `compactCommandIsActive` / `compactionRunning` for [activeRun]. */
private fun compactionBlocksSteering(projection: JsonObject, activeRun: JsonObject, steersDuringCompaction: Boolean): Boolean {
    val runId = activeRun.string("id")
    val items = presentationItems(projection).filter { it.string("runId") == runId }
    val compactCommandIsActive = items.lastOrNull {
        it.string("type") == "user_message" && it.string("text").trim().lowercase() == COMPACT_COMMAND && it.noAttachments()
    } != null && items.none { it.string("type") == "compaction" && it.string("status") in TERMINAL_ITEM_STATUSES }
    if (compactCommandIsActive) return true
    if (items.none { it.string("type") == "compaction" && it.string("status") == "running" }) return false
    if (!steersDuringCompaction) return true
    val message = projection.objects("messages").firstOrNull { it.string("id") == activeRun.string("userMessageId") }
    return message != null && message.noAttachments() && Regex("^/compact(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(message.string("text").trim())
}

/**
 * Something on the selected thread can be stopped: a live run (`activeRunId`), pending background work, or an active
 * subagent that outlived the parent's run (T3 desktop shows Stop for all three).
 */
internal fun canStopThread(activeRunId: String?, backgroundWork: List<PendingBackgroundTask>, subagents: List<Subagent>): Boolean =
    activeRunId != null || backgroundWork.isNotEmpty() || subagents.any { it.isActive }

/**
 * T3 `interruptThreadTurn` without `threadStop`: the run `run.interrupt {holdQueue: true}` targets. The last live run,
 * else the run pending background work hangs off (pull request watches excluded: they are stopped by unwatching).
 */
internal fun stopRunTarget(projection: JsonObject): String? {
    activeThreadRun(projection)?.string("id")?.takeIf { it.isNotEmpty() }?.let { return it }
    if (pendingBackgroundWork(projection).none { !it.taskId.startsWith(PULL_REQUEST_WATCH_TASK_ID) }) return null
    val runs = projection.objects("runs")
    val latest = usageLimitRunPresentedAsLatest(runs, projection.objects("turnItems"), providerSessionError(projection)) ?: latestUnheldRun(runs)
    return latest?.string("id")?.takeIf { it.isNotEmpty() }
}

/** T3 stops pull request watches with `thread.pull-request.watch {host, repository, number, watching: false}`. */
internal fun pullRequestUnwatches(projection: JsonObject): List<JsonObject> = projection.obj("thread").objects("pullRequests").mapNotNull { link ->
    if (link["watch"] !is JsonObject || link.string("source") == "stack-dismissed" || link.obj("snapshot").string("state") == "merged") return@mapNotNull null
    val number = link["number"] as? JsonPrimitive ?: return@mapNotNull null
    JsonObject(mapOf("host" to JsonPrimitive(link.string("host")), "repository" to JsonPrimitive(link.string("repository")),
        "number" to number, "watching" to JsonPrimitive(false)))
}

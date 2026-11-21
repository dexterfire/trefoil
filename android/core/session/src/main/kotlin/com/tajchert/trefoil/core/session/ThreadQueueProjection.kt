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

private val TERMINAL_ITEM_STATUSES = setOf("completed", "failed", "interrupted", "cancelled")
private const val COMPACT_COMMAND = "/compact"
private val COMPACT_PREFIX = Regex("^/compact(?:\\s|$)", RegexOption.IGNORE_CASE)
private val SETUP_RUN_STATUSES = setOf("preparing", "starting")
internal const val QUEUED_MESSAGE_FALLBACK = "Queued message"

private fun JsonObject.present(key: String): Boolean = get(key).let { it != null && it != JsonNull }
private fun JsonObject.noAttachments(): Boolean = (get("attachments") as? JsonArray).isNullOrEmpty()

/** T3 `resolveActiveThreadRun`: the last run (wire order) still preparing, starting, running or waiting. */
internal fun activeThreadRun(projection: JsonObject): JsonObject? =
    projection.objects("runs").lastOrNull { it.string("status") in ACTIVE_RUN_STATUSES }

/**
 * T3 `getUserQueuedThreadRuns` in `deriveThreadQueueWorkflowState` order: `queued` runs, without delegated-completion
 * and notification wakes, sorted by `queuePosition ?? ordinal` then `ordinal`. The text is the queued message's (a guest
 * message shows the guest's words), else [QUEUED_MESSAGE_FALLBACK]. [QueuedMessage.canSendNow] is [canSendQueuedNow].
 */
internal fun queuedMessages(projection: JsonObject, promotion: SteerPromotion? = steerPromotion(projection)): List<QueuedMessage> {
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
            QueuedMessage(run.string("id"), messageId, text ?: QUEUED_MESSAGE_FALLBACK, canSendNow = canSendQueuedNow(projection, run, promotion))
        }
}

/**
 * T3 "send now" eligibility for one queued [run]: with no active run, `canSendNow` (thread neither archived nor deleted)
 * for `queued-run.start`; with one, `canPromoteToSteer`, and the server's `queued-message.promote-to-steer` rule that a
 * `freshContext` message cannot steer unless the target is a `/compact` turn (which the server skips instead).
 */
internal fun canSendQueuedNow(projection: JsonObject, run: JsonObject, promotion: SteerPromotion? = steerPromotion(projection)): Boolean {
    if (promotion == null) {
        val thread = projection.obj("thread")
        return !thread.present("archivedAt") && !thread.present("deletedAt")
    }
    return promotion.allowed && (promotion.skipsCompaction || run.flag("freshContext") != true)
}

/** T3 `isHeld`: any queued run (wakes included) is held by a stop or restart until `queue.resume`. */
internal fun queueHeld(projection: JsonObject): Boolean =
    projection.objects("runs").any { it.string("status") == "queued" && it.flag("queueHeld") == true }

private fun isCompactionCommand(message: JsonObject): Boolean = message.string("role") == "user" &&
    isCompactText(message.string("text")) && message.noAttachments()

private fun isCompactText(text: String): Boolean = text.trim().lowercase() == COMPACT_COMMAND

internal class SteerPromotion(val activeRun: JsonObject, val turns: JsonObject?, val allowed: Boolean, val skipsCompaction: Boolean)

/**
 * T3 `canPromoteToSteer` for the active run (null when none): the provider session's `capabilities.turns`
 * `supportsActiveSteering` / `supportsSteeringByInterruptRestart`, a setup-phase run with no known session, or a
 * `/compact` turn (`promoteSkipsCompaction`).
 */
internal fun steerPromotion(projection: JsonObject): SteerPromotion? {
    val activeRun = activeThreadRun(projection) ?: return null
    val turns = providerSession(projection)?.obj("capabilities")?.get("turns") as? JsonObject
    val activeMessage = projection.objects("messages").firstOrNull { it.string("id") == activeRun.string("userMessageId") }
    val skipsCompaction = activeMessage != null && isCompactionCommand(activeMessage)
    val attemptId = activeRun.string("activeAttemptId")
    val steerableTurn = activeRun.string("status") == "running" && attemptId.isNotEmpty() &&
        projection.objects("providerTurns").any { it.string("runAttemptId") == attemptId && it.string("status") == "running" }
    val steers = turns?.flag("supportsActiveSteering") == true || turns?.flag("supportsSteeringByInterruptRestart") == true
    val allowed = skipsCompaction ||
        (activeRun.string("status") in SETUP_RUN_STATUSES && (turns == null || steers)) ||
        (steerableTurn && steers)
    return SteerPromotion(activeRun, turns, allowed, skipsCompaction)
}

/**
 * T3 mobile `canSteerActiveTurn` (the composer's Steer): [steerPromotion] allowed and not blocked by a running
 * `/compact` or compaction ([compactionBlocksSteering]).
 */
internal fun canSteer(projection: JsonObject, steerPromotion: SteerPromotion? = steerPromotion(projection), items: List<JsonObject>? = null): Boolean {
    val promotion = steerPromotion?.takeIf { it.allowed } ?: return false
    return !compactionBlocksSteering(projection, items ?: presentationItems(projection), promotion.activeRun, promotion.turns?.flag("activeSteeringDuringCompaction") == true)
}

/** T3 `compactionBlocksSteering` with mobile's `compactCommandIsActive` / `compactionRunning` for [activeRun]. */
private fun compactionBlocksSteering(projection: JsonObject, allItems: List<JsonObject>, activeRun: JsonObject, steersDuringCompaction: Boolean): Boolean {
    val runId = activeRun.string("id")
    val items = allItems.filter { it.string("runId") == runId }
    val compactCommandIsActive = items.lastOrNull {
        it.string("type") == "user_message" && isCompactText(it.string("text")) && it.noAttachments()
    } != null && items.none { it.string("type") == "compaction" && it.string("status") in TERMINAL_ITEM_STATUSES }
    if (compactCommandIsActive) return true
    if (items.none { it.string("type") == "compaction" && it.string("status") == "running" }) return false
    if (!steersDuringCompaction) return true
    val message = projection.objects("messages").firstOrNull { it.string("id") == activeRun.string("userMessageId") }
    return message != null && message.noAttachments() && COMPACT_PREFIX.containsMatchIn(message.string("text").trim())
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

/** T3 `visibleThreadPullRequests` with a live `watch` (a merged link's watch still counts until it is turned off). */
internal fun watchedPullRequests(thread: JsonObject): List<JsonObject> =
    thread.objects("pullRequests").filter { it["watch"] is JsonObject && it.string("source") != "stack-dismissed" }

/** T3 stops pull request watches with `thread.pull-request.watch {host, repository, number, watching: false}`. */
internal fun pullRequestUnwatches(projection: JsonObject): List<JsonObject> = watchedPullRequests(projection.obj("thread")).mapNotNull { link ->
    val number = link["number"] as? JsonPrimitive ?: return@mapNotNull null
    JsonObject(mapOf("host" to JsonPrimitive(link.string("host")), "repository" to JsonPrimitive(link.string("repository")),
        "number" to number, "watching" to JsonPrimitive(false)))
}

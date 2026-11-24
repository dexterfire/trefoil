package com.tajchert.trefoil.core.model

/**
 * Agent awareness: which threads deserve a notification, derived from shell rows only. Ported from T3 Code
 * `packages/shared/src/agentAwareness.ts` and the alert rules of its relay (MIT, see `protocol/T3-LICENSE.txt`).
 * Everything here is pure; thread titles are conversation content and are never logged.
 */

enum class AwarenessPhase { Starting, Running, WaitingForApproval, WaitingForInput, Completed, Failed }

enum class AgentAlertKind { Approval, Input, Finished, Failed }

/** One thing to tell the user about [threadId]; [threadTitle] may be blank. */
data class AgentAlert(
    val environmentId: String, val threadId: String, val kind: AgentAlertKind,
    val threadTitle: String, val projectId: String,
)

/** Edge-trigger memory between observations. Opaque to callers; start from `AgentAlertTrack()`. */
data class AgentAlertTrack(
    val environmentId: String? = null,
    /** Null until a fresh snapshot was adopted, so nothing fires at first load, reconnect or environment switch. */
    val phases: Map<String, AwarenessPhase>? = null,
    /** Top-level threads seen starting or running since the baseline; only these may alert as finished or failed. */
    val worked: Set<String> = emptySet(),
    /** Completions waiting out the confirm delay: thread id to the time they were first seen, epoch millis. */
    val pendingCompletions: Map<String, Long> = emptyMap(),
    /** Threads with an alert that was returned and not cleared yet, by kind. */
    val alerted: Map<String, AgentAlertKind> = emptyMap(),
)

/**
 * Result of one observation. Post [alerts], cancel the notifications of the thread ids in [clear], and observe
 * again at [recheckAtMillis] (epoch millis) even if the state does not change.
 */
data class AgentAlertStep(
    val track: AgentAlertTrack, val alerts: List<AgentAlert> = emptyList(),
    val clear: Set<String> = emptySet(), val recheckAtMillis: Long? = null,
)

/** How long a completion must hold before it alerts; a run can end just before its continuation starts. */
const val AGENT_COMPLETION_CONFIRM_MS = 5_000L

/**
 * The awareness phase of top-level [thread] given all shell rows in [threads], or null when there is nothing to show.
 * T3 `projectThreadAwarenessV2` plus the relay's archive filter, newest waiting subagent and `snoozedCompletion`.
 * [now] is unused: T3 judges a snooze at the completion time, not the observation time.
 */
@Suppress("UNUSED_PARAMETER")
fun threadAwareness(thread: ThreadSummary, threads: List<ThreadSummary>, now: Long): AwarenessPhase? =
    awarenessOf(thread, waitingChildKinds(threads)[thread.id])

/**
 * One observation of the client state. [connected] is a live connection and [fresh] means the shell snapshot is
 * current, not the cache kept across a reconnect. Rules follow T3's relay alerts: only a move into an attention phase
 * alerts, and a terminal phase alerts only for a thread seen working since the baseline, a completion only once it held
 * for [AGENT_COMPLETION_CONFIRM_MS]. A thread that gets a new alert in this step is never also in `clear`; its new
 * notification replaces the old one.
 */
fun agentAlertStep(
    track: AgentAlertTrack, environmentId: String?, connected: Boolean, fresh: Boolean,
    threads: List<ThreadSummary>, now: Long,
): AgentAlertStep {
    if (environmentId == null || !connected || environmentId != track.environmentId) {
        val reset = AgentAlertTrack(environmentId)
        val clear = track.alerted.keys
        // A switch straight into a live, fresh snapshot adopts it now; the next change may be the one that alerts.
        if (environmentId == null || !connected || !fresh) return AgentAlertStep(reset, clear = clear)
        return AgentAlertStep(adopt(reset, threads), clear = clear)
    }
    val previous = track.phases ?: return if (fresh) AgentAlertStep(adopt(track, threads)) else AgentAlertStep(track)

    val children = waitingChildKinds(threads)
    val rows = threads.distinctBy { it.id }
    val phases = LinkedHashMap<String, AwarenessPhase>()
    val worked = HashSet<String>()
    val pending = LinkedHashMap<String, Long>()
    val alerted = LinkedHashMap<String, AgentAlertKind>()
    val alerts = ArrayList<AgentAlert>()
    val clear = LinkedHashSet<String>()
    for (row in rows) {
        val id = row.id
        val before = previous[id]
        val phase = awarenessOf(row, children[id])
        if (phase != null) phases[id] = phase
        var working = id in track.worked || phase == AwarenessPhase.Starting || phase == AwarenessPhase.Running
        var kind: AgentAlertKind? = null
        when (phase) {
            AwarenessPhase.WaitingForApproval, AwarenessPhase.WaitingForInput -> if (!before.isAttention()) {
                kind = if (phase == AwarenessPhase.WaitingForInput) AgentAlertKind.Input else AgentAlertKind.Approval
            }
            // T3 alerts terminal moves from a non-terminal phase only; Completed -> Failed reaches here only while that
            // completion is still unconfirmed, because a confirmed one already took the thread out of `worked`.
            AwarenessPhase.Failed -> if (before != AwarenessPhase.Failed && working) { kind = AgentAlertKind.Failed; working = false }
            AwarenessPhase.Completed -> if (working) {
                val firstSeen = track.pendingCompletions[id] ?: now.takeIf { before != AwarenessPhase.Completed }
                if (firstSeen != null) {
                    if (now >= firstSeen + AGENT_COMPLETION_CONFIRM_MS) { kind = AgentAlertKind.Finished; working = false }
                    else pending[id] = firstSeen
                }
            }
            else -> Unit
        }
        if (working) worked += id
        val old = track.alerted[id]
        if (kind != null) {
            alerts += AgentAlert(environmentId, id, kind, row.title, row.projectId)
            alerted[id] = kind
        } else if (old != null) {
            if (old.matches(phase)) alerted[id] = old else clear += id
        }
    }
    // Alerts of threads gone from the list (deleted, or dropped from the shell).
    track.alerted.keys.forEach { if (it !in alerted) clear += it }
    val recheck = pending.values.minOrNull()?.plus(AGENT_COMPLETION_CONFIRM_MS)
    return AgentAlertStep(AgentAlertTrack(environmentId, phases, worked, pending, alerted), alerts, clear, recheck)
}

/** First fresh snapshot: remember phases without alerting; threads already starting or running count as working. */
private fun adopt(track: AgentAlertTrack, threads: List<ThreadSummary>): AgentAlertTrack {
    val children = waitingChildKinds(threads)
    val phases = LinkedHashMap<String, AwarenessPhase>()
    threads.distinctBy { it.id }.forEach { row -> awarenessOf(row, children[row.id])?.let { phases[row.id] = it } }
    val worked = phases.filterValues { it == AwarenessPhase.Starting || it == AwarenessPhase.Running }.keys
    return track.copy(phases = phases, worked = worked, pendingCompletions = emptyMap(), alerted = emptyMap())
}

private fun AwarenessPhase?.isAttention() = this == AwarenessPhase.WaitingForApproval || this == AwarenessPhase.WaitingForInput

/** An approval that became an input wait (or back) is still the same attention; T3 alerts only the move into it. */
private fun AgentAlertKind.matches(phase: AwarenessPhase?) = when (this) {
    AgentAlertKind.Approval, AgentAlertKind.Input -> phase.isAttention()
    AgentAlertKind.Finished -> phase == AwarenessPhase.Completed
    AgentAlertKind.Failed -> phase == AwarenessPhase.Failed
}

private fun ThreadSummary.isListedLineage() = relationshipToParent != "subagent" && !sideChat && !workflowStep

/** T3 `subagentAwaitsUser`, plus the relay's archive check. */
private fun ThreadSummary.subagentAwaitsUser(): Boolean {
    val kind = pendingRequestKind ?: return false
    return kind != "auth_refresh" && (kind != "user_input" || pendingRequestUserOnly) &&
        relationshipToParent == "subagent" && !isProviderNativeSubagentThread() && activityRunStatus != null &&
        archivedAt == null && !archived && deletedAt == null
}

/**
 * Top-level thread id to the request kind of its newest waiting subagent (relay `currentSubagentRequestFor`). A child
 * maps to the ancestor reached by walking `parentThreadId` through subagents and side chats; a missing parent or a
 * cycle drops it. Among several, the newest `updatedAt` wins and the first listed breaks ties.
 */
private fun waitingChildKinds(threads: List<ThreadSummary>): Map<String, String> {
    val byId = HashMap<String, ThreadSummary>()
    threads.forEach { byId.putIfAbsent(it.id, it) }
    val newest = HashMap<String, Pair<Long, String>>()
    for (child in threads) {
        if (!child.subagentAwaitsUser()) continue
        val top = topLevelOf(child, byId) ?: continue
        val updated = epochMillis(child.updatedAt) ?: Long.MIN_VALUE
        val current = newest[top]
        if (current == null || updated > current.first) newest[top] = updated to child.pendingRequestKind!!
    }
    return newest.mapValues { it.value.second }
}

private fun topLevelOf(child: ThreadSummary, byId: Map<String, ThreadSummary>): String? {
    val seen = hashSetOf(child.id)
    var current = child
    while (current.relationshipToParent == "subagent" || current.sideChat) {
        val parentId = current.parentThreadId ?: return null
        if (!seen.add(parentId)) return null
        current = byId[parentId] ?: return null
    }
    return current.id
}

private fun awarenessOf(thread: ThreadSummary, childKind: String?): AwarenessPhase? {
    if (!thread.isListedLineage() || thread.archivedAt != null || thread.archived || thread.deletedAt != null) return null
    val runStatus = thread.activityRunStatus ?: thread.status
    val phase = when {
        thread.pendingRequestKind == "user_input" -> AwarenessPhase.WaitingForInput
        thread.pendingRequestKind != null && thread.pendingRequestKind != "auth_refresh" -> AwarenessPhase.WaitingForApproval
        childKind != null -> if (childKind == "user_input") AwarenessPhase.WaitingForInput else AwarenessPhase.WaitingForApproval
        else -> when (runStatus) {
            "preparing", "starting" -> AwarenessPhase.Starting
            "running", "waiting" -> AwarenessPhase.Running
            "completed" -> if (thread.completionHeldBackgroundWork || thread.monitorHoldsWakeCompletion) AwarenessPhase.Running else AwarenessPhase.Completed
            "failed" -> AwarenessPhase.Failed
            else -> null
        }
    } ?: return null
    if (phase == AwarenessPhase.Completed && thread.latestRunStopRequested) return null
    if (thread.settledOverride == "settled" && !thread.completionHeldBackgroundWork &&
        (phase == AwarenessPhase.Completed || phase == AwarenessPhase.Failed || (phase == AwarenessPhase.Running && runStatus == "completed"))
    ) return null
    if (phase == AwarenessPhase.Completed && snoozedCompletion(thread)) return null
    return phase
}

/** Relay `snoozedCompletion`: the completion happened inside a snooze set before it. */
private fun snoozedCompletion(thread: ThreadSummary): Boolean {
    val completedAt = epochMillis(thread.latestRunCompletedAt) ?: return false
    val snoozedAt = epochMillis(thread.snoozedAt)
    if (snoozedAt != null && completedAt < snoozedAt) return false
    return isSnoozedAt(thread, completedAt, snoozedAt)
}

/** T3 server `isSnoozed(thread, at)`; an unreadable timestamp counts as awake, so a notification is not lost. */
private fun isSnoozedAt(thread: ThreadSummary, at: Long, snoozedAt: Long?): Boolean {
    val until = epochMillis(thread.snoozedUntil) ?: return false
    if (until <= at) return false
    if (thread.pendingGuestMessageCount > 0) return false
    if (thread.pendingRequestKind != null) {
        val created = epochMillis(thread.pendingRequestCreatedAt)
        if (thread.pendingRequestKind != "user_input" || snoozedAt == null || created == null || created > snoozedAt) return false
    }
    val completedAt = epochMillis(thread.latestRunCompletedAt)
    val wokeOnError = thread.status == "failed" && (snoozedAt == null || (completedAt != null && completedAt > snoozedAt))
    return !wokeOnError
}

private fun epochMillis(value: String?): Long? =
    value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

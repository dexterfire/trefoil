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

/**
 * What a thread waits on: an approval or input [kind] and the deciding request (the thread's own, or its newest waiting
 * subagent's). A null [requestId] matches any request of the same kind.
 */
data class AgentAttention(val kind: AgentAlertKind, val requestId: String?) {
    fun sameAs(other: AgentAttention?) =
        other != null && kind == other.kind && (requestId == null || other.requestId == null || requestId == other.requestId)
}

/** Edge-trigger memory between observations. Opaque to callers; start from `AgentAlertTrack()`. */
data class AgentAlertTrack(
    val environmentId: String? = null,
    /** Null until a fresh snapshot was adopted, so nothing fires at first load, reconnect or environment switch. */
    val phases: Map<String, AwarenessPhase>? = null,
    /** Top-level threads seen working (or waiting during a run) since the baseline; only these may alert as finished or failed. */
    val worked: Set<String> = emptySet(),
    /** Completions waiting out the confirm delay: thread id to the time they were first seen, epoch millis. */
    val pendingCompletions: Map<String, Long> = emptyMap(),
    /** Threads with an alert that was returned and not cleared yet, by kind. */
    val alerted: Map<String, AgentAlertKind> = emptyMap(),
    /** Which completion each [pendingCompletions] entry is (`latestRunId` and `latestRunCompletedAt`). */
    val pendingCompletionRuns: Map<String, String> = emptyMap(),
    /** The attention of every thread observed waiting at the last observation. */
    val attention: Map<String, AgentAttention> = emptyMap(),
    /** The attention behind each approval or input entry of [alerted]. */
    val alertedAttention: Map<String, AgentAttention> = emptyMap(),
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

/** T3 relay `TERMINAL_NOTIFICATION_FRESHNESS_MS`: an older finish or failure is history, not news. */
private const val TERMINAL_FRESHNESS_MS = 2 * 60 * 1_000L

/** Stale rows hold a due completion back; tick again after this instead of immediately. */
private const val STALE_RECHECK_MS = 1_000L

/**
 * The awareness phase of top-level [thread] given all shell rows in [threads], or null when there is nothing to show.
 * T3 `projectThreadAwarenessV2` plus the relay's archive filter, newest waiting subagent and `snoozedCompletion`.
 * [now] is unused: T3 judges a snooze at the completion time, not the observation time.
 */
@Suppress("UNUSED_PARAMETER")
fun threadAwareness(thread: ThreadSummary, threads: List<ThreadSummary>, now: Long): AwarenessPhase? =
    awarenessOf(thread, waitingChildren(threads)[thread.id]?.kind)

/**
 * One observation of the client state. [connected] is a live connection and [fresh] means the shell snapshot is
 * current, not the cache kept across a reconnect. Rules follow T3's relay alerts:
 * - a move into attention, or to another request or kind while waiting, alerts;
 * - a terminal phase alerts only for a thread seen working since the baseline and only while its completion time is
 *   at most two minutes old, a completion only once the same completion held for [AGENT_COMPLETION_CONFIRM_MS]
 *   (settling it inside that window does not cancel it, but that alert is one-shot: it is not remembered, so no later
 *   step clears it, because a settled thread has no phase that could show it no longer applies);
 * - a thread whose earlier alert no longer applies is in `clear`, also when it gets a new alert in the same step, so
 *   cancelling `clear` before posting `alerts` never leaves a stale notification;
 * - a connection loss forgets the baseline but keeps posted alerts until the next fresh snapshot shows whether they
 *   still apply; only an environment change clears them unseen.
 */
fun agentAlertStep(
    track: AgentAlertTrack, environmentId: String?, connected: Boolean, fresh: Boolean,
    threads: List<ThreadSummary>, now: Long,
): AgentAlertStep {
    if (environmentId == null || environmentId != track.environmentId) {
        val reset = AgentAlertTrack(environmentId)
        val clear = track.alerted.keys
        // A switch straight into a live, fresh snapshot adopts it now; the next change may be the one that alerts.
        if (environmentId == null || !connected || !fresh) return AgentAlertStep(reset, clear = clear)
        return AgentAlertStep(adopt(reset, threads).first, clear = clear)
    }
    if (!connected) {
        val kept = AgentAlertTrack(environmentId, alerted = track.alerted, alertedAttention = track.alertedAttention)
        return AgentAlertStep(kept)
    }
    val previous = track.phases
    if (previous == null) {
        if (!fresh) return AgentAlertStep(track)
        val (adopted, clear) = adopt(track, threads)
        return AgentAlertStep(adopted, clear = clear)
    }
    if (!fresh) {
        val due = track.pendingCompletions.values.minOrNull()?.plus(AGENT_COMPLETION_CONFIRM_MS)
        return AgentAlertStep(track, recheckAtMillis = due?.coerceAtLeast(now + STALE_RECHECK_MS))
    }

    val children = waitingChildren(threads)
    val phases = LinkedHashMap<String, AwarenessPhase>()
    val worked = HashSet<String>()
    val pending = LinkedHashMap<String, Long>()
    val pendingRuns = LinkedHashMap<String, String>()
    val attention = LinkedHashMap<String, AgentAttention>()
    val alerted = LinkedHashMap<String, AgentAlertKind>()
    val alertedAttention = LinkedHashMap<String, AgentAttention>()
    val alerts = ArrayList<AgentAlert>()
    val clear = LinkedHashSet<String>()
    for (row in threads.distinctBy { it.id }) {
        val id = row.id
        val child = children[id]
        val phase = awarenessOf(row, child?.kind)
        if (phase != null) phases[id] = phase
        val waiting = attentionOf(row, child, phase)
        if (waiting != null) attention[id] = waiting
        var working = id in track.worked || countsAsWork(row, phase, waiting)
        var kind: AgentAlertKind? = null
        /** Settled during its confirm window: alerted once and never tracked, since nothing later could clear it. */
        var oneShot = false

        /** A held completion: alert once it is due (if still fresh), else keep waiting. */
        fun holdCompletion(firstSeen: Long, run: String) {
            if (now >= firstSeen + AGENT_COMPLETION_CONFIRM_MS) {
                working = false
                if (terminalIsFresh(row, now)) kind = AgentAlertKind.Finished
            } else { pending[id] = firstSeen; pendingRuns[id] = run }
        }

        val prior = track.pendingCompletions[id]
        val sameRun = prior != null && track.pendingCompletionRuns[id] == runIdentity(row)
        when {
            waiting != null -> if (!waiting.sameAs(track.attention[id])) kind = waiting.kind
            // T3 alerts terminal moves from a non-terminal phase only; Completed -> Failed reaches here only while that
            // completion is still unconfirmed, because a confirmed one already took the thread out of `worked`.
            phase == AwarenessPhase.Failed -> if (previous[id] != AwarenessPhase.Failed && working) {
                working = false
                if (terminalIsFresh(row, now)) kind = AgentAlertKind.Failed
            }
            phase == AwarenessPhase.Completed -> if (working) {
                val firstSeen = when {
                    sameRun -> prior
                    prior != null || previous[id] != AwarenessPhase.Completed -> now
                    else -> null
                }
                if (firstSeen != null) holdCompletion(firstSeen, runIdentity(row))
            }
            // Relay: a held completion settled during its hold still alerts; only settlement may hide it.
            phase == null && sameRun && working && row.settledOverride == "settled" &&
                awarenessOf(row.copy(settledOverride = null), child?.kind) == AwarenessPhase.Completed -> {
                holdCompletion(prior!!, runIdentity(row))
                oneShot = true
            }
        }
        if (working) worked += id
        val old = track.alerted[id]
        if (kind != null) {
            alerts += AgentAlert(environmentId, id, kind!!, row.title, row.projectId)
            if (!oneShot) alerted[id] = kind!!
            if (waiting != null) alertedAttention[id] = waiting
            if (old != null) clear += id
        } else if (old != null) {
            if (alertStillApplies(old, track.alertedAttention[id], phase, waiting)) {
                alerted[id] = old
                track.alertedAttention[id]?.let { alertedAttention[id] = it }
            } else clear += id
        }
    }
    // Alerts of threads gone from the list (deleted, or dropped from the shell).
    track.alerted.keys.forEach { if (it !in alerted) clear += it }
    val recheck = pending.values.minOrNull()?.plus(AGENT_COMPLETION_CONFIRM_MS)
    val next = AgentAlertTrack(environmentId, phases, worked, pending, alerted, pendingRuns, attention, alertedAttention)
    return AgentAlertStep(next, alerts, clear, recheck)
}

/**
 * Fresh snapshot after a (re)connect: remember phases without alerting. Threads already starting, running or waiting
 * during a run count as working. Alerts kept across a connection loss stay when they still apply, else they are cleared.
 */
private fun adopt(track: AgentAlertTrack, threads: List<ThreadSummary>): Pair<AgentAlertTrack, Set<String>> {
    val children = waitingChildren(threads)
    val phases = LinkedHashMap<String, AwarenessPhase>()
    val attention = LinkedHashMap<String, AgentAttention>()
    val worked = HashSet<String>()
    for (row in threads.distinctBy { it.id }) {
        val child = children[row.id]
        val phase = awarenessOf(row, child?.kind) ?: continue
        phases[row.id] = phase
        val waiting = attentionOf(row, child, phase)
        if (waiting != null) attention[row.id] = waiting
        if (countsAsWork(row, phase, waiting)) worked += row.id
    }
    val alerted = track.alerted.filter { (id, kind) -> alertStillApplies(kind, track.alertedAttention[id], phases[id], attention[id]) }
    val adopted = track.copy(
        phases = phases, worked = worked, pendingCompletions = emptyMap(), pendingCompletionRuns = emptyMap(),
        attention = attention, alerted = alerted, alertedAttention = track.alertedAttention.filterKeys { it in alerted },
    )
    return adopted to (track.alerted.keys - alerted.keys)
}

/** Starting or running, or waiting on the user while a run is active (T3 alerts terminal moves from any live phase). */
private fun countsAsWork(row: ThreadSummary, phase: AwarenessPhase?, waiting: AgentAttention?) =
    phase == AwarenessPhase.Starting || phase == AwarenessPhase.Running ||
        (waiting != null && (row.activityRunStatus != null || row.activeRunId != null || row.hasOwnRequest()))

private fun ThreadSummary.hasOwnRequest() = pendingRequestKind != null && pendingRequestKind != "auth_refresh"

private fun attentionOf(row: ThreadSummary, child: WaitingChild?, phase: AwarenessPhase?): AgentAttention? {
    val kind = when (phase) {
        AwarenessPhase.WaitingForApproval -> AgentAlertKind.Approval
        AwarenessPhase.WaitingForInput -> AgentAlertKind.Input
        else -> return null
    }
    return AgentAttention(kind, if (row.hasOwnRequest()) row.pendingRequestId else child?.requestId)
}

private fun alertStillApplies(kind: AgentAlertKind, alertedFor: AgentAttention?, phase: AwarenessPhase?, waiting: AgentAttention?) =
    when (kind) {
        AgentAlertKind.Approval, AgentAlertKind.Input -> waiting != null && (alertedFor ?: AgentAttention(kind, null)).sameAs(waiting)
        AgentAlertKind.Finished -> phase == AwarenessPhase.Completed
        AgentAlertKind.Failed -> phase == AwarenessPhase.Failed
    }

private fun runIdentity(row: ThreadSummary) = "${row.latestRunId}\n${row.latestRunCompletedAt}"

/** Relay `isFreshTerminalNotification`; an unreadable completion time counts as fresh, so the alert is not lost. */
private fun terminalIsFresh(row: ThreadSummary, now: Long): Boolean {
    val completedAt = epochMillis(row.latestRunCompletedAt) ?: return true
    return now - completedAt <= TERMINAL_FRESHNESS_MS
}

private fun ThreadSummary.isListedLineage() = relationshipToParent != "subagent" && !sideChat && !workflowStep

/** T3 `subagentAwaitsUser`, plus the relay's archive check. */
private fun ThreadSummary.subagentAwaitsUser(): Boolean {
    val kind = pendingRequestKind ?: return false
    return kind != "auth_refresh" && (kind != "user_input" || pendingRequestUserOnly) &&
        relationshipToParent == "subagent" && !isProviderNativeSubagentThread() && activityRunStatus != null &&
        archivedAt == null && !archived && deletedAt == null
}

private class WaitingChild(val kind: String, val requestId: String?, val updatedAt: Long)

/**
 * Top-level thread id to its newest waiting subagent (relay `currentSubagentRequestFor`). A child maps to the
 * ancestor reached by walking `parentThreadId` through subagents and side chats; a missing parent or a cycle drops
 * it. Among several, the newest `updatedAt` wins and the first listed breaks ties.
 */
private fun waitingChildren(threads: List<ThreadSummary>): Map<String, WaitingChild> {
    val byId = HashMap<String, ThreadSummary>()
    threads.forEach { byId.putIfAbsent(it.id, it) }
    val newest = HashMap<String, WaitingChild>()
    for (child in threads) {
        if (!child.subagentAwaitsUser()) continue
        val top = topLevelOf(child, byId) ?: continue
        val updated = epochMillis(child.updatedAt) ?: Long.MIN_VALUE
        val current = newest[top]
        if (current == null || updated > current.updatedAt) newest[top] = WaitingChild(child.pendingRequestKind!!, child.pendingRequestId, updated)
    }
    return newest
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

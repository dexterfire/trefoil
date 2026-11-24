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

/** The awareness phase of top-level [thread] given all shell rows in [threads], or null when there is nothing to show. */
fun threadAwareness(thread: ThreadSummary, threads: List<ThreadSummary>, now: Long): AwarenessPhase? =
    TODO("stream A")

/**
 * One observation of the client state; see BRIEF for the rules. [connected] is a live connection and [fresh]
 * means the shell snapshot is current, not the cache kept across a reconnect.
 */
fun agentAlertStep(
    track: AgentAlertTrack, environmentId: String?, connected: Boolean, fresh: Boolean,
    threads: List<ThreadSummary>, now: Long,
): AgentAlertStep = TODO("stream A")

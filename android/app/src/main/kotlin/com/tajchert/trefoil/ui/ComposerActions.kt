package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.FollowUpMode

/** What the composer's primary (trailing) button does for the current text and thread state. */
internal enum class ComposerPrimary(val description: String) {
    Send("Send message"), Steer("Steer message"), Queue("Queue message"), Stop("Stop");

    /** The follow-up delivery this action sends with, or null for a plain send / stop. */
    val followUp: FollowUpMode? get() = when (this) { Steer -> FollowUpMode.Steer; Queue -> FollowUpMode.Queue; else -> null }
}

internal data class ComposerButton(val action: ComposerPrimary, val enabled: Boolean) {
    /** Long-press offers both delivery modes, only while a message waiting in the composer can be sent as a follow-up. */
    val offersAlternate: Boolean get() = action == ComposerPrimary.Steer || action == ComposerPrimary.Queue
}

private val ACTIVE_RUN_STATUSES = setOf("preparing", "starting", "running", "waiting")
private val SETUP_STATUSES = setOf("preparing", "starting")

/**
 * The selected thread has work a message must queue behind. Mirrors the session's `messageDispatchMode`: an active run,
 * any run in an active status, or a shell row whose run is still preparing/starting (a projection can lag behind it).
 */
internal val ClientState.threadBusy: Boolean get() {
    val shell = threads.firstOrNull { it.id == selectedThreadId }
    return activeRunId != null || runs.any { it.status in ACTIVE_RUN_STATUSES } ||
        shell?.activityRunStatus in SETUP_STATUSES || shell?.status in SETUP_STATUSES
}

/** The thread is busy, so a message is a follow-up. A new-thread draft never is. */
internal fun ClientState.runningFollowUp(isDraft: Boolean): Boolean = !isDraft && threadBusy

/** How an existing-thread send goes out, checked against the live state at the moment of the tap. */
internal sealed interface SendRoute {
    /** Idle thread: the session starts the run immediately. */
    data object Plain : SendRoute
    data class FollowUp(val mode: FollowUpMode) : SendRoute
    /** The chosen delivery is no longer possible (the run ended, steering is unsupported): nothing is sent. */
    data object Refused : SendRoute
}

/**
 * [requested] is the delivery the user chose, or null for the Send label. A chosen Steer or Queue is never downgraded to
 * an immediate send, and a Steer is refused when the turn no longer accepts it; a plain Send into a busy thread queues.
 */
internal fun routeSend(state: ClientState, isDraft: Boolean, requested: FollowUpMode?): SendRoute {
    val running = state.runningFollowUp(isDraft)
    return when {
        requested == null -> if (running) SendRoute.FollowUp(FollowUpMode.Queue) else SendRoute.Plain
        !running -> SendRoute.Refused
        requested == FollowUpMode.Steer && !state.canSteer -> SendRoute.Refused
        else -> SendRoute.FollowUp(requested)
    }
}

/** The view model's answer for one composer submission. */
enum class SendOutcome { Accepted, Failed }

/** One send from a composer: [id] carries the environment and thread it was made for, so outcomes never settle another send. */
data class ComposerSubmission(val id: String, val environmentId: String?, val threadId: String?, val text: String)

internal fun newSubmission(state: ClientState, text: String, nonce: String = java.util.UUID.randomUUID().toString()): ComposerSubmission =
    ComposerSubmission("${state.environment?.id}:${state.selectedThreadId}:$nonce", state.environment?.id, state.selectedThreadId, text)

/** Key of a thread's per-environment state, such as its Stop in flight. */
internal fun stopKey(environmentId: String?, threadId: String?): String = "$environmentId:$threadId"

/**
 * T3's composer send presentation. Blank text with something to stop shows Stop (disabled while a stop is
 * [stopPending], like desktop `useStopPhase`); text while the agent runs steers when the turn accepts it, otherwise queues.
 */
internal fun resolveComposerButton(state: ClientState, isDraft: Boolean, text: String, waiting: Boolean, stopPending: Boolean): ComposerButton {
    val canSend = state.canWrite && !state.busy
    val running = state.runningFollowUp(isDraft)
    if (text.isBlank() && !isDraft && state.canStop) return ComposerButton(ComposerPrimary.Stop, state.canWrite && !stopPending)
    val action = when {
        !running -> ComposerPrimary.Send
        state.canSteer -> ComposerPrimary.Steer
        else -> ComposerPrimary.Queue
    }
    return ComposerButton(action, canSend && text.isNotBlank() && !waiting)
}

internal fun composerPlaceholder(state: ClientState, isDraft: Boolean): String = when {
    isDraft -> "What should the agent do?"
    state.runningFollowUp(isDraft) -> if (state.canSteer) "Steer or queue a message…" else "Queue a message…"
    else -> "Reply…"
}

/**
 * Whether [pending] shows up as a new message or queued message. [idsAtSend] holds every message id and queued message id
 * present at submission, so an older identical message (a queued one moving into the timeline included) never confirms it.
 */
internal fun sendAccepted(pending: String, state: ClientState, idsAtSend: Collection<String>): Boolean {
    val text = pending.trim()
    return state.messages.any { it.role == "user" && it.text.trim() == text && it.id !in idsAtSend } ||
        state.queuedMessages.any { it.text.trim() == text && it.messageId !in idsAtSend }
}

/** Sending a queued message now needs a write path and either an active run that accepts steering or an idle thread. */
internal fun canSendQueuedNow(state: ClientState, canWrite: Boolean = state.canWrite): Boolean = canWrite && (state.canSteer || !state.threadBusy)

internal val ClientState.queuePaused: Boolean get() = queueHeld || queuedMessages.any { it.held }

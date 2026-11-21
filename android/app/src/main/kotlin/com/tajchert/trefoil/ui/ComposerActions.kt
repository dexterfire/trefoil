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

/** The thread's agent is mid-run, so a message is a follow-up. A new-thread draft never is. */
internal fun ClientState.runningFollowUp(isDraft: Boolean): Boolean = !isDraft && activeRunId != null

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

/** Whether the server accepted [pending]: it shows up as a new message or in the queue, never matched against what was there before the send. */
internal fun sendAccepted(pending: String, state: ClientState, messageIdsAtSend: Collection<String>, queuedIdsAtSend: Collection<String>): Boolean {
    val text = pending.trim()
    return state.messages.any { it.role == "user" && it.text.trim() == text && it.id !in messageIdsAtSend } ||
        state.queuedMessages.any { it.text.trim() == text && it.messageId !in queuedIdsAtSend }
}

/** Sending a queued message now needs a write path and either an active run that accepts steering or an idle thread. */
internal fun canSendQueuedNow(state: ClientState): Boolean = state.canWrite && (state.canSteer || state.activeRunId == null)

internal val ClientState.queuePaused: Boolean get() = queueHeld || queuedMessages.any { it.held }

package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ClientState
import kotlinx.coroutines.flow.StateFlow

/** What a queue command changes: the rows waiting, and whether the queue is held. */
internal fun ClientState.queueSignature(): List<Any> = listOf(queueHeld, queuedMessages.map { Triple(it.runId, it.held, it.canSendNow) })

/**
 * One queue operation (Resume, Send now, Remove) in flight per thread. The guard is taken synchronously at the tap, so a
 * double tap cannot send the command twice, and is released once the command has an outcome and the queue projection
 * has changed (or [StopGuard.holdMs] passes), so a stale strip never accepts a second tap either.
 */
internal class QueueOperationGuard(private val guard: StopGuard) {
    private val baseline = HashMap<String, List<Any>>()
    private val completed = HashSet<String>()
    /** Keys (see [stopKey]) whose queue operation is in flight. */
    val pending: StateFlow<Set<String>> get() = guard.pending
    val holdMs: Long get() = guard.holdMs

    /** True when the caller may dispatch: no operation was in flight for [key]. [signature] is the queue as the tap saw it. */
    fun tryBegin(key: String, signature: List<Any>): Boolean {
        if (!guard.tryBegin(key)) return false
        baseline[key] = signature; completed.remove(key)
        return true
    }
    /** The command got its answer (accepted); the guard now waits for the queue to refresh. [signature] is the current queue. */
    fun completed(key: String, signature: List<Any>) { completed += key; onState(key, signature) }
    /** The command failed or its outcome is uncertain: nothing to wait for. */
    fun failed(key: String) = release(key)
    /** Feed every state: releases a completed operation once the queue differs from what the tap saw. */
    fun onState(key: String, signature: List<Any>) { if (key in completed && baseline[key] != signature) release(key) }
    fun expire(key: String) { guard.expire(key); if (key !in guard.pending.value) forget(key) }
    private fun release(key: String) { guard.release(key); forget(key) }
    private fun forget(key: String) { baseline.remove(key); completed.remove(key) }
}

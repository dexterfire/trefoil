package com.tajchert.trefoil.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One Stop in flight per thread, like desktop `useStopPhase`: held from the tap until the thread has nothing left to stop,
 * or [holdMs]. Checked when a stop is dispatched (double taps, a re-entered conversation) and shown by the Stop button.
 * Confine calls to one thread (the main dispatcher).
 */
internal class StopGuard(private val clock: () -> Long, val holdMs: Long = STOP_HOLD_MS) {
    private val deadlines = HashMap<String, Long>()
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    /** Keys (see [stopKey]) whose Stop is in flight. */
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    /** True when [key] had no stop in flight: the caller dispatches it. */
    fun tryBegin(key: String): Boolean {
        expire(key)
        if (key in deadlines) return false
        deadlines[key] = clock() + holdMs
        publish()
        return true
    }
    fun release(key: String) { if (deadlines.remove(key) != null) publish() }
    /** Drops [key]'s hold once its deadline passed (the view model calls this after [holdMs]). */
    fun expire(key: String) { if (deadlines[key]?.let { it <= clock() } == true) release(key) }
    private fun publish() = _pending.update { deadlines.keys.toSet() }

    companion object { const val STOP_HOLD_MS = 10_000L }
}

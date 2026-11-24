package com.tajchert.trefoil.notifications

import com.tajchert.trefoil.core.model.AgentAlertKind
import com.tajchert.trefoil.core.model.AgentAlertStep
import com.tajchert.trefoil.core.model.AgentAlertTrack
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.agentAlertStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns client state into local notifications. It runs the awareness step ([step], injectable) on every state change and at the
 * step's recheck time, keeps the edge-trigger track, and posts alerts only while the app is in the background and the setting is
 * on. Alerts produced in the foreground are dropped (the in-app snackbar covers them) but the track still advances, so nothing
 * fires late on return. Clears always cancel. Titles and ids are conversation content and are never logged.
 *
 * A completion is held for the confirm window by the step; whether it may post is decided when its hold starts: one first seen in
 * the foreground (or with the setting off) is consumed silently even if the app goes to the background before it is due. Right
 * before a finished or failed alert is acted on, the live client state is read again: when the shell is no longer fresh or the
 * thread's run changed since the snapshot the step evaluated, the whole step is dropped and the next observation decides.
 *
 * State changes and recheck ticks are serialized by one mutex, and every observation reads the latest client state, presence and
 * setting when it runs (never values captured before a wait), so a tick can never act on a state that was replaced meanwhile.
 */
internal class AgentAlertDriver(
    private val scope: CoroutineScope,
    private val clientState: StateFlow<ClientState>,
    private val sink: AgentAlertSink,
    private val enabled: StateFlow<Boolean>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val step: (AgentAlertTrack, String?, Boolean, Boolean, List<ThreadSummary>, Long) -> AgentAlertStep = ::agentAlertStep,
) {
    /** The slice of [ClientState] the awareness rules read. */
    private data class Snapshot(
        val environmentId: String?, val connected: Boolean, val fresh: Boolean, val threads: List<ThreadSummary>,
        val projects: List<Project>, val selectedThreadId: String?,
    )

    private data class Presence(val foreground: Boolean, val threadShown: Boolean)

    private val presence = MutableStateFlow(Presence(foreground = true, threadShown = false))
    private val mutex = Mutex()
    private var track = AgentAlertTrack()
    private var lastEnabled = true
    private var lastShown: Pair<String, String>? = null
    /** Each held completion's start (as in the track's pending completions) and whether it may post. Guarded by [mutex]. */
    private val holds = HashMap<String, Pair<Long, Boolean>>()
    /** The pending recheck tick; replaced by every observation. Guarded by [mutex]. */
    private var recheck: Job? = null

    init {
        // Notifications of an earlier process are stale: its track is gone, so nothing would clear them.
        sink.cancelAll()
        scope.launch {
            // The emitted values only trigger; observeLatest reads the current ones itself.
            combine(clientState.map(::snapshotOf).distinctUntilChanged(), presence, enabled) { _, _, _ -> Unit }
                .collect { observeLatest() }
        }
    }

    fun setForeground(foreground: Boolean) { presence.value = presence.value.copy(foreground = foreground) }

    /** A conversation is on screen (the selected thread); it only counts while in the foreground. */
    fun setThreadShown(shown: Boolean) { presence.value = presence.value.copy(threadShown = shown) }

    /** The user disconnected, the machine was forgotten or the app's model ended: nothing of it stays in the shade. */
    fun cancelAll() = sink.cancelAll()

    private suspend fun observeLatest(tick: Job? = null): Unit = mutex.withLock {
        // A tick that a newer observation replaced has nothing left to do.
        if (tick != null && tick !== recheck) return@withLock
        if (tick == null) recheck?.cancel()
        recheck = null
        val recheckAt = observe(snapshotOf(clientState.value)) ?: return@withLock
        recheck = scope.launch {
            delay((recheckAt - clock()).coerceAtLeast(1L))
            observeLatest(coroutineContext[Job])
        }
    }

    /** Returns when to observe again, epoch millis, or null. Call under [mutex]. */
    private fun observe(snapshot: Snapshot): Long? {
        val previous = track
        val previousEnvironment = previous.environmentId
        val now = clock()
        val result = step(previous, snapshot.environmentId, snapshot.connected, snapshot.fresh, snapshot.threads, now)
        // The state may have moved on while the step ran; a terminal alert must still describe the live thread.
        if (result.alerts.any { it.kind == AgentAlertKind.Finished || it.kind == AgentAlertKind.Failed } && !terminalStillLive(snapshot, result)) {
            return now + STALE_RECHECK_MS
        }
        track = result.track
        // A reset clears the alerts of the machine the old track watched.
        if (previousEnvironment != null) result.clear.forEach { sink.cancel(previousEnvironment, it) }
        val enabled = enabled.value
        val silent = consumeHolds(previous, result, eligible = enabled && !presence.value.foreground)
        if (!enabled) { if (lastEnabled) sink.cancelAll() }
        else result.alerts.forEach { alert ->
            if (alert.kind == AgentAlertKind.Finished && alert.threadId in silent) return@forEach
            // Checked right before each post: the app may have come to the foreground, or notifications been turned off.
            if (!presence.value.foreground && this.enabled.value && sink.canPost()) {
                sink.post(alert, snapshot.projects.firstOrNull { it.id == alert.projectId }?.title)
            }
        }
        lastEnabled = enabled
        val presence = presence.value
        val shown = snapshot.environmentId?.let { environmentId -> snapshot.selectedThreadId?.takeIf { presence.foreground && presence.threadShown }?.let { environmentId to it } }
        if (shown != null && shown != lastShown) sink.cancel(shown.first, shown.second)
        lastShown = shown
        return result.recheckAtMillis
    }

    /**
     * Records when each held completion started and whether it could post then, and returns the threads whose completion confirmed
     * in this step but started in the foreground or with the setting off: those are consumed without a notification.
     */
    private fun consumeHolds(previous: AgentAlertTrack, result: AgentAlertStep, eligible: Boolean): Set<String> {
        val silent = HashSet<String>()
        for (alert in result.alerts) {
            if (alert.kind != AgentAlertKind.Finished) continue
            val started = previous.pendingCompletions[alert.threadId] ?: continue
            val hold = holds[alert.threadId]
            if (hold != null && hold.first == started && !hold.second) silent += alert.threadId
        }
        val pending = result.track.pendingCompletions
        holds.keys.retainAll(pending.keys)
        pending.forEach { (id, started) -> if (holds[id]?.first != started) holds[id] = started to eligible }
        return silent
    }

    /** The live state still shows the evaluated environment, freshly, with the same run for every thread with a terminal alert. */
    private fun terminalStillLive(snapshot: Snapshot, result: AgentAlertStep): Boolean {
        val live = clientState.value
        if (!shellFresh(live) || live.environment?.id != snapshot.environmentId) return false
        return result.alerts.filter { it.kind == AgentAlertKind.Finished || it.kind == AgentAlertKind.Failed }.all { alert ->
            val evaluated = snapshot.threads.firstOrNull { it.id == alert.threadId }
            val current = live.threads.firstOrNull { it.id == alert.threadId }
            evaluated != null && current != null && evaluated.latestRunId == current.latestRunId &&
                evaluated.latestRunCompletedAt == current.latestRunCompletedAt
        }
    }

    private companion object {
        /** A dropped step is observed again after this, unless a state change comes first. */
        const val STALE_RECHECK_MS = 1_000L

        /**
         * Fresh means the live shell stream is synchronized ([ClientState.shellLive]), not [ClientState.canRead]: that one also
         * waits for the selected conversation's detail, and threads other than the open one must not be missed meanwhile.
         */
        fun snapshotOf(state: ClientState) = Snapshot(
            state.environment?.id, state.connection == ConnectionStatus.Connected, shellFresh(state), state.threads, state.projects,
            state.selectedThreadId,
        )
    }
}

/** The thread rows follow the server: the live shell is synchronized. Shared by the notification driver and the in-app snackbar. */
internal fun shellFresh(state: ClientState): Boolean = state.shellLive && state.connection == ConnectionStatus.Connected

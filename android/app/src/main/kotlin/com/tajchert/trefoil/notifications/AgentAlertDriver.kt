package com.tajchert.trefoil.notifications

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
        val previousEnvironment = track.environmentId
        val result = step(track, snapshot.environmentId, snapshot.connected, snapshot.fresh, snapshot.threads, clock())
        track = result.track
        // A reset clears the alerts of the machine the old track watched.
        if (previousEnvironment != null) result.clear.forEach { sink.cancel(previousEnvironment, it) }
        val enabled = enabled.value
        if (!enabled) { if (lastEnabled) sink.cancelAll() }
        else result.alerts.forEach { alert ->
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

    private companion object {
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

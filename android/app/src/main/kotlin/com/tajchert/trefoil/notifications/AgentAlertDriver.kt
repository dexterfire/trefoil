package com.tajchert.trefoil.notifications

import com.tajchert.trefoil.core.model.AgentAlertStep
import com.tajchert.trefoil.core.model.AgentAlertTrack
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.agentAlertStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns client state into local notifications. It runs the awareness step ([step], injectable) on every state change and at the
 * step's recheck time, keeps the edge-trigger track, and posts alerts only while the app is in the background and the setting is
 * on. Alerts produced in the foreground are dropped (the in-app snackbar covers them) but the track still advances, so nothing
 * fires late on return. Clears always cancel. Titles and ids are conversation content and are never logged.
 */
internal class AgentAlertDriver(
    scope: CoroutineScope,
    clientState: Flow<ClientState>,
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
    private var track = AgentAlertTrack()
    private var lastEnabled = true
    private var lastShown: Pair<String, String>? = null

    init {
        // Notifications of an earlier process are stale: its track is gone, so nothing would clear them.
        sink.cancelAll()
        scope.launch {
            combine(
                clientState.map { Snapshot(it.environment?.id, it.connection == ConnectionStatus.Connected, it.canRead, it.threads, it.projects, it.selectedThreadId) }
                    .distinctUntilChanged(),
                presence, enabled,
            ) { snapshot, presence, enabled -> Triple(snapshot, presence, enabled) }
                .collectLatest { (snapshot, presence, enabled) ->
                    // Observing is synchronous; only the wait for the recheck time suspends, and a newer state replaces it.
                    while (true) {
                        val recheckAt = observe(snapshot, presence, enabled) ?: break
                        delay((recheckAt - clock()).coerceAtLeast(1L))
                    }
                }
        }
    }

    fun setForeground(foreground: Boolean) { presence.value = presence.value.copy(foreground = foreground) }

    /** A conversation is on screen (the selected thread); it only counts while in the foreground. */
    fun setThreadShown(shown: Boolean) { presence.value = presence.value.copy(threadShown = shown) }

    /** The user disconnected or the machine was forgotten: nothing of it stays in the shade. */
    fun cancelAll() = sink.cancelAll()

    /** Returns when to observe again, epoch millis, or null. */
    private fun observe(snapshot: Snapshot, presence: Presence, enabled: Boolean): Long? {
        val previousEnvironment = track.environmentId
        val result = step(track, snapshot.environmentId, snapshot.connected, snapshot.fresh, snapshot.threads, clock())
        track = result.track
        // A reset clears the alerts of the machine the old track watched.
        if (previousEnvironment != null) result.clear.forEach { sink.cancel(previousEnvironment, it) }
        if (!enabled) { if (lastEnabled) sink.cancelAll() }
        else if (!presence.foreground && sink.canPost()) {
            result.alerts.forEach { alert -> sink.post(alert, snapshot.projects.firstOrNull { it.id == alert.projectId }?.title) }
        }
        lastEnabled = enabled
        val shown = snapshot.environmentId?.let { environmentId -> snapshot.selectedThreadId?.takeIf { presence.foreground && presence.threadShown }?.let { environmentId to it } }
        if (shown != null && shown != lastShown) sink.cancel(shown.first, shown.second)
        lastShown = shown
        return result.recheckAtMillis
    }
}

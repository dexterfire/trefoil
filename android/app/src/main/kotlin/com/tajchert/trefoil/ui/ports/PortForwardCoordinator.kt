package com.tajchert.trefoil.ui.ports

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.PortForward
import com.tajchert.trefoil.core.model.PortForwardError
import com.tajchert.trefoil.core.model.PortForwardStatus
import com.tajchert.trefoil.core.model.TrefoilClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The in-app tunnel browser: one forwarded dev server of one environment. [url] is a loopback address; never log it. */
data class PortBrowserState(val environmentId: String, val remotePort: Int, val url: String, val title: String)

/**
 * How long a forward handed to an external browser holds the connection, counted from the last time it was handed over.
 * Trefoil cannot tell whether a browser still uses a forward (connections half-close, pages idle), so the hold is capped;
 * "Keep connected in background" has no limit.
 */
const val EXTERNAL_FORWARD_HOLD_MS = 30 * 60_000L

/**
 * A forward whose URL an external browser took: one listener ([instance]) of one environment's remote port. Memory only;
 * the ephemeral local port is never kept. A replacement forward on the same port has another [instance].
 */
data class ExternalForward(val environmentId: String, val remotePort: Int, val instance: Long)

/** A loopback URL for the UI to hand to an external browser; the UI confirms delivery with [PortForwardCoordinator.externalOpened]. */
data class ExternalOpenRequest(val url: String, val forward: ExternalForward)

/** Why a forward handed to an external browser stopped without the user stopping it. */
enum class ForwardLoss { Failed, ConnectionEnded, HoldExpired }

/**
 * Forwards of [environmentId] handed to an external browser that stopped without the user stopping them, by remote
 * port. [announced] holds the ports the app-wide notice already reported, so each loss is announced once.
 */
data class StoppedForwardNotice(val environmentId: String, val lost: Map<Int, ForwardLoss>, val announced: Set<Int> = emptySet()) {
    val message: String get() = stoppedMessage(lost)
}

/**
 * Starts and stops port forwards for the ports screen and tracks the in-app browser surface.
 * Forwards belong to the live connection: the browser closes when its forward disappears (disconnect, reconnect,
 * environment switch) or fails, and nothing here ever restarts a forward by itself. Runs on the main-safe [scope].
 */
class PortForwardCoordinator(
    private val scope: CoroutineScope,
    private val client: TrefoilClient,
    /** [externalForwards] changed; called synchronously so a hold starts while the app is still in the foreground. */
    private val onExternalForwardsChanged: () -> Unit = {},
    private val onError: (String) -> Unit,
) {
    private val mutableBrowser = MutableStateFlow<PortBrowserState?>(null)
    val browser: StateFlow<PortBrowserState?> = mutableBrowser.asStateFlow()
    private val mutableExternal = MutableSharedFlow<ExternalOpenRequest>(extraBufferCapacity = 1)
    /** Requests to open a forward externally. A request no collector receives (the UI is stopped) is simply lost. */
    val externalUrls: SharedFlow<ExternalOpenRequest> = mutableExternal.asSharedFlow()
    private val starting = HashSet<Int>()
    /** Each held forward and its cap timer: one timer per mark, replaced only by a re-open and cancelled with the mark. */
    private val holds = HashMap<ExternalForward, Job>()
    /** Forwards whose hold expired and that still run; reported as [ForwardLoss.HoldExpired] if they stop on their own. */
    private val expired = HashSet<ExternalForward>()
    private val mutableExternalForwards = MutableStateFlow<Set<ExternalForward>>(emptySet())
    /**
     * Forwards an external browser took ([externalOpened]) that are still live. While one exists the view model keeps the
     * connection through the background (the keep-alive service), so the page keeps loading. An entry ends with its
     * listener (a Stop, a failure, a replacement, a disconnect or reconnect, an environment switch),
     * [EXTERNAL_FORWARD_HOLD_MS] after it was last handed to a browser, or on [releaseAll]. The in-app browser never adds one.
     */
    val externalForwards: StateFlow<Set<ExternalForward>> = mutableExternalForwards.asStateFlow()
    private val mutableStoppedNotices = MutableStateFlow<Map<String, StoppedForwardNotice>>(emptyMap())
    /**
     * Lost-forward notices by environment id: the one source for both the ports screen of that environment and the
     * app-wide notice. A loss is recorded whenever a held (or expired) forward stops without the user stopping it, so
     * whether the app was in the foreground when the update arrived never decides it.
     */
    val stoppedNotices: StateFlow<Map<String, StoppedForwardNotice>> = mutableStoppedNotices.asStateFlow()

    init {
        scope.launch {
            client.state.collect { state ->
                pruneExternal(state)
                val open = mutableBrowser.value ?: return@collect
                val forward = state.portForwards.firstOrNull { it.environmentId == open.environmentId && it.remotePort == open.remotePort }
                if (state.environment?.id != open.environmentId || forward == null || forward.status == PortForwardStatus.Failed) mutableBrowser.value = null
            }
        }
    }

    /** The exact listener [mark] was made for, while it is live in the current environment. */
    private fun ClientState.liveForward(mark: ExternalForward): PortForward? {
        if (environment?.id != mark.environmentId) return null
        return portForwards.firstOrNull {
            it.environmentId == mark.environmentId && it.remotePort == mark.remotePort && it.instance == mark.instance && it.status == PortForwardStatus.Active
        }
    }

    private fun ClientState.failed(mark: ExternalForward) =
        portForwards.any { it.remotePort == mark.remotePort && it.instance == mark.instance && it.status == PortForwardStatus.Failed }

    private fun pruneExternal(state: ClientState) {
        val goneExpired = expired.filter { state.liveForward(it) == null }
        expired.removeAll(goneExpired.toSet())
        goneExpired.forEach { recordLoss(it, if (state.failed(it)) ForwardLoss.Failed else ForwardLoss.HoldExpired) }
        val gone = holds.keys.filter { state.liveForward(it) == null }
        if (gone.isEmpty()) return
        gone.forEach { mark ->
            holds.remove(mark)?.cancel()
            recordLoss(mark, if (state.failed(mark)) ForwardLoss.Failed else ForwardLoss.ConnectionEnded)
        }
        publishHolds()
    }

    private fun publishHolds() {
        mutableExternalForwards.value = holds.keys.toSet()
        onExternalForwardsChanged()
    }

    /** Adds to the environment's notice, so losses at different times are all reported; a port lost again is announced again. */
    private fun recordLoss(mark: ExternalForward, loss: ForwardLoss) {
        mutableStoppedNotices.update { notices ->
            val current = notices[mark.environmentId]
            val notice = StoppedForwardNotice(mark.environmentId, (current?.lost ?: emptyMap()) + (mark.remotePort to loss),
                (current?.announced ?: emptySet()) - mark.remotePort)
            notices + (mark.environmentId to notice)
        }
    }

    /** Forwards [port] if needed, then shows it in the in-app browser. */
    fun openInApp(port: Int) = start(port) { forward ->
        mutableBrowser.value = PortBrowserState(forward.environmentId, port, forward.localUrl!!, titleFor(port))
    }

    /**
     * Forwards [port] if needed, then asks the UI to open its loopback URL externally. Nothing is held until the UI
     * reports that a browser took the URL ([externalOpened]); a request no UI received holds nothing.
     */
    fun openExternal(port: Int) = start(port) { forward ->
        mutableExternal.tryEmit(ExternalOpenRequest(forward.localUrl!!, ExternalForward(forward.environmentId, forward.remotePort, forward.instance)))
    }

    /**
     * An external browser took [request]'s URL: hold its forward while that exact listener stays live, for at most
     * [EXTERNAL_FORWARD_HOLD_MS] from now. Handing the same forward over again restarts that time.
     */
    fun externalOpened(request: ExternalOpenRequest) {
        val mark = request.forward
        client.state.value.liveForward(mark) ?: return
        expired.remove(mark)
        holds.remove(mark)?.cancel()
        lateinit var timer: Job
        timer = scope.launch {
            delay(EXTERNAL_FORWARD_HOLD_MS)
            // Only the timer the mark still owns may end it.
            if (holds[mark] !== timer) return@launch
            holds.remove(mark)
            expired += mark
            publishHolds()
        }
        holds[mark] = timer
        publishHolds()
    }

    /** Ends every hold without a notice: the user asked to disconnect. */
    fun releaseAll() {
        expired.clear()
        if (holds.isEmpty()) return
        holds.values.forEach { it.cancel() }; holds.clear()
        publishHolds()
    }

    /**
     * The losses of [environmentId] the app-wide notice has not reported yet, as a message, and marks them reported;
     * null when there are none.
     */
    fun announceStopped(environmentId: String): String? {
        val notice = mutableStoppedNotices.value[environmentId] ?: return null
        val fresh = notice.lost.filterKeys { it !in notice.announced }
        if (fresh.isEmpty()) return null
        mutableStoppedNotices.update { it + (environmentId to notice.copy(announced = notice.lost.keys)) }
        return stoppedMessage(fresh)
    }

    fun dismissStoppedNotice(environmentId: String) { mutableStoppedNotices.update { it - environmentId } }

    private fun release(entries: Set<ExternalForward>) {
        expired.removeAll(entries)
        if (entries.none { it in holds }) return
        entries.forEach { holds.remove(it)?.cancel() }
        publishHolds()
    }

    /** A port typed by hand: forward it and open it in-app. */
    fun forwardManual(port: Int) = openInApp(port)

    /** Closing a forward is local cleanup, not a server write, so it stays available offline. */
    fun stop(port: Int) {
        if (mutableBrowser.value?.remotePort == port) mutableBrowser.value = null
        release((holds.keys + expired).filterTo(HashSet()) { it.remotePort == port })
        scope.launch {
            try { client.stopPortForward(port) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { onError("Could not stop the forward for port $port.") }
        }
    }

    fun closeBrowser() { mutableBrowser.value = null }

    private fun titleFor(port: Int): String =
        client.state.value.localServers.servers.firstOrNull { it.port == port }?.processName?.trim()?.takeIf { it.isNotEmpty() } ?: ":$port"

    private fun start(port: Int, onReady: (PortForward) -> Unit) {
        val state = client.state.value
        when {
            port !in 1..65535 -> return onError("Choose a port from 1 to 65535.")
            state.connection != ConnectionStatus.Connected || !state.canWrite -> return onError("Reconnect with write access to open dev servers.")
            state.previewTunnelDisabled -> return onError(describe(PortForwardError.TunnelDisabled))
        }
        val environmentId = state.environment?.id ?: return
        if (!starting.add(port)) return
        dismissStoppedNotice(environmentId)
        scope.launch {
            try {
                val forward = client.startPortForward(port)
                when {
                    client.state.value.environment?.id != environmentId -> Unit
                    forward.status == PortForwardStatus.Failed || forward.localUrl == null -> onError(describe(forward.error))
                    else -> onReady(forward)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { onError("Choose a port from 1 to 65535.") }
            catch (_: Exception) { onError("Could not forward port $port. Check the connection and try again.") }
            finally { starting.remove(port) }
        }
    }

    private fun describe(error: PortForwardError?): String = when (error) {
        PortForwardError.TunnelDisabled -> "Browser tunnels are turned off on this T3 server."
        PortForwardError.ServerOutdated -> "This T3 server is too old for port forwarding."
        PortForwardError.Unauthorized -> "Not authorized to open tunnels. Reconnect this machine."
        PortForwardError.LocalBindFailed -> "No free port on this device to forward to."
        else -> "Could not forward the port. Check the connection and try again."
    }
}

private fun stoppedMessage(lost: Map<Int, ForwardLoss>): String {
    fun ports(list: List<Int>) = if (list.size == 1) "The forward for port ${list[0]}" else "The forwards for ports ${list.joinToString(", ")}"
    val holdMinutes = EXTERNAL_FORWARD_HOLD_MS / 60_000
    val sentences = ForwardLoss.entries.mapNotNull { loss ->
        val list = lost.filterValues { it == loss }.keys.sorted().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        when (loss) {
            ForwardLoss.Failed -> "${ports(list)} failed."
            ForwardLoss.ConnectionEnded -> "${ports(list)} stopped because the connection to the machine ended."
            ForwardLoss.HoldExpired -> "${ports(list)} stopped in the background: Trefoil keeps a forward opened in your browser connected for " +
                "$holdMinutes minutes. Turn on Keep connected in background to remove this limit."
        }
    }
    val them = if (lost.size == 1) "it" else "them"
    return (sentences + "Open $them again to keep using $them in your browser.").joinToString(" ")
}

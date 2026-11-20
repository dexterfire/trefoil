package com.tajchert.trefoil.ui.ports

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.PortForward
import com.tajchert.trefoil.core.model.PortForwardError
import com.tajchert.trefoil.core.model.PortForwardStatus
import com.tajchert.trefoil.core.model.TrefoilClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The in-app tunnel browser: one forwarded dev server of one environment. [url] is a loopback address; never log it. */
data class PortBrowserState(val environmentId: String, val remotePort: Int, val url: String, val title: String)

/** A forward whose URL was handed to an external browser. Memory only; the ephemeral local port is never kept. */
data class ExternalForward(val environmentId: String, val remotePort: Int)

/** Shown on the ports screen of [environmentId] after externally opened forwards ended while Trefoil was in the background. */
data class StoppedForwardNotice(val environmentId: String, val message: String)

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
    private val mutableExternal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Loopback URLs the UI should hand to an external browser, once each. */
    val externalUrls: SharedFlow<String> = mutableExternal.asSharedFlow()
    private val starting = HashSet<Int>()
    private val mutableExternalForwards = MutableStateFlow<Set<ExternalForward>>(emptySet())
    /**
     * Forwards opened in an external browser that are still active. While one exists the view model keeps the connection
     * through the background (the keep-alive service), so the page keeps loading. An entry ends with its forward: a Stop,
     * a failure, a disconnect or reconnect, or an environment switch. Opening in the in-app browser never adds one.
     */
    val externalForwards: StateFlow<Set<ExternalForward>> = mutableExternalForwards.asStateFlow()
    private val mutableStoppedNotice = MutableStateFlow<StoppedForwardNotice?>(null)
    val stoppedNotice: StateFlow<StoppedForwardNotice?> = mutableStoppedNotice.asStateFlow()
    /** Set by the view model on pause and resume; only forwards lost in the background get a [stoppedNotice]. */
    var inBackground = false

    init {
        scope.launch {
            client.state.collect { state ->
                pruneExternal(state)
                val open = mutableBrowser.value ?: return@collect
                if (!state.isLive(open.environmentId, open.remotePort)) mutableBrowser.value = null
            }
        }
    }

    private fun ClientState.isLive(environmentId: String, remotePort: Int): Boolean {
        val forward = portForwards.firstOrNull { it.environmentId == environmentId && it.remotePort == remotePort }
        return environment?.id == environmentId && forward != null && forward.status != PortForwardStatus.Failed
    }

    private fun pruneExternal(state: ClientState) {
        val external = mutableExternalForwards.value
        val gone = external.filterNot { state.isLive(it.environmentId, it.remotePort) }
        if (gone.isEmpty()) return
        mutableExternalForwards.value = external - gone.toSet()
        if (inBackground) {
            val failed = gone.all { lost -> state.portForwards.any { it.environmentId == lost.environmentId && it.remotePort == lost.remotePort && it.status == PortForwardStatus.Failed } }
            mutableStoppedNotice.value = StoppedForwardNotice(gone.first().environmentId, stoppedMessage(gone.map { it.remotePort }.sorted(), failed))
        }
        onExternalForwardsChanged()
    }

    /** Forwards [port] if needed, then shows it in the in-app browser. */
    fun openInApp(port: Int) = start(port) { forward ->
        mutableBrowser.value = PortBrowserState(forward.environmentId, port, forward.localUrl!!, titleFor(port))
    }

    /**
     * Forwards [port] if needed, then asks the UI to open its loopback URL externally. The forward counts as opened
     * externally from the moment its URL is handed to the UI ([externalForwards]), until it ends or the UI reports that
     * no browser took it ([externalOpenFailed]).
     */
    fun openExternal(port: Int) = start(port) { forward ->
        val url = forward.localUrl!!
        holdExternal(forward)
        mutableExternal.tryEmit(url)
    }

    /** Marks [forward] as opened externally. Internal for tests that cannot bind a real forward. */
    internal fun holdExternal(forward: PortForward) {
        if (!client.state.value.isLive(forward.environmentId, forward.remotePort)) return
        val entry = ExternalForward(forward.environmentId, forward.remotePort)
        if (entry in mutableExternalForwards.value) return
        mutableExternalForwards.value = mutableExternalForwards.value + entry
        onExternalForwardsChanged()
    }

    /** No app took [url]: its forward no longer needs the background hold. */
    fun externalOpenFailed(url: String) {
        val state = client.state.value
        val failed = mutableExternalForwards.value.filter { entry ->
            state.portForwards.any { it.environmentId == entry.environmentId && it.remotePort == entry.remotePort && it.localUrl == url }
        }
        release(failed.toSet())
    }

    fun dismissStoppedNotice() { mutableStoppedNotice.value = null }

    private fun release(entries: Set<ExternalForward>) {
        if (entries.isEmpty()) return
        mutableExternalForwards.value = mutableExternalForwards.value - entries
        onExternalForwardsChanged()
    }

    /** A port typed by hand: forward it and open it in-app. */
    fun forwardManual(port: Int) = openInApp(port)

    /** Closing a forward is local cleanup, not a server write, so it stays available offline. */
    fun stop(port: Int) {
        if (mutableBrowser.value?.remotePort == port) mutableBrowser.value = null
        release(mutableExternalForwards.value.filterTo(HashSet()) { it.remotePort == port })
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
        mutableStoppedNotice.value = null
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

    private fun stoppedMessage(ports: List<Int>, failed: Boolean): String {
        val which = if (ports.size == 1) "The forward for port ${ports[0]}" else "The forwards for ports ${ports.joinToString(", ")}"
        val why = if (failed) "failed while Trefoil was in the background" else "stopped because the connection ended while Trefoil was in the background"
        return "$which $why. Open ${if (ports.size == 1) "it" else "them"} again to keep using ${if (ports.size == 1) "it" else "them"} in your browser."
    }

    private fun describe(error: PortForwardError?): String = when (error) {
        PortForwardError.TunnelDisabled -> "Browser tunnels are turned off on this T3 server."
        PortForwardError.ServerOutdated -> "This T3 server is too old for port forwarding."
        PortForwardError.Unauthorized -> "Not authorized to open tunnels. Reconnect this machine."
        PortForwardError.LocalBindFailed -> "No free port on this device to forward to."
        else -> "Could not forward the port. Check the connection and try again."
    }
}

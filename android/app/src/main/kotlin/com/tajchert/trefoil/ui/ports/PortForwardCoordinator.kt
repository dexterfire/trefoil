package com.tajchert.trefoil.ui.ports

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

/**
 * Starts and stops port forwards for the ports screen and tracks the in-app browser surface.
 * Forwards belong to the live connection: the browser closes when its forward disappears (disconnect, reconnect,
 * environment switch) or fails, and nothing here ever restarts a forward by itself. Runs on the main-safe [scope].
 */
class PortForwardCoordinator(
    private val scope: CoroutineScope,
    private val client: TrefoilClient,
    private val onError: (String) -> Unit,
) {
    private val mutableBrowser = MutableStateFlow<PortBrowserState?>(null)
    val browser: StateFlow<PortBrowserState?> = mutableBrowser.asStateFlow()
    private val mutableExternal = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Loopback URLs the UI should hand to an external browser, once each. */
    val externalUrls: SharedFlow<String> = mutableExternal.asSharedFlow()
    private val starting = HashSet<Int>()

    init {
        scope.launch {
            client.state.collect { state ->
                val open = mutableBrowser.value ?: return@collect
                val forward = state.portForwards.firstOrNull { it.environmentId == open.environmentId && it.remotePort == open.remotePort }
                if (state.environment?.id != open.environmentId || forward == null || forward.status == PortForwardStatus.Failed) mutableBrowser.value = null
            }
        }
    }

    /** Forwards [port] if needed, then shows it in the in-app browser. */
    fun openInApp(port: Int) = start(port) { forward ->
        mutableBrowser.value = PortBrowserState(forward.environmentId, port, forward.localUrl!!, titleFor(port))
    }

    /** Forwards [port] if needed, then asks the UI to open its loopback URL externally. */
    fun openExternal(port: Int) = start(port) { forward -> mutableExternal.tryEmit(forward.localUrl!!) }

    /** A port typed by hand: forward it and open it in-app. */
    fun forwardManual(port: Int) = openInApp(port)

    /** Closing a forward is local cleanup, not a server write, so it stays available offline. */
    fun stop(port: Int) {
        if (mutableBrowser.value?.remotePort == port) mutableBrowser.value = null
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

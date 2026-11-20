package com.tajchert.trefoil.core.model

/**
 * A listener T3 found on the environment host (`subscribeDiscoveredLocalServers`, T3 v0.1.11
 * `packages/contracts/src/preview.ts` `DiscoveredLocalServer`). [url] is the server's own
 * loopback URL on the remote host; it is display-only and never loaded directly by the app.
 */
data class DiscoveredLocalServer(
    val host: String, val port: Int, val url: String,
    val processName: String? = null, val pid: Int? = null,
    /** `terminal.threadId` / `terminal.terminalId`: the listener descends from a T3 terminal. */
    val terminalThreadId: String? = null, val terminalId: String? = null,
    /** `agent.threadId`: an agent process of that thread started the listener. Older servers omit it. */
    val agentThreadId: String? = null,
) {
    /** The thread that owns this server, preferring the agent over the terminal. */
    val threadId: String? get() = agentThreadId ?: terminalThreadId
}

/** Latest `DiscoveredLocalServerList` snapshot for the connected environment. */
data class LocalServerCatalog(
    val servers: List<DiscoveredLocalServer> = emptyList(),
    /** `scannedAt` (ISO-8601) of the latest snapshot; null before the first one. */
    val scannedAt: String? = null,
    /** True while the discovery subscription is live and has delivered a snapshot. */
    val live: Boolean = false,
    /** False when the server rejected the subscription (older server, missing method); the list stays empty. */
    val supported: Boolean = true,
)

enum class PortForwardStatus { Starting, Active, Failed }

/** Why a forward or one of its connections failed. Classified from the tunnel handshake response. */
enum class PortForwardError {
    /** 403 with `x-t3-tunnel-disabled: 1`: the setting or a managed policy turned tunnels off. */
    TunnelDisabled,
    /** `x-t3-tunnel-port-refused: 1` (403, or 502 on v0.1.11): nothing listens there, or it is T3's own port. */
    PortRefused,
    /** 2xx or 404 to the upgrade: the server predates `/api/preview/tunnel`. */
    ServerOutdated,
    /** 401/403 without a tunnel header, or the media ticket mint failed for auth. */
    Unauthorized,
    /** Network failure, timeout, or any other status. */
    Unreachable,
    /** No local loopback port could be bound. */
    LocalBindFailed,
}

/**
 * A device-local loopback listener that pipes each accepted TCP connection through its own
 * `/api/preview/tunnel` WebSocket to [remotePort] on the environment host. [localPort] equals
 * [remotePort] when that port was free on the device, so pages keep their `localhost:PORT` origin.
 * Forwards belong to one live connection: they stop on disconnect, environment switch or close.
 */
data class PortForward(
    val environmentId: String, val remotePort: Int,
    val localPort: Int? = null,
    val status: PortForwardStatus = PortForwardStatus.Starting,
    val error: PortForwardError? = null,
    val activeConnections: Int = 0,
    /**
     * The host of [localUrl]. [LOCALHOST] when both `127.0.0.1:N` and `[::1]:N` are ours, so whichever address
     * `localhost` resolves to reaches this forward; [LOOPBACK_V4] when only IPv4 could be bound, so the URL can
     * never resolve to an IPv6 listener of another app.
     */
    val localHost: String = LOCALHOST,
    /** Identifies one listener: a forward that replaces this one on the same port (a retry after a failure) gets a new value. */
    val instance: Long = 0L,
) {
    /** URL that the in-app WebView or an external browser opens; null until bound. */
    val localUrl: String? get() = localPort?.let { "http://$localHost:$it/" }

    companion object {
        const val LOCALHOST = "localhost"
        const val LOOPBACK_V4 = "127.0.0.1"
    }
}

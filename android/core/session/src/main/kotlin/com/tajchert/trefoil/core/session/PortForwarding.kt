package com.tajchert.trefoil.core.session

// Tunnel handshake classification and pumping adapted from T3 Code
// apps/mobile/modules/t3-preview-tunnel/android/.../SocksListener.kt (MIT).

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** Browsers open about six connections per host; this leaves room for several pages, HMR and fetches. */
internal const val MAX_TUNNEL_CONNECTIONS = 64
internal const val TUNNEL_DISABLED_HEADER = "x-t3-tunnel-disabled"
internal const val TUNNEL_PORT_REFUSED_HEADER = "x-t3-tunnel-port-refused"
private const val TUNNEL_MARKER = "1"
/** Tunnel connections across every forward of one client; also the tunnel dispatcher's request limit. */
internal const val MAX_CLIENT_TUNNEL_CONNECTIONS = 256
private const val SEND_HIGH_WATER_BYTES = 1024L * 1024
private const val TUNNEL_OPEN_TIMEOUT_MS = 15_000L
/** Mint again this long before `expiresAt`, so a connection never presents a ticket that lapses mid-handshake. */
internal const val MEDIA_TICKET_REFRESH_MARGIN_MS = 30_000L
/** A ticket without a usable `expiresAt` is reused this long (T3 issues five-minute media tickets). */
internal const val MEDIA_TICKET_UNKNOWN_EXPIRY_MS = 240_000L

/**
 * One `DiscoveredLocalServerList` snapshot (T3 v0.1.11 `packages/contracts/src/preview.ts`). Entries without a
 * host, URL or a port in 1..65535 are skipped; optional fields of the wrong type read as absent; additive
 * fields are ignored. One row per port (the tunnel dials by port only), preferring a row that names its
 * thread. Null when the value has no `servers` array, so a malformed snapshot keeps the previous list.
 */
internal fun discoveredLocalServers(value: JsonObject): LocalServerCatalog? {
    val entries = value["servers"] as? JsonArray ?: return null
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    val servers = entries.mapNotNull { entry ->
        val server = entry as? JsonObject ?: return@mapNotNull null
        val host = server.text("host") ?: return@mapNotNull null
        val url = server.text("url") ?: return@mapNotNull null
        val port = server.int("port")?.takeIf { it in 1..65535 } ?: return@mapNotNull null
        val terminal = server["terminal"] as? JsonObject
        val terminalThreadId = terminal?.text("threadId")
        val terminalId = terminal?.text("terminalId")
        DiscoveredLocalServer(host, port, url, server.text("processName"), server.int("pid")?.takeIf { it > 0 },
            terminalThreadId.takeIf { terminalId != null }, terminalId.takeIf { terminalThreadId != null },
            (server["agent"] as? JsonObject)?.text("threadId"))
    }
    val byPort = servers.groupBy { it.port }
    val unique = servers.map { it.port }.distinct().map { port -> byPort.getValue(port).let { rows -> rows.firstOrNull { it.threadId != null } ?: rows.first() } }
    return LocalServerCatalog(unique, value.string("scannedAt").ifBlank { null }, live = true, supported = true)
}

/**
 * Classifies a refused `/api/preview/tunnel` upgrade. The T3 markers decide first, because a status alone
 * cannot tell T3 from a proxy in front of it. T3 sets them to exactly "1"; any other value falls through to the status.
 * [status] is null when no HTTP response arrived.
 */
internal fun classifyTunnelHandshake(status: Int?, header: (String) -> String?): PortForwardError = when {
    header(TUNNEL_DISABLED_HEADER)?.trim() == TUNNEL_MARKER -> PortForwardError.TunnelDisabled
    header(TUNNEL_PORT_REFUSED_HEADER)?.trim() == TUNNEL_MARKER -> PortForwardError.PortRefused
    status == null -> PortForwardError.Unreachable
    status in 200..299 || status == 404 -> PortForwardError.ServerOutdated
    status == 401 || status == 403 -> PortForwardError.Unauthorized
    else -> PortForwardError.Unreachable
}

/** These end the whole forward: no other connection of it can succeed until the user acts. */
internal val PortForwardError.endsForward: Boolean get() = this == PortForwardError.TunnelDisabled ||
    this == PortForwardError.ServerOutdated || this == PortForwardError.Unauthorized || this == PortForwardError.LocalBindFailed

/** A media ticket could not be minted; [error] is how a waiting tunnel connection fails. */
internal class MediaTicketUnavailable(val error: PortForwardError) : IllegalStateException("Media ticket unavailable")

/** A minted media ticket. [toString] never shows it. */
internal class MintedMediaTicket(val value: String, val expiresAtMillis: Long?) {
    override fun toString(): String = "MintedMediaTicket(redacted)"
}

/** `expiresAt` of a ticket reply: ISO-8601 (T3 `DateTimeUtc`) or epoch milliseconds; null when unreadable. */
internal fun mediaTicketExpiry(reply: JsonObject): Long? {
    val value = reply["expiresAt"] as? JsonPrimitive ?: return null
    return if (value.isString) runCatching { java.time.Instant.parse(value.content).toEpochMilli() }.getOrNull()
    else value.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.toLong()
}

/**
 * The connection's shared `/api/auth/websocket-ticket/media` ticket. T3 media tickets are reusable until they
 * expire and are not DPoP-bound, so every tunnel connection presents the same one. Concurrent callers share one
 * mint. A ticket is replaced [MEDIA_TICKET_REFRESH_MARGIN_MS] before `expiresAt`, or after
 * [MEDIA_TICKET_UNKNOWN_EXPIRY_MS] when the server did not say.
 */
internal class MediaTicketCache(private val clock: () -> Long = System::currentTimeMillis, private val mint: suspend () -> MintedMediaTicket) {
    private class Held(val value: String, val expiresAt: Long?, val mintedAt: Long)
    private val mutex = Mutex()
    private val held = AtomicReference<Held?>(null)
    suspend fun ticket(): String = mutex.withLock {
        held.get()?.takeIf(::usable)?.value ?: mint().let { Held(it.value, it.expiresAtMillis, clock()) }.also(held::set).value
    }
    /** Drops [ticket] after the server refused it, so the next connection mints a fresh one. */
    fun invalidate(ticket: String) { held.updateAndGet { current -> current?.takeUnless { it.value == ticket } } }
    private fun usable(ticket: Held): Boolean {
        val now = clock()
        return ticket.expiresAt?.let { now < it - MEDIA_TICKET_REFRESH_MARGIN_MS } ?: (now - ticket.mintedAt in 0 until MEDIA_TICKET_UNKNOWN_EXPIRY_MS)
    }
    override fun toString(): String = "MediaTicketCache(redacted)"
}

/**
 * Admission for tunnel connections shared by every forward of one client. It equals the tunnel dispatcher's
 * request limit and is taken before a WebSocket is enqueued, so an upgrade never waits in OkHttp's queue.
 */
internal class TunnelAdmission(val limit: Int = MAX_CLIENT_TUNNEL_CONNECTIONS) {
    private val held = java.util.concurrent.atomic.AtomicInteger()
    fun tryAcquire(): Boolean {
        while (true) { val now = held.get(); if (now >= limit) return false; if (held.compareAndSet(now, now + 1)) return true }
    }
    fun release() { held.decrementAndGet() }
    val inUse: Int get() = held.get()
}

/**
 * A client for tunnel WebSockets. OkHttp 4 holds a dispatcher slot for each WebSocket's whole life, so tunnels get
 * their own [okhttp3.Dispatcher] sized to [admission] instead of competing with the RPC socket and HTTP calls for
 * the shared 64 slots. No read timeout: tunnels idle for as long as the page keeps them open (HMR, keep-alive).
 */
internal fun tunnelClient(base: OkHttpClient, admission: TunnelAdmission): OkHttpClient = base.newBuilder()
    .dispatcher(okhttp3.Dispatcher().apply { maxRequests = admission.limit; maxRequestsPerHost = admission.limit })
    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
    .build()

/** Accept loops and upstream pumps block on sockets; this view grows past the shared IO pool's 64 threads. */
private val tunnelDispatcher = Dispatchers.IO.limitedParallelism(512)

/**
 * Binds `127.0.0.1:[preferredPort]` so pages keep their `localhost:PORT` origin, or an ephemeral port when that one
 * is taken or privileged (below 1024).
 */
internal fun bindLoopback(preferredPort: Int): ServerSocket {
    val loopback = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    if (preferredPort in 1024..65535) try { return ServerSocket(preferredPort, 50, loopback) } catch (_: IOException) {}
    return ServerSocket(0, 50, loopback)
}

/**
 * A device-local loopback listener for one remote port. Each accepted TCP connection gets its own
 * `/api/preview/tunnel` WebSocket that carries its bytes both ways; at most [maxConnections] run at once and
 * further ones are closed on accept. A connection the server refuses for the port, or that cannot reach the
 * server, records [PortForward.error] and closes; the listener stays open and a later success clears the error.
 * A refusal that no connection can overcome ([endsForward]) fails the forward and closes the listener.
 *
 * Lock order: the owner may call [snapshot] and [close] under its own lock; this class never calls [onChange]
 * while holding its lock, so [onChange] may take the owner's lock and call [snapshot].
 */
internal class LoopbackPortForwarder(
    val environmentId: String,
    val remotePort: Int,
    private val client: OkHttpClient,
    /** The tunnel upgrade URL for a ticket; it carries the ticket and is never logged. */
    private val tunnelUrl: (String) -> HttpUrl,
    private val tickets: MediaTicketCache,
    private val onChange: () -> Unit,
    private val maxConnections: Int = MAX_TUNNEL_CONNECTIONS,
    /** Shared by every forward that uses [client]; its limit must not exceed the client's dispatcher limits. */
    private val admission: TunnelAdmission = TunnelAdmission(maxConnections),
    private val openTimeoutMillis: Long = TUNNEL_OPEN_TIMEOUT_MS,
    private val binder: (Int) -> ServerSocket = ::bindLoopback,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + tunnelDispatcher)
    private var server: ServerSocket? = null
    private val connections = mutableSetOf<Connection>()
    private var status = PortForwardStatus.Starting
    private var error: PortForwardError? = null
    private var localPort: Int? = null
    private var closed = false
    /** Completes once [bind] has finished, bound or not. */
    val bound = CompletableDeferred<Unit>()

    fun snapshot(): PortForward = synchronized(lock) { PortForward(environmentId, remotePort, localPort, status, error, connections.size) }

    /** Binds the loopback listener and starts accepting. Blocking. False when it failed or the forward was closed meanwhile. */
    fun bind(): Boolean {
        try {
            val socket: ServerSocket? = try { binder(remotePort) } catch (_: Exception) { null }
            val listener = synchronized(lock) {
                if (closed) { socket?.closeQuietly(); return false }
                if (socket == null) { status = PortForwardStatus.Failed; error = PortForwardError.LocalBindFailed; closed = true; return false }
                server = socket; localPort = socket.localPort; status = PortForwardStatus.Active
                socket
            }
            scope.launch { accept(listener) }
            return true
        } finally { bound.complete(Unit) }
    }

    /** Closes the listener and every connection. The last state stays readable through [snapshot]. */
    fun close() {
        val open = synchronized(lock) {
            closed = true
            server?.closeQuietly()
            connections.toList().also { connections.clear() }
        }
        open.forEach { it.close() }
        scope.cancel()
        bound.complete(Unit)
    }

    private fun accept(listener: ServerSocket) {
        while (true) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            val connection = synchronized(lock) {
                if (closed || connections.size >= maxConnections || !admission.tryAcquire()) null else Connection(socket).also { connections += it }
            }
            if (connection == null) { socket.closeQuietly(); continue }
            changed()
            scope.launch { connection.run() }
        }
    }

    private fun changed() { if (!synchronized(lock) { closed }) onChange() }

    /**
     * Records a connection's failure. A terminal one sets Failed and marks the forward closed in the same critical
     * section, so no concurrent success or failure can overwrite it; teardown follows outside the lock.
     */
    internal fun recordFailure(failure: PortForwardError) {
        val ends = synchronized(lock) {
            if (closed) return
            error = failure
            if (failure.endsForward) { status = PortForwardStatus.Failed; localPort = null; closed = true }
            failure.endsForward
        }
        if (ends) {
            close()
            onChange()
        } else changed()
    }

    internal fun recordSuccess() {
        val cleared = synchronized(lock) { (!closed && error != null).also { if (it) error = null } }
        if (cleared) onChange()
    }

    private inner class Connection(private val socket: Socket) {
        @Volatile private var webSocket: WebSocket? = null
        private val ended = CompletableDeferred<Unit>()
        private val released = java.util.concurrent.atomic.AtomicBoolean()
        /** Set before every cancel this side starts, so only an unexpected transport failure reports Unreachable. */
        @Volatile private var shuttingDown = false
        /** The tunnel broke after the upgrade: the page gets a reset instead of a clean EOF that looks complete. */
        @Volatile private var aborted = false

        fun close() { shuttingDown = true; webSocket?.cancel(); socket.closeQuietly(); ended.complete(Unit); release() }

        /** Returns the admission slot once, whether [run] ran or the forward closed before it started. */
        private fun release() { if (released.compareAndSet(false, true)) admission.release() }

        suspend fun run() {
            try {
                val ws = open() ?: return
                recordSuccess()
                val upstream = scope.launch { pumpUp(ws) }
                ended.await()
                if (aborted) { runCatching { socket.setSoLinger(true, 0) }; return }
                // Let the page read what already arrived before the connection ends.
                runCatching { socket.getOutputStream().flush(); socket.shutdownOutput() }
                withTimeoutOrNull(1_000) { upstream.join() }
            } finally {
                shuttingDown = true
                webSocket?.cancel()
                socket.closeQuietly()
                release()
                val removed = synchronized(lock) { connections.remove(this) }
                if (removed) changed()
            }
        }

        /** Opens the tunnel, retrying once with a fresh ticket when the server refuses the cached one. */
        private suspend fun open(): WebSocket? {
            var retried = false
            while (true) {
                val ticket = try { tickets.ticket() }
                catch (error: CancellationException) { throw error }
                catch (error: MediaTicketUnavailable) { recordFailure(error.error); return null }
                catch (_: Exception) { recordFailure(PortForwardError.Unreachable); return null }
                val (ws, failure) = handshake(ticket)
                if (ws != null) return ws
                // Neither: the forward closed before the upgrade could be enqueued.
                if (failure == null) return null
                if (failure == PortForwardError.Unauthorized && !retried) { tickets.invalidate(ticket); retried = true; continue }
                recordFailure(failure)
                return null
            }
        }

        /** The open tunnel, or its refusal; both null when the forward closed first and nothing was enqueued. */
        private suspend fun handshake(ticket: String): Pair<WebSocket?, PortForwardError?> {
            val opened = CompletableDeferred<PortForwardError?>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(null) }
                // Writing on OkHttp's reader thread blocks it, so a page that stops reading stops the tunnel from reading.
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { toPage(webSocket, bytes.toByteArray()) }
                override fun onMessage(webSocket: WebSocket, text: String) { toPage(webSocket, text.encodeToByteArray()) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); ended.complete(Unit) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    // A failed handshake belongs to this attempt only; a retry must not inherit its end.
                    val failure = if (response == null) PortForwardError.Unreachable else classifyTunnelHandshake(response.code) { response.header(it) }
                    if (opened.complete(failure)) return
                    // After the upgrade: a reset or dropped transport the page did not ask for.
                    if (!shuttingDown) { aborted = true; recordFailure(PortForwardError.Unreachable) }
                    ended.complete(Unit)
                }
            }
            // Enqueued under the lock that [close] takes, so an upgrade is either visible to close or never starts.
            val ws = synchronized(lock) {
                if (closed || this !in connections) return null to null
                client.newWebSocket(Request.Builder().url(tunnelUrl(ticket)).build(), listener).also { webSocket = it }
            }
            // A null outcome is an open tunnel, so a timeout is told apart by its exception, not by a null.
            val failure = try { withTimeout(openTimeoutMillis) { opened.await() } }
            catch (_: TimeoutCancellationException) { PortForwardError.Unreachable }
            catch (error: CancellationException) { shuttingDown = true; ws.cancel(); throw error }
            if (failure != null) { ws.cancel(); webSocket = null; return null to failure }
            return ws to null
        }

        private fun toPage(webSocket: WebSocket, bytes: ByteArray) {
            try { socket.getOutputStream().write(bytes) } catch (_: IOException) { shuttingDown = true; webSocket.cancel(); ended.complete(Unit) }
        }

        private suspend fun pumpUp(ws: WebSocket) {
            val buffer = ByteArray(64 * 1024)
            try {
                val input = socket.getInputStream()
                while (true) {
                    // Local EOF may be a half-close after the request: the tunnel has no half-close signal, so the
                    // response keeps flowing until the remote side ends, errors, or the page closes its socket.
                    val count = input.read(buffer)
                    if (count < 0) return
                    if (!ws.send(buffer.toByteString(0, count))) return
                    // OkHttp queues sends without limit and fails the socket past 16 MiB.
                    while (ws.queueSize() > SEND_HIGH_WATER_BYTES && !ended.isCompleted) delay(5)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: IOException) { shuttingDown = true; ws.cancel(); ended.complete(Unit) }
        }
    }
}

private fun ServerSocket.closeQuietly() { runCatching { close() } }
private fun Socket.closeQuietly() { runCatching { close() } }

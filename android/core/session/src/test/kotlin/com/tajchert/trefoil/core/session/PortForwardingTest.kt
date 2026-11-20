package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PortForwardingTest {
    private val loopback: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))

    @Test fun discoverySnapshotToleratesAdditiveFieldsAndSkipsMalformedEntries() {
        val snapshot = Json.parseToJsonElement("""{
            "servers":[
              {"host":"127.0.0.1","port":5173,"url":"http://127.0.0.1:5173/","processName":"node","pid":42,
               "terminal":{"threadId":"t1","terminalId":"term"},"agent":{"threadId":"t2"},"future":{"x":1}},
              {"host":"::1","port":5173,"url":"http://[::1]:5173/","processName":null,"pid":null,"terminal":null},
              {"host":"127.0.0.1","port":3000,"url":"http://127.0.0.1:3000/","processName":7,"pid":-1,"terminal":{"threadId":"t3"}},
              {"host":"0.0.0.0","port":8080,"url":"http://0.0.0.0:8080/","processName":"  ","pid":"12","terminal":null,"agent":null},
              {"port":4000,"url":"http://127.0.0.1:4000/"},
              {"host":"127.0.0.1","port":0,"url":"http://127.0.0.1:0/"},
              {"host":"127.0.0.1","port":70000,"url":"http://127.0.0.1:70000/"},
              {"host":"127.0.0.1","port":"5000","url":"http://127.0.0.1:5000/"},
              {"host":"127.0.0.1","port":5001},
              "not an object", null
            ],
            "scannedAt":"2026-11-20T10:00:00.000Z","configuredUrlProbing":true,"extra":[1,2]
        }""").jsonObject
        val catalog = discoveredLocalServers(snapshot)!!
        assertEquals(listOf(5173, 3000, 8080), catalog.servers.map { it.port })
        assertEquals("2026-11-20T10:00:00.000Z", catalog.scannedAt)
        assertTrue(catalog.live); assertTrue(catalog.supported)
        val vite = catalog.servers[0]
        assertEquals(DiscoveredLocalServer("127.0.0.1", 5173, "http://127.0.0.1:5173/", "node", 42, "t1", "term", "t2"), vite)
        assertEquals("t2", vite.threadId)
        val partial = catalog.servers[1]
        assertNull(partial.processName); assertNull(partial.pid); assertNull(partial.terminalThreadId); assertNull(partial.threadId)
        assertNull(catalog.servers[2].processName); assertNull(catalog.servers[2].pid)
        assertNull(discoveredLocalServers(Json.parseToJsonElement("""{"scannedAt":"x"}""").jsonObject))
        assertEquals(emptyList<DiscoveredLocalServer>(), discoveredLocalServers(Json.parseToJsonElement("""{"servers":[],"scannedAt":"x"}""").jsonObject)!!.servers)
    }

    @Test fun handshakeClassificationKeysOnTheTunnelHeaders() {
        fun classify(status: Int?, vararg headers: Pair<String, String>) = classifyTunnelHandshake(status) { name -> headers.firstOrNull { it.first.equals(name, true) }?.second }
        assertEquals(PortForwardError.TunnelDisabled, classify(403, "x-t3-tunnel-disabled" to "1"))
        assertEquals(PortForwardError.TunnelDisabled, classify(502, "X-T3-Tunnel-Disabled" to "1"))
        assertEquals(PortForwardError.PortRefused, classify(403, "x-t3-tunnel-port-refused" to "1"))
        assertEquals(PortForwardError.PortRefused, classify(502, "x-t3-tunnel-port-refused" to "1"))
        assertEquals(PortForwardError.ServerOutdated, classify(200))
        assertEquals(PortForwardError.ServerOutdated, classify(204))
        assertEquals(PortForwardError.ServerOutdated, classify(404))
        assertEquals(PortForwardError.Unauthorized, classify(401))
        assertEquals(PortForwardError.Unauthorized, classify(403))
        assertEquals(PortForwardError.Unreachable, classify(502))
        assertEquals(PortForwardError.Unreachable, classify(400))
        assertEquals(PortForwardError.Unreachable, classify(500))
        assertEquals(PortForwardError.Unreachable, classify(null))
    }

    @Test fun mediaTicketIsReusedAndRefreshedBeforeExpiry() = runBlocking {
        var now = 1_000_000L
        val mints = AtomicInteger()
        var expiresIn: Long? = 300_000L
        val cache = MediaTicketCache({ now }) { val n = mints.incrementAndGet(); MintedMediaTicket("ticket-$n", expiresIn?.let { now + it }) }
        assertEquals("ticket-1", cache.ticket())
        now += 200_000
        assertEquals("ticket-1", cache.ticket())
        now += 70_001 // within 30 s of expiresAt
        assertEquals("ticket-2", cache.ticket())
        cache.invalidate("ticket-1") // stale refusal does not drop the newer ticket
        assertEquals("ticket-2", cache.ticket())
        cache.invalidate("ticket-2")
        expiresIn = null
        assertEquals("ticket-3", cache.ticket())
        now += 239_000
        assertEquals("ticket-3", cache.ticket())
        now += 2_000 // unknown expiry, older than 4 minutes
        assertEquals("ticket-4", cache.ticket())
        assertEquals(4, mints.get())
        assertFalse(cache.toString().contains("ticket-"))
        assertFalse(MintedMediaTicket("secret", null).toString().contains("secret"))
    }

    @Test fun concurrentConnectionsShareOneMint() = runBlocking {
        val mints = AtomicInteger()
        val cache = MediaTicketCache { mints.incrementAndGet(); delay(50); MintedMediaTicket("shared", null) }
        val tickets = (1..6).map { async(Dispatchers.Default) { cache.ticket() } }.awaitAll()
        assertEquals(setOf("shared"), tickets.toSet()); assertEquals(1, mints.get())
    }

    @Test fun mediaTicketExpiryReadsIsoAndMillis() {
        assertEquals(java.time.Instant.parse("2026-11-20T10:05:00Z").toEpochMilli(), mediaTicketExpiry(buildJsonObject { put("expiresAt", "2026-11-20T10:05:00Z") }))
        assertEquals(1_800_000_000_000L, mediaTicketExpiry(buildJsonObject { put("expiresAt", 1_800_000_000_000L) }))
        assertNull(mediaTicketExpiry(buildJsonObject { put("expiresAt", "soon") }))
        assertNull(mediaTicketExpiry(buildJsonObject {}))
    }

    @Test fun bytesRoundTripBothWaysThroughOneWebSocketPerConnection() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(5173)
            assertTrue(forwarder.bind())
            val first = f.open(forwarder)
            assertEquals("hello", first.readText(5)) // the server may speak first (binary frame)
            assertEquals("text!", first.readText(5)) // a text frame arrives as its UTF-8 bytes
            val payload = ByteArray(200_000) { (it % 251).toByte() }
            first.getOutputStream().write(payload)
            assertArrayEquals(payload, first.readBytes(payload.size))
            val second = f.open(forwarder)
            assertEquals("hello", second.readText(5))
            second.getOutputStream().write("ping".toByteArray())
            assertEquals("text!ping", second.readText(9))
            waitFor { forwarder.snapshot().activeConnections == 2 }
            val requests = listOf(f.requests.poll()!!, f.requests.poll()!!)
            requests.forEach { request ->
                assertEquals("/api/preview/tunnel", request.requestUrl!!.encodedPath)
                assertEquals("5173", request.requestUrl!!.queryParameter("port"))
                assertEquals("ticket-1", request.requestUrl!!.queryParameter("wsTicket"))
                assertEquals("websocket", request.getHeader("Upgrade"))
            }
            assertEquals(1, f.mints.get())
            assertEquals(PortForwardStatus.Active, forwarder.snapshot().status)
            assertNull(forwarder.snapshot().error)
            first.close()
            waitFor { forwarder.snapshot().activeConnections == 1 }
            forwarder.close()
        }
    }

    @Test fun portRefusalMarksTheForwardButKeepsListening() = runBlocking {
        TunnelFixture().use { f ->
            val refusals = AtomicInteger(2)
            f.respond = {
                if (refusals.getAndDecrement() == 2) MockResponse().setResponseCode(502).setHeader(TUNNEL_PORT_REFUSED_HEADER, "1").setBody("Nothing accepted")
                else if (refusals.get() == 0) MockResponse().setResponseCode(502).setBody("proxy")
                else MockResponse().withWebSocketUpgrade(f.echo)
            }
            val forwarder = f.forwarder(3000).also { it.bind() }
            val refused = f.open(forwarder)
            assertEquals(-1, refused.getInputStream().read())
            waitFor { forwarder.snapshot().error == PortForwardError.PortRefused }
            assertEquals(PortForwardStatus.Active, forwarder.snapshot().status)
            val unreachable = f.open(forwarder)
            assertEquals(-1, unreachable.getInputStream().read())
            waitFor { forwarder.snapshot().error == PortForwardError.Unreachable }
            assertEquals(PortForwardStatus.Active, forwarder.snapshot().status)
            val ok = f.open(forwarder)
            assertEquals("hello", ok.readText(5))
            waitFor { forwarder.snapshot().error == null }
            forwarder.close()
        }
    }

    @Test fun disabledOutdatedAndUnauthorizedFailTheForwardAndCloseTheListener() = runBlocking {
        val cases = listOf(
            PortForwardError.TunnelDisabled to { MockResponse().setResponseCode(403).setHeader(TUNNEL_DISABLED_HEADER, "1") },
            PortForwardError.ServerOutdated to { MockResponse().setResponseCode(404) },
            PortForwardError.ServerOutdated to { MockResponse().setResponseCode(200).setBody("<html>") },
            PortForwardError.Unauthorized to { MockResponse().setResponseCode(401) },
            PortForwardError.Unauthorized to { MockResponse().setResponseCode(403) },
        )
        for ((expected, response) in cases) TunnelFixture().use { f ->
            f.respond = { response() }
            val forwarder = f.forwarder(4321).also { it.bind() }
            val port = forwarder.snapshot().localPort!!
            val socket = Socket(loopback, port).apply { soTimeout = 5_000 }
            assertEquals(-1, socket.getInputStream().read())
            waitFor { forwarder.snapshot().status == PortForwardStatus.Failed }
            assertEquals(expected, forwarder.snapshot().error)
            assertNull(forwarder.snapshot().localUrl)
            assertRefused(port)
            // Unauthorized retries once with a fresh ticket before failing.
            assertEquals(if (expected == PortForwardError.Unauthorized) 2 else 1, f.mints.get())
        }
    }

    @Test fun refusedTicketIsReplacedOnceAndTheConnectionSucceeds() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { request -> if (request.requestUrl!!.queryParameter("wsTicket") == "ticket-1") MockResponse().setResponseCode(401) else MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(4000).also { it.bind() }
            assertEquals("hello", f.open(forwarder).readText(5))
            assertEquals(2, f.mints.get())
            assertNull(forwarder.snapshot().error)
            forwarder.close()
        }
    }

    @Test fun ticketMintFailuresClassifyTheConnection() = runBlocking {
        TunnelFixture().use { f ->
            f.mintFailure = PortForwardError.Unreachable
            val forwarder = f.forwarder(4001).also { it.bind() }
            assertEquals(-1, f.open(forwarder).getInputStream().read())
            waitFor { forwarder.snapshot().error == PortForwardError.Unreachable }
            assertEquals(PortForwardStatus.Active, forwarder.snapshot().status)
            f.mintFailure = PortForwardError.Unauthorized
            assertEquals(-1, f.open(forwarder).getInputStream().read())
            waitFor { forwarder.snapshot().status == PortForwardStatus.Failed }
            assertEquals(PortForwardError.Unauthorized, forwarder.snapshot().error)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun capsConcurrentConnectionsAtSixtyFour() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(4002).also { it.bind() }
            val open = (1..MAX_TUNNEL_CONNECTIONS).map { f.open(forwarder).also { s -> assertEquals("hello", s.readText(5)) } }
            assertEquals(MAX_TUNNEL_CONNECTIONS, forwarder.snapshot().activeConnections)
            val extra = f.open(forwarder)
            assertEquals(-1, extra.getInputStream().read())
            assertEquals(MAX_TUNNEL_CONNECTIONS, f.server.requestCount)
            open.first().close()
            waitFor { forwarder.snapshot().activeConnections == MAX_TUNNEL_CONNECTIONS - 1 }
            assertEquals("hello", f.open(forwarder).readText(5))
            forwarder.close()
            open.drop(1).forEach { assertClosed(it) }
        }
    }

    @Test fun bindsTheSamePortAndFallsBackWhenBusyOrPrivileged() {
        val free = ServerSocket(0, 50, loopback).use { it.localPort }
        bindLoopback(free).use { assertEquals(free, it.localPort) }
        ServerSocket(0, 50, loopback).use { busy ->
            bindLoopback(busy.localPort).use { assertNotEquals(busy.localPort, it.localPort); assertTrue(it.localPort > 0) }
        }
        bindLoopback(80).use { assertNotEquals(80, it.localPort) }
        bindLoopback(free).use { assertEquals(loopback, it.inetAddress) }
    }

    @Test fun bindFailureFailsTheForward() = runBlocking {
        TunnelFixture().use { f ->
            val forwarder = LoopbackPortForwarder("env", 5000, f.http, f::tunnelUrl, f.tickets, {}, binder = { throw IOException("no ports") })
            assertFalse(forwarder.bind())
            assertEquals(PortForward("env", 5000, null, PortForwardStatus.Failed, PortForwardError.LocalBindFailed), forwarder.snapshot())
        }
    }

    @Test fun closeEndsEveryConnectionAndTheListener() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(4003).also { it.bind() }
            val port = forwarder.snapshot().localPort!!
            val sockets = (1..3).map { f.open(forwarder).also { s -> assertEquals("hello", s.readText(5)) } }
            forwarder.close()
            sockets.forEach { assertClosed(it) }
            assertRefused(port)
            withTimeout(5_000) { while (f.serverClosed.get() < 3) delay(10) }
            assertEquals(0, forwarder.snapshot().activeConnections)
        }
    }

    private class TunnelFixture : AutoCloseable {
        val server = MockWebServer()
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        @Volatile var respond: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(500) }
        val mints = AtomicInteger()
        @Volatile var mintFailure: PortForwardError? = null
        val serverClosed = AtomicInteger()
        val http: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val tickets = MediaTicketCache {
            mintFailure?.let { throw MediaTicketUnavailable(it) }
            MintedMediaTicket("ticket-${mints.incrementAndGet()}", null)
        }
        /** Speaks first in binary and text, then echoes every binary frame back. */
        val echo = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("hello".encodeUtf8()); webSocket.send("text!") }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); serverClosed.incrementAndGet() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { serverClosed.incrementAndGet() }
        }
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return respond(request) }
            }
            server.start()
        }
        fun tunnelUrl(ticket: String): HttpUrl = server.url("/api/preview/tunnel").newBuilder().addQueryParameter("port", "5173").addQueryParameter("wsTicket", ticket).build()
        fun forwarder(remotePort: Int) = LoopbackPortForwarder("env", remotePort, http,
            { ticket -> server.url("/api/preview/tunnel").newBuilder().addQueryParameter("port", remotePort.toString()).addQueryParameter("wsTicket", ticket).build() },
            tickets, {}, binder = { ServerSocket(0, 50, InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))) })
        fun open(forwarder: LoopbackPortForwarder): Socket =
            Socket(InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), forwarder.snapshot().localPort!!).apply { soTimeout = 5_000 }
        override fun close() { server.shutdown() }
    }

    private fun Socket.readBytes(count: Int): ByteArray {
        val out = ByteArray(count); var read = 0
        while (read < count) { val n = getInputStream().read(out, read, count - read); if (n < 0) error("EOF after $read bytes"); read += n }
        return out
    }
    private fun Socket.readText(count: Int) = String(readBytes(count))
    private fun assertClosed(socket: Socket) {
        // Drains bytes already delivered; the connection must then end (EOF or reset) within the socket timeout.
        val result = try { val input = socket.getInputStream(); var next = input.read(); var drained = 0
            while (next >= 0 && drained++ < 1_000_000) next = input.read(); next } catch (_: IOException) { -1 }
        assertEquals(-1, result)
    }
    private fun assertRefused(port: Int) {
        try { Socket(loopback, port).close(); fail("Listener on $port is still open") } catch (_: ConnectException) {}
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
}

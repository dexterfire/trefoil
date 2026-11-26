package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PortForwardingTest {
    private val loopback: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    private val ipv6Loopback: InetAddress = InetAddress.getByAddress("localhost", ByteArray(16).also { it[15] = 1 })
    private fun ipv6LoopbackUsable() = try { ServerSocket(0, 50, ipv6Loopback).close(); true } catch (_: IOException) { false }

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
        // Only T3's exact "1" is a marker; anything else falls through to the status.
        assertEquals(PortForwardError.PortRefused, classify(502, "x-t3-tunnel-port-refused" to " 1 "))
        assertEquals(PortForwardError.Unauthorized, classify(403, "x-t3-tunnel-port-refused" to "0"))
        assertEquals(PortForwardError.Unreachable, classify(502, "x-t3-tunnel-disabled" to "yes"))
        assertEquals(PortForwardError.ServerOutdated, classify(404, "x-t3-tunnel-disabled" to ""))
        assertEquals(PortForwardError.Unauthorized, classify(401, "x-t3-tunnel-disabled" to "true", "x-t3-tunnel-port-refused" to "10"))
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
            // A FIN is only a half-close (see aHalfClosedRequest...); a page that drops the connection resets it.
            first.setSoLinger(true, 0); first.close()
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
            awaitRefused(port)
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
            open.first().apply { setSoLinger(true, 0) }.close()
            waitFor { forwarder.snapshot().activeConnections == MAX_TUNNEL_CONNECTIONS - 1 }
            assertEquals("hello", f.open(forwarder).readText(5))
            forwarder.close()
            open.drop(1).forEach { assertClosed(it) }
        }
    }

    @Test fun forwardsShareOneAdmissionSizedToTheirOwnDispatcherSoUpgradesNeverQueue() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val admission = TunnelAdmission(3)
            val client = tunnelClient(f.http, admission)
            assertNotSame(f.http.dispatcher, client.dispatcher)
            assertEquals(3, client.dispatcher.maxRequests)
            assertEquals(0, client.readTimeoutMillis)
            val a = f.forwarder(4100, client, admission).also { it.bind() }
            val b = f.forwarder(4101, client, admission).also { it.bind() }
            val open = listOf(f.open(a), f.open(a), f.open(b)).onEach { assertEquals("hello", it.readText(5)) }
            // The limit is shared: B has room of its own but the client does not.
            assertEquals(-1, f.open(b).getInputStream().read())
            assertEquals(3, f.server.requestCount)
            assertEquals(0, client.dispatcher.queuedCallsCount())
            open.first().apply { setSoLinger(true, 0); close() }
            waitFor { a.snapshot().activeConnections == 1 && admission.inUse == 2 }
            assertEquals("hello", f.open(b).readText(5))
            assertEquals(0, client.dispatcher.queuedCallsCount())
            a.close(); b.close()
            waitFor { admission.inUse == 0 }
        }
    }

    @Test fun closeWhileAConnectionHoldsATicketNeverEnqueuesItsUpgrade() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val minting = CountDownLatch(1)
            val release = CountDownLatch(1)
            // Blocks without suspending, so the ticket comes back to a worker whose forward closed meanwhile.
            val tickets = MediaTicketCache { minting.countDown(); release.await(); MintedMediaTicket("late", null) }
            val forwarder = f.forwarder(4102, tickets = tickets).also { it.bind() }
            val socket = f.open(forwarder)
            assertTrue(minting.await(5, TimeUnit.SECONDS))
            forwarder.close()
            release.countDown()
            assertClosed(socket)
            delay(300)
            assertEquals("no upgrade may be built after close", 0, f.upgrades.get())
            assertEquals(0, f.server.requestCount)
            assertEquals(0, f.http.dispatcher.runningCallsCount() + f.http.dispatcher.queuedCallsCount())
        }
    }

    @Test fun aHalfClosedRequestStillReceivesTheWholeResponse() = runBlocking {
        TunnelFixture().use { f ->
            // Like T3: replies after a while, and ends the tunnel on a close frame.
            val slowReply = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    thread { Thread.sleep(200); webSocket.send("reply:".encodeUtf8().toByteArray().plus(bytes.toByteArray()).let { ByteString.of(*it) }); webSocket.close(1000, null) }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            }
            f.respond = { MockResponse().withWebSocketUpgrade(slowReply) }
            val forwarder = f.forwarder(4103).also { it.bind() }
            val socket = f.open(forwarder)
            socket.getOutputStream().apply { write("ping".toByteArray()); flush() }
            socket.shutdownOutput()
            assertEquals("reply:ping", String(socket.getInputStream().readBytes()))
            waitFor { forwarder.snapshot().activeConnections == 0 }
            assertNull(forwarder.snapshot().error)
            forwarder.close()
        }
    }

    @Test fun aTransportFailureAfterTheUpgradeReportsUnreachableButAPageResetDoesNot() = runBlocking {
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(4104).also { it.bind() }
            val dropped = f.open(forwarder)
            assertEquals("hello", dropped.readText(5))
            // The server vanishes mid-stream: its sockets close without a close frame.
            f.server.shutdown()
            assertClosed(dropped)
            waitFor { forwarder.snapshot().error == PortForwardError.Unreachable }
            assertEquals(PortForwardStatus.Active, forwarder.snapshot().status)
            waitFor { forwarder.snapshot().activeConnections == 0 }
            forwarder.close()
        }
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(4107).also { it.bind() }
            val page = f.open(forwarder)
            assertEquals("hello", page.readText(5))
            waitFor { forwarder.snapshot().error == null }
            page.setSoLinger(true, 0); page.close()
            waitFor { forwarder.snapshot().activeConnections == 0 }
            withTimeout(5_000) { while (f.serverClosed.get() < 1) delay(10) }
            assertNull(forwarder.snapshot().error)
            forwarder.close()
        }
    }

    @Test fun aTerminalFailureIsNeverOverwritten() {
        TunnelFixture().use { f ->
            val forwarder = f.forwarder(4105).also { it.bind() }
            forwarder.recordFailure(PortForwardError.Unauthorized)
            forwarder.recordSuccess()
            forwarder.recordFailure(PortForwardError.PortRefused)
            forwarder.recordFailure(PortForwardError.TunnelDisabled)
            assertEquals(PortForward("env", 4105, null, PortForwardStatus.Failed, PortForwardError.Unauthorized, instance = forwarder.instance), forwarder.snapshot())
            repeat(200) { round ->
                val racing = f.forwarder(4106).also { it.bind() }
                val start = CyclicBarrier(4)
                // Other connections keep succeeding and failing while one hits a terminal refusal.
                fun churn(action: () -> Unit): () -> Unit = { repeat(2_000) { action() } }
                listOf({ racing.recordFailure(PortForwardError.ServerOutdated) },
                    churn { racing.recordFailure(PortForwardError.PortRefused); racing.recordSuccess() },
                    churn { racing.recordSuccess() }, churn { racing.recordFailure(PortForwardError.Unreachable) })
                    .map { action -> thread { start.await(); action() } }.forEach { it.join() }
                val snapshot = racing.snapshot()
                assertEquals("round $round", PortForwardStatus.Failed, snapshot.status)
                assertEquals("round $round", PortForwardError.ServerOutdated, snapshot.error)
            }
        }
    }

    @Test fun bindsTheSamePortAndFallsBackWhenBusyOrPrivileged() {
        val free = ServerSocket(0, 50, loopback).use { it.localPort }
        bindLoopback(free).use { assertEquals(free, it.port) }
        ServerSocket(0, 50, loopback).use { busy ->
            bindLoopback(busy.localPort).use { assertNotEquals(busy.localPort, it.port); assertTrue(it.port > 0) }
        }
        bindLoopback(80).use { assertNotEquals(80, it.port) }
        bindLoopback(free).use { listeners -> assertTrue(listeners.sockets.all { it.inetAddress.isLoopbackAddress }) }
    }

    @Test fun bindsBothLoopbackAddressesOnOnePortAndClosesThemTogether() {
        Assume.assumeTrue("No IPv6 loopback on this machine", ipv6LoopbackUsable())
        val free = ServerSocket(0, 50, loopback).use { it.localPort }
        val listeners = bindLoopback(free)
        assertTrue(listeners.dualStack)
        assertEquals(PortForward.LOCALHOST, listeners.host)
        assertEquals(setOf<InetAddress>(loopback, ipv6Loopback), listeners.sockets.map { it.inetAddress }.toSet())
        assertEquals(listOf(free, free), listeners.sockets.map { it.localPort })
        listeners.close()
        assertTrue(listeners.sockets.all { it.isClosed })
    }

    @Test fun anotherAppsIpv6ListenerOnThePreferredPortMovesTheForwardToAPortWeOwnOnBothAddresses() {
        Assume.assumeTrue("No IPv6 loopback on this machine", ipv6LoopbackUsable())
        ServerSocket(0, 50, ipv6Loopback).use { foreign ->
            val listeners = bindLoopback(foreign.localPort)
            try {
                assertTrue(listeners.dualStack)
                assertNotEquals(foreign.localPort, listeners.port)
                assertEquals(PortForward.LOCALHOST, listeners.host)
                // The old port is still free on IPv4: it must not stay bound, or the page's origin would be half ours.
                ServerSocket(foreign.localPort, 50, loopback).close()
            } finally { listeners.close() }
        }
    }

    @Test fun withoutIpv6LoopbackOnlyIpv4IsBoundAndTheUrlSaysSo() {
        val noIpv6: (InetAddress, Int) -> ServerSocket = { address, port ->
            if (address.address.size == 16) throw java.net.SocketException("Cannot assign requested address") else ServerSocket(port, 50, address)
        }
        val free = ServerSocket(0, 50, loopback).use { it.localPort }
        bindLoopback(free, noIpv6).use { listeners ->
            assertFalse(listeners.dualStack)
            assertEquals(1, listeners.sockets.size)
            assertEquals(free, listeners.port)
            assertEquals(PortForward.LOOPBACK_V4, listeners.host)
            assertEquals("http://127.0.0.1:$free/", PortForward("env", 1, listeners.port, PortForwardStatus.Active, localHost = listeners.host).localUrl)
        }
        bindLoopback(80, noIpv6).use { assertFalse(it.dualStack); assertNotEquals(80, it.port) }
    }

    @Test fun anIpv4OnlyForwardHandsOutALiteralIpv4UrlAndOwnsNothingElse() = runBlocking {
        TunnelFixture().use { f ->
            val forwarder = LoopbackPortForwarder("env", 4011, f.http, f::tunnelUrl, f.tickets, {}, binder = { LoopbackListeners(listOf(ServerSocket(0, 50, loopback)), dualStack = false) })
            assertTrue(forwarder.bind())
            val snapshot = forwarder.snapshot()
            assertEquals("http://127.0.0.1:${snapshot.localPort}/", snapshot.localUrl)
            forwarder.close()
        }
    }

    @Test fun aForwardThatLostOneListenerFailsInsteadOfLeavingItsAddressFree() = runBlocking {
        TunnelFixture().use { f ->
            Assume.assumeTrue("No IPv6 loopback on this machine", ipv6LoopbackUsable())
            // One port on both families, like a real dual-stack forward.
            val v4 = ServerSocket(0, 50, loopback)
            val v6 = try { ServerSocket(v4.localPort, 50, ipv6Loopback) } catch (error: IOException) { v4.close(); Assume.assumeNoException(error); return@use }
            val forwarder = LoopbackPortForwarder("env", 4012, f.http, f::tunnelUrl, f.tickets, {}, binder = { LoopbackListeners(listOf(v4, v6), dualStack = true) })
            assertTrue(forwarder.bind())
            v6.close()
            waitFor { forwarder.snapshot().status == PortForwardStatus.Failed }
            assertEquals(PortForwardError.LocalBindFailed, forwarder.snapshot().error)
            // Failed is published before the surviving listener is closed, so wait for that close.
            waitFor { v4.isClosed }
            assertTrue(v4.isClosed)
        }
    }

    @Test fun bindFailureFailsTheForward() = runBlocking {
        TunnelFixture().use { f ->
            val forwarder = LoopbackPortForwarder("env", 5000, f.http, f::tunnelUrl, f.tickets, {}, binder = { throw IOException("no ports") })
            assertFalse(forwarder.bind())
            assertEquals(PortForward("env", 5000, null, PortForwardStatus.Failed, PortForwardError.LocalBindFailed, instance = forwarder.instance), forwarder.snapshot())
            // A retry on the same port is another listener: holds made for this one never carry over.
            val retry = LoopbackPortForwarder("env", 5000, f.http, f::tunnelUrl, f.tickets, {}, binder = { throw IOException("no ports") })
            assertNotEquals(forwarder.instance, retry.instance)
            assertNotEquals(forwarder.snapshot(), retry.snapshot().copy(status = PortForwardStatus.Failed, error = PortForwardError.LocalBindFailed))
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
            assertTrue("The server still holds a tunnel", f.awaitServerClosed(3))
            assertEquals(0, forwarder.snapshot().activeConnections)
        }
    }

    /**
     * `localhost` may resolve to `::1` first, and the forward's URL says `localhost`: an IPv6 listener of another app
     * on that port would answer instead of the tunnel. So `[::1]:N` must be ours too, and close with the forward.
     */
    @Test fun theForwardAnswersOnIpv6LoopbackAndClosesItWithTheIpv4Listener() = runBlocking {
        Assume.assumeTrue("No IPv6 loopback on this machine", ipv6LoopbackUsable())
        TunnelFixture().use { f ->
            f.respond = { MockResponse().withWebSocketUpgrade(f.echo) }
            val forwarder = f.forwarder(4010).also { assertTrue(it.bind()) }
            val port = forwarder.snapshot().localPort!!
            val viaV6 = Socket(ipv6Loopback, port).apply { soTimeout = 5_000 }
            assertEquals("hello", viaV6.readText(5))
            val viaV4 = f.open(forwarder)
            assertEquals("hello", viaV4.readText(5))
            waitFor { forwarder.snapshot().activeConnections == 2 }
            forwarder.close()
            assertClosed(viaV6); assertClosed(viaV4)
            assertRefused(port)
            try { Socket(ipv6Loopback, port).close(); fail("[::1]:$port is still open") } catch (_: ConnectException) {}
        }
    }

    private class TunnelFixture : AutoCloseable {
        val server = MockWebServer()
        val requests = ConcurrentLinkedQueue<RecordedRequest>()
        @Volatile var respond: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(500) }
        val mints = AtomicInteger()
        /** Upgrade URLs built: each one is a WebSocket about to be enqueued. */
        val upgrades = AtomicInteger()
        @Volatile var mintFailure: PortForwardError? = null
        val serverClosed = AtomicInteger()
        /** Released once per tunnel the server saw end, so a test waits on the signal instead of polling. */
        private val serverClosedSignal = Semaphore(0)
        /** True when [count] tunnels ended before the deadline; the deadline only guards a real hang. */
        fun awaitServerClosed(count: Int, timeoutMillis: Long = 15_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMillis
            repeat(count) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0 || !serverClosedSignal.tryAcquire(remaining, TimeUnit.MILLISECONDS)) return false
            }
            return true
        }
        val http: OkHttpClient = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val tickets = MediaTicketCache {
            mintFailure?.let { throw MediaTicketUnavailable(it) }
            MintedMediaTicket("ticket-${mints.incrementAndGet()}", null)
        }
        /** Speaks first in binary and text, then echoes every binary frame back. */
        val echo = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("hello".encodeUtf8()); webSocket.send("text!") }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); serverClosed.incrementAndGet(); serverClosedSignal.release() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { serverClosed.incrementAndGet(); serverClosedSignal.release() }
        }
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse { requests += request; return respond(request) }
            }
            server.start()
        }
        fun tunnelUrl(ticket: String): HttpUrl = server.url("/api/preview/tunnel").newBuilder().addQueryParameter("port", "5173").addQueryParameter("wsTicket", ticket).build()
        fun forwarder(remotePort: Int, client: OkHttpClient = http, admission: TunnelAdmission? = null, tickets: MediaTicketCache = this.tickets) =
            LoopbackPortForwarder("env", remotePort, client,
                { ticket -> upgrades.incrementAndGet(); server.url("/api/preview/tunnel").newBuilder().addQueryParameter("port", remotePort.toString()).addQueryParameter("wsTicket", ticket).build() },
                tickets, {}, admission = admission ?: TunnelAdmission(MAX_TUNNEL_CONNECTIONS))
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
        // Drains bytes already delivered; the connection must then end with EOF or a reset. A read that merely times
        // out means the connection is still established, which is not closed.
        val result = try { val input = socket.getInputStream(); var next = input.read(); var drained = 0
            while (next >= 0 && drained++ < 1_000_000) next = input.read(); next }
        catch (_: java.net.SocketTimeoutException) { fail("Socket is still open: the read timed out"); return }
        catch (_: IOException) { -1 }
        assertEquals(-1, result)
    }
    private fun assertRefused(port: Int) {
        try { Socket(loopback, port).close(); fail("Listener on $port is still open") } catch (_: ConnectException) {}
    }
    /**
     * [LoopbackPortForwarder.recordFailure] publishes Failed before it closes the listeners, so the port can still accept
     * for a moment. Wait for the close (condition, not a sleep), then keep the same refusal assertion.
     */
    private suspend fun awaitRefused(port: Int) {
        withTimeoutOrNull(5_000) { while (try { Socket(loopback, port).close(); true } catch (_: ConnectException) { false }) delay(2) }
        assertRefused(port)
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }
}

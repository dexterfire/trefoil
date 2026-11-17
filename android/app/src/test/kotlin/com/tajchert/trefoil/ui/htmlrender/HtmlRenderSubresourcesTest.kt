package com.tajchert.trefoil.ui.htmlrender

import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Call
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class HtmlRenderSubresourcesTest {
    private fun dnsReturning(vararg literals: String) = PublicOnlyDns(object : Dns {
        override fun lookup(hostname: String) = literals.map { InetAddress.getByName(it) }
    })

    @Test fun dnsAllowsPublicAnswers() {
        assertEquals(2, dnsReturning("93.184.216.34", "2606:4700::1111").lookup("cdn.example").size)
    }

    @Test fun dnsRejectsAnyNonPublicAnswer() {
        listOf(
            "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.10", "169.254.169.254", "100.64.0.1",
            "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fd12:3456::1", "fc00::1", "ff02::1",
            "::ffff:127.0.0.1", "::ffff:192.168.1.1", "::127.0.0.1", "64:ff9b::a00:1",
        ).forEach { literal ->
            assertThrows(literal, UnknownHostException::class.java) { dnsReturning(literal).lookup("rebind.example") }
            // A single private answer among public ones still refuses the name.
            assertThrows(literal, UnknownHostException::class.java) { dnsReturning("93.184.216.34", literal).lookup("rebind.example") }
        }
        assertThrows(UnknownHostException::class.java) { dnsReturning().lookup("empty.example") }
    }

    @Test fun dnsRejectsNat64LocalUseAndSixToFourWithPrivateEmbeddedAddress() {
        listOf(
            // 64:ff9b:1::/48 (RFC 8215) is local-use: refused whatever it embeds, including a public-looking IPv4.
            "64:ff9b:1::", "64:ff9b:1::a00:1", "64:ff9b:1:0:0:c0a8:101:0", "64:ff9b:1:ffff:ffff:ffff:ffff:ffff", "64:ff9b:1::5db8:d822",
            // 6to4 (2002::/16) with the embedded IPv4 in bits 16..47: loopback, RFC1918, link-local, CGNAT, this-network.
            "2002:7f00:1::1", "2002:a00:1::", "2002:c0a8:101::1", "2002:ac10:1::1", "2002:a9fe:a9fe::1", "2002:6440:1::1", "2002:0:0::1",
        ).forEach { literal ->
            assertTrue(literal, HtmlRenderAddresses.isNonPublic(InetAddress.getByName(literal)))
            assertThrows(literal, UnknownHostException::class.java) { dnsReturning(literal).lookup("rebind.example") }
            assertThrows(literal, UnknownHostException::class.java) { dnsReturning("93.184.216.34", literal).lookup("rebind.example") }
        }
    }

    @Test fun addressFilterStillAllowsPublicIpv6() {
        listOf(
            "2606:4700::1111", "2001:4860:4860::8888", "64:ff9b::5db8:d822", "64:ff9b:2::1", "2002:5db8:d822::1", "2003::1",
        ).forEach { literal -> assertFalse(literal, HtmlRenderAddresses.isNonPublic(InetAddress.getByName(literal))) }
    }

    @Test fun dnsRefusesPrivateNamesWithoutResolving() {
        val dns = PublicOnlyDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = fail("resolved $hostname").let { emptyList() }
        })
        listOf("localhost", "printer.local", "nas.home.arpa", "intranet", "db.internal").forEach {
            assertThrows(it, UnknownHostException::class.java) { dns.lookup(it) }
        }
    }

    @Test fun requestHeadersAreFiltered() {
        val headers = htmlRenderForwardedRequestHeaders(mapOf(
            "Accept" to "*/*", "Accept-Language" to "en", "Range" to "bytes=0-9", "Origin" to "https://html-render.trefoil.invalid",
            "Referer" to "https://html-render.trefoil.invalid/abc/index.html", "Cookie" to "a=b", "Authorization" to "Bearer fixture-token",
            "User-Agent" to "fixture", "X-Requested-With" to "com.tajchert.trefoil", "If-None-Match" to "\"x\"",
        ))
        // Range is never forwarded: the WebView slices the full stream itself.
        assertEquals(setOf("Accept", "Accept-Language", "Origin"), headers.names())
    }

    @Test fun responseHeadersKeepCorsAndContentOnly() {
        val passed = htmlRenderPassedResponseHeaders(headersOf(
            "Content-Type", "application/json", "Access-Control-Allow-Origin", "*", "access-control-expose-headers", "x-a",
            "Set-Cookie", "a=b", "Set-Cookie", "c=d", "Content-Encoding", "gzip", "Content-Length", "10", "Cache-Control", "max-age=60",
            "Location", "https://10.0.0.1/", "Content-Security-Policy", "default-src *", "Content-Range", "bytes 0-9/100", "Accept-Ranges", "bytes",
        ))
        assertEquals(
            mapOf(
                "Content-Type" to "application/json", "Access-Control-Allow-Origin" to "*", "access-control-expose-headers" to "x-a",
                "Accept-Ranges" to "bytes", "Cache-Control" to "no-store",
            ),
            passed,
        )
    }

    /** [gated] puts the production gate interceptor in front, standing in for its post-connect position. */
    private class FakeServer(gated: Boolean = false, val respond: (Request) -> Response) {
        val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())
        @Volatile var lastCall: Call? = null
        val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .apply { if (gated) addInterceptor(HtmlRenderGateInterceptor) }
            .addInterceptor { chain -> requests += chain.request(); lastCall = chain.call(); respond(chain.request()) }.build()
    }

    private fun response(request: Request, code: Int, location: String? = null, body: String = "", type: String = "text/plain") =
        response(request, code, location, body.toByteArray(Charsets.UTF_8), type)

    private fun response(request: Request, code: Int, location: String?, body: ByteArray, type: String) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Found")
            .apply { location?.let { header("Location", it) } }
            .header("Set-Cookie", "tracking=1")
            .body(body.toResponseBody(type.toMediaType())).build()

    private val policy = HtmlRenderPolicy(htmlRenderDocumentUrl("fixture-env", "fixture-thread", "fixture"), HtmlRenderNetworkAccess({ setOf("env.example") }, { true }))

    @Test fun redirectToPrivateHostIsBlockedBeforeItIsRequested() {
        listOf("https://10.0.0.1/secret", "https://localhost/x", "https://env.example/api", "http://cdn.example/x", "https://[fd00::1]/x").forEach { target ->
            val server = FakeServer { response(it, 302, location = target) }
            val result = HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/x.js", emptyMap(), policy::allowsFetch)
            assertEquals(target, 403, result.status)
            assertEquals(target, 1, server.requests.size)
        }
    }

    @Test fun publicRedirectsAreFollowedWithFilteredHeaders() {
        val server = FakeServer { request ->
            when (request.url.host + request.url.encodedPath) {
                "cdn.example/x.js" -> response(request, 301, location = "/v2/x.js")
                "cdn.example/v2/x.js" -> response(request, 307, location = "https://cdn2.example/x.js")
                else -> response(request, 200, body = "ok()", type = "text/javascript; charset=utf-8")
            }
        }
        val result = HtmlRenderSubresourceFetcher(server.client)
            .fetch("https://cdn.example/x.js", mapOf("Accept" to "*/*", "Cookie" to "a=b"), policy::allowsFetch)
        assertEquals(200, result.status)
        assertEquals("text/javascript", result.mimeType)
        assertEquals("UTF-8", result.encoding)
        assertEquals("ok()", result.body.toString(Charsets.UTF_8))
        assertNull(result.headers["Set-Cookie"])
        assertEquals(listOf("https://cdn.example/x.js", "https://cdn.example/v2/x.js", "https://cdn2.example/x.js"), server.requests.map { it.url.toString() })
        assertTrue(server.requests.all { it.header("Cookie") == null && it.header("Accept") == "*/*" && it.method == "GET" })
        assertFalse(result.toString().contains("ok()"))
    }

    @Test fun connectionGateIsCheckedOnEveryHop() {
        var hops = 0
        val server = FakeServer { response(it, 302, location = "https://cdn.example/next") }
        val result = HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/x.js", emptyMap()) { hops++ == 0 }
        assertEquals(403, result.status)
        assertEquals(1, server.requests.size)
    }

    @Test fun redirectLoopsAreBounded() {
        val server = FakeServer { response(it, 302, location = "https://cdn.example/again") }
        assertEquals(403, HtmlRenderSubresourceFetcher(server.client, maxRedirects = 3).fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch).status)
        assertEquals(4, server.requests.size)
    }

    @Test fun oversizedBodyFails() {
        val server = FakeServer { response(it, 200, body = "a".repeat(101)) }
        assertEquals(502, HtmlRenderSubresourceFetcher(server.client, maxBytes = 100).fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch).status)
        assertEquals(200, HtmlRenderSubresourceFetcher(server.client, maxBytes = 101).fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch).status)
    }

    @Test fun nonHttpsOrUnparseableStartIsBlockedWithoutRequest() {
        val server = FakeServer { fail("requested"); throw IllegalStateException() }
        listOf("http://cdn.example/x", "not a url", "https://192.168.0.1/x").forEach {
            assertEquals(it, 403, HtmlRenderSubresourceFetcher(server.client).fetch(it, emptyMap(), policy::allowsFetch).status)
        }
    }

    @Test fun transportFailureIsBadGateway() {
        val server = FakeServer { throw java.io.IOException("down") }
        assertEquals(502, HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch).status)
    }

    @Test fun rangeIsNotForwardedAndFullBodyIsReturned() {
        val server = FakeServer { response(it, 200, body = "0123456789", type = "video/mp4") }
        val result = HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/v.mp4", mapOf("Range" to "bytes=4-"), policy::allowsFetch)
        assertEquals(200, result.status)
        assertEquals("0123456789", result.body.toString(Charsets.UTF_8))
        assertNull(server.requests.single().header("Range"))
        assertNull(result.headers["Content-Range"])
    }

    @Test fun unexpectedPartialResponseFails() {
        val server = FakeServer { response(it, 206, body = "456789") }
        assertEquals(502, HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/v.mp4", emptyMap(), policy::allowsFetch).status)
    }

    @Test fun gateIsRecheckedRightBeforeTheRequestIsSent() {
        var checks = 0
        val server = FakeServer(gated = true) { response(it, 200, body = "x") }
        // Allowed when the hop starts, closed by the time the request would be written.
        val result = HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/x.js", emptyMap()) { checks++ == 0 }
        assertEquals(403, result.status)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun ungatedRequestNeverLeaves() {
        val server = FakeServer(gated = true) { response(it, 200) }
        assertThrows(HtmlRenderGateClosed::class.java) {
            server.client.newCall(Request.Builder().url("https://cdn.example/x").build()).execute()
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test fun gateIsRecheckedBeforeTheBodyIsRead() {
        var checks = 0
        val server = FakeServer(gated = true) { response(it, 200, body = "secret") }
        val result = HtmlRenderSubresourceFetcher(server.client).fetch("https://cdn.example/x.js", emptyMap()) { checks++ < 2 }
        assertEquals(403, result.status)
        assertEquals(0, result.body.size)
        assertEquals(1, server.requests.size)
    }

    @Test fun sessionLimitsConcurrentFetchesPerPage() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(HtmlRenderFetchSession.MAX_CONCURRENT)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val server = FakeServer { request ->
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            running.decrementAndGet()
            response(request, 200, body = "ok")
        }
        val session = HtmlRenderFetchSession(HtmlRenderSubresourceFetcher(server.client))
        val statuses = Collections.synchronizedList(mutableListOf<Int>())
        val threads = (1..10).map { n ->
            thread { statuses += session.fetch("https://cdn.example/$n.js", emptyMap(), policy::allowsFetch).status }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(HtmlRenderFetchSession.MAX_CONCURRENT, running.get())
        assertEquals(HtmlRenderFetchSession.MAX_CONCURRENT, session.inFlight)
        release.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(List(10) { 200 }, statuses.toList())
        assertEquals(HtmlRenderFetchSession.MAX_CONCURRENT, peak.get())
        assertEquals(0, session.inFlight)
    }

    @Test fun losingContextCancelsInFlightCallsAndDiscardsResults() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = FakeServer { request ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            response(request, 200, body = "late")
        }
        val session = HtmlRenderFetchSession(HtmlRenderSubresourceFetcher(server.client))
        var result: HtmlRenderResource? = null
        val worker = thread { result = session.fetch("https://cdn.example/x.js", emptyMap(), policy::allowsFetch) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        session.cancelInFlight()
        release.countDown()
        worker.join(5_000)
        assertTrue(server.lastCall!!.isCanceled())
        assertEquals(403, result!!.status)
        assertEquals(0, result!!.body.size)
        // Reconnected: later requests from the same page work again.
        assertEquals(200, session.fetch("https://cdn.example/y.js", emptyMap(), policy::allowsFetch).status)
    }

    @Test fun closedSessionRefusesWithoutRequesting() {
        val server = FakeServer { response(it, 200) }
        val session = HtmlRenderFetchSession(HtmlRenderSubresourceFetcher(server.client))
        session.close()
        assertEquals(403, session.fetch("https://cdn.example/x.js", emptyMap(), policy::allowsFetch).status)
        assertTrue(server.requests.isEmpty())
    }

    @Test fun sessionRechecksGateBeforeHandingOverTheBody() {
        var open = true
        val server = FakeServer { request -> response(request, 200, body = "x").also { open = false } }
        val session = HtmlRenderFetchSession(HtmlRenderSubresourceFetcher(server.client))
        assertEquals(403, session.fetch("https://cdn.example/x.js", emptyMap()) { open && policy.allowsFetch(it) }.status)
    }

    @Test fun budgetReservationsGrowOnlyWithinTheLimitAndReleaseOnce() {
        val budget = HtmlRenderByteBudget(100)
        val a = budget.reservation()
        val b = budget.reservation()
        assertTrue(a.growTo(60))
        assertTrue(a.growTo(30))
        assertEquals(60, budget.inUse)
        assertFalse(b.growTo(41))
        assertEquals(60, budget.inUse)
        assertTrue(b.growTo(40))
        assertEquals(100, budget.inUse)
        a.release()
        a.release()
        assertEquals(40, budget.inUse)
        assertFalse(a.growTo(10))
        assertEquals(40, budget.inUse)
        b.release()
        assertEquals(0, budget.inUse)
    }

    @Test fun processWideBudgetIsThirtyTwoMebibytes() {
        assertEquals(32L * 1024 * 1024, HtmlRenderByteBudget.shared.limit)
        assertEquals(HtmlRenderByteBudget.shared.limit, HtmlRenderByteBudget.DEFAULT_LIMIT)
    }

    @Test fun overBudgetFetchFailsCleanlyAndTheBudgetIsReleased() {
        val budget = HtmlRenderByteBudget(64 * 1024)
        val server = FakeServer { response(it, 200, body = "a".repeat(10_000)) }
        val fetcher = HtmlRenderSubresourceFetcher(server.client, budget = budget)
        val other = budget.reservation().also { assertTrue(it.growTo(60 * 1024)) }
        val refused = fetcher.fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch)
        assertEquals(502, refused.status)
        assertEquals(0, refused.body.size)
        assertEquals(60L * 1024, budget.inUse)
        // Freed budget lets the next page through: a refused fetch starved nobody.
        other.release()
        assertEquals(200, fetcher.fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch).status)
        assertEquals(0, budget.inUse)
    }

    @Test fun declaredLengthIsReservedBeforeBuffering() {
        val budget = HtmlRenderByteBudget(1_000)
        val buffered = AtomicInteger()
        val server = FakeServer { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    override fun contentType() = "text/plain".toMediaType()
                    override fun contentLength() = 5_000L
                    override fun source(): BufferedSource = object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long = buffered.incrementAndGet().let { -1L }
                        override fun timeout() = Timeout.NONE
                        override fun close() {}
                    }.buffer()
                }).build()
        }
        val result = HtmlRenderSubresourceFetcher(server.client, budget = budget).fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch)
        assertEquals(502, result.status)
        assertEquals(0, buffered.get())
        assertEquals(0, budget.inUse)
    }

    @Test fun budgetIsReleasedOnEveryOutcome() {
        val budget = HtmlRenderByteBudget(1024 * 1024)
        fun fetcher(maxBytes: Long = 100_000, respond: (Request) -> Response) =
            HtmlRenderSubresourceFetcher(FakeServer(respond = respond).client, maxBytes = maxBytes, budget = budget)
        fun get(f: HtmlRenderSubresourceFetcher) = f.fetch("https://cdn.example/x.css", emptyMap(), policy::allowsFetch)

        // Success, with a held body.
        assertEquals(200, get(fetcher { response(it, 200, body = "ok") }).status)
        assertEquals(0, budget.inUse)
        // Over the per-resource cap.
        assertEquals(502, get(fetcher(maxBytes = 10) { response(it, 200, body = "a".repeat(11)) }).status)
        assertEquals(0, budget.inUse)
        // Server error status and an unusable partial response.
        assertEquals(500, get(fetcher { response(it, 500, body = "boom") }).status)
        assertEquals(502, get(fetcher { response(it, 206, body = "x") }).status)
        assertEquals(0, budget.inUse)
        // Transport failure while the body is being read.
        val failing = fetcher { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource {
                    var first = true
                    return object : Source {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (!first) throw java.io.IOException("reset")
                            first = false
                            sink.write(ByteArray(5_000))
                            return 5_000
                        }
                        override fun timeout() = Timeout.NONE
                        override fun close() {}
                    }.buffer()
                }
            }).build()
        }
        assertEquals(502, get(failing).status)
        assertEquals(0, budget.inUse)
        // A redirected stylesheet that is rebased.
        val css = fetcher { request ->
            if (request.url.encodedPath == "/x.css") response(request, 302, location = "/v2/x.css")
            else response(request, 200, body = "a{background:url(b.png)}", type = "text/css")
        }
        assertEquals(200, get(css).status)
        assertEquals(0, budget.inUse)
        // Blocked hops never reserve.
        assertEquals(403, fetcher { response(it, 302, location = "https://10.0.0.1/") }.let(::get).status)
        assertEquals(0, budget.inUse)
    }

    @Test fun cancellingAndClosingASessionMidBodyReleasesTheBudget() {
        val budget = HtmlRenderByteBudget(1024 * 1024)
        val reading = CountDownLatch(1)
        var fake: FakeServer? = null
        val server = FakeServer { request ->
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(object : ResponseBody() {
                override fun contentType() = "text/plain".toMediaType()
                override fun contentLength() = -1L
                override fun source(): BufferedSource = object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        sink.write(ByteArray(1_000))
                        reading.countDown()
                        // A stalled upstream: only the call being cancelled ends the read.
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (fake?.lastCall?.isCanceled() != true) {
                            if (System.nanoTime() > deadline) return -1L
                            Thread.sleep(5)
                        }
                        throw java.io.IOException("Canceled")
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() {}
                }.buffer()
            }).build()
        }
        fake = server
        val session = HtmlRenderFetchSession(HtmlRenderSubresourceFetcher(server.client, budget = budget))
        var result: HtmlRenderResource? = null
        val worker = thread { result = session.fetch("https://cdn.example/x", emptyMap(), policy::allowsFetch) }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        assertTrue(budget.inUse > 0)
        session.close()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertEquals(403, result!!.status)
        assertEquals(0, budget.inUse)
        // A closed session never reserves again.
        assertEquals(403, session.fetch("https://cdn.example/y", emptyMap(), policy::allowsFetch).status)
        assertEquals(0, budget.inUse)
    }

    @Test fun parallelPagesShareOneBudgetAndAllReleaseIt() {
        val budget = HtmlRenderByteBudget(100 * 1024)
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val server = FakeServer { request ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            response(request, 200, body = "a".repeat(30 * 1024))
        }
        val sessions = List(3) { HtmlRenderFetchSession(HtmlRenderSubresourceFetcher(server.client, budget = budget)) }
        val statuses = Collections.synchronizedList(mutableListOf<Int>())
        val threads = sessions.mapIndexed { n, session ->
            thread { statuses += session.fetch("https://cdn.example/$n", emptyMap(), policy::allowsFetch).status }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        threads.forEach { it.join(10_000) }
        // Three 30 KiB bodies (plus copies) may not all fit in 100 KiB at once: each either succeeds or fails cleanly.
        assertEquals(3, statuses.size)
        assertTrue(statuses.all { it == 200 || it == 502 })
        assertEquals(0, budget.inUse)
        sessions.forEach(HtmlRenderFetchSession::close)
        assertEquals(0, budget.inUse)
    }

    /** A plain TCP listener on loopback that counts connection attempts; a refused client never reaches it. */
    private class LoopbackListener : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).apply { soTimeout = 200 }
        val port get() = socket.localPort
        val accepted = AtomicInteger()
        @Volatile private var open = true
        private val acceptor = thread {
            while (open) {
                try { socket.accept().close(); accepted.incrementAndGet() } catch (_: SocketTimeoutException) {} catch (_: java.io.IOException) { return@thread }
            }
        }
        override fun close() { open = false; socket.close(); acceptor.join(2_000) }
    }

    @Test fun productionClientKeepsItsHardenedConfiguration() {
        val client = createHtmlRenderHttpClient()
        assertSame(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.dns is PublicOnlyDns)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertSame(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertTrue(client.interceptors.isEmpty())
        assertEquals(listOf<Any>(PublicPeerInterceptor, HtmlRenderGateInterceptor), client.networkInterceptors)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(15_000, client.readTimeoutMillis)
        assertEquals(10_000, client.writeTimeoutMillis)
        assertEquals(30_000, client.callTimeoutMillis)
    }

    @Test fun productionClientRefusesANameThatResolvesToLoopback() {
        LoopbackListener().use { listener ->
            val loopback = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }
            val client = createHtmlRenderHttpClient(loopback)
            val fetcher = HtmlRenderSubresourceFetcher(client, budget = HtmlRenderByteBudget(1024 * 1024))
            // A public-looking name that rebinds to 127.0.0.1, and one that resolves to a private LAN host.
            assertEquals(502, fetcher.fetch("https://rebind.example:${listener.port}/x.js", emptyMap()) { true }.status)
            val lan = createHtmlRenderHttpClient(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("192.168.1.10")) })
            assertEquals(502, HtmlRenderSubresourceFetcher(lan).fetch("https://rebind.example/x.js", emptyMap()) { true }.status)
            // Private-looking names are refused even if the resolver answers with a public address.
            val publicAnswer = createHtmlRenderHttpClient(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("93.184.216.34")) })
            assertEquals(502, HtmlRenderSubresourceFetcher(publicAnswer).fetch("https://localhost:${listener.port}/x.js", emptyMap()) { true }.status)
            // A request outside the fetcher carries no hop gate and DNS still stops it before any connection.
            assertThrows(java.io.IOException::class.java) { client.newCall(Request.Builder().url("https://rebind.example:${listener.port}/").build()).execute() }
            Thread.sleep(300)
            assertEquals(0, listener.accepted.get())
        }
    }

    @Test fun productionClientNeverCompletesARequestToALoopbackLiteral() {
        LoopbackListener().use { listener ->
            // An IP literal skips DNS entirely; it must still never yield a response (TLS or the peer check stops it).
            val result = HtmlRenderSubresourceFetcher(createHtmlRenderHttpClient()).fetch("https://127.0.0.1:${listener.port}/x.js", emptyMap()) { true }
            assertEquals(502, result.status)
            assertEquals(0, result.body.size)
        }
    }

    private val cssBase = "https://cdn.example/v2/css/style.css".toHttpUrl()

    @Test fun cssRelativeUrlsAreRebased() {
        val css = """
            @import "base.css";
            @import url(theme.css) screen;
            @IMPORT 'print.css' print;
            a { background: url(img/a.png) no-repeat, URL( "../font.woff2" ), url('b.png?x=1#y'); }
            b { background: url(//cdn3.example/x.png); }
        """.trimIndent()
        assertEquals(
            """
            @import "https://cdn.example/v2/css/base.css";
            @import url(https://cdn.example/v2/css/theme.css) screen;
            @IMPORT 'https://cdn.example/v2/css/print.css' print;
            a { background: url(https://cdn.example/v2/css/img/a.png) no-repeat, URL( "https://cdn.example/v2/font.woff2" ), url('https://cdn.example/v2/css/b.png?x=1#y'); }
            b { background: url(https://cdn3.example/x.png); }
            """.trimIndent(),
            htmlRenderRebaseCss(css, cssBase),
        )
    }

    @Test fun cssNonRelativeAndOpaqueTokensAreKept() {
        listOf(
            "a{background:url(data:image/png;base64,AAAA)}",
            "a{background:url(\"data:image/svg+xml;utf8,<svg xmlns='x'/>\")}",
            "a{background:url(https://other.example/x.png)}",
            "a{fill:url(#gradient)}",
            "a{fill:url('#g')}",
            "a{background:url()}",
            "a{background:url( )}",
            "/* url(c.png) and @import \"d.css\"; */",
            "a::before{content:\"url(d.png)\"}",
            "a{background:myurl(e.png)}",
            "a{background:url(a\\).png)}",
            "a{background:url(\"esc\\\"aped.png\")}",
            "a{background:url(f.png",
            "a{background:url(\"unterminated.png",
            "a{background:url(sp ace.png)}",
            "@import-ish 'x.css';",
        ).forEach { assertEquals(it, it, htmlRenderRebaseCss(it, cssBase)) }
    }

    @Test fun cssBytesKeepTheirCharset() {
        val latin1 = "@charset \"iso-8859-1\";\n/* caf\u00e9 */ a{background:url(a.png)}".toByteArray(Charsets.ISO_8859_1)
        val rebased = htmlRenderRebaseCssBytes(latin1, null, cssBase)
        assertEquals("@charset \"iso-8859-1\";\n/* caf\u00e9 */ a{background:url(https://cdn.example/v2/css/a.png)}", rebased.toString(Charsets.ISO_8859_1))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a{background:url(\u00e9.png)}".toByteArray()
        val withBom = htmlRenderRebaseCssBytes(bom, null, cssBase)
        assertArrayEquals(bom.copyOf(3), withBom.copyOf(3))
        assertEquals("\uFEFFa{background:url(https://cdn.example/v2/css/%C3%A9.png)}", withBom.toString(Charsets.UTF_8))
        val malformed = byteArrayOf(0xC3.toByte(), 0x28) + "a{background:url(a.png)}".toByteArray()
        assertArrayEquals(malformed, htmlRenderRebaseCssBytes(malformed, Charsets.UTF_8, cssBase))
        val plain = "a{color:red}".toByteArray()
        assertSame(plain, htmlRenderRebaseCssBytes(plain, null, cssBase))
    }

    @Test fun redirectedStylesheetIsRebasedOntoTheFinalUrl() {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/latest/style.css" -> response(request, 302, location = "/v2/style.css")
                "/latest/app.js" -> response(request, 302, location = "/v2/app.js")
                "/v2/app.js" -> response(request, 200, body = "import('./url(x).js')", type = "text/javascript")
                else -> response(request, 200, body = "@import 'a.css';body{background:url(bg.png)}", type = "text/css; charset=utf-8")
            }
        }
        val fetcher = HtmlRenderSubresourceFetcher(server.client)
        val redirected = fetcher.fetch("https://cdn.example/latest/style.css", emptyMap(), policy::allowsFetch)
        assertEquals("@import 'https://cdn.example/v2/a.css';body{background:url(https://cdn.example/v2/bg.png)}", redirected.body.toString(Charsets.UTF_8))
        assertEquals("text/css", redirected.mimeType)
        assertEquals("UTF-8", redirected.encoding)
        assertEquals("https://cdn.example/v2/style.css", redirected.finalUrl)
        val direct = fetcher.fetch("https://cdn.example/v2/style.css", emptyMap(), policy::allowsFetch)
        assertEquals("@import 'a.css';body{background:url(bg.png)}", direct.body.toString(Charsets.UTF_8))
        // Other types cannot carry a new base through WebResourceResponse and are passed through untouched.
        assertEquals("import('./url(x).js')", fetcher.fetch("https://cdn.example/latest/app.js", emptyMap(), policy::allowsFetch).body.toString(Charsets.UTF_8))
    }

    @Test fun rebasedStylesheetStillRespectsTheCap() {
        val css = "a{background:url(b.png)}"
        val server = FakeServer { request ->
            if (request.url.encodedPath == "/latest.css") response(request, 302, location = "/v2/style.css")
            else response(request, 200, body = css, type = "text/css")
        }
        val capped = HtmlRenderSubresourceFetcher(server.client, maxBytes = css.length.toLong() + 5)
        assertEquals(502, capped.fetch("https://cdn.example/latest.css", emptyMap(), policy::allowsFetch).status)
        assertEquals(200, capped.fetch("https://cdn.example/v2/style.css", emptyMap(), policy::allowsFetch).status)
    }
}

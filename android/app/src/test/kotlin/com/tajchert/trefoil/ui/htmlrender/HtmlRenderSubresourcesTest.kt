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

    private val policy = HtmlRenderPolicy(htmlRenderDocumentUrl(), HtmlRenderNetworkAccess({ setOf("env.example") }, { true }))

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
        assertEquals(listOf<Any>(PublicHostInterceptor), client.interceptors)
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

    @Test fun productionClientNeverConnectsToANonPublicIpLiteral() {
        LoopbackListener().use { listener ->
            val client = createHtmlRenderHttpClient()
            val fetcher = HtmlRenderSubresourceFetcher(client, budget = HtmlRenderByteBudget(1024 * 1024))
            // An IP literal skips DNS entirely, so even a permissive hop gate must not get a socket opened.
            listOf("127.0.0.1", "[::1]", "[::ffff:127.0.0.1]", "[::127.0.0.1]").forEach { host ->
                val result = fetcher.fetch("https://$host:${listener.port}/x.js", emptyMap()) { true }
                assertEquals(host, 502, result.status)
                assertEquals(host, 0, result.body.size)
            }
            // Outside the fetcher, and for a private LAN literal, too.
            assertThrows(java.io.IOException::class.java) { client.newCall(Request.Builder().url("https://127.0.0.1:${listener.port}/").build()).execute() }
            assertEquals(502, fetcher.fetch("https://192.168.1.10/x.js", emptyMap()) { true }.status)
            Thread.sleep(300)
            assertEquals(0, listener.accepted.get())
        }
    }

    @Test fun urlPolicyGateRejectsNonPublicLiteralsBeforeAnyConnection() {
        LoopbackListener().use { listener ->
            val fetcher = HtmlRenderSubresourceFetcher(createHtmlRenderHttpClient(), budget = HtmlRenderByteBudget(1024 * 1024))
            // The same policy the WebView passes as the hop gate, for the literal and for redirects to it.
            listOf("127.0.0.1", "[::1]", "10.0.0.1", "169.254.169.254", "[::ffff:127.0.0.1]").forEach { host ->
                val result = fetcher.fetch("https://$host:${listener.port}/x.js", emptyMap(), policy::allowsFetch)
                assertEquals(host, 403, result.status)
                assertEquals(host, 0, result.body.size)
            }
            Thread.sleep(300)
            assertEquals(0, listener.accepted.get())
        }
    }

    @Test fun publicPeerInterceptorRejectsAConnectionToANonPublicPeerBeforeTheRequestIsSent() {
        LoopbackListener().use { listener ->
            // Plain HTTP so the network interceptor runs right after the TCP connect, standing in for the TLS case.
            val guarded = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addNetworkInterceptor(PublicPeerInterceptor).build()
            val blocked = assertThrows(java.io.IOException::class.java) {
                guarded.newCall(Request.Builder().url("http://127.0.0.1:${listener.port}/").build()).execute()
            }
            assertEquals("Blocked peer", blocked.message)
            // Control: without the interceptor the same request gets past the connect and only then fails.
            val unguarded = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val other = assertThrows(java.io.IOException::class.java) {
                unguarded.newCall(Request.Builder().url("http://127.0.0.1:${listener.port}/").build()).execute()
            }
            assertNotEquals("Blocked peer", other.message)
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

    private fun rebaseBytes(bytes: ByteArray, charset: java.nio.charset.Charset?) =
        htmlRenderRebaseCssBytes(bytes, charset, cssBase, 1L shl 20) { true }!!

    @Test fun cssBytesKeepTheirCharset() {
        val latin1 = "@charset \"iso-8859-1\";\n/* caf\u00e9 */ a{background:url(a.png)}".toByteArray(Charsets.ISO_8859_1)
        val rebased = rebaseBytes(latin1, null)
        assertEquals("@charset \"iso-8859-1\";\n/* caf\u00e9 */ a{background:url(https://cdn.example/v2/css/a.png)}", rebased.toString(Charsets.ISO_8859_1))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a{background:url(\u00e9.png)}".toByteArray()
        val withBom = rebaseBytes(bom, null)
        assertArrayEquals(bom.copyOf(3), withBom.copyOf(3))
        assertEquals("\uFEFFa{background:url(https://cdn.example/v2/css/%C3%A9.png)}", withBom.toString(Charsets.UTF_8))
        val malformed = byteArrayOf(0xC3.toByte(), 0x28) + "a{background:url(a.png)}".toByteArray()
        assertArrayEquals(malformed, rebaseBytes(malformed, Charsets.UTF_8))
        val plain = "a{color:red}".toByteArray()
        assertSame(plain, rebaseBytes(plain, null))
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

    private val cssFixtures = listOf(
        """
        @import "base.css";
        @import url(theme.css) screen;
        @IMPORT 'print.css' print;
        a { background: url(img/a.png) no-repeat, URL( "../font.woff2" ), url('b.png?x=1#y'); }
        b { background: url(//cdn3.example/x.png); }
        """.trimIndent(),
        "a{background:url(data:image/png;base64,AAAA)}", "a{fill:url(#gradient)}", "a{background:url( )}",
        "/* url(c.png) and @import \"d.css\"; */", "a::before{content:\"url(d.png)\"}", "a{background:myurl(e.png)}",
        "a{background:url(a\\).png)}", "a{background:url(f.png", "a{background:url(\"unterminated.png", "a{background:url(sp ace.png)}",
        "a{background:url(  x.png  )}", "@import /* c */ 'y.css';", "plain text without references", "",
    )

    @Test fun countingPassAgreesWithTheBuildPassOnEveryFixture() {
        cssFixtures.forEach { css ->
            val rebased = htmlRenderRebaseCss(css, cssBase)
            val count = htmlRenderCountRebasedCss(css, cssBase)!!
            assertEquals(css, rebased.length.toLong(), count.length)
            assertEquals(css, rebased != css, count.rewritten)
            // The limit is on the rebased length: exactly that passes, one less refuses.
            assertNotNull(css, htmlRenderCountRebasedCss(css, cssBase, rebased.length.toLong()))
            if (rebased.isNotEmpty()) assertNull(css, htmlRenderCountRebasedCss(css, cssBase, rebased.length - 1L))
        }
    }

    @Test fun buildPassWritesExactlyTheCountedCharsIntoAnArrayBackedBuffer() {
        cssFixtures.forEach { css ->
            val count = htmlRenderCountRebasedCss(css, cssBase)!!
            val chars = htmlRenderBuildRebasedCss(css, cssBase, count.length.toInt())
            assertNotNull(css, chars)
            assertEquals(css, count.length, chars!!.size.toLong())
            assertEquals(css, htmlRenderRebaseCss(css, cssBase), String(chars))
            // A length that is off by one either way is refused rather than padded or truncated.
            assertNull(css, htmlRenderBuildRebasedCss(css, cssBase, count.length.toInt() + 1))
            if (count.length > 0) assertNull(css, htmlRenderBuildRebasedCss(css, cssBase, count.length.toInt() - 1))
        }
        // The encoder is fed from that array, so a rewritten stylesheet round-trips through the byte path.
        val css = "a{background:url(b.png)}".toByteArray()
        assertEquals(
            "a{background:url(https://cdn.example/v2/css/b.png)}",
            htmlRenderRebaseCssBytes(css, null, cssBase, 1_000) { true }!!.toString(Charsets.UTF_8),
        )
    }

    private val longToken = "t".repeat(9_000)
    private val longCss = "a{background:url(\"sprite.svg?token=$longToken\")}"
    private val longRebased = "a{background:url(\"https://cdn.example/v2/css/sprite.svg?token=$longToken\")}"

    @Test fun longRelativeTargetsAreRebasedToo() {
        assertEquals(longRebased, htmlRenderRebaseCss(longCss, cssBase))
        // Unquoted and @import forms, and a target far over the old copy-as-written limit.
        val huge = "h".repeat(20_000)
        assertEquals("a{background:url(https://cdn.example/v2/css/$huge)}", htmlRenderRebaseCss("a{background:url($huge)}", cssBase))
        assertEquals("@import 'https://cdn.example/v2/css/$huge';", htmlRenderRebaseCss("@import '$huge';", cssBase))
        // Through the byte path with its reservations, and the counting pass agrees with the build pass.
        val rebased = htmlRenderRebaseCssBytes(longCss.toByteArray(), null, cssBase, 1L shl 20) { true }!!
        assertEquals(longRebased, rebased.toString(Charsets.UTF_8))
        assertEquals(longRebased.length.toLong(), htmlRenderCountRebasedCss(longCss, cssBase)!!.length)
    }

    @Test fun longTargetReservesExtraScratchBeforeItIsResolvedAndRefusalFailsTheRebase() {
        val reservations = mutableListOf<Long>()
        htmlRenderRebaseCssBytes(longCss.toByteArray(), null, cssBase, 1L shl 20) { reservations += it; true }!!
        // Decode buffer, decoded string, fixed scratch, scratch for the long target, rebuilt text, encode buffer, trim.
        val extra = 4L * (longToken.length + "sprite.svg?token=".length - 8_192) * 2
        assertEquals(extra, reservations[3])
        assertEquals(7, reservations.size)
        var calls = 0
        assertNull(htmlRenderRebaseCssBytes(longCss.toByteArray(), null, cssBase, 1L shl 20) { calls++ != 3 })
        // Refused at the scratch: nothing after it (builder, encode buffer) was reserved.
        assertEquals(4, calls)
        // Targets within the limit never ask for extra scratch, however many there are.
        val shortCss = "a{background:url(b.png)}".repeat(100)
        var shortCalls = 0
        assertNotNull(htmlRenderRebaseCssBytes(shortCss.toByteArray(), null, cssBase, 1L shl 20) { shortCalls++; true })
        assertEquals(6, shortCalls)
        // Many long targets share one scratch: only growth over the longest so far is reserved.
        val many = "a{background:url(\"s.svg?t=$longToken\")}".repeat(50)
        var manyCalls = 0
        assertNotNull(htmlRenderRebaseCssBytes(many.toByteArray(), null, cssBase, 1L shl 24) { manyCalls++; true })
        assertEquals(7, manyCalls)
    }

    @Test fun redirectedStylesheetWithALongTargetIsRebasedAndUnderATooSmallBudgetFailsCleanly() {
        val server = redirectedStylesheet(longCss)
        val rebased = HtmlRenderSubresourceFetcher(server.client, budget = HtmlRenderByteBudget(2L * 1024 * 1024))
            .fetch("https://cdn.example/latest.css", emptyMap(), policy::allowsFetch)
        assertEquals(200, rebased.status)
        assertEquals(
            "a{background:url(\"https://cdn.example/v2/p/sprite.svg?token=$longToken\")}",
            rebased.body.toString(Charsets.UTF_8),
        )
        val outcomes = (10_000..400_000 step 1_000).map { limit ->
            val budget = HtmlRenderByteBudget(limit.toLong())
            val result = HtmlRenderSubresourceFetcher(server.client, budget = budget)
                .fetch("https://cdn.example/latest.css", emptyMap(), policy::allowsFetch)
            assertEquals("limit $limit", 0, budget.inUse)
            if (result.status == 200) assertEquals("limit $limit", rebased.body.toString(Charsets.UTF_8), result.body.toString(Charsets.UTF_8))
            else { assertEquals(502, result.status); assertEquals(0, result.body.size) }
            limit to result.status
        }
        val firstOk = outcomes.first { it.second == 200 }.first
        assertTrue(outcomes.all { (limit, status) -> (status == 200) == (limit >= firstOk) })
        assertTrue("first ok $firstOk", firstOk > 10_000)
    }

    /** `url(a)` many times against a long final URL: the rebased text is vastly larger than the stylesheet. */
    private val bombCss = "u{background:url(a)}".repeat(3_000)
    private val bombBase = "https://cdn.example/${"p/".repeat(500)}style.css".toHttpUrl()

    /** The fixed scratch reserved for resolving targets: 4 x (base URL + 8 192 chars) x 2 bytes. */
    private fun scratchBytes(base: okhttp3.HttpUrl) = 4L * (base.toString().length + 8_192) * 2

    @Test fun expansionBombIsRefusedBeforeAnythingProportionalToTheOutputIsReserved() {
        val bytes = bombCss.toByteArray()
        val maxBytes = 100_000L
        assertTrue(bytes.size < maxBytes)
        // Rebased, this would be about 3 000 x 1 000 chars.
        assertTrue(htmlRenderRebaseCss(bombCss, bombBase).length > 1_000_000)
        var reserved = 0L
        val result = htmlRenderRebaseCssBytes(bytes, null, bombBase, maxBytes) { reserved += it; true }
        assertNull(result)
        // Only the decode stages (a few times the input) and the fixed resolve scratch were reserved, never a
        // builder or encode buffer.
        assertTrue("reserved $reserved", reserved <= bytes.size * 4L + scratchBytes(bombBase))
    }

    @Test fun rebaseReservesEveryStageBeforeAllocatingAndRefusesWhenOverBudget() {
        val css = "a{background:url(b.png)}".toByteArray()
        val reservations = mutableListOf<Long>()
        val rebased = htmlRenderRebaseCssBytes(css, null, cssBase, 1_000) { reservations += it; true }!!
        assertEquals("a{background:url(https://cdn.example/v2/css/b.png)}", rebased.toString(Charsets.UTF_8))
        // Decode buffer, decoded string, resolve scratch, rebuilt text, encode buffer, and the trimmed copy of it.
        assertEquals(6, reservations.size)
        assertTrue(reservations.all { it > 0 })
        assertEquals(scratchBytes(cssBase), reservations[2])
        // Refusing any one of them fails the whole rebase, without ever reaching the next stage.
        reservations.indices.forEach { refused ->
            var calls = 0
            assertNull("stage $refused", htmlRenderRebaseCssBytes(css, null, cssBase, 1_000) { calls++ != refused })
            assertEquals(refused + 1, calls)
        }
        // Over the output cap: refused after the counting pass, before the builder is reserved.
        var calls = 0
        assertNull(htmlRenderRebaseCssBytes(css, null, cssBase, css.size + 5L) { calls++; true })
        assertEquals(3, calls)
    }

    private fun redirectedStylesheet(body: String, target: String = "/v2/p/style.css") = FakeServer { request ->
        if (request.url.encodedPath.startsWith("/latest")) response(request, 302, location = target)
        else response(request, 200, body = body, type = "text/css")
    }

    @Test fun redirectedExpansionBombFailsWith502AndReleasesTheBudget() {
        val budget = HtmlRenderByteBudget(2L * 1024 * 1024)
        val longPath = "p/".repeat(500)
        val server = redirectedStylesheet(bombCss, target = "/${longPath}style.css")
        val fetcher = HtmlRenderSubresourceFetcher(server.client, maxBytes = 100_000, budget = budget)
        val result = fetcher.fetch("https://cdn.example/latest.css", emptyMap(), policy::allowsFetch)
        assertEquals(502, result.status)
        assertEquals(0, result.body.size)
        assertEquals(0, budget.inUse)
        // The same sheet without a redirect is not rebased and fits.
        assertEquals(200, fetcher.fetch("https://cdn.example/${longPath}style.css", emptyMap(), policy::allowsFetch).status)
        assertEquals(0, budget.inUse)
    }

    @Test fun redirectedStylesheetUnderATooSmallBudgetFailsCleanlyAndRebasesWithEnough() {
        val css = "a{background:url(b.png)}"
        val expected = "a{background:url(https://cdn.example/v2/p/b.png)}"
        // Sweep from below the buffering reservation (one 8 KiB chunk) past the rebase's own reservations,
        // which include the fixed resolve scratch: the rebase needs well more than the chunk.
        val outcomes = (8_000..100_000 step 250).map { limit ->
            val budget = HtmlRenderByteBudget(limit.toLong())
            val result = HtmlRenderSubresourceFetcher(redirectedStylesheet(css).client, budget = budget)
                .fetch("https://cdn.example/latest.css", emptyMap(), policy::allowsFetch)
            assertEquals("limit $limit", 0, budget.inUse)
            if (result.status == 200) assertEquals(expected, result.body.toString(Charsets.UTF_8))
            else { assertEquals(502, result.status); assertEquals(0, result.body.size) }
            limit to result.status
        }
        // Monotone: once the budget is big enough it stays big enough.
        val firstOk = outcomes.first { it.second == 200 }.first
        assertTrue(outcomes.all { (limit, status) -> (status == 200) == (limit >= firstOk) })
        assertTrue("first ok $firstOk", firstOk > 8_192 + css.length)
    }
}

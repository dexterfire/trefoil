package com.tajchert.trefoil.ui.htmlrender

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
        assertEquals(setOf("Accept", "Accept-Language", "Range", "Origin"), headers.names())
    }

    @Test fun responseHeadersKeepCorsAndContentOnly() {
        val passed = htmlRenderPassedResponseHeaders(headersOf(
            "Content-Type", "application/json", "Access-Control-Allow-Origin", "*", "access-control-expose-headers", "x-a",
            "Set-Cookie", "a=b", "Set-Cookie", "c=d", "Content-Encoding", "gzip", "Content-Length", "10", "Cache-Control", "max-age=60",
            "Location", "https://10.0.0.1/", "Content-Security-Policy", "default-src *",
        ))
        assertEquals(
            mapOf("Content-Type" to "application/json", "Access-Control-Allow-Origin" to "*", "access-control-expose-headers" to "x-a", "Cache-Control" to "no-store"),
            passed,
        )
    }

    private class FakeServer(val respond: (Request) -> Response) {
        val requests = mutableListOf<Request>()
        val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain -> chain.request().also { requests += it }.let(respond) }.build()
    }

    private fun response(request: Request, code: Int, location: String? = null, body: String = "", type: String = "text/plain") =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message(if (code == 200) "OK" else "Found")
            .apply { location?.let { header("Location", it) } }
            .header("Set-Cookie", "tracking=1")
            .body(body.toResponseBody(type.toMediaType())).build()

    private val policy = HtmlRenderPolicy(htmlRenderDocumentUrl("fixture"), HtmlRenderNetworkAccess({ setOf("env.example") }, { true }))

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
}

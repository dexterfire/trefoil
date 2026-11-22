package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.EnvironmentRoute
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.RouteKind
import com.tajchert.trefoil.core.model.RouteSource
import com.tajchert.trefoil.core.model.SavedEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EnvironmentProbeTest {
    private lateinit var server: MockWebServer
    private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun environment(id: String = "env-1") = SavedEnvironment(id, "Test", server.url("/").toString(), "fixture-token")

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { runCatching { server.shutdown() } }

    @Test fun savedCredentialsAcceptedIsOnlineWithReadOnlyRequests() = runBlocking {
        server.enqueue(json(descriptor))
        server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}"""))
        assertEquals(MachineProbe.Online, EnvironmentProbe().probe(environment()))
        val first = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", first.method); assertEquals("/.well-known/t3/environment", first.path)
        assertNull("The token is never sent to the public descriptor", first.getHeader("Authorization"))
        val second = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", second.method); assertEquals("/api/auth/session", second.path)
        assertEquals("Bearer fixture-token", second.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test fun rejectedTokenIsAuthFailed() = runBlocking {
        server.enqueue(json(descriptor)); server.enqueue(json("""{"error":"unauthorized"}""", 401))
        assertEquals(MachineProbe.AuthFailed, EnvironmentProbe().probe(environment()))
        server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":false}"""))
        assertEquals(MachineProbe.AuthFailed, EnvironmentProbe().probe(environment()))
    }

    @Test fun silentServerTimesOutAsUnreachable() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()
        assertEquals(MachineProbe.Unreachable, EnvironmentProbe(timeoutMillis = 300).probe(environment()))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
    }

    @Test fun refusedConnectionAndServerErrorsAreUnreachable() = runBlocking {
        server.enqueue(json("oops", 502))
        assertEquals(MachineProbe.Unreachable, EnvironmentProbe().probe(environment()))
        val closed = environment(); server.shutdown()
        assertEquals(MachineProbe.Unreachable, EnvironmentProbe(timeoutMillis = 2_000).probe(closed))
    }

    @Test fun identityChangeNeedsAttentionWithoutSendingToken() = runBlocking {
        server.enqueue(json(descriptor.replace("env-1", "someone-else")))
        assertEquals(MachineProbe.NeedsAttention, EnvironmentProbe().probe(environment()))
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun urlPolicyBlocksBeforeAnyRequest() = runBlocking {
        val probe = EnvironmentProbe(timeoutMillis = 500)
        // Public HTTP is never allowed, even with a saved flag; private LAN HTTP needs saved consent.
        assertEquals(MachineProbe.NeedsAttention, probe.probe(SavedEnvironment("env-1", "Public", "http://8.8.8.8/", "fixture-token", allowInsecureHttp = true)))
        assertEquals(MachineProbe.NeedsAttention, probe.probe(SavedEnvironment("env-1", "Lan", "http://192.168.1.20/", "fixture-token", allowInsecureHttp = false)))
        assertEquals(MachineProbe.NeedsAttention, probe.probe(SavedEnvironment("env-1", "Ssh", "", "fixture-token")))
        assertEquals(0, server.requestCount)
    }

    @Test fun routesProbeInOrderFirstOnlineWins() = runBlocking {
        val tunnel = MockWebServer().apply { start() }
        try {
            val lan = EnvironmentRoute(RouteKind.Lan, server.url("/").toString(), RouteSource.Paired)
            val far = EnvironmentRoute(RouteKind.Tunnel, tunnel.url("/").toString(), RouteSource.Learned)
            // An identity mismatch moves on without the token; the tunnel then answers.
            server.enqueue(json(descriptor.replace("env-1", "someone-else")))
            tunnel.enqueue(json(descriptor)); tunnel.enqueue(json("""{"authenticated":true}"""))
            assertEquals(EnvironmentProbe.RouteProbe(MachineProbe.Online, far), EnvironmentProbe().probe(environment(), listOf(lan, EnvironmentRoute(RouteKind.Ssh, "", RouteSource.Paired), far)))
            assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            // The first online route wins without contacting later ones.
            server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true}"""))
            assertEquals(EnvironmentProbe.RouteProbe(MachineProbe.Online, lan), EnvironmentProbe().probe(environment(), listOf(lan, far)))
            assertEquals(2, tunnel.requestCount)
            // AuthFailed is decisive.
            server.enqueue(json(descriptor)); server.enqueue(json("{}", 401))
            assertEquals(EnvironmentProbe.RouteProbe(MachineProbe.AuthFailed, lan), EnvironmentProbe().probe(environment(), listOf(lan, far)))
            assertEquals(2, tunnel.requestCount)
            // A mismatch is remembered when nothing is online; nothing tried is Unknown.
            server.enqueue(json(descriptor.replace("env-1", "someone-else"))); tunnel.enqueue(json("oops", 502))
            assertEquals(EnvironmentProbe.RouteProbe(MachineProbe.NeedsAttention, lan), EnvironmentProbe().probe(environment(), listOf(lan, far)))
            tunnel.enqueue(json("oops", 502))
            assertEquals(EnvironmentProbe.RouteProbe(MachineProbe.Unreachable, null), EnvironmentProbe().probe(environment(), listOf(far)))
            assertEquals(EnvironmentProbe.RouteProbe(MachineProbe.Unknown, null), EnvironmentProbe().probe(environment(), listOf(EnvironmentRoute(RouteKind.Ssh, "", RouteSource.Paired))))
        } finally { tunnel.shutdown() }
    }

    /** Records the thread of every body read, as a dispatcher check that holds for any OkHttp version. */
    private class ReadThreads : Interceptor {
        val readers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = response.body!!
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long { readers += Thread.currentThread().name; return super.read(sink, byteCount) }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source(): BufferedSource = source
            }).build()
        }
    }

    @Test(timeout = 30_000) fun bodiesAreReadOffTheCallersThread() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            // Headers first, then the body in slow chunks: a read on the caller's thread would block it.
            server.enqueue(json(descriptor).throttleBody(16, 20, TimeUnit.MILLISECONDS))
            server.enqueue(json("""{"authenticated":true}""").throttleBody(8, 20, TimeUnit.MILLISECONDS))
            val reads = ReadThreads()
            val result = runBlocking(main.asCoroutineDispatcher()) {
                EnvironmentProbe(http = OkHttpClient.Builder().addInterceptor(reads).build()).probe(environment())
            }
            assertEquals(MachineProbe.Online, result)
            assertTrue("Bodies were read", reads.readers.isNotEmpty())
            assertTrue("No body read on the caller's thread: ${reads.readers}", reads.readers.none { it.startsWith("fake-main") })
        } finally { main.shutdownNow() }
    }

    @Test(timeout = 30_000) fun cancellingTheCallerAbortsABodyReadInProgress() = runBlocking {
        // The descriptor's headers arrive, its body stalls; the probe's own budget is far longer than the test waits.
        server.enqueue(json(descriptor + " ".repeat(4_096)).throttleBody(1, 10, TimeUnit.SECONDS))
        val probe = EnvironmentProbe(timeoutMillis = 20_000)
        val attempt = launch(Dispatchers.Default) { probe.probe(environment()) }
        server.takeRequest(5, TimeUnit.SECONDS)!!
        delay(300)
        val started = System.nanoTime()
        withTimeout(5_000) { attempt.cancelAndJoin() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
    }
}

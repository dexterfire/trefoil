package com.tajchert.trefoil.ui.htmlrender

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * What an agent page may reach, read live on every request: the hosts the environment is currently reached
 * through, whether the card's environment and thread are still the connected selection, and the fetcher.
 * [currentChanges] emits [isCurrent] as it changes, so a page can cancel its in-flight fetches the moment
 * it loses its connection or thread.
 */
internal class HtmlRenderNetworkAccess(
    val environmentHosts: () -> Set<String> = { emptySet() },
    val isCurrent: () -> Boolean = { false },
    val fetcher: HtmlRenderSubresourceFetcher? = null,
    val currentChanges: Flow<Boolean> = emptyFlow(),
) {
    companion object {
        /** No remote subresources at all: previews, tests, and cards without a loader. */
        val Offline = HtmlRenderNetworkAccess()
    }
}

/** Address classes an agent page must never reach, shared by the URL policy and the DNS filter. */
internal object HtmlRenderAddresses {
    fun isNonPublic(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> isNonPublicIpv4(address.address.map { it.toInt() and 0xFF })
        is Inet6Address -> {
            val bytes = address.address.map { it.toInt() and 0xFF }
            val embedded = bytes.takeLast(4)
            address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
                address.isMulticastAddress || (bytes[0] and 0xFE) == 0xFC ||
                // IPv4-compatible/mapped (::/96, ::ffff:0:0/96) and NAT64 (64:ff9b::/96) carry an IPv4 address.
                (bytes.take(10).all { it == 0 } && (bytes[10] == bytes[11] && (bytes[10] == 0 || bytes[10] == 0xFF)) && isNonPublicIpv4(embedded)) ||
                (bytes.take(12) == NAT64_PREFIX && isNonPublicIpv4(embedded)) ||
                // NAT64 local-use (64:ff9b:1::/48, RFC 8215) is never globally routable and can translate to any
                // address, so all of it is refused. 6to4 (2002::/16) carries its IPv4 address in bytes 2..5.
                bytes.take(6) == NAT64_LOCAL_USE_PREFIX ||
                (bytes[0] == 0x20 && bytes[1] == 0x02 && isNonPublicIpv4(bytes.subList(2, 6)))
        }
        else -> true
    }

    /** This network, loopback, RFC1918, link-local, CGNAT, benchmarking, IETF protocol, multicast and reserved. */
    fun isNonPublicIpv4(octets: List<Int>): Boolean {
        val (a, b) = octets
        return a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 100 && b in 64..127) || (a == 198 && b in 18..19) || (a == 192 && b == 0 && octets[2] == 0)
    }

    private val NAT64_PREFIX = listOf(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0)
    private val NAT64_LOCAL_USE_PREFIX = listOf(0x00, 0x64, 0xFF, 0x9B, 0x00, 0x01)
}

/**
 * Resolves through [delegate] and refuses the whole name when any address is non-public. OkHttp connects
 * only to addresses this returned, so a name cannot rebind to a private address between check and connect.
 */
internal class PublicOnlyDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (HtmlRenderPolicy.isNonPublicName(hostname)) throw UnknownHostException("Blocked host")
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(HtmlRenderAddresses::isNonPublic)) throw UnknownHostException("Non-public address")
        return addresses
    }
}

/**
 * Application interceptor: refuses a non-public host before any socket is opened. OkHttp skips [Dns] for IP
 * literals, so this is what stops `https://127.0.0.1/` (and every other private, loopback or link-local
 * literal) from being connected to; names are refused here too and again by [PublicOnlyDns].
 */
internal object PublicHostInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (HtmlRenderPolicy.isNonPublicName(chain.request().url.host)) throw IOException("Blocked host")
        return chain.proceed(chain.request())
    }
}

/** Last line of defence: the socket actually connected must be a public address over TLS. */
internal object PublicPeerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val peer = chain.connection()?.route()?.socketAddress?.address
        if (peer == null || HtmlRenderAddresses.isNonPublic(peer) || !chain.request().url.isHttps) throw IOException("Blocked peer")
        return chain.proceed(chain.request())
    }
}

/** The page's hop decision, attached to each request so it can be re-read after DNS and connect. */
internal class HtmlRenderHopGate(val allows: () -> Boolean)

/** The gate closed (page released, connection or thread lost) while the request was underway. */
internal class HtmlRenderGateClosed : IOException("Gate closed")

/**
 * Network interceptor: re-checks the request's [HtmlRenderHopGate] after connect, right before the request
 * is written. A request without a gate is refused, so nothing reaches the network outside the fetcher.
 */
internal object HtmlRenderGateInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val gate = chain.request().tag(HtmlRenderHopGate::class.java)
        if (gate == null || !gate.allows()) throw HtmlRenderGateClosed()
        return chain.proceed(chain.request())
    }
}

/**
 * A byte budget shared by every page's fetches (the process-wide [shared] one by default): a fetch holds a
 * [Reservation] for as long as it buffers a body, and over budget the fetch fails cleanly instead of growing
 * memory further. Reservations are released when the fetch ends, whatever the outcome.
 */
internal class HtmlRenderByteBudget(val limit: Long = DEFAULT_LIMIT) {
    private val used = AtomicLong()

    /** Bytes currently reserved across all open reservations. */
    val inUse: Long get() = used.get()

    fun reservation() = Reservation()

    private fun add(bytes: Long): Boolean {
        while (true) {
            val current = used.get()
            if (bytes > limit - current) return false
            if (used.compareAndSet(current, current + bytes)) return true
        }
    }

    inner class Reservation internal constructor() {
        private var held = 0L
        private var released = false

        /** Raises the reservation to at least [total] bytes; false (nothing changes) when over budget or released. */
        @Synchronized
        fun growTo(total: Long): Boolean {
            if (released) return false
            if (total <= held) return true
            if (!add(total - held)) return false
            held = total
            return true
        }

        /** Raises the reservation by [extra] bytes; false (nothing changes) when over budget or released. */
        @Synchronized
        fun growBy(extra: Long): Boolean {
            if (extra < 0 || extra > Long.MAX_VALUE - held) return false
            return growTo(held + extra)
        }

        /** Gives every held byte back; safe to call more than once. */
        @Synchronized
        fun release() {
            if (released) return
            released = true
            used.addAndGet(-held)
            held = 0
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 32L * 1024 * 1024

        val shared by lazy { HtmlRenderByteBudget() }
    }
}

/** A proxied subresource; `toString` never prints the URL or body. [finalUrl] is the last hop actually read. */
internal class HtmlRenderResource(
    val status: Int,
    val reason: String,
    val mimeType: String?,
    val encoding: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
    val finalUrl: String? = null,
) {
    override fun toString() = "HtmlRenderResource(status=$status, bytes=${body.size})"

    companion object {
        private val NO_STORE = mapOf("Cache-Control" to "no-store")
        fun blocked() = HtmlRenderResource(403, "Blocked", "text/plain", "UTF-8", NO_STORE, byteArrayOf())
        fun failed() = HtmlRenderResource(502, "Bad Gateway", "text/plain", "UTF-8", NO_STORE, byteArrayOf())
    }
}

/**
 * `Range` is never forwarded: the WebView slices a byte-range request itself by seeking into the stream it is
 * given, so the full resource (still capped) is always fetched and returned as 200.
 */
private val FORWARDED_REQUEST_HEADERS = setOf("accept", "accept-language", "origin")
private val PASSED_RESPONSE_HEADERS = setOf(
    "content-type", "content-language", "accept-ranges", "timing-allow-origin", "cross-origin-resource-policy",
)

/** Only content negotiation and the (synthetic) Origin leave the device: no cookies, auth, Referer, or Range. */
internal fun htmlRenderForwardedRequestHeaders(headers: Map<String, String>): Headers = Headers.Builder().apply {
    headers.forEach { (name, value) ->
        if (name.lowercase() in FORWARDED_REQUEST_HEADERS && value.length <= 1024) runCatching { add(name, value) }
    }
}.build()

/** CORS and content headers the page needs; Set-Cookie, caching, Content-Range, and transport headers are dropped. */
internal fun htmlRenderPassedResponseHeaders(headers: Headers): Map<String, String> {
    val passed = linkedMapOf<String, String>()
    headers.names().forEach { name ->
        val lower = name.lowercase()
        if (lower in PASSED_RESPONSE_HEADERS || lower.startsWith("access-control-")) passed[name] = headers.values(name).joinToString(", ")
    }
    passed["Cache-Control"] = "no-store"
    return passed
}

/**
 * The production client for agent-page subresources: no proxy, a [PublicOnlyDns] over [systemDns], no
 * redirects, cookies or cache, a public-host check before any connect, and the public-peer and hop-gate network
 * interceptors. Only [systemDns] is injectable, so tests can drive the real client's DNS filter without a network.
 */
internal fun createHtmlRenderHttpClient(systemDns: Dns = Dns.SYSTEM): OkHttpClient =
    OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(PublicOnlyDns(systemDns))
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .addInterceptor(PublicHostInterceptor)
        .addNetworkInterceptor(PublicPeerInterceptor)
        .addNetworkInterceptor(HtmlRenderGateInterceptor)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

/**
 * Fetches an agent page's subresources for the WebView, so every hop passes [HtmlRenderPolicy]: GET over
 * https only, manual bounded redirects with the policy re-checked per hop, public addresses only (DNS and
 * connected peer), no cookies, cache, auth, or proxy, and a size cap. The hop decision is re-read after
 * connect (by [HtmlRenderGateInterceptor]) and again before the body is read. A stylesheet served from a
 * redirect has its relative references rebased onto the final URL. Blocking: call off the main thread.
 * Never logs URLs.
 */
internal class HtmlRenderSubresourceFetcher(
    http: OkHttpClient? = null,
    private val maxBytes: Long = MAX_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
    private val budget: HtmlRenderByteBudget = HtmlRenderByteBudget.shared,
) {
    private val client by lazy { http ?: sharedClient }

    /**
     * [allowHop] decides the first URL and every redirect target, and is re-read after connect and before the
     * body; a refused hop returns an empty 403. [onCall] sees each call before it executes, so its owner can
     * cancel it.
     */
    fun fetch(url: String, requestHeaders: Map<String, String>, allowHop: (String) -> Boolean): HtmlRenderResource =
        fetch(url, requestHeaders, {}, allowHop)

    fun fetch(
        url: String,
        requestHeaders: Map<String, String>,
        onCall: (Call) -> Unit,
        allowHop: (String) -> Boolean,
    ): HtmlRenderResource {
        val requested: HttpUrl = url.toHttpUrlOrNull() ?: return HtmlRenderResource.blocked()
        var current = requested
        val headers = htmlRenderForwardedRequestHeaders(requestHeaders)
        for (hop in 0..maxRedirects) {
            val hopUrl = current.toString()
            if (!current.isHttps || !allowHop(hopUrl)) return HtmlRenderResource.blocked()
            val request = Request.Builder().url(current).headers(headers).get()
                .tag(HtmlRenderHopGate::class.java, HtmlRenderHopGate { allowHop(hopUrl) })
                .build()
            val next = try {
                val call = client.newCall(request)
                onCall(call)
                call.execute().use { response ->
                    if (!response.isRedirect) {
                        if (!allowHop(hopUrl)) return HtmlRenderResource.blocked()
                        return read(response, requested, current)
                    }
                    response.header("Location")?.let(current::resolve) ?: return HtmlRenderResource.failed()
                }
            } catch (_: HtmlRenderGateClosed) {
                return HtmlRenderResource.blocked()
            } catch (_: IOException) {
                return HtmlRenderResource.failed()
            }
            current = next
        }
        return HtmlRenderResource.blocked()
    }

    /** Buffers the body under a [budget] reservation that is released on every path, handed-over bytes included. */
    private fun read(response: Response, requested: HttpUrl, final: HttpUrl): HtmlRenderResource {
        val reservation = budget.reservation()
        try {
            return readBuffered(response, requested, final, reservation)
        } finally {
            reservation.release()
        }
    }

    private fun readBuffered(
        response: Response,
        requested: HttpUrl,
        final: HttpUrl,
        reservation: HtmlRenderByteBudget.Reservation,
    ): HtmlRenderResource {
        val code = response.code
        // No Range was sent, so a partial response cannot be passed on as the whole resource.
        if (code !in 100..599 || code in 300..399 || code == 206) return HtmlRenderResource.failed()
        val body = response.body ?: return HtmlRenderResource.failed()
        val declared = body.contentLength()
        if (declared > maxBytes) return HtmlRenderResource.failed()
        if (declared > 0 && !reservation.growTo(declared)) return HtmlRenderResource.failed()
        val buffer = Buffer()
        val source = body.source()
        // Reserved before each chunk is buffered, so the budget is never overshot by more than one chunk.
        var endOfBody = false
        while (!endOfBody) {
            if (!reservation.growTo(buffer.size + CHUNK_BYTES)) return HtmlRenderResource.failed()
            if (source.read(buffer, CHUNK_BYTES) == -1L) endOfBody = true
            else if (buffer.size > maxBytes) return HtmlRenderResource.failed()
        }
        val type = body.contentType()
        val reason = response.message.filter { it in ' '..'~' }.take(64).trim().ifEmpty { if (code < 400) "OK" else "Error" }
        val mimeType = type?.let { "${it.type}/${it.subtype}".lowercase() }
        // The buffer and the copy made from it are both live.
        val size = buffer.size
        if (!reservation.growTo(size * 2)) return HtmlRenderResource.failed()
        var bytes = buffer.readByteArray()
        if (final != requested && mimeType == "text/css") {
            // The rebase reserves everything it allocates (decode, count, build, encode) before allocating it, and
            // refuses to build output past the cap.
            bytes = htmlRenderRebaseCssBytes(bytes, type?.charset(), final, maxBytes, reservation::growBy)
                ?: return HtmlRenderResource.failed()
        }
        return HtmlRenderResource(
            status = code,
            reason = reason,
            mimeType = mimeType,
            encoding = type?.charset()?.name(),
            headers = htmlRenderPassedResponseHeaders(response.headers),
            body = bytes,
            finalUrl = final.toString(),
        )
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        private const val CHUNK_BYTES = 8_192L

        /** Shared for connection reuse; never shares a cookie jar, cache, interceptors, or pool with the T3 client. */
        val shared by lazy { HtmlRenderSubresourceFetcher() }

        private val sharedClient: OkHttpClient by lazy { createHtmlRenderHttpClient() }
    }
}

/**
 * One page's (one WebView's) fetches: at most [maxConcurrent] at a time, each call tracked so it can be
 * cancelled. [cancelInFlight] (connection or thread lost) cancels running calls and discards their results;
 * [close] (WebView released) does the same and refuses everything after. Results are re-checked against the
 * hop gate before they are handed to the WebView; anything invalidated becomes an empty 403.
 */
internal class HtmlRenderFetchSession(
    private val fetcher: HtmlRenderSubresourceFetcher,
    maxConcurrent: Int = MAX_CONCURRENT,
    private val queueTimeoutMillis: Long = QUEUE_TIMEOUT_MILLIS,
) {
    private val permits = Semaphore(maxConcurrent)
    private val calls: MutableSet<Call> = Collections.synchronizedSet(LinkedHashSet())
    private val generation = AtomicInteger()
    @Volatile private var closed = false

    fun fetch(url: String, requestHeaders: Map<String, String>, allowHop: (String) -> Boolean): HtmlRenderResource {
        val started = generation.get()
        val valid = { !closed && generation.get() == started }
        if (!valid() || !acquire(valid)) return if (valid()) HtmlRenderResource.failed() else HtmlRenderResource.blocked()
        val mine = mutableListOf<Call>()
        try {
            if (!valid()) return HtmlRenderResource.blocked()
            val result = fetcher.fetch(url, requestHeaders, onCall = { call ->
                mine += call
                calls += call
                // Registered after the check above: an invalidation in between is caught here.
                if (!valid()) call.cancel()
            }) { valid() && allowHop(it) }
            val final = result.finalUrl
            return if (valid() && (final == null || allowHop(final))) result else HtmlRenderResource.blocked()
        } finally {
            calls.removeAll(mine.toSet())
            permits.release()
        }
    }

    /** Waits for a slot, giving up when invalidated or after the queue timeout. */
    private fun acquire(valid: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(queueTimeoutMillis)
        try {
            while (valid()) {
                if (permits.tryAcquire(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (valid()) return true
                    permits.release()
                    return false
                }
                if (System.nanoTime() >= deadline) return false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return false
    }

    /** The page lost its connection or thread: cancel running calls and discard what they return. */
    fun cancelInFlight() {
        generation.incrementAndGet()
        val running = synchronized(calls) { calls.toList() }
        running.forEach(Call::cancel)
    }

    /** The WebView is being destroyed: cancel everything and refuse any later request. */
    fun close() {
        closed = true
        cancelInFlight()
    }

    internal val inFlight: Int get() = calls.size

    companion object {
        const val MAX_CONCURRENT = 6
        const val QUEUE_TIMEOUT_MILLIS = 60_000L
        private const val POLL_MILLIS = 100L
    }
}

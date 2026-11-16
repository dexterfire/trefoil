package com.tajchert.trefoil.ui.htmlrender

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
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
 */
internal class HtmlRenderNetworkAccess(
    val environmentHosts: () -> Set<String> = { emptySet() },
    val isCurrent: () -> Boolean = { false },
    val fetcher: HtmlRenderSubresourceFetcher? = null,
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
                (bytes.take(12) == NAT64_PREFIX && isNonPublicIpv4(embedded))
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

/** Last line of defence: the socket actually connected must be a public address over TLS. */
private object PublicPeerInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val peer = chain.connection()?.route()?.socketAddress?.address
        if (peer == null || HtmlRenderAddresses.isNonPublic(peer) || !chain.request().url.isHttps) throw IOException("Blocked peer")
        return chain.proceed(chain.request())
    }
}

/** A proxied subresource; `toString` never prints the URL or body. */
internal class HtmlRenderResource(
    val status: Int,
    val reason: String,
    val mimeType: String?,
    val encoding: String?,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    override fun toString() = "HtmlRenderResource(status=$status, bytes=${body.size})"

    companion object {
        private val NO_STORE = mapOf("Cache-Control" to "no-store")
        fun blocked() = HtmlRenderResource(403, "Blocked", "text/plain", "UTF-8", NO_STORE, byteArrayOf())
        fun failed() = HtmlRenderResource(502, "Bad Gateway", "text/plain", "UTF-8", NO_STORE, byteArrayOf())
    }
}

private val FORWARDED_REQUEST_HEADERS = setOf("accept", "accept-language", "range", "origin")
private val PASSED_RESPONSE_HEADERS = setOf(
    "content-type", "content-language", "content-range", "accept-ranges", "timing-allow-origin", "cross-origin-resource-policy",
)

/** Only content negotiation, ranges and the (synthetic) Origin leave the device: no cookies, auth, or Referer. */
internal fun htmlRenderForwardedRequestHeaders(headers: Map<String, String>): Headers = Headers.Builder().apply {
    headers.forEach { (name, value) ->
        if (name.lowercase() in FORWARDED_REQUEST_HEADERS && value.length <= 1024) runCatching { add(name, value) }
    }
}.build()

/** CORS and content headers the page needs; Set-Cookie, caching, and transport headers are dropped. */
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
 * Fetches an agent page's subresources for the WebView, so every hop passes [HtmlRenderPolicy]: GET over
 * https only, manual bounded redirects with the policy re-checked per hop, public addresses only (DNS and
 * connected peer), no cookies, cache, auth, or proxy, and a size cap. Blocking: call off the main thread.
 * Never logs URLs.
 */
internal class HtmlRenderSubresourceFetcher(
    http: OkHttpClient? = null,
    private val maxBytes: Long = MAX_BYTES,
    private val maxRedirects: Int = MAX_REDIRECTS,
) {
    private val client by lazy { http ?: sharedClient }

    /** [allowHop] decides the first URL and every redirect target; a refused hop returns an empty 403. */
    fun fetch(url: String, requestHeaders: Map<String, String>, allowHop: (String) -> Boolean): HtmlRenderResource {
        var current: HttpUrl = url.toHttpUrlOrNull() ?: return HtmlRenderResource.blocked()
        val headers = htmlRenderForwardedRequestHeaders(requestHeaders)
        for (hop in 0..maxRedirects) {
            if (!current.isHttps || !allowHop(current.toString())) return HtmlRenderResource.blocked()
            val request = Request.Builder().url(current).headers(headers).get().build()
            val next = try {
                client.newCall(request).execute().use { response ->
                    if (!response.isRedirect) return read(response)
                    response.header("Location")?.let(current::resolve) ?: return HtmlRenderResource.failed()
                }
            } catch (_: IOException) {
                return HtmlRenderResource.failed()
            }
            current = next
        }
        return HtmlRenderResource.blocked()
    }

    private fun read(response: Response): HtmlRenderResource {
        val code = response.code
        if (code !in 100..599 || code in 300..399) return HtmlRenderResource.failed()
        val body = response.body ?: return HtmlRenderResource.failed()
        if (body.contentLength() > maxBytes) return HtmlRenderResource.failed()
        val buffer = Buffer()
        val source = body.source()
        while (source.read(buffer, 8_192) != -1L) {
            if (buffer.size > maxBytes) return HtmlRenderResource.failed()
        }
        val type = body.contentType()
        val reason = response.message.filter { it in ' '..'~' }.take(64).trim().ifEmpty { if (code < 400) "OK" else "Error" }
        return HtmlRenderResource(
            status = code,
            reason = reason,
            mimeType = type?.let { "${it.type}/${it.subtype}" },
            encoding = type?.charset()?.name(),
            headers = htmlRenderPassedResponseHeaders(response.headers),
            body = buffer.readByteArray(),
        )
    }

    companion object {
        const val MAX_BYTES = 10L * 1024 * 1024
        const val MAX_REDIRECTS = 5

        /** Shared for connection reuse; never shares a cookie jar, cache, interceptors, or pool with the T3 client. */
        val shared by lazy { HtmlRenderSubresourceFetcher() }

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(PublicOnlyDns())
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .addNetworkInterceptor(PublicPeerInterceptor)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

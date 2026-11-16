package com.tajchert.trefoil.downloads

import android.util.LruCache
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.HtmlRenderReference
import com.tajchert.trefoil.core.model.TrefoilClient
import com.tajchert.trefoil.ui.htmlrender.HtmlRenderNetworkAccess
import com.tajchert.trefoil.ui.htmlrender.HtmlRenderSubresourceFetcher
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Result of fetching an `html_render` page. Never carries a URL, title, or attachment id in `toString`. */
sealed interface HtmlRenderFetch {
    class Ready(val html: String) : HtmlRenderFetch {
        override fun toString() = "Ready(content=***)"
    }
    /** Not connected, or the environment/thread is no longer the selected one; nothing was cached. */
    data object NotCurrent : HtmlRenderFetch
    data object TooLarge : HtmlRenderFetch
    data object Failed : HtmlRenderFetch
}

/**
 * Fetches `html_render` pages through the authenticated client into memory only: never disk, never
 * MediaStore. The WebView receives the decoded document, so it never sees the bearer, the signed URL, or a
 * tunnel port. Nothing here logs content, titles, ids, or URLs. Page subresources go through
 * [subresources] (never the T3 client) and only while [isCurrent].
 */
class HtmlRenderLoader internal constructor(
    private val client: TrefoilClient,
    private val maxSourceBytes: Long,
    cacheBytes: Int,
    private val subresources: HtmlRenderSubresourceFetcher?,
) {
    constructor(client: TrefoilClient, maxSourceBytes: Long = MAX_SOURCE_BYTES, cacheBytes: Int = CACHE_BYTES) :
        this(client, maxSourceBytes, cacheBytes, null)

    private data class Key(val environmentId: String, val threadId: String, val referenceId: String)
    private class Page(val html: String, val bytes: Int)
    private val cache = object : LruCache<Key, Page>(cacheBytes) {
        override fun sizeOf(key: Key, value: Page) = value.bytes.coerceAtLeast(1)
    }

    /** Connected, and [environmentId]/[threadId] are still the selection. Safe to call from any thread. */
    fun isCurrent(environmentId: String, threadId: String): Boolean = isCurrent(client.state.value, environmentId, threadId)

    private fun isCurrent(state: ClientState, environmentId: String, threadId: String): Boolean =
        // Same rule as InlineImageLoader: not canRead, which blinks off on every thread event.
        state.connection == ConnectionStatus.Connected && state.environment?.id == environmentId && state.selectedThreadId == threadId

    private fun key(reference: HtmlRenderReference, environmentId: String, threadId: String) =
        Key(environmentId, threadId, reference.file.identity)

    /** Cached page, if any; works offline. */
    fun cached(reference: HtmlRenderReference, environmentId: String, threadId: String): String? =
        synchronized(cache) { cache.get(key(reference, environmentId, threadId))?.html }

    suspend fun load(reference: HtmlRenderReference, environmentId: String, threadId: String): HtmlRenderFetch {
        cached(reference, environmentId, threadId)?.let { return HtmlRenderFetch.Ready(it) }
        if (!isCurrent(environmentId, threadId)) return HtmlRenderFetch.NotCurrent
        val sink = LimitedSink(maxSourceBytes) { isCurrent(environmentId, threadId) }
        try {
            client.downloadFile(reference.file, sink, environmentId, threadId)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(environmentId, threadId)) return HtmlRenderFetch.NotCurrent
            val bytes = sink.bytes()
            val html = bytes.toString(Charsets.UTF_8)
            synchronized(cache) { cache.put(key(reference, environmentId, threadId), Page(html, bytes.size)) }
            return HtmlRenderFetch.Ready(html)
        } catch (cancelled: CancellationException) {
            if (sink.contextChanged) return HtmlRenderFetch.NotCurrent
            throw cancelled
        } catch (_: Exception) {
            return when {
                sink.overflowed -> HtmlRenderFetch.TooLarge
                sink.contextChanged || !isCurrent(environmentId, threadId) -> HtmlRenderFetch.NotCurrent
                else -> HtmlRenderFetch.Failed
            }
        }
    }

    /**
     * Lowercase hosts the environment is reached through (paired address, learned routes, active route).
     * Page subresources to these hosts are refused even when they look public.
     */
    fun environmentHosts(environmentId: String): Set<String> {
        val state = client.state.value
        val environment = state.environment?.takeIf { it.id == environmentId } ?: return emptySet()
        return (listOf(environment.baseUrl) + environment.routes.map { it.baseUrl } + listOfNotNull(state.activeRoute?.baseUrl))
            .mapNotNull { runCatching { URI(it.trim()).host }.getOrNull()?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.') }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Live network rules for a page shown in this environment and thread; every call reads current state. */
    internal fun networkAccess(environmentId: String, threadId: String) = HtmlRenderNetworkAccess(
        environmentHosts = { environmentHosts(environmentId) },
        isCurrent = { isCurrent(environmentId, threadId) },
        fetcher = subresources ?: HtmlRenderSubresourceFetcher.shared,
        currentChanges = client.state.map { isCurrent(it, environmentId, threadId) }.distinctUntilChanged(),
    )

    private class LimitedSink(private val limit: Long, private val stillCurrent: () -> Boolean) : OutputStream() {
        private val buffer = ByteArrayOutputStream()
        @Volatile var overflowed = false
        @Volatile var contextChanged = false
        override fun write(b: Int) { guard(1); buffer.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { guard(len); buffer.write(b, off, len) }
        private fun guard(extra: Int) {
            if (buffer.size() + extra.toLong() > limit) { overflowed = true; throw IOException("Page too large") }
            if (!stillCurrent()) { contextChanged = true; throw CancellationException("Context changed") }
        }
        fun bytes(): ByteArray = buffer.toByteArray()
    }

    companion object {
        const val MAX_SOURCE_BYTES = 10L * 1024 * 1024
        const val CACHE_BYTES = 8 * 1024 * 1024
    }
}

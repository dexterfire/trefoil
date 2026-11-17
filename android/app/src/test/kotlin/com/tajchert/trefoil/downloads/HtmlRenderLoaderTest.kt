package com.tajchert.trefoil.downloads

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HtmlRenderLoaderTest {
    private val ref = HtmlRenderReference("fixture-attachment", "Fixture chart", 320)
    private val html = "<!doctype html><p>Fixture ✓</p>"

    @Test fun fetchesThroughAttachmentResourceAndCaches() = runBlocking {
        val client = ImageClient(html.toByteArray())
        val loader = HtmlRenderLoader(client)
        val first = loader.load(ref, "fixture", "thread")
        assertEquals(html, (first as HtmlRenderFetch.Ready).html)
        assertEquals(html, (loader.load(ref, "fixture", "thread") as HtmlRenderFetch.Ready).html)
        assertEquals(html, loader.cached(ref, "fixture", "thread"))
        assertEquals(1, client.requests)
        assertNull(loader.cached(ref, "fixture", "other-thread"))
        assertFalse(first.toString().contains("Fixture"))
    }

    @Test fun overCapIsTooLargeAndNotCached() = runBlocking {
        val client = ImageClient(ByteArray(101) { 'a'.code.toByte() })
        val loader = HtmlRenderLoader(client, maxSourceBytes = 100)
        assertEquals(HtmlRenderFetch.TooLarge, loader.load(ref, "fixture", "thread"))
        assertNull(loader.cached(ref, "fixture", "thread"))
    }

    @Test fun defaultsMatchTheBrief() {
        assertEquals(10L * 1024 * 1024, HtmlRenderLoader.MAX_SOURCE_BYTES)
        assertEquals(8 * 1024 * 1024, HtmlRenderLoader.CACHE_BYTES)
    }

    @Test fun notCurrentDoesNotFetch() = runBlocking {
        val client = ImageClient(html.toByteArray())
        val loader = HtmlRenderLoader(client)
        assertEquals(HtmlRenderFetch.NotCurrent, loader.load(ref, "fixture", "other-thread"))
        assertEquals(HtmlRenderFetch.NotCurrent, loader.load(ref, "other-env", "thread"))
        client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
        assertEquals(HtmlRenderFetch.NotCurrent, loader.load(ref, "fixture", "thread"))
        assertEquals(0, client.requests)
    }

    @Test fun cachedPageRendersOffline() = runBlocking {
        val client = ImageClient(html.toByteArray())
        val loader = HtmlRenderLoader(client)
        loader.load(ref, "fixture", "thread")
        client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
        assertEquals(html, (loader.load(ref, "fixture", "thread") as HtmlRenderFetch.Ready).html)
        assertEquals(1, client.requests)
    }

    @Test fun failureIsFailed() = runBlocking {
        assertEquals(HtmlRenderFetch.Failed, HtmlRenderLoader(ImageClient(ByteArray(0), fail = true)).load(ref, "fixture", "thread"))
    }

    @Test fun lruEvictsBeyondCacheBytes() = runBlocking {
        // 60 characters cost 120 bytes in memory (UTF-16), so two do not fit in 200.
        val client = ImageClient(ByteArray(60) { 'a'.code.toByte() })
        val loader = HtmlRenderLoader(client, cacheBytes = 200)
        val other = ref.copy(attachmentId = "fixture-other")
        loader.load(ref, "fixture", "thread")
        loader.load(other, "fixture", "thread")
        assertNull(loader.cached(ref, "fixture", "thread"))
        assertNotNull(loader.cached(other, "fixture", "thread"))
    }

    @Test fun pageLargerThanTheCacheIsServedButNotCachedAndKeepsOthers() = runBlocking {
        val client = ImageClient("small".toByteArray())
        val loader = HtmlRenderLoader(client, cacheBytes = 100)
        val other = ref.copy(attachmentId = "fixture-other")
        loader.load(other, "fixture", "thread")
        client.bytes = ByteArray(51) { 'a'.code.toByte() } // 102 bytes as a string, over the 100 byte cap
        val big = loader.load(ref, "fixture", "thread")
        assertEquals(51, (big as HtmlRenderFetch.Ready).html.length)
        assertNull(loader.cached(ref, "fixture", "thread"))
        assertEquals("small", loader.cached(other, "fixture", "thread"))
    }

    @Test fun cacheIsSizedByDecodedStringNotDownloadBytes() = runBlocking {
        // "✓" is 3 UTF-8 bytes but one char (2 bytes in memory): 40 of them = 120 download bytes, 80 cached bytes.
        val client = ImageClient("✓".repeat(40).toByteArray())
        val loader = HtmlRenderLoader(client, cacheBytes = 100)
        loader.load(ref, "fixture", "thread")
        assertNotNull(loader.cached(ref, "fixture", "thread"))
    }

    @Test fun evictEnvironmentDropsOnlyThatEnvironment() = runBlocking {
        val client = ImageClient(html.toByteArray())
        val loader = HtmlRenderLoader(client)
        loader.load(ref, "fixture", "thread")
        client.state.value = client.state.value.copy(environment = client.environment.copy(id = "second"))
        loader.load(ref, "second", "thread")
        loader.evictEnvironment("fixture")
        assertNull(loader.cached(ref, "fixture", "thread"))
        assertEquals(html, loader.cached(ref, "second", "thread"))
    }

    @Test fun decodesOffTheCallingThread() = runBlocking {
        val callingThread = Thread.currentThread()
        var decodeThread: Thread? = null
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                Thread { decodeThread = Thread.currentThread(); block.run() }.start()
            }
        }
        val loader = HtmlRenderLoader(ImageClient(html.toByteArray()), HtmlRenderLoader.MAX_SOURCE_BYTES, HtmlRenderLoader.CACHE_BYTES, null, dispatcher)
        assertEquals(html, (loader.load(ref, "fixture", "thread") as HtmlRenderFetch.Ready).html)
        assertNotNull(decodeThread)
        assertNotSame(callingThread, decodeThread)
    }

    @Test fun contextChangeMidDownloadIsNotCurrentAndNotCached() = runBlocking {
        val inner = ImageClient(html.toByteArray())
        // The user leaves the thread after the transfer started but before the bytes arrive.
        val client = object : TrefoilClient by inner {
            override suspend fun downloadFile(reference: RemoteFileReference, output: java.io.OutputStream, environmentId: String, threadId: String, onProgress: (FileDownloadProgress) -> Unit): DownloadedFile {
                inner.state.value = inner.state.value.copy(selectedThreadId = "other-thread")
                return inner.downloadFile(reference, output, environmentId, threadId, onProgress)
            }
        }
        val loader = HtmlRenderLoader(client)
        assertEquals(HtmlRenderFetch.NotCurrent, loader.load(ref, "fixture", "thread"))
        assertNull(loader.cached(ref, "fixture", "thread"))
        assertEquals(1, inner.requests)
    }

    @Test fun cacheIsIsolatedPerEnvironmentAndThread() = runBlocking {
        val client = ImageClient("first".toByteArray())
        val loader = HtmlRenderLoader(client)
        loader.load(ref, "fixture", "thread")
        client.bytes = "second".toByteArray()
        client.state.value = client.state.value.copy(selectedThreadId = "other-thread")
        assertEquals("second", (loader.load(ref, "fixture", "other-thread") as HtmlRenderFetch.Ready).html)
        client.bytes = "third".toByteArray()
        client.state.value = client.state.value.copy(environment = client.environment.copy(id = "other-env"), selectedThreadId = "thread")
        assertEquals("third", (loader.load(ref, "other-env", "thread") as HtmlRenderFetch.Ready).html)
        assertEquals(3, client.requests)
        assertEquals("first", loader.cached(ref, "fixture", "thread"))
        assertEquals("second", loader.cached(ref, "fixture", "other-thread"))
        assertEquals("third", loader.cached(ref, "other-env", "thread"))
        assertNull(loader.cached(ref, "other-env", "other-thread"))
    }

    @Test fun networkAccessReadsLiveClientState() {
        val client = ImageClient(ByteArray(0))
        val access = HtmlRenderLoader(client).networkAccess("fixture", "thread")
        assertTrue(access.isCurrent())
        assertNotNull(access.fetcher)
        assertFalse("learned.example" in access.environmentHosts())
        client.state.value = client.state.value.copy(
            environment = client.environment.copy(routes = listOf(EnvironmentRoute(RouteKind.Lan, "https://learned.example/", RouteSource.Learned))),
        )
        assertTrue("learned.example" in access.environmentHosts())
        client.state.value = client.state.value.copy(selectedThreadId = "other-thread")
        assertFalse(access.isCurrent())
        client.state.value = client.state.value.copy(selectedThreadId = "thread", connection = ConnectionStatus.Disconnected)
        assertFalse(access.isCurrent())
        assertFalse(HtmlRenderLoader(client).networkAccess("other-env", "thread").isCurrent())
    }

    @Test fun currentChangesFollowsConnectionAndSelection() = runBlocking {
        val client = ImageClient(ByteArray(0))
        val changes = HtmlRenderLoader(client).networkAccess("fixture", "thread").currentChanges
        assertTrue(changes.first())
        client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
        assertFalse(changes.first())
        client.state.value = client.state.value.copy(connection = ConnectionStatus.Connected, selectedThreadId = "other-thread")
        assertFalse(changes.first())
    }

    @Test fun environmentHostsCoverPairedAndLearnedRoutes() {
        val client = ImageClient(ByteArray(0))
        client.state.value = client.state.value.copy(
            environment = client.environment.copy(baseUrl = "https://Fixture.Example/", routes = listOf(EnvironmentRoute(RouteKind.Lan, "http://192.168.1.20:3773/", RouteSource.Learned))),
        )
        assertEquals(setOf("fixture.example", "192.168.1.20"), HtmlRenderLoader(client).environmentHosts("fixture"))
        assertEquals(emptySet<String>(), HtmlRenderLoader(client).environmentHosts("other"))
    }
}

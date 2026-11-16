package com.tajchert.trefoil.downloads

import com.tajchert.trefoil.core.model.*
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
        val client = ImageClient(ByteArray(60) { 'a'.code.toByte() })
        val loader = HtmlRenderLoader(client, cacheBytes = 100)
        val other = ref.copy(attachmentId = "fixture-other")
        loader.load(ref, "fixture", "thread")
        loader.load(other, "fixture", "thread")
        assertNull(loader.cached(ref, "fixture", "thread"))
        assertNotNull(loader.cached(other, "fixture", "thread"))
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

    @Test fun environmentHostsCoverPairedAndLearnedRoutes() {
        val client = ImageClient(ByteArray(0))
        client.state.value = client.state.value.copy(
            environment = client.environment.copy(baseUrl = "https://Fixture.Example/", routes = listOf(EnvironmentRoute(RouteKind.Lan, "http://192.168.1.20:3773/", RouteSource.Learned))),
        )
        assertEquals(setOf("fixture.example", "192.168.1.20"), HtmlRenderLoader(client).environmentHosts("fixture"))
        assertEquals(emptySet<String>(), HtmlRenderLoader(client).environmentHosts("other"))
    }
}

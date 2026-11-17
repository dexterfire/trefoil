package com.tajchert.trefoil.ui.htmlrender

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.downloads.HtmlRenderLoader
import com.tajchert.trefoil.downloads.ImageClient
import com.tajchert.trefoil.ui.FileDownloadUi
import com.tajchert.trefoil.ui.LocalFileDownloadUi
import com.tajchert.trefoil.ui.TrefoilTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HtmlRenderCardTest {
    @get:Rule val compose = createComposeRule()
    private val reference = HtmlRenderReference("fixture-attachment", "Fixture chart", 240)
    private val html = "<!doctype html><html><head></head><body><p>Fixture page</p></body></html>"

    private fun content(state: HtmlRenderState, canRetry: Boolean = false, onRetry: () -> Unit = {}, inspection: Boolean = false) {
        compose.setContent {
            TrefoilTheme(darkTheme = true) {
                CompositionLocalProvider(LocalInspectionMode provides inspection) {
                    HtmlRenderCardContent(reference, state, canRetry = canRetry, onRetry = onRetry)
                }
            }
        }
    }

    @Test fun offlineState() {
        content(HtmlRenderState.Offline)
        compose.onNodeWithText("Reconnect to load “Fixture chart”").assertExists()
        compose.onNodeWithText("Fixture chart").assertExists()
        compose.onNodeWithContentDescription("Open “Fixture chart” full screen").assertIsNotEnabled()
    }

    @Test fun offlineStateOffersRetryOnlyWhenAllowed() {
        var retries = 0
        content(HtmlRenderState.Offline, canRetry = true, onRetry = { retries++ })
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun staleOfflineWhileConnectedRecoversThroughRetry() {
        // The card was told it may download, but the client does not (yet) show this thread: load() says NotCurrent.
        val client = ImageClient(html.toByteArray())
        client.state.value = client.state.value.copy(selectedThreadId = "other-thread")
        val ui = FileDownloadUi("fixture", "thread", true, htmlLoader = HtmlRenderLoader(client))
        compose.setContent {
            TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui, LocalInspectionMode provides true) { HtmlRenderCard(reference) } }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Reconnect to load “Fixture chart”").assertExists()
        assertEquals(0, client.requests)
        client.state.value = client.state.value.copy(selectedThreadId = "thread")
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(HTML_RENDER_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, client.requests)
    }

    @Test fun failedThenRetryThenReady() {
        val client = ImageClient(html.toByteArray(), fail = true)
        val ui = FileDownloadUi("fixture", "thread", true, htmlLoader = HtmlRenderLoader(client))
        compose.setContent {
            TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui, LocalInspectionMode provides true) { HtmlRenderCard(reference) } }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Unable to load “Fixture chart”").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, client.requests)
        client.fail = false
        compose.onNodeWithText("Retry").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag(HTML_RENDER_PLACEHOLDER_TAG).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unable to load “Fixture chart”").assertDoesNotExist()
        assertEquals(2, client.requests)
    }

    @Test fun measuredHeightSurvivesTheRowLeavingAndReturningToComposition() {
        val show = mutableStateOf(true)
        val height = mutableStateOf(html)
        var measured: androidx.compose.runtime.MutableState<Float?>? = null
        compose.setContent {
            val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
            if (show.value) holder.SaveableStateProvider("row") { measured = rememberHtmlRenderContentHeight(reference, height.value) }
        }
        compose.runOnIdle { measured!!.value = 480f }
        compose.runOnIdle { show.value = false }
        compose.runOnIdle { show.value = true }
        compose.runOnIdle { assertEquals(480f, measured!!.value) }
        // A different page under the same row starts unmeasured.
        compose.runOnIdle { height.value = "$html<!-- v2 -->" }
        compose.runOnIdle { assertNull(measured!!.value) }
    }

    @Test fun tooLargeState() {
        content(HtmlRenderState.TooLarge)
        compose.onNodeWithText("“Fixture chart” is too large to preview").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun errorStateRetriesOnlyWhenAllowed() {
        var retries = 0
        content(HtmlRenderState.Failed, canRetry = true, onRetry = { retries++ })
        compose.onNodeWithText("Unable to load “Fixture chart”").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun errorStateWithoutConnectionHasNoRetry() {
        content(HtmlRenderState.Failed, canRetry = false)
        compose.onNodeWithText("Unable to load “Fixture chart”").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun loadingStateShowsNoMessage() {
        content(HtmlRenderState.Loading)
        compose.onNodeWithText("Fixture chart").assertExists()
        compose.onNodeWithText("Unable to load “Fixture chart”").assertDoesNotExist()
        compose.onNodeWithText("Reconnect to load “Fixture chart”").assertDoesNotExist()
    }

    @Test fun readyInInspectionModeShowsPlaceholderAndOpensFullScreen() {
        content(HtmlRenderState.Ready(html), inspection = true)
        compose.onNodeWithTag(HTML_RENDER_PLACEHOLDER_TAG).assertExists()
        compose.onNodeWithContentDescription("Open “Fixture chart” full screen").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Close").assertExists().performClick()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
    }

    @Test fun fullScreenStaysOpenAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            TrefoilTheme(darkTheme = true) {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    HtmlRenderCardContent(reference, HtmlRenderState.Ready(html))
                }
            }
        }
        compose.onNodeWithContentDescription("Open “Fixture chart” full screen").performClick()
        compose.onNodeWithContentDescription("Close").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Close").assertExists().performClick()
        compose.onNodeWithContentDescription("Close").assertDoesNotExist()
    }

    @Test fun readyStateToStringRedactsContent() {
        assertFalse(HtmlRenderState.Ready(html).toString().contains("Fixture"))
    }

    @Test fun cardLoadsThroughLoaderIntoHardenedWebView() {
        val client = ImageClient(html.toByteArray())
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        val upstream = mutableListOf<String>()
        val fetcher = HtmlRenderSubresourceFetcher(fakeHttp { request ->
            upstream += request.url.toString()
            okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .header("Set-Cookie", "tracking=1").header("Access-Control-Allow-Origin", "*")
                .body("chart()".toResponseBody("text/javascript; charset=utf-8".toMediaType())).build()
        })
        val ui = FileDownloadUi("fixture", "thread", true, htmlLoader = HtmlRenderLoader(client, HtmlRenderLoader.MAX_SOURCE_BYTES, HtmlRenderLoader.CACHE_BYTES, fetcher))
        val shown = mutableStateOf(true)
        var root: View? = null
        compose.setContent {
            root = LocalView.current.rootView
            TrefoilTheme(darkTheme = true) {
                CompositionLocalProvider(LocalFileDownloadUi provides ui, LocalUriHandler provides uriHandler) {
                    if (shown.value) HtmlRenderCard(reference)
                }
            }
        }
        compose.waitUntil(5_000) { root?.let(::findWebView) != null }
        val view = findWebView(root!!)!!
        compose.runOnIdle {
            assertEquals(1, client.requests)
            with(view.settings) {
                assertTrue(javaScriptEnabled)
                assertFalse(domStorageEnabled)
                assertFalse(allowFileAccess)
                assertFalse(allowContentAccess)
                assertFalse(javaScriptCanOpenWindowsAutomatically)
                assertFalse(supportMultipleWindows())
                assertFalse(supportZoom())
                assertEquals(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
            }
            val shadow = shadowOf(view)
            val documentUrl = htmlRenderDocumentUrl("fixture", "thread", reference.attachmentId)
            assertTrue(shadow.lastLoadedUrl.startsWith("$documentUrl#t3-theme=%7B%22appearance%22%3A%22dark%22"))
            assertFalse(shadow.lastLoadedUrl.contains("fixture.invalid"))
            val client2 = shadow.webViewClient
            val served = client2.shouldInterceptRequest(view, request(documentUrl))!!
            assertEquals(200, served.statusCode)
            assertEquals("no-store", served.responseHeaders["Cache-Control"])
            assertEquals(HTML_RENDER_CSP, served.responseHeaders["Content-Security-Policy"])
            val body = served.data.bufferedReader().readText()
            assertTrue(body.contains("<html><head></head><body><p>Fixture page</p></body></html>$HTML_RENDER_HEIGHT_SCRIPT"))
            assertFalse(body.contains("fixture-token"))
            assertEquals(403, client2.shouldInterceptRequest(view, request(documentUrl, method = "POST"))!!.statusCode)
            assertEquals(403, client2.shouldInterceptRequest(view, request("https://cdn.example/x.html"))!!.statusCode)
            val proxied = client2.shouldInterceptRequest(view, request("https://cdn.example/chart.js", mainFrame = false, headers = mapOf("Cookie" to "a=b", "Accept" to "*/*")))!!
            assertEquals(200, proxied.statusCode)
            assertEquals("text/javascript", proxied.mimeType)
            assertEquals("*", proxied.responseHeaders["Access-Control-Allow-Origin"])
            assertNull(proxied.responseHeaders["Set-Cookie"])
            assertEquals("chart()", proxied.data.bufferedReader().readText())
            assertEquals(listOf("https://cdn.example/chart.js"), upstream)
            assertNull(client2.shouldInterceptRequest(view, request("data:image/png;base64,iVBORw0KGgo=", mainFrame = false)))
            assertEquals(403, client2.shouldInterceptRequest(view, request("https://192.168.1.4/x.js", mainFrame = false))!!.statusCode)
            assertEquals(403, client2.shouldInterceptRequest(view, request("https://fixture.invalid/api", mainFrame = false))!!.statusCode)
            assertEquals(403, client2.shouldInterceptRequest(view, request("http://cdn.example/x.js", mainFrame = false))!!.statusCode)
            // A cached page left on screen after disconnecting gets no remote subresources.
            client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
            assertEquals(403, client2.shouldInterceptRequest(view, request("https://cdn.example/chart.js", mainFrame = false))!!.statusCode)
            assertEquals(1, upstream.size)
            // Navigation never happens in place; gesture http(s) links go to the system browser.
            assertTrue(client2.shouldOverrideUrlLoading(view, request("https://example.com/docs", gesture = false)))
            assertTrue(client2.shouldOverrideUrlLoading(view, request("https://example.com/docs", gesture = true)))
            assertTrue(client2.shouldOverrideUrlLoading(view, request("intent://x#Intent;end", gesture = true)))
        }
        compose.runOnIdle { assertEquals(listOf("https://example.com/docs"), opened) }
        compose.runOnIdle { shown.value = false }
        compose.runOnIdle { assertTrue(shadowOf(view).wasDestroyCalled()) }
    }

    @Test fun themeChangedWhileLoadingIsReappliedOnPageFinished() {
        val client = ImageClient(html.toByteArray())
        val ui = FileDownloadUi("fixture", "thread", true, htmlLoader = HtmlRenderLoader(client))
        val dark = mutableStateOf(true)
        var root: View? = null
        compose.setContent {
            root = LocalView.current.rootView
            TrefoilTheme(darkTheme = dark.value) { CompositionLocalProvider(LocalFileDownloadUi provides ui) { HtmlRenderCard(reference) } }
        }
        compose.waitUntil(5_000) { root?.let(::findWebView) != null }
        val view = findWebView(root!!)!!
        compose.runOnIdle { dark.value = false }
        compose.runOnIdle {
            assertSame(view, findWebView(root!!))
            assertNull(shadowOf(view).lastEvaluatedJavascript)
            shadowOf(view).webViewClient.onPageFinished(view, shadowOf(view).lastLoadedUrl)
            assertTrue(shadowOf(view).lastEvaluatedJavascript.contains("\"theme\":\"light\""))
            assertFalse(CookieManager.getInstance().acceptCookie())
        }
    }

    @Test fun renderProcessGoneDestroysAndShowsError() {
        val client = ImageClient(html.toByteArray())
        val ui = FileDownloadUi("fixture", "thread", true, htmlLoader = HtmlRenderLoader(client))
        var root: View? = null
        compose.setContent {
            root = LocalView.current.rootView
            TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui) { HtmlRenderCard(reference) } }
        }
        compose.waitUntil(5_000) { root?.let(::findWebView) != null }
        val view = findWebView(root!!)!!
        compose.runOnIdle {
            val handled = shadowOf(view).webViewClient.onRenderProcessGone(view, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            })
            assertTrue(handled)
            assertTrue(shadowOf(view).wasDestroyCalled())
        }
        compose.onNodeWithText("Unable to load “Fixture chart”").assertExists()
        assertNull(findWebView(root!!))
        // Retry re-creates the page from the memory cache without another download.
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertNotNull(findWebView(root!!))
        assertNotSame(view, findWebView(root!!))
        assertEquals(1, client.requests)
    }

    @Test fun disconnectedWithoutCacheShowsOfflineAndDoesNotFetch() {
        val client = ImageClient(html.toByteArray())
        client.state.value = client.state.value.copy(connection = ConnectionStatus.Disconnected)
        val ui = FileDownloadUi("fixture", "thread", false, htmlLoader = HtmlRenderLoader(client))
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalFileDownloadUi provides ui) { HtmlRenderCard(reference) } } }
        compose.onNodeWithText("Reconnect to load “Fixture chart”").assertExists()
        assertEquals(0, client.requests)
    }

    @Test fun missingLoaderIsOffline() {
        compose.setContent { TrefoilTheme { HtmlRenderCard(reference) } }
        compose.onNodeWithText("Reconnect to load “Fixture chart”").assertExists()
    }

    private fun fakeHttp(respond: (okhttp3.Request) -> okhttp3.Response) =
        okhttp3.OkHttpClient.Builder().followRedirects(false).addInterceptor { respond(it.request()) }.build()

    private fun request(value: String, mainFrame: Boolean = true, method: String = "GET", gesture: Boolean = false, headers: Map<String, String> = emptyMap()): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(value)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = headers
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}

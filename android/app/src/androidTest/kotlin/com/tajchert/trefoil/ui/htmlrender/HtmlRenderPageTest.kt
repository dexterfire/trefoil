package com.tajchert.trefoil.ui.htmlrender

import android.os.SystemClock
import android.view.View.MeasureSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import com.tajchert.trefoil.ui.TrefoilDark
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads fixture pages in the real hardened WebView. JVM tests cannot catch WebView request details, such as
 * the main-frame URL arriving with its `#t3-theme=` fragment (which once made every page fail).
 */
@RunWith(AndroidJUnit4::class)
class HtmlRenderPageTest {
    private val fixture = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style id="t3-theme">:root{color-scheme:dark;--background:#0a0a0a;--foreground:#f5f5f5}</style></head><body>
<div id="g"></div><script>document.getElementById('g').insertAdjacentHTML('beforeend', '<div style="height:240px">Fixture</div>');</script>
</body></html>"""
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val pages = mutableListOf<HtmlRenderPage>()

    @Before fun needsMessageListener() {
        // Page readiness is observed through the height bridge.
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
    }

    @After fun releasePages() {
        instrumentation.runOnMainSync { pages.forEach { it.release() } }
    }

    @Test fun fixturePageLoadsAndReportsHeight() {
        val failed = AtomicBoolean(false)
        val page = open(fixture, "fixture-attachment-html") { it.onFailure = { failed.set(true) } }
        assertFalse("page reported a failure", failed.get())
        assertEquals("\"${page.policy.origin}\"", evaluate(page, "location.origin"))
    }

    @Test fun pagesNeverShareStorage() {
        val writer = open(fixture, "fixture-attachment-a")
        val reader = open(fixture, "fixture-attachment-b")
        val otherThread = open(fixture, "fixture-attachment-a", threadId = "fixture-thread-2")
        assertNotEquals(writer.policy.origin, reader.policy.origin)
        assertNotEquals(writer.policy.origin, otherThread.policy.origin)
        evaluate(writer, "caches.open('fixture').then(function(c){return c.put('/k',new Response('fixture'))}).then(function(){window.__r='ok'},function(e){window.__r='error:'+e.name});1")
        assertEquals("\"ok\"", poll(writer, "window.__r"))
        evaluate(writer, "caches.keys().then(function(k){window.__k=JSON.stringify(k)});1")
        assertEquals("\"[\\\"fixture\\\"]\"", poll(writer, "window.__k"))
        listOf(reader, otherThread).forEach { page ->
            evaluate(page, "caches.keys().then(function(k){window.__k=JSON.stringify(k)});1")
            assertEquals("\"[]\"", poll(page, "window.__k"))
        }
    }

    /** Opens [html] as a page and waits until it reports its height. */
    private fun open(
        html: String,
        attachmentId: String,
        environmentId: String = "fixture-env",
        threadId: String = "fixture-thread",
        configure: (HtmlRenderPage) -> Unit = {},
    ): HtmlRenderPage {
        val reported = CountDownLatch(1)
        val height = AtomicReference<Float?>(null)
        lateinit var page: HtmlRenderPage
        instrumentation.runOnMainSync {
            page = HtmlRenderPage(instrumentation.targetContext, html, environmentId, threadId, attachmentId, htmlRenderTheme(TrefoilDark), HtmlRenderNetworkAccess.Offline, fullScreen = false)
            pages += page
            configure(page)
            page.onHeight = { height.set(it); reported.countDown() }
            page.root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(900, MeasureSpec.EXACTLY))
            page.root.layout(0, 0, 1080, 900)
        }
        reported.await(20, TimeUnit.SECONDS)
        assertNotNull("page never reported its height (main document refused?)", height.get())
        return page
    }

    /** The JSON-encoded result of [script] in [page]'s main frame. */
    private fun evaluate(page: HtmlRenderPage, script: String): String? {
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        instrumentation.runOnMainSync { page.webView.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("script did not finish", done.await(10, TimeUnit.SECONDS))
        return result.get()
    }

    /** Re-evaluates [expression] until it is defined (async page work), up to [timeoutMs]. */
    private fun poll(page: HtmlRenderPage, expression: String, timeoutMs: Long = 10_000): String? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var value = evaluate(page, expression)
        while ((value == null || value == "null") && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            value = evaluate(page, expression)
        }
        return value
    }
}

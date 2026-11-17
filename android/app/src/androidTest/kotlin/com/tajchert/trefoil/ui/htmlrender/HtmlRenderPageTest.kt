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

    @After fun releasePages() {
        instrumentation.runOnMainSync { pages.forEach { it.release() } }
    }

    @Test fun fixturePageLoadsAndReportsHeight() {
        val failed = AtomicBoolean(false)
        val page = open(fixture) { it.onFailure = { failed.set(true) } }
        assertFalse("page reported a failure", failed.get())
        assertEquals("\"${page.policy.origin}\"", evaluate(page, "location.origin"))
    }

    @Test fun pagesHaveAnOpaqueOriginAndNoStorage() {
        val first = open(fixture)
        val second = open(fixture)
        assertNotEquals(first.policy.origin, second.policy.origin)
        // The CSP sandbox makes the origin opaque: nothing can be stored, so nothing is shared or left on disk.
        assertEquals("\"null\"", evaluate(first, "self.origin"))
        assertEquals("\"undefined\"", evaluate(first, "typeof caches"))
        assertEquals("\"SecurityError\"", evaluate(first, "(function(){try{indexedDB.open('fixture');return 'opened'}catch(e){return e.name}})()"))
        assertEquals("\"SecurityError\"", evaluate(first, "(function(){try{return typeof document.cookie}catch(e){return e.name}})()"))
    }

    @Test fun peerConnectionsAndWebTransportAreGone() {
        val page = open(probe)
        // `caches` too: Cache Storage is not cleared by WebStorage.deleteOrigin when the session is released.
        listOf("RTCPeerConnection", "webkitRTCPeerConnection", "WebTransport", "caches").forEach { name ->
            assertEquals(name, "\"undefined\"", evaluate(page, "typeof window.$name"))
            // The page cannot delete or redefine the lockdown.
            assertEquals(name, "\"undefined\"", evaluate(page, "(function(){try{delete window.$name;}catch(e){}return typeof window.$name})()"))
            assertEquals(name, "\"threw\"", evaluate(page, "(function(){try{Object.defineProperty(window,'$name',{value:1});return 'redefined'}catch(e){return 'threw'}})()"))
        }
        assertEquals("\"threw\"", evaluate(page, "(function(){try{new RTCPeerConnection({iceServers:[{urls:'stun:192.168.1.1:3478'}]});return 'created'}catch(e){return 'threw'}})()"))
        assertEquals("\"undefined\"", evaluate(page, "typeof window.__pageSawRtc"))
        // A fresh child frame still has its own untouched globals, but under the sandbox it is cross-origin:
        // the page cannot read them, however it reaches the window.
        assertEquals("\"SecurityError\"", evaluate(page, "(function(){var f=document.createElement('iframe');document.body.appendChild(f);try{return typeof f.contentWindow.RTCPeerConnection}catch(e){return e.name}})()"))
        assertEquals("\"SecurityError\"", evaluate(page, "(function(){document.body.appendChild(document.createElement('iframe'));try{return typeof window[window.length-1].RTCPeerConnection}catch(e){return e.name}})()"))
        assertEquals("\"SecurityError\"", evaluate(page, "(function(){document.body.appendChild(document.createElement('iframe'));try{new window.frames[window.frames.length-1].RTCPeerConnection({iceServers:[{urls:'stun:192.168.1.1:3478'}]});return 'created'}catch(e){return e.name}})()"))
    }

    @Test fun scriptInChildFramesNeverSeesPeerConnections() {
        val page = open(probe)
        // `srcdoc` frames run script in their own realm, where the document-start lockdown runs first; a
        // `javascript:` frame is cross-origin to the page that names it and runs nothing.
        evaluate(page, "window.__m=[];window.addEventListener('message',function(e){window.__m.push(String(e.data))});var p='[typeof RTCPeerConnection,typeof WebTransport,typeof caches].join()';var s=document.createElement('iframe');s.srcdoc='<script>parent.postMessage(\\'srcdoc:\\'+'+p+',\\'*\\')<\\/script>';document.body.appendChild(s);var j=document.createElement('iframe');j.src='javascript:parent.postMessage(\\'jsurl:\\'+'+p+',\\'*\\')';document.body.appendChild(j);var d=document.createElement('div');d.innerHTML='<iframe srcdoc=\"&lt;script&gt;parent.postMessage(\\'parsed:\\'+'+p+',\\'*\\')&lt;/script&gt;\"></iframe>';document.body.appendChild(d);1")
        poll(page, "window.__m.length>=2?window.__m.length:null")
        SystemClock.sleep(1_000)
        assertEquals(
            "\"parsed:undefined,undefined,undefined|srcdoc:undefined,undefined,undefined\"",
            evaluate(page, "window.__m.slice().sort().join('|')"),
        )
    }

    @Test fun scriptBeforeAParserClosedCommentNeverSeesPeerConnections() {
        // `<!-->` closes the comment for the HTML parser, so this script is the first thing that runs.
        val page = open("<!--><script>window.savedRTC=typeof RTCPeerConnection;window.savedTransport=typeof WebTransport</script><!-- --><!doctype html><p>x</p>")
        assertEquals("\"undefined\"", evaluate(page, "window.savedRTC"))
        assertEquals("\"undefined\"", evaluate(page, "window.savedTransport"))
    }

    @Test fun withoutDocumentStartThePageRunsNoScript() {
        val html = "<!--><script>document.title='ran'</script><!-- --><!doctype html><html><head><title>static</title>" +
            "<script>document.title='ran';new RTCPeerConnection({iceServers:[{urls:'stun:192.168.1.1:3478'}]})</script></head><body><p>x</p></body></html>"
        val page = open(html, allowScript = false)
        val deadline = SystemClock.uptimeMillis() + 20_000
        var title: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { title = page.webView.title.takeIf { page.webView.progress == 100 } }
            if (title == "static" || title == "ran") break
            SystemClock.sleep(100)
        }
        assertFalse(page.scripted)
        instrumentation.runOnMainSync { assertFalse(page.webView.settings.javaScriptEnabled) }
        assertEquals("static", title)
    }

    @Test fun webSocketIsBlocked() {
        val page = open(probe)
        evaluate(page, "try{var s=new WebSocket('wss://echo.example/');s.onopen=function(){window.__open=true};}catch(e){};1")
        assertViolation(page, "connect-src")
        assertEquals("null", evaluate(page, "window.__open"))
    }

    @Test fun iframeIsBlocked() {
        val page = open(probe)
        evaluate(page, "var f=document.createElement('iframe');f.src='https://cdn.example/frame.html';document.body.appendChild(f);1")
        assertViolation(page, "frame-src")
    }

    @Test fun workerIsBlocked() {
        val page = open(probe)
        assertEquals("\"blocked\"", evaluate(page, "(function(){try{new Worker(URL.createObjectURL(new Blob(['postMessage(1)'],{type:'text/javascript'})));return 'created'}catch(e){return 'blocked'}})()"))
        assertViolation(page, "worker-src")
    }

    @Test fun formPostIsBlocked() {
        val page = open(probe)
        val origin = "\"${page.policy.origin}\""
        evaluate(page, "var f=document.createElement('form');f.method='post';f.action='https://cdn.example/collect';document.body.appendChild(f);f.submit();1")
        // The sandbox (no allow-forms) drops the submit before `form-action` is consulted, so there is no CSP
        // violation to wait for. The in-memory document is still the one in the frame.
        SystemClock.sleep(1_000)
        assertEquals("\"[]\"", evaluate(page, "JSON.stringify(window.__violations)"))
        assertEquals(origin, evaluate(page, "location.origin"))
        assertEquals("\"fixture\"", evaluate(page, "document.getElementById('probe').textContent"))
    }

    /** Records CSP violations; also notes if the page could ever see a peer connection at parse time. */
    private val probe = """<!doctype html><html><head><script>window.__violations=[];
document.addEventListener('securitypolicyviolation',function(e){window.__violations.push(e.effectiveDirective||e.violatedDirective)});
if(typeof RTCPeerConnection!=='undefined')window.__pageSawRtc=true;</script></head><body><p id="probe">fixture</p></body></html>"""

    private fun assertViolation(page: HtmlRenderPage, directive: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var violations = evaluate(page, "JSON.stringify(window.__violations)")
        while (violations?.contains(directive) != true && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
            violations = evaluate(page, "JSON.stringify(window.__violations)")
        }
        assertTrue("no $directive violation in $violations", violations?.contains(directive) == true)
    }

    /**
     * Opens [html] as a page. A scripted page (the default; skipped where the WebView lacks document-start
     * injection or the message listener) is ready once it reports its height; a static one is returned at once.
     */
    private fun open(
        html: String,
        allowScript: Boolean = true,
        configure: (HtmlRenderPage) -> Unit = {},
    ): HtmlRenderPage {
        if (allowScript) {
            assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        }
        val reported = CountDownLatch(1)
        val height = AtomicReference<Float?>(null)
        lateinit var page: HtmlRenderPage
        instrumentation.runOnMainSync {
            page = HtmlRenderPage(instrumentation.targetContext, html, htmlRenderTheme(TrefoilDark), HtmlRenderNetworkAccess.Offline, fullScreen = false, allowScript = allowScript)
            pages += page
            configure(page)
            page.onHeight = { height.set(it); reported.countDown() }
            page.root.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(900, MeasureSpec.EXACTLY))
            page.root.layout(0, 0, 1080, 900)
        }
        if (!allowScript) return page
        reported.await(20, TimeUnit.SECONDS)
        assertTrue(page.scripted)
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

package com.tajchert.trefoil.ui.htmlrender

import org.junit.Assert.*
import org.junit.Test

class HtmlRenderPolicyTest {
    private val documentUrl = htmlRenderDocumentUrl()
    private val otherPage = newHtmlRenderOrigin()
    private var hosts = setOf("fixture.example", "[fd00::5]")
    private var connected = true
    private val policy = HtmlRenderPolicy(documentUrl, HtmlRenderNetworkAccess({ hosts }, { connected }))
    private val theme = HtmlRenderTheme(dark = true, variables = mapOf("--background" to "#000"))

    @Test fun documentUrlIsSyntheticAndRandomPerSession() {
        assertTrue(documentUrl, documentUrl.matches(Regex("https://[0-9a-f]{32}\\.html-render\\.trefoil\\.invalid/index\\.html")))
        assertEquals(documentUrl, "${policy.origin}/index.html")
        assertEquals(documentUrl, htmlRenderDocumentUrl(policy.origin))
        // Never derived from anything: every session gets a fresh origin.
        assertNotEquals(documentUrl, htmlRenderDocumentUrl())
    }

    @Test fun everySessionHasItsOwnOrigin() {
        val origins = List(2_000) { newHtmlRenderOrigin() }
        assertEquals(origins.size, origins.toSet().size)
        origins.forEach {
            assertTrue(it, it.matches(Regex("https://[0-9a-f]{32}\\.html-render\\.trefoil\\.invalid")))
            assertFalse(it, it == "https://$HTML_RENDER_HOST")
        }
    }

    @Test fun policyOnlyAcceptsSyntheticDocumentUrls() {
        listOf(
            "https://cdn.example/index.html", "https://$HTML_RENDER_HOST.cdn.example/index.html", "https://$HTML_RENDER_HOST/index.html",
            "http://${otherPage.removePrefix("https://")}/index.html", "$otherPage:8443/index.html", "$otherPage/other.html", "not a url",
            // Only one 32-hex session label directly under the synthetic host.
            "https://fixture.$HTML_RENDER_HOST/index.html", "https://${"a".repeat(31)}.$HTML_RENDER_HOST/index.html",
            "https://${"A".repeat(32)}.$HTML_RENDER_HOST/index.html", "https://x.${otherPage.removePrefix("https://")}/index.html",
        ).forEach { url -> assertThrows(url, IllegalArgumentException::class.java) { HtmlRenderPolicy(url, HtmlRenderNetworkAccess.Offline) } }
    }

    @Test fun mainFrameOnlyExactGetDocument() {
        assertTrue(policy.allowsMainFrame(documentUrl, "GET", false))
        assertFalse(policy.allowsMainFrame(documentUrl, "POST", false))
        assertFalse(policy.allowsMainFrame(documentUrl, "GET", true))
        // WebView reports the main-frame request with the theme fragment it was loaded with.
        val themed = documentUrl + htmlRenderThemeFragment(HtmlRenderTheme(dark = true, variables = mapOf("--background" to "#000")))
        assertTrue(policy.allowsMainFrame(themed, "GET", false))
        assertFalse(policy.allowsMainFrame("$documentUrl?x=1", "GET", false))
        assertFalse(policy.allowsMainFrame("$documentUrl?x=1#t3-theme=x", "GET", false))
        assertFalse(policy.allowsMainFrame("${documentUrl}x#t3-theme=x", "GET", false))
        assertFalse(policy.allowsMainFrame("https://html-render.trefoil.invalid/other/index.html", "GET", false))
        assertFalse(policy.allowsMainFrame("https://html-render.trefoil.invalid/index.html", "GET", false))
        assertFalse(policy.allowsMainFrame("$otherPage/index.html", "GET", false))
        assertFalse(policy.allowsMainFrame("$otherPage/index.html#t3-theme=x", "GET", false))
        assertFalse(policy.allowsMainFrame("${policy.origin}/other.html", "GET", false))
        assertFalse(policy.allowsMainFrame("https://cdn.example/index.html", "GET", false))
    }

    @Test fun allowsPublicHttpsGetAndInlineSchemes() {
        listOf(
            "https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js",
            "https://fonts.gstatic.com/s/inter.woff2",
            "https://93.184.216.34/image.png",
            "https://[2606:4700::1111]/x.js",
            "data:image/png;base64,iVBORw0KGgo=",
            "blob:https://html-render.trefoil.invalid/6c1c",
            "blob:${policy.origin}/6c1c",
        ).forEach { assertTrue(it, policy.allowsSubresource(it, "GET")) }
    }

    @Test fun blocksEverythingElse() {
        listOf(
            "http://cdn.example/x.js",
            "https://localhost/x", "https://foo.localhost/x", "https://printer.local/x", "https://intranet/x",
            "https://127.0.0.1/x", "https://127.1.2.3/x", "https://0.0.0.0/x", "https://[::1]/x", "https://[::]/x",
            "https://10.0.0.5/x", "https://172.16.4.1/x", "https://172.31.255.255/x", "https://192.168.1.10/x",
            "https://169.254.169.254/latest/meta-data", "https://100.100.100.100/x", "https://[fe80::1]/x",
            "https://[fd12:3456::1]/x", "https://[::ffff:192.168.1.1]/x", "https://[::ffff:7f00:1]/x",
            "https://2130706433/x", "https://0x7f.0.0.1/x", "https://127.1/x", "https://010.0.0.1/x",
            "https://fixture.example/api", "https://FIXTURE.example./api", "https://[fd00::5]/x",
            "https://html-render.trefoil.invalid/other.css",
            // Neither this page's own origin nor another page's is ever a subresource.
            "${policy.origin}/index.html", "${policy.origin}/other.css", "$otherPage/index.html",
            "https://X.HTML-RENDER.TREFOIL.INVALID./x", "https://a.b.html-render.trefoil.invalid/x",
            "https://user:pass@cdn.example/x",
            "file:///sdcard/x", "content://media/x", "javascript:alert(1)", "about:blank", "wss://cdn.example/x",
            "intent://x#Intent;end", "ftp://cdn.example/x", "not a url",
        ).forEach { assertFalse(it, policy.allowsSubresource(it, "GET")) }
        listOf("POST", "PUT", "DELETE", "HEAD", "OPTIONS", null).forEach {
            assertFalse("$it", policy.allowsSubresource("https://cdn.example/x", it))
        }
    }

    @Test fun environmentHostsAreReadPerRequest() {
        assertTrue(policy.allowsSubresource("https://new-route.example/x", "GET"))
        hosts = hosts + "New-Route.Example."
        assertFalse(policy.allowsSubresource("https://new-route.example/x", "GET"))
        assertFalse(policy.allowsFetch("https://new-route.example/x"))
        hosts = emptySet()
        assertTrue(policy.allowsSubresource("https://fixture.example/api", "GET"))
    }

    @Test fun remoteFetchesNeedHttpsAndACurrentConnection() {
        assertTrue(policy.allowsFetch("https://cdn.example/x.js"))
        assertFalse(policy.allowsFetch("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(policy.allowsFetch("http://cdn.example/x.js"))
        assertFalse(policy.allowsFetch("https://10.0.0.1/x.js"))
        connected = false
        assertFalse(policy.allowsFetch("https://cdn.example/x.js"))
        // Inline schemes stay allowed so cached pages still render offline.
        assertTrue(policy.allowsSubresource("data:image/png;base64,iVBORw0KGgo=", "GET"))
    }

    @Test fun offlineAccessNeverFetches() {
        val offline = HtmlRenderPolicy(documentUrl, HtmlRenderNetworkAccess.Offline)
        assertFalse(offline.allowsFetch("https://cdn.example/x.js"))
        assertNull(HtmlRenderNetworkAccess.Offline.fetcher)
    }

    @Test fun externalLinksNeedGestureAndHttp() {
        assertEquals("https://example.com/docs", policy.externalLink("https://example.com/docs", true))
        assertEquals("http://example.com/", policy.externalLink("http://example.com/", true))
        assertNull(policy.externalLink("https://example.com/docs", false))
        assertNull(policy.externalLink("intent://scan#Intent;scheme=zxing;end", true))
        assertNull(policy.externalLink("javascript:alert(1)", true))
        assertNull(policy.externalLink("file:///sdcard/x", true))
        assertNull(policy.externalLink("$documentUrl#section", true))
        assertNull(policy.externalLink("$otherPage/index.html", true))
        assertNull(policy.externalLink("https://html-render.trefoil.invalid/", true))
        assertNull(policy.externalLink("https://user@example.com/", true))
    }

    @Test fun reportedHeightIsParsedAndClamped() {
        assertEquals(412f, htmlRenderReportedHeight("412")!!, 0f)
        assertEquals(80f, htmlRenderReportedHeight("12")!!, 0f)
        assertEquals(20_000f, htmlRenderReportedHeight("99999999")!!, 0f)
        listOf(null, "", "abc", "NaN", "Infinity", "-5", "0", "1".repeat(40)).forEach { assertNull(it, htmlRenderReportedHeight(it)) }
    }

    @Test fun servedDocumentAppendsOnlyTheHeightScript() {
        val html = "<!doctype html><p>Fixture</p>"
        val served = htmlRenderDocument(html, scripted = true, theme = theme).toString(Charsets.UTF_8)
        assertTrue(served.startsWith(html))
        assertEquals(HTML_RENDER_HEIGHT_SCRIPT, served.removePrefix(html))
        assertTrue(HTML_RENDER_HEIGHT_SCRIPT.contains("window.$HTML_RENDER_BRIDGE"))
        assertTrue(HTML_RENDER_CSP.contains("connect-src https: data: blob:"))
        assertTrue(HTML_RENDER_CSP.contains("form-action 'none'"))
    }

    @Test fun noRtcScriptRemovesPeerConnectionsAndWebTransport() {
        listOf("'RTCPeerConnection'", "'webkitRTCPeerConnection'", "'WebTransport'", "'caches'").forEach { assertTrue(it, HTML_RENDER_NO_RTC_SCRIPT.contains(it)) }
        assertTrue(HTML_RENDER_NO_RTC_SCRIPT.contains("Object.defineProperty(w,n[i],{value:undefined,writable:false,enumerable:false,configurable:false})"))
        assertTrue(HTML_RENDER_NO_RTC_SCRIPT.contains("l(window);"))
        // Same-origin iframe windows are locked when reached through the element.
        assertTrue(HTML_RENDER_NO_RTC_SCRIPT.contains("'contentWindow'"))
        assertTrue(HTML_RENDER_NO_RTC_SCRIPT.contains("'contentDocument'"))
        assertFalse(HTML_RENDER_NO_RTC_SCRIPT.contains("</script"))
    }

    @Test fun scriptRunsOnlyBehindTheDocumentStartLockdown() {
        var installs = 0
        assertTrue(htmlRenderRunsScript(supportsDocumentStart = true) { installs++ })
        assertEquals(1, installs)
        // Without document-start support the lockdown is never attempted and the page gets no script.
        assertFalse(htmlRenderRunsScript(supportsDocumentStart = false) { installs++ })
        assertEquals(1, installs)
        // A failed install is the same as no support.
        assertFalse(htmlRenderRunsScript(supportsDocumentStart = true) { throw IllegalStateException("fixture") })
    }

    @Test fun staticDocumentGetsNoScriptOnlyTheThemeRule() {
        // The parser-trick page from the review: nothing may be inserted ahead of the page's own markup.
        val tricky = "<!--><script>window.savedRTC=RTCPeerConnection</script><!-- --><!doctype html><p>x</p>"
        val served = htmlRenderDocument(tricky, scripted = false, theme = theme).toString(Charsets.UTF_8)
        assertEquals(tricky + htmlRenderStaticThemeStyle(theme), served)
        assertFalse(served.contains(HTML_RENDER_NO_RTC_SCRIPT))
        assertFalse(served.contains(HTML_RENDER_HEIGHT_SCRIPT))
        assertFalse(htmlRenderStaticThemeStyle(theme).contains("<script"))
        assertTrue(htmlRenderDocument(tricky, scripted = true, theme = theme).toString(Charsets.UTF_8).startsWith(tricky))
    }

    @Test fun staticThemeRuleCarriesOnlySafeVariables() {
        val style = htmlRenderStaticThemeStyle(HtmlRenderTheme(dark = false, variables = linkedMapOf(
            "--background" to "#ffffff", "--font-sans" to "\"Segoe UI\", sans-serif",
            "--evil" to "red}</style><script>x()</script>", "--semi" to "red;color:blue", "notvar" to "red", "--nl" to "a\nb",
        )))
        assertEquals("<style>:root{color-scheme:light;--background:#ffffff;--font-sans:\"Segoe UI\", sans-serif;}</style>", style)
    }

    @Test fun cspBlocksFramesAndWorkers() {
        assertEquals(
            "connect-src https: data: blob:; form-action 'none'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; sandbox allow-scripts",
            HTML_RENDER_CSP,
        )
        // Opaque origins for the page and its frames: never weaken the sandbox with allow-same-origin.
        assertFalse(HTML_RENDER_CSP.contains("allow-same-origin"))
        // img-src/script-src stay unrestricted, so data:/blob: images and scripts keep working.
        assertFalse(HTML_RENDER_CSP.contains("img-src"))
        assertFalse(HTML_RENDER_CSP.contains("script-src"))
        assertFalse(HTML_RENDER_CSP.contains("default-src"))
    }
}

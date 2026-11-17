package com.tajchert.trefoil.ui.htmlrender

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.tajchert.trefoil.core.model.HTML_RENDER_MAX_FIT_HEIGHT
import com.tajchert.trefoil.core.model.HTML_RENDER_MIN_HEIGHT
import com.tajchert.trefoil.core.model.isPrivateLanAddress
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import java.security.SecureRandom

/**
 * Suffix of the synthetic page origins: `.invalid` never resolves, so nothing here reaches the network. The bare
 * host was the shared origin of builds before per-page origins; its leftover storage is deleted.
 */
internal const val HTML_RENDER_HOST = "html-render.trefoil.invalid"
internal const val HTML_RENDER_BRIDGE = "TrefoilHtmlRender"

/** The synthetic host itself or any subdomain of it. */
internal fun isHtmlRenderHost(normalizedHost: String): Boolean =
    normalizedHost == HTML_RENDER_HOST || normalizedHost.endsWith(".$HTML_RENDER_HOST")

private val HTML_RENDER_SESSION_LABEL = Regex("[0-9a-f]{32}")
private val sessionRandom by lazy { SecureRandom() }

/**
 * `https://<32 random hex>.html-render.trefoil.invalid`: a fresh origin for every WebView session, never derived
 * from ids. Storage, BroadcastChannel and other same-origin state never cross pages or sessions, a new session can
 * never inherit storage left behind by a process death, and the forwarded `Origin` header cannot link views.
 */
internal fun newHtmlRenderOrigin(): String {
    val bytes = ByteArray(16).also(sessionRandom::nextBytes)
    return "https://${bytes.joinToString("") { "%02x".format(it) }}.$HTML_RENDER_HOST"
}

/** `<session origin>/index.html`, without a fragment. */
internal fun htmlRenderDocumentUrl(origin: String = newHtmlRenderOrigin()): String = "$origin/index.html"

/**
 * Reports the page height through the origin-restricted web message listener, with T3's measure: the
 * scroll height when the page overflows, else the root box height, so the frame can shrink as well as grow.
 */
internal const val HTML_RENDER_HEIGHT_SCRIPT = "<script>(function(){var p=window.$HTML_RENDER_BRIDGE,h;if(!p||!p.postMessage)return;" +
    "function z(){var r=document.documentElement,v=Math.ceil(r.scrollHeight>r.clientHeight?r.scrollHeight:r.getBoundingClientRect().height);" +
    "if(!(v>0)||v===h)return;h=v;p.postMessage(String(v));}" +
    "if(window.ResizeObserver){var o=new ResizeObserver(z);o.observe(document.documentElement);if(document.body)o.observe(document.body);}" +
    "document.addEventListener(\"DOMContentLoaded\",z);window.addEventListener(\"load\",z);z();})();</script>"

/**
 * Header CSP on the served document: no WebSockets (`https:` never matches `wss:`), no form posts that would
 * replace the in-memory main frame, no plugins, and no frames or workers, which would bring their own policy
 * (or none) and whose WebSockets and fetches the request interceptor cannot see. Images and scripts from
 * `data:`/`blob:` keep working. Everything else is filtered per request by [HtmlRenderPolicy].
 *
 * `sandbox allow-scripts` gives the document an opaque origin, and every child frame another one. `frame-src`
 * does not stop `about:blank` and `srcdoc` frames, and a fresh frame's window, read by index before any script
 * can be injected into it, still has `RTCPeerConnection` and `WebTransport`; cross-origin, the page cannot
 * reach it. An opaque origin also has no IndexedDB, Cache Storage or cookies, so a page stores nothing.
 */
internal const val HTML_RENDER_CSP = "connect-src https: data: blob:; form-action 'none'; object-src 'none'; " +
    "frame-src 'none'; child-src 'none'; worker-src 'none'; sandbox allow-scripts"

/**
 * Removes WebRTC and WebTransport before any page script runs: ICE sends STUN/TURN to arbitrary (LAN) hosts and
 * WebTransport speaks QUIC, and neither passes `shouldInterceptRequest` or the CSP. Cache Storage (`caches`) goes
 * too: `WebStorage.deleteOrigin` does not clear it, so a released session would leave it on disk. Installed only through
 * document-start injection, which also runs in frames by itself. The globals become undefined and
 * non-configurable, and same-origin iframe windows reached through `contentWindow`/`contentDocument` are locked
 * the same way as a second layer.
 */
internal const val HTML_RENDER_NO_RTC_SCRIPT = "(function(){var n=['RTCPeerConnection','webkitRTCPeerConnection','WebTransport','caches'];" +
    "function l(w){if(w)for(var i=0;i<n.length;i++){try{Object.defineProperty(w,n[i],{value:undefined,writable:false,enumerable:false,configurable:false});}catch(e){}}return w;}" +
    "l(window);var p=window.HTMLIFrameElement&&HTMLIFrameElement.prototype;if(!p)return;" +
    "var w=Object.getOwnPropertyDescriptor(p,'contentWindow'),d=Object.getOwnPropertyDescriptor(p,'contentDocument');" +
    "if(w&&w.get&&w.configurable)Object.defineProperty(p,'contentWindow',{get:function(){return l(w.get.call(this));},enumerable:w.enumerable,configurable:false});" +
    "if(d&&d.get&&d.configurable)Object.defineProperty(p,'contentDocument',{get:function(){var x=d.get.call(this);if(x)l(x.defaultView);return x;},enumerable:d.enumerable,configurable:false});})();"

/** Whether this WebView can inject scripts at document start ([HTML_RENDER_NO_RTC_SCRIPT]). */
internal fun htmlRenderSupportsDocumentStart(): Boolean =
    runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }.getOrDefault(false)

/**
 * Whether page script may run: only when [install] put [HTML_RENDER_NO_RTC_SCRIPT] in place at document start.
 * An in-document copy of the lockdown can be outrun (a parser-closed comment before it, or a fresh frame realm
 * reached by index), so without document-start support the page is rendered with JavaScript off instead.
 */
internal fun htmlRenderRunsScript(supportsDocumentStart: Boolean, install: () -> Unit): Boolean =
    supportsDocumentStart && runCatching(install).isSuccess

/**
 * Installs [HTML_RENDER_NO_RTC_SCRIPT] at document start for a page origin; false when this WebView cannot, and
 * that page is then static. Robolectric has no WebView features, so JVM tests swap [install] to reach the
 * scripted path.
 */
internal object HtmlRenderDocumentStart {
    val platform: (WebView, String) -> Boolean = { webView, origin ->
        htmlRenderRunsScript(htmlRenderSupportsDocumentStart()) {
            // The sandboxed page and its `srcdoc` frames have opaque origins, which only `*` matches; this
            // WebView never shows anything but the page.
            WebViewCompat.addDocumentStartJavaScript(webView, HTML_RENDER_NO_RTC_SCRIPT, setOf("*"))
        }
    }
    @VisibleForTesting @Volatile var install: (WebView, String) -> Boolean = platform
}

private val CSS_CUSTOM_PROPERTY = Regex("--[A-Za-z0-9_-]+")

/**
 * The theme for a page without script, where T3's bootstrap cannot read the URL fragment: the variables as a
 * `:root` rule, appended so it wins over the page's own `:root` defaults. Values that could leave the rule are
 * dropped.
 */
internal fun htmlRenderStaticThemeStyle(theme: HtmlRenderTheme): String = buildString {
    append("<style>:root{color-scheme:").append(theme.appearance).append(';')
    theme.variables.forEach { (name, value) ->
        if (name.matches(CSS_CUSTOM_PROPERTY) && value.none { it in "<>{};\\\n\r" }) append(name).append(':').append(value).append(';')
    }
    append("}</style>")
}

/**
 * The served bytes: the agent's (already bootstrapped) document plus, when [scripted], the appended height
 * script, else the static theme rule. Nothing is ever inserted ahead of the page's own markup.
 */
internal fun htmlRenderDocument(html: String, scripted: Boolean, theme: HtmlRenderTheme): ByteArray =
    (html + if (scripted) HTML_RENDER_HEIGHT_SCRIPT else htmlRenderStaticThemeStyle(theme)).toByteArray(Charsets.UTF_8)

/** Parses a height message from the page; anything else is ignored. */
internal fun htmlRenderReportedHeight(data: String?): Float? =
    data?.takeIf { it.length <= 16 }?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(HTML_RENDER_MIN_HEIGHT.toFloat(), HTML_RENDER_MAX_FIT_HEIGHT.toFloat())

/**
 * Request policy for an agent page. Only the exact in-memory document is served at its session's synthetic
 * origin; no synthetic host (its own or another page's) is ever a subresource or external link.
 * Subresources are GET `https:` to public-looking hosts (plus `data:`/`blob:`); loopback, `localhost`,
 * mDNS, single-label names, private/link-local/CGNAT/ULA literals, and the environment's current hosts are
 * refused. Remote ones are fetched by [HtmlRenderSubresourceFetcher] only while [network] is current, with
 * this policy re-checked on every redirect hop and DNS answers limited to public addresses.
 */
internal class HtmlRenderPolicy(val documentUrl: String, private val network: HtmlRenderNetworkAccess) {
    /** `https://<session>.html-render.trefoil.invalid`, the only origin whose messages this page accepts. */
    val origin: String = runCatching { URI(documentUrl) }.getOrNull().let { uri ->
        require(uri != null && uri.scheme == "https" && uri.host?.substringBefore('.')?.matches(HTML_RENDER_SESSION_LABEL) == true &&
            uri.host.substringAfter('.') == HTML_RENDER_HOST && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawPath == "/index.html" && uri.rawQuery == null && uri.rawFragment == null) { "not an html render document" }
        "https://${uri.host}"
    }

    /** The WebView passes the loaded URL with its `#t3-theme=` fragment, so the fragment is ignored here. */
    fun allowsMainFrame(url: String, method: String?, redirect: Boolean): Boolean =
        url.substringBefore('#') == documentUrl && method.equals("GET", ignoreCase = true) && !redirect

    /** A remote hop (first request or redirect target) the fetcher may make right now. */
    fun allowsFetch(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && allowsSubresource(url, "GET") && network.isCurrent()

    fun allowsSubresource(url: String, method: String?): Boolean {
        if (!method.equals("GET", ignoreCase = true)) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "data", "blob" -> true
            "https" -> uri.rawUserInfo == null && uri.host != null && !isBlockedHost(uri.host)
            else -> false
        }
    }

    /** The URL to open in the user's browser for a gesture-initiated http(s) navigation, else null. */
    fun externalLink(url: String, hasGesture: Boolean): String? {
        if (!hasGesture) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        if (isHtmlRenderHost(normalizeHost(uri.host))) return null
        return url
    }

    /** Environment hosts are read live, so a route or active base learned after the page opened is refused. */
    fun isBlockedHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty() || isHtmlRenderHost(host)) return true
        if (network.environmentHosts().any { normalizeHost(it) == host }) return true
        return isNonPublicName(host)
    }

    companion object {
        fun normalizeHost(host: String): String = host.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')

        /** Names and literals that are never public, without resolving anything. */
        fun isNonPublicName(rawHost: String): Boolean {
            val host = normalizeHost(rawHost)
            if (host.isEmpty()) return true
            if (host == "localhost" || listOf(".localhost", ".local", ".internal", ".home.arpa").any(host::endsWith)) return true
            if (':' in host) return runCatching { InetAddress.getByName(host) }.getOrNull()?.let(HtmlRenderAddresses::isNonPublic) ?: true
            if ('.' !in host) return true
            val labels = host.split('.')
            val last = labels.last()
            // WHATWG treats a numeric (or hex) last label as IPv4; only plain dotted-quad public literals pass.
            if (last.all(Char::isDigit) || last.startsWith("0x")) {
                if (labels.size != 4 || labels.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) || (it.length > 1 && it[0] == '0') }) return true
                val octets = labels.map { it.toInt() }
                if (octets.any { it > 255 }) return true
                return HtmlRenderAddresses.isNonPublicIpv4(octets) || isPrivateLanAddress(host)
            }
            return false
        }
    }
}

/**
 * Keeps the page theme in step with the app. The URL fragment carries the theme at load time; a theme that
 * changes before the document finishes loading is posted once it has, instead of to a page not yet there.
 */
internal class HtmlRenderThemeSync(private val loaded: HtmlRenderTheme) {
    var latest: HtmlRenderTheme = loaded
        private set
    private var documentReady = false

    /** Records [next]; true when the live document should receive it now. */
    fun update(next: HtmlRenderTheme): Boolean {
        if (next == latest) return false
        latest = next
        return documentReady
    }

    /** The document finished loading: the theme to post if it differs from the one in the URL fragment. */
    fun documentFinished(): HtmlRenderTheme? {
        documentReady = true
        return latest.takeIf { it != loaded }
    }
}

/** Keeps vertical drags with the conversation: the page cannot claim the parent's gestures. */
private class InlineHtmlFrame(context: Context) : FrameLayout(context) {
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
}

private fun deniedResource() = HtmlRenderResource.blocked().toWebResponse()

private fun HtmlRenderResource.toWebResponse() = WebResourceResponse(mimeType, encoding, status, reason, headers, ByteArrayInputStream(body))

/**
 * Cookies are never accepted (as in StreamWebView), so `document.cookie` on a synthetic origin
 * cannot carry state between pages or environments. Cookies from builds before this rule are dropped once;
 * the app has no web login, so nothing else lives in the jar.
 */
private object HtmlRenderCookies {
    @Volatile private var cleared = false
    fun disable(webView: WebView) {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(false)
        cookies.setAcceptThirdPartyCookies(webView, false)
        if (!cleared) {
            cleared = true
            runCatching { cookies.removeAllCookies(null) }
        }
    }
}

/**
 * DOM storage, Web SQL and Cache Storage are off, but IndexedDB and similar still work per origin, and every
 * WebView session has its own random origin. A released session's origin is deleted. Once per process, every
 * other html render origin left on disk (older builds' shared or per-page origins, sessions lost to process
 * death) is deleted too; a live session's origin is never a leftover, and no new session can reuse an old one.
 */
private object HtmlRenderStorage {
    private val live = HashSet<String>()
    private var swept = false

    @Synchronized fun opened(origin: String) {
        live += origin
        if (swept) return
        swept = true
        runCatching {
            val storage = WebStorage.getInstance()
            storage.getOrigins { origins ->
                origins?.keys?.map { it.toString() }?.filter(::isLeftover)?.forEach { runCatching { storage.deleteOrigin(it) } }
            }
        }
    }

    @Synchronized fun released(origin: String) {
        live -= origin
        runCatching { WebStorage.getInstance().deleteOrigin(origin) }
    }

    @Synchronized private fun isLeftover(origin: String): Boolean {
        val host = runCatching { URI(origin).host }.getOrNull() ?: return false
        return isHtmlRenderHost(HtmlRenderPolicy.normalizeHost(host)) && origin.trimEnd('/') !in live
    }
}

/** One hardened WebView session showing one in-memory page. Not reused: [release] destroys it. */
@SuppressLint("SetJavaScriptEnabled")
internal class HtmlRenderPage(
    context: Context,
    html: String,
    initialTheme: HtmlRenderTheme,
    private val network: HtmlRenderNetworkAccess,
    val fullScreen: Boolean,
    /** False forces a static render, as on a WebView without document-start injection. */
    allowScript: Boolean = true,
) {
    val webView = WebView(context)
    val root: View
    /** A fresh random origin for this WebView session. */
    val policy = HtmlRenderPolicy(htmlRenderDocumentUrl(), network)
    var onHeight: (Float) -> Unit = {}
    var onOpenLink: (String) -> Unit = {}
    var onFailure: () -> Unit = {}
    /** JavaScript runs only behind the document-start lockdown; otherwise the page is a static render. */
    val scripted = allowScript && HtmlRenderDocumentStart.install(webView, policy.origin)
    @Volatile private var documentBytes = htmlRenderDocument(html, scripted, initialTheme)
    @Volatile private var active = true
    /** This WebView's remote fetches: bounded, tracked, and cancelled on release or loss of context. */
    private val fetches = network.fetcher?.let(::HtmlRenderFetchSession)
    private val themes = HtmlRenderThemeSync(initialTheme)
    var destroyed = false
        private set

    init {
        webView.tag = this
        webView.isSaveEnabled = false
        webView.setBackgroundColor(initialTheme.backgroundArgb)
        if (!fullScreen) {
            webView.isVerticalScrollBarEnabled = false
            webView.overScrollMode = View.OVER_SCROLL_NEVER
        }
        with(webView.settings) {
            javaScriptEnabled = scripted
            domStorageEnabled = false
            @Suppress("DEPRECATION")
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(fullScreen)
            builtInZoomControls = fullScreen
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION")
            saveFormData = false
            setGeolocationEnabled(false)
        }
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        HtmlRenderCookies.disable(webView)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
        webView.setDownloadListener { _, _, _, _, _ -> /* Pages never trigger downloads or intents. */ }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request != null && active) policy.externalLink(request.url.toString(), request.hasGesture())?.let { url -> webView.post { if (active) onOpenLink(url) } }
                return true
            }
            @Deprecated("Legacy callback remains blocked")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || !active) return deniedResource()
                val url = request.url.toString()
                if (request.isForMainFrame) {
                    return if (policy.allowsMainFrame(url, request.method, request.isRedirect)) {
                        WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf(
                            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                            "Content-Security-Policy" to HTML_RENDER_CSP, "Referrer-Policy" to "no-referrer",
                        ), ByteArrayInputStream(documentBytes))
                    } else deniedResource()
                }
                if (!policy.allowsSubresource(url, request.method)) return deniedResource()
                if (request.url.scheme?.lowercase() in setOf("data", "blob")) return null
                // Remote GETs never reach the WebView's own network stack: the fetcher re-checks every hop and
                // the connection gate, so a cached page shown offline gets no remote subresources.
                val fetches = fetches ?: return deniedResource()
                if (!policy.allowsFetch(url)) return deniedResource()
                val resource = fetches.fetch(url, request.requestHeaders.orEmpty()) { hop -> active && policy.allowsFetch(hop) }
                return if (active) resource.toWebResponse() else deniedResource()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!active || !scripted || url?.startsWith(policy.documentUrl) != true) return
                themes.documentFinished()?.let { webView.evaluateJavascript(htmlRenderThemeScript(it), null) }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { handler?.cancel() }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) fail()
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true) fail()
            }
            override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }
            override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) { request?.cancel() }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // The renderer is gone: this WebView must never be used again.
                release()
                onFailure()
                return true
            }
        }
        if (scripted && !fullScreen) runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                // The sandboxed main frame reports an opaque origin, so the rule is `*` and only the main frame counts.
                WebViewCompat.addWebMessageListener(webView, HTML_RENDER_BRIDGE, setOf("*")) { _, message, _, isMainFrame, _ ->
                    if (active && isMainFrame) htmlRenderReportedHeight(message.data)?.let { onHeight(it) }
                }
            }
        }
        root = (if (fullScreen) FrameLayout(context) else InlineHtmlFrame(context)).apply {
            tag = this@HtmlRenderPage
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        HtmlRenderStorage.opened(policy.origin)
        webView.loadUrl(policy.documentUrl + htmlRenderThemeFragment(initialTheme))
    }

    private fun fail() {
        if (!active) return
        webView.post { if (!destroyed) onFailure() }
    }

    fun applyTheme(next: HtmlRenderTheme) {
        if (!active || next == themes.latest) return
        webView.setBackgroundColor(next.backgroundArgb)
        if (themes.update(next) && scripted) webView.evaluateJavascript(htmlRenderThemeScript(next), null)
    }

    /** The card's environment or thread is no longer the connected selection: drop in-flight fetches. */
    fun connectionLost() { fetches?.cancelInFlight() }

    fun pause() { if (active) webView.onPause() }
    fun resume() { if (active) webView.onResume() }

    fun release() {
        active = false
        fetches?.close()
        documentBytes = byteArrayOf()
        if (destroyed) return
        destroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.clearHistory()
        webView.clearCache(true)
        webView.removeAllViews()
        webView.destroy()
        HtmlRenderStorage.released(policy.origin)
    }
}

/**
 * Hosts one [HtmlRenderPage] for [html]. The WebView is destroyed when this leaves composition (including
 * when the timeline row scrolls away) and paused with the lifecycle. Inline pages report their height. Every
 * new WebView gets a fresh random origin; [attachmentId] only keys the WebView, so another page never reuses it.
 */
@Composable
internal fun HtmlRenderWebView(
    html: String,
    attachmentId: String,
    theme: HtmlRenderTheme,
    network: HtmlRenderNetworkAccess,
    fullScreen: Boolean,
    onContentHeight: (Float) -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val heightCallback by rememberUpdatedState(onContentHeight)
    val failureCallback by rememberUpdatedState(onFailure)
    val holder = remember { arrayOfNulls<HtmlRenderPage>(1) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> holder[0]?.pause()
                Lifecycle.Event.ON_RESUME -> holder[0]?.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Losing the connection or thread cancels running subresource fetches; new ones are refused by the gate.
    LaunchedEffect(network) {
        network.currentChanges.collect { current -> if (!current) holder[0]?.connectionLost() }
    }
    key(html, attachmentId, fullScreen, network) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                HtmlRenderPage(context, html, theme, network, fullScreen).also { page ->
                    page.onHeight = { heightCallback(it) }
                    page.onFailure = { failureCallback() }
                    page.onOpenLink = { url -> runCatching { uriHandler.openUri(url) } }
                    holder[0] = page
                }.root
            },
            update = { view -> (view.tag as? HtmlRenderPage)?.applyTheme(theme) },
            onRelease = { view ->
                (view.tag as? HtmlRenderPage)?.release()
                if (holder[0]?.root === view) holder[0] = null
            },
        )
    }
}

package com.tajchert.trefoil.ui.htmlrender

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
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
import java.security.MessageDigest

/** Synthetic origin for in-memory pages: `.invalid` never resolves, so nothing here reaches the network. */
internal const val HTML_RENDER_HOST = "html-render.trefoil.invalid"
internal const val HTML_RENDER_ORIGIN = "https://$HTML_RENDER_HOST"
internal const val HTML_RENDER_BRIDGE = "TrefoilHtmlRender"

/** `https://html-render.trefoil.invalid/<sha256(attachmentId)[0..16]>/index.html`, without a fragment. */
internal fun htmlRenderDocumentUrl(attachmentId: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(attachmentId.toByteArray(Charsets.UTF_8))
    return "$HTML_RENDER_ORIGIN/${digest.take(8).joinToString("") { "%02x".format(it) }}/index.html"
}

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
 */
internal const val HTML_RENDER_CSP = "connect-src https: data: blob:; form-action 'none'; object-src 'none'; " +
    "frame-src 'none'; child-src 'none'; worker-src 'none'"

/** The appended height script is the only change to the agent's (already bootstrapped) document. */
internal fun htmlRenderDocument(html: String): ByteArray = (html + HTML_RENDER_HEIGHT_SCRIPT).toByteArray(Charsets.UTF_8)

/** Parses a height message from the page; anything else is ignored. */
internal fun htmlRenderReportedHeight(data: String?): Float? =
    data?.takeIf { it.length <= 16 }?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
        ?.coerceIn(HTML_RENDER_MIN_HEIGHT.toFloat(), HTML_RENDER_MAX_FIT_HEIGHT.toFloat())

/**
 * Request policy for an agent page. Only the exact in-memory document is served at the synthetic origin.
 * Subresources are GET `https:` to public-looking hosts (plus `data:`/`blob:`); loopback, `localhost`,
 * mDNS, single-label names, private/link-local/CGNAT/ULA literals, and the environment's current hosts are
 * refused. Remote ones are fetched by [HtmlRenderSubresourceFetcher] only while [network] is current, with
 * this policy re-checked on every redirect hop and DNS answers limited to public addresses.
 */
internal class HtmlRenderPolicy(val documentUrl: String, private val network: HtmlRenderNetworkAccess) {
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
        if (normalizeHost(uri.host) == HTML_RENDER_HOST) return null
        return url
    }

    /** Environment hosts are read live, so a route or active base learned after the page opened is refused. */
    fun isBlockedHost(rawHost: String): Boolean {
        val host = normalizeHost(rawHost)
        if (host.isEmpty() || host == HTML_RENDER_HOST) return true
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
 * Cookies are never accepted (as in StreamWebView), so `document.cookie` on the shared synthetic origin
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

/** One hardened WebView showing one in-memory page. Not reused: [release] destroys it. */
@SuppressLint("SetJavaScriptEnabled")
internal class HtmlRenderPage(
    context: Context,
    html: String,
    attachmentId: String,
    initialTheme: HtmlRenderTheme,
    private val network: HtmlRenderNetworkAccess,
    val fullScreen: Boolean,
) {
    val webView = WebView(context)
    val root: View
    val policy = HtmlRenderPolicy(htmlRenderDocumentUrl(attachmentId), network)
    var onHeight: (Float) -> Unit = {}
    var onOpenLink: (String) -> Unit = {}
    var onFailure: () -> Unit = {}
    @Volatile private var documentBytes = htmlRenderDocument(html)
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
            javaScriptEnabled = true
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
                if (!active || url?.startsWith(policy.documentUrl) != true) return
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
        if (!fullScreen) runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(webView, HTML_RENDER_BRIDGE, setOf(HTML_RENDER_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                    if (active && isMainFrame && sourceOrigin.toString().trimEnd('/') == HTML_RENDER_ORIGIN) htmlRenderReportedHeight(message.data)?.let { onHeight(it) }
                }
            }
        }
        root = (if (fullScreen) FrameLayout(context) else InlineHtmlFrame(context)).apply {
            tag = this@HtmlRenderPage
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        webView.loadUrl(policy.documentUrl + htmlRenderThemeFragment(initialTheme))
    }

    private fun fail() {
        if (!active) return
        webView.post { if (!destroyed) onFailure() }
    }

    fun applyTheme(next: HtmlRenderTheme) {
        if (!active || next == themes.latest) return
        webView.setBackgroundColor(next.backgroundArgb)
        if (themes.update(next)) webView.evaluateJavascript(htmlRenderThemeScript(next), null)
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
    }
}

/**
 * Hosts one [HtmlRenderPage] for [html]. The WebView is destroyed when this leaves composition (including
 * when the timeline row scrolls away) and paused with the lifecycle. Inline pages report their height.
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
                HtmlRenderPage(context, html, attachmentId, theme, network, fullScreen).also { page ->
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

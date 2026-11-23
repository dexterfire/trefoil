package com.tajchert.trefoil.ui.ports

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tajchert.trefoil.R
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import java.net.URI

/** What the in-app browser does with a navigation request. */
internal sealed interface TunnelNavigation {
    /** Load it in the WebView: the forwarded origin itself. */
    data object Allow : TunnelNavigation
    /** Hand [url] to the system (LocalUriHandler). */
    data class External(val url: String) : TunnelNavigation
    data object Block : TunnelNavigation
}

/**
 * Navigation policy for the forwarded page at [boundUrl] (`http://localhost:N/`, or `http://127.0.0.1:N/` when only
 * IPv4 could be bound). Only `http` to `localhost` (when [boundUrl] uses it) or `127.0.0.1` on the bound port stays
 * in the WebView. Other loopback names and ports are refused so a page cannot
 * steer the WebView at other services on the device. A gesture-initiated http(s) link to anywhere else goes to the
 * system browser; every other scheme (file, content, intent, javascript, data…) and any URL with credentials is blocked.
 */
internal fun tunnelNavigation(boundUrl: String, target: String, hasGesture: Boolean): TunnelNavigation {
    val bound = runCatching { URI(boundUrl) }.getOrNull() ?: return TunnelNavigation.Block
    val uri = runCatching { URI(target) }.getOrNull() ?: return TunnelNavigation.Block
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return TunnelNavigation.Block
    if (uri.rawUserInfo != null) return TunnelNavigation.Block
    val host = uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return TunnelNavigation.Block
    if (isLoopbackName(host)) {
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        val boundPort = if (bound.port != -1) bound.port else 80
        // `localhost` may resolve to `::1`, which only a dual-stack forward owns; an IPv4-only one is bound as `127.0.0.1`.
        val boundHost = bound.host?.lowercase()?.trimEnd('.')
        val allowedHost = host == "127.0.0.1" || (host == "localhost" && boundHost == "localhost")
        return if (scheme == "http" && allowedHost && port == boundPort) TunnelNavigation.Allow else TunnelNavigation.Block
    }
    return if (hasGesture) TunnelNavigation.External(target) else TunnelNavigation.Block
}

/** Names that reach this device: never handed to another app, never loaded except the bound origin. */
private fun isLoopbackName(host: String): Boolean {
    val bare = host.removePrefix("[").removeSuffix("]")
    if (bare == "localhost" || bare.endsWith(".localhost")) return true
    if (':' in bare) return bare == "::1" || bare == "::" || bare.startsWith("::ffff:127.") || bare == "0:0:0:0:0:0:0:1"
    val octets = bare.split('.')
    if (octets.size == 4 && octets.all { it.isNotEmpty() && it.all(Char::isDigit) }) return octets[0] == "127" || octets.all { it == "0" }
    return false
}

/** Page chrome the WebView reports. Plain observable fields so the layout stays stateless. */
@Stable
internal class TunnelBrowserWebState(initialUrl: String) {
    var webView: WebView? = null
    var currentUrl by mutableStateOf(initialUrl)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var progress by mutableIntStateOf(0)
    /** Main-frame load failure; the value is the WebView's own description, possibly empty. */
    var error by mutableStateOf<String?>(null)

    fun syncHistory(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }
}

/**
 * In-app browser for a forwarded dev server. [url] is the local forward (`http://localhost:N/`); the page title
 * is [title]. JavaScript and DOM storage are on because this is a dev tool. Navigation stays on the forwarded
 * origin, see [tunnelNavigation]. [onOpenExternal] opens the forward in the device browser, [onBack] leaves.
 * [singleToolbar] swaps the title bar, address row and bottom page controls for one toolbar row (list-detail panes);
 * [browserActions] is drawn in that row, or in the title bar, just before "open externally".
 */
@Composable
fun TunnelBrowserScreen(
    url: String, title: String, onBack: () -> Unit, onOpenExternal: () -> Unit, modifier: Modifier = Modifier,
    singleToolbar: Boolean = false, browserActions: (@Composable RowScope.() -> Unit)? = null, panelToolbar: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    var generation by remember(url) { mutableIntStateOf(0) }
    val web = remember(url, generation) { TunnelBrowserWebState(url) }
    BackHandler { if (web.canGoBack) web.webView?.goBack() else onBack() }
    TunnelBrowserLayout(
        title = title.ifBlank { stringResource(R.string.browser_default_title) },
        address = web.currentUrl,
        canGoBack = web.canGoBack, canGoForward = web.canGoForward,
        loading = web.loading, progress = web.progress, error = web.error,
        onClose = onBack,
        onPageBack = { web.webView?.goBack() },
        onPageForward = { web.webView?.goForward() },
        onReload = { web.error = null; web.webView?.reload() },
        onStop = { web.webView?.stopLoading() },
        onOpenExternal = onOpenExternal,
        modifier = modifier,
        singleToolbar = singleToolbar, actions = browserActions, panelToolbar = panelToolbar,
    ) {
        TunnelWebView(url, web, onOpenLink = { link -> runCatching { uriHandler.openUri(link) } }, onRenderGone = { generation++ }, Modifier.fillMaxSize())
    }
}

/** Creates the WebView of a forward; tests swap it to count creations. */
internal val LocalWebViewFactory = staticCompositionLocalOf<(Context) -> WebView> { { context -> WebView(context) } }

/** Stateless chrome around the page: close and external buttons, address, load progress, error state and page controls. */
@Composable
internal fun TunnelBrowserLayout(
    title: String,
    address: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    progress: Int,
    error: String?,
    onClose: () -> Unit,
    onPageBack: () -> Unit,
    onPageForward: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier,
    singleToolbar: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
    panelToolbar: Boolean = false,
    content: @Composable () -> Unit,
) {
    val c = Tf.colors
    val compactHeight = LocalCompactHeight.current
    val addressLabel = stringResource(R.string.browser_address)
    Column(modifier.fillMaxSize().background(c.bg)) {
        if (singleToolbar) {
            Row(Modifier.fillMaxWidth().height(if (compactHeight) 48.dp else 52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TfIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browser_back), onPageBack, enabled = canGoBack, tint = if (canGoBack) c.text else c.muted.copy(alpha = .5f))
                TfIconButton(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.browser_forward), onPageForward, enabled = canGoForward, tint = if (canGoForward) c.text else c.muted.copy(alpha = .5f))
                if (loading && error == null) TfIconButton(TfIcons.Close, stringResource(R.string.browser_stop), onStop, tint = c.text, iconSize = 20.dp)
                else TfIconButton(TfIcons.Refresh, stringResource(R.string.browser_reload), onReload, tint = c.text, iconSize = 22.dp)
                Surface(Modifier.weight(1f).padding(horizontal = 4.dp), shape = RoundedCornerShape(18.dp), color = c.s2) {
                    Row(
                        Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp).semantics { contentDescription = "$addressLabel $address" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(TfIcons.Globe, null, Modifier.size(14.dp), tint = c.muted)
                        Text(address, Modifier.weight(1f), style = TrefoilType.mono, color = c.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
                actions?.invoke(this)
                TfIconButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.browser_open_external), onOpenExternal, tint = c.text, iconSize = 22.dp)
                TfIconButton(TfIcons.Close, stringResource(R.string.browser_close), onClose, tint = c.text, iconSize = 22.dp)
            }
        } else if (panelToolbar) {
            // Docked side panel: one 48dp toolbar straight under the panel's tab bar. It starts with the arrow back to the server list
            // (the close action of the full-size title bar) and holds the address in place of the title and the address row.
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TfIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browser_close), onClose, tint = c.text, iconSize = 22.dp)
                Surface(Modifier.weight(1f).padding(horizontal = 4.dp), shape = RoundedCornerShape(18.dp), color = c.s2) {
                    Row(
                        Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp).semantics { contentDescription = "$addressLabel $address" },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(TfIcons.Globe, null, Modifier.size(14.dp), tint = c.muted)
                        Text(address, Modifier.weight(1f), style = TrefoilType.mono, color = c.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
                actions?.invoke(this)
                TfIconButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.browser_open_external), onOpenExternal, tint = c.text, iconSize = 22.dp)
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = if (compactHeight) 48.dp else 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TfIconButton(TfIcons.Close, stringResource(R.string.browser_close), onClose, tint = c.text, iconSize = 22.dp)
                Text(title, Modifier.weight(1f).padding(start = 4.dp), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions?.invoke(this)
                TfIconButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.browser_open_external), onOpenExternal, tint = c.text, iconSize = 22.dp)
            }
            Surface(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), shape = RoundedCornerShape(18.dp), color = c.s2) {
                Row(
                    Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp).semantics { contentDescription = "$addressLabel $address" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(TfIcons.Globe, null, Modifier.size(14.dp), tint = c.muted)
                    Text(address, style = TrefoilType.mono, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(color = c.line)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
            if (error != null) Column(
                Modifier.fillMaxSize().background(c.bg).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(56.dp).background(c.s2, CircleShape), contentAlignment = Alignment.Center) { Icon(TfIcons.Alert, null, Modifier.size(26.dp), tint = c.red) }
                Text(stringResource(R.string.browser_error_title), Modifier.padding(top = 16.dp), style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
                Text(stringResource(R.string.browser_error_body), Modifier.padding(top = 8.dp), style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
                if (error.isNotBlank()) Text(error, Modifier.padding(top = 8.dp), style = TrefoilType.mono, color = c.muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Button(onClick = onReload, Modifier.padding(top = 20.dp)) { Text(stringResource(R.string.browser_error_retry)) }
            } else if (loading) {
                LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.s2)
            }
        }
        if (!singleToolbar) {
            HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TfIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.browser_back), onPageBack, enabled = canGoBack, tint = if (canGoBack) c.text else c.muted.copy(alpha = .5f))
                TfIconButton(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.browser_forward), onPageForward, enabled = canGoForward, tint = if (canGoForward) c.text else c.muted.copy(alpha = .5f))
                if (loading && error == null) TfIconButton(TfIcons.Close, stringResource(R.string.browser_stop), onStop, tint = c.text, iconSize = 20.dp)
                else TfIconButton(TfIcons.Refresh, stringResource(R.string.browser_reload), onReload, tint = c.text, iconSize = 22.dp)
            }
        }
    }
}

/**
 * The WebView for one forward. Destroyed when it leaves composition and paused with the lifecycle. Cookies are
 * accepted only while it exists (the dev server may need a session) and are wiped on release, so a dev server's
 * cookies never outlive the screen or reach the agent pages that keep cookies off.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TunnelWebView(url: String, web: TunnelBrowserWebState, onOpenLink: (String) -> Unit, onRenderGone: () -> Unit, modifier: Modifier) {
    val openLink by rememberUpdatedState(onOpenLink)
    val renderGone by rememberUpdatedState(onRenderGone)
    val lifecycleOwner = LocalLifecycleOwner.current
    val newWebView = LocalWebViewFactory.current
    DisposableEffect(lifecycleOwner, web) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> web.webView?.onPause()
                Lifecycle.Event.ON_RESUME -> web.webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            newWebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isSaveEnabled = false
                with(settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    // A dev server changes under the page; never serve it stale.
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    @Suppress("DEPRECATION")
                    saveFormData = false
                    setGeolocationEnabled(false)
                }
                val cookies = CookieManager.getInstance()
                cookies.setAcceptCookie(true)
                cookies.setAcceptThirdPartyCookies(this, false)
                setDownloadListener { _, _, _, _, _ -> /* Dev pages never trigger downloads. */ }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) { web.progress = newProgress }
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) { callback?.invoke(origin, false, false) }
                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = false
                    override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        if (request == null || !request.isForMainFrame) return false
                        return when (val decision = tunnelNavigation(url, request.url.toString(), request.hasGesture() && !request.isRedirect)) {
                            TunnelNavigation.Allow -> false
                            is TunnelNavigation.External -> { post { openLink(decision.url) }; true }
                            TunnelNavigation.Block -> true
                        }
                    }
                    @Deprecated("Legacy callback stays blocked")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true
                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        web.loading = true
                        web.error = null
                        pageUrl?.let { web.currentUrl = it }
                    }
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        web.loading = false
                        pageUrl?.let { web.currentUrl = it }
                        view?.let(web::syncHistory)
                    }
                    override fun doUpdateVisitedHistory(view: WebView?, pageUrl: String?, isReload: Boolean) {
                        pageUrl?.let { web.currentUrl = it }
                        view?.let(web::syncHistory)
                    }
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true) {
                            web.loading = false
                            web.error = error?.description?.toString().orEmpty()
                        }
                    }
                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) { handler?.cancel() }
                    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) { handler?.cancel() }
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        // This WebView must not be used again: drop it and start a fresh one.
                        post { renderGone() }
                        return true
                    }
                }
                web.webView = this
                loadUrl(url)
            }
        },
        onRelease = { view ->
            if (web.webView === view) web.webView = null
            runCatching {
                (view.parent as? ViewGroup)?.removeView(view)
                view.stopLoading()
                view.clearHistory()
                view.removeAllViews()
                view.destroy()
            }
            CookieManager.getInstance().apply {
                runCatching { removeAllCookies(null) }
                setAcceptCookie(false)
            }
        },
    )
}

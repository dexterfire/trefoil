package com.tajchert.trefoil.ui.htmlrender

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tajchert.trefoil.core.model.HTML_RENDER_MAX_HEIGHT
import com.tajchert.trefoil.core.model.HtmlRenderReference
import com.tajchert.trefoil.downloads.HtmlRenderFetch
import com.tajchert.trefoil.ui.LocalFileDownloadUi
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilType

/** What the inline card shows. [Ready] carries the page; its `toString` never prints content. */
internal sealed interface HtmlRenderState {
    data object Loading : HtmlRenderState
    data object Offline : HtmlRenderState
    data object Failed : HtmlRenderState
    data object TooLarge : HtmlRenderState
    class Ready(val html: String) : HtmlRenderState {
        override fun toString() = "Ready(content=***)"
    }
}

internal const val HTML_RENDER_PLACEHOLDER_TAG = "html-render-placeholder"
internal const val HTML_RENDER_WEBVIEW_TAG = "html-render-webview"
private const val MAX_NOT_CURRENT_RETRIES = 2

/**
 * Inline thread row for an agent `html_render` page. Reads environment/thread/loader from LocalFileDownloadUi.
 * Fetches only while connected and on the same environment and thread; cached pages render offline.
 */
@Composable
internal fun HtmlRenderCard(reference: HtmlRenderReference, modifier: Modifier = Modifier) {
    val ui = LocalFileDownloadUi.current
    val loader = ui.htmlLoader
    val environment = ui.environmentId
    val thread = ui.threadId
    fun cachedState(): HtmlRenderState? =
        if (loader == null || environment == null || thread == null) null
        else loader.cached(reference, environment, thread)?.let { HtmlRenderState.Ready(it) }
    var state by remember(reference, environment, thread, loader) {
        mutableStateOf(cachedState() ?: if (ui.canDownload && loader != null) HtmlRenderState.Loading else HtmlRenderState.Offline)
    }
    var attempt by remember(reference, environment, thread, loader) { mutableIntStateOf(0) }
    var failedAttempt by remember(reference, environment, thread, loader) { mutableIntStateOf(-1) }
    // A connection blink shorter than a recomposition can make load() answer NotCurrent while canDownload never changed.
    val canDownloadNow by rememberUpdatedState(ui.canDownload)
    var notCurrentRetries by remember(reference, environment, thread, loader) { mutableIntStateOf(0) }
    // Leaving the thread disposes this effect, which cancels the transfer. Failures wait for an explicit Retry.
    LaunchedEffect(reference, environment, thread, loader, ui.canDownload, attempt) {
        if (loader == null || environment == null || thread == null) return@LaunchedEffect
        val current = state
        if (current is HtmlRenderState.Ready || current is HtmlRenderState.TooLarge) return@LaunchedEffect
        if (current is HtmlRenderState.Failed && failedAttempt == attempt) return@LaunchedEffect
        if (!ui.canDownload) { state = cachedState() ?: HtmlRenderState.Offline; return@LaunchedEffect }
        state = HtmlRenderState.Loading
        state = when (val result = loader.load(reference, environment, thread)) {
            is HtmlRenderFetch.Ready -> HtmlRenderState.Ready(result.html)
            HtmlRenderFetch.NotCurrent -> {
                // Rerun once or twice (bounded) if the connection is back; otherwise Offline offers Retry.
                if (canDownloadNow && notCurrentRetries < MAX_NOT_CURRENT_RETRIES) { notCurrentRetries++; attempt++ }
                HtmlRenderState.Offline
            }
            HtmlRenderFetch.TooLarge -> HtmlRenderState.TooLarge
            HtmlRenderFetch.Failed -> HtmlRenderState.Failed.also { failedAttempt = attempt }
        }
    }
    // Read live per request: current routes/active base, and whether this environment and thread are still current.
    val network = remember(loader, environment, thread) {
        if (loader != null && environment != null && thread != null) loader.networkAccess(environment, thread) else HtmlRenderNetworkAccess.Offline
    }
    val canRetry = ui.canDownload || cachedState() != null
    HtmlRenderCardContent(
        reference = reference,
        state = state,
        modifier = modifier,
        canRetry = canRetry,
        onRetry = {
            state = cachedState() ?: HtmlRenderState.Loading
            attempt++
        },
        network = network,
        onPageFailure = {
            failedAttempt = attempt
            state = HtmlRenderState.Failed
        },
    )
}

/**
 * The measured page height, kept across a lazy-list row leaving and re-entering composition so the list does not
 * jump while the page reports its height again. A new page (different reference or html) starts unmeasured.
 */
@Composable
internal fun rememberHtmlRenderContentHeight(reference: HtmlRenderReference, html: String?): MutableState<Float?> =
    rememberSaveable(reference, html) { mutableStateOf<Float?>(null) }

/**
 * Stateless card: header with title and full-screen button, then the page or a state message. In
 * LocalInspectionMode (previews, screenshot tests) the Ready state shows a placeholder instead of a WebView.
 */
@Composable
internal fun HtmlRenderCardContent(
    reference: HtmlRenderReference,
    state: HtmlRenderState,
    modifier: Modifier = Modifier,
    canRetry: Boolean = false,
    onRetry: () -> Unit = {},
    network: HtmlRenderNetworkAccess = HtmlRenderNetworkAccess.Offline,
    onPageFailure: () -> Unit = {},
) {
    val c = Tf.colors
    val shape = RoundedCornerShape(14.dp)
    val theme = remember(c) { htmlRenderTheme(c) }
    var fullScreen by remember(reference) { mutableStateOf(false) }
    var contentHeight by rememberHtmlRenderContentHeight(reference, (state as? HtmlRenderState.Ready)?.html)
    val ready = state as? HtmlRenderState.Ready
    Surface(modifier.fillMaxWidth(), shape = shape, color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Code, null, Modifier.size(16.dp), tint = c.muted)
                Text(reference.title, Modifier.weight(1f).padding(horizontal = 8.dp), style = TrefoilType.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { fullScreen = true }, enabled = ready != null, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.OpenInFull, "Open “${reference.title}” full screen", Modifier.size(18.dp), tint = if (ready != null) c.muted else c.muted.copy(alpha = .4f))
                }
            }
            HorizontalDivider(color = c.line)
            BoxWithConstraints(Modifier.fillMaxWidth().background(c.bg)) {
                val fallback = htmlRenderFrameHeight(reference, maxWidth.value)
                when (state) {
                    is HtmlRenderState.Ready -> {
                        val frame = htmlRenderFrameHeight(reference, maxWidth.value, contentHeight)
                        val inline = minOf(frame, HTML_RENDER_MAX_HEIGHT)
                        Box(Modifier.fillMaxWidth().height(inline.dp)) {
                            if (LocalInspectionMode.current) {
                                Box(Modifier.fillMaxSize().semantics { testTag = HTML_RENDER_PLACEHOLDER_TAG }, contentAlignment = Alignment.Center) {
                                    Text(reference.title, style = TrefoilType.meta, color = c.muted)
                                }
                            } else {
                                HtmlRenderWebView(
                                    html = state.html,
                                    attachmentId = reference.attachmentId,
                                    theme = theme,
                                    network = network,
                                    fullScreen = false,
                                    onContentHeight = { contentHeight = it },
                                    onFailure = onPageFailure,
                                    modifier = Modifier.fillMaxSize().semantics { testTag = HTML_RENDER_WEBVIEW_TAG },
                                )
                            }
                            if (frame > inline) {
                                Box(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp)
                                        .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg))),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    TextButton(onClick = { fullScreen = true }, Modifier.padding(bottom = 4.dp)) { Text("Open full screen", style = TrefoilType.meta) }
                                }
                            }
                        }
                    }
                    HtmlRenderState.Loading -> Box(Modifier.fillMaxWidth().height(fallback.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = c.muted, strokeWidth = 2.dp)
                    }
                    HtmlRenderState.Offline -> StateMessage("Reconnect to load “${reference.title}”") {
                        if (canRetry) TextButton(onClick = onRetry) { Text("Retry", style = TrefoilType.meta) }
                    }
                    HtmlRenderState.TooLarge -> StateMessage("“${reference.title}” is too large to preview")
                    HtmlRenderState.Failed -> StateMessage("Unable to load “${reference.title}”") {
                        if (canRetry) TextButton(onClick = onRetry) { Text("Retry", style = TrefoilType.meta) }
                    }
                }
            }
        }
    }
    if (fullScreen && ready != null) {
        HtmlRenderFullScreen(reference, ready.html, theme, network) { fullScreen = false }
    }
}

@Composable
private fun StateMessage(text: String, action: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = TrefoilType.meta, color = Tf.colors.muted, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        action()
    }
}

/** Full-screen page: scrolls and pinch-zooms. Reuses the bytes the card already holds; never refetches. */
@Composable
private fun HtmlRenderFullScreen(reference: HtmlRenderReference, html: String, theme: HtmlRenderTheme, network: HtmlRenderNetworkAccess, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = Tf.colors
        var failed by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize(), color = c.bg, contentColor = c.text) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(c.s1).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = c.text) }
                    Text(reference.title, Modifier.weight(1f).padding(end = 12.dp), style = TrefoilType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = c.line)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        failed -> StateMessage("Unable to load “${reference.title}”")
                        LocalInspectionMode.current -> Box(Modifier.fillMaxSize().semantics { testTag = HTML_RENDER_PLACEHOLDER_TAG })
                        else -> HtmlRenderWebView(
                            html = html,
                            attachmentId = reference.attachmentId,
                            theme = theme,
                            network = network,
                            fullScreen = true,
                            onContentHeight = {},
                            onFailure = { failed = true },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

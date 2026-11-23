package com.tajchert.trefoil.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.SectionLabel
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.TfTooltip
import com.tajchert.trefoil.ui.adaptive.TfTwoPane
import com.tajchert.trefoil.ui.adaptive.maxContentWidth
import com.tajchert.trefoil.ui.adaptive.rememberTwoPaneState
import com.tajchert.trefoil.ui.shell.PanelActionEffect
import com.tajchert.trefoil.ui.shell.SidePanelHost
import java.util.Locale
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/**
 * The remote screen sessions catalog and live viewer. Layout follows this screen's own width: below 840dp the catalog and the viewer
 * replace each other (a capped, centred catalog from 600dp); from 840dp they sit side by side. [onLeave] null means the screen is
 * shown beside a navigation rail: no leave arrow and Back is not claimed while nothing is open.
 */
@Composable
fun ScreenSessionsScreen(
    state: ClientState,
    viewerState: ScreenViewerState,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (ScreenTarget) -> Unit,
    onClose: () -> Unit,
    onLeave: (() -> Unit)?,
    modifier: Modifier = Modifier,
    isAccessCurrent: (ScreenAccess) -> Boolean = { false },
    panel: SidePanelHost? = null,
    viewerContent: (@Composable (StreamController, (StreamViewerStatus) -> Unit) -> Unit)? = null,
) {
    val target = viewerState.target
    val access = viewerState.access
    val environment = state.environment
    val key = target?.key
    val controller = remember(key) { StreamController() }
    var status by remember(key, access) { mutableStateOf(StreamViewerStatus()) }
    var textEntry by remember { mutableStateOf(false) }
    // Typed text may be a password: plain remember only (never saved state, never logged), and dropped when the session changes.
    var keyboardText by remember(key) { mutableStateOf("") }
    var showControls by rememberSaveable(key) { mutableStateOf(true) }
    val transportConnected = state.connection == ConnectionStatus.Connected && state.environment?.id == viewerState.environmentId
    val connected = transportConnected && (access == null || isAccessCurrent(access))
    val ready = connected && access?.interactive == true && status.phase == StreamPhase.Streaming
    val canInput = ready && status.inputConnected
    val view = LocalView.current
    DisposableEffect(fullscreen, view) {
        val activity = view.context.activity()
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else bars?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(view, target) {
        val previous = view.keepScreenOn
        if (target != null) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    // Without a leave action (shown beside the rail) Back is only claimed while something can be closed.
    BackHandler(enabled = onLeave != null || fullscreen || target != null) {
        when { fullscreen -> onFullscreen(false); target != null -> onClose(); else -> onLeave?.invoke() }
    }
    LaunchedEffect(state.environment?.id, state.selectedThreadId, state.connection, state.canRead, state.canWrite) {
        if (target == null && connectedForCatalog(state)) onRefresh()
    }
    val c = Tf.colors
    // The same send call and enabled rule for the dialog and the inline field.
    val sendTyped = { if (controller.send(StreamCommand.Text(keyboardText))) { keyboardText = ""; textEntry = false } }
    val viewer = ViewerContext(
        target, access, environment, connected, transportConnected, ready, canInput, status, viewerState.loading, viewerState.error, controller,
        reconnect = { if (target != null) onOpen(target) }, fullscreen, onFullscreen, showControls, { showControls = !showControls },
        { textEntry = true }, viewerContent, { status = it }, keyboardText, { keyboardText = it }, { sendTyped() },
    )
    // One stage for every layout: the stream view moves between slots instead of being recreated when the layout changes.
    val stage = remember { movableContentOf { v: ViewerContext -> ViewerStage(v) } }
    // Choosing another session behaves like leaving the viewer and opening the row: same callbacks, same order.
    val openFromCatalog: (ScreenTarget) -> Unit = { next -> if (next.key != key) { if (target != null) onClose(); onOpen(next) } }
    // The fold is in window coordinates; this screen's own position in the window says whether the hinge crosses the viewer.
    val fold = LocalFold.current
    val compactHeight = LocalCompactHeight.current
    val density = LocalDensity.current.density
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val tabletop = bounds?.let { tabletopSplit(fold, it.top / density, it.bottom / density, target != null) }
    val foldedFullscreen = bounds?.let { foldedFullscreenSplit(fold, it.left / density, it.right / density, target != null, fullscreen) }
    val tracked = if (fold == null) modifier else modifier.onGloballyPositioned { bounds = it.boundsInWindow() }
    BoxWithConstraints(tracked.fillMaxSize().background(if (fullscreen) Color.Black else c.bg)) {
        val wide = maxWidth.value >= ListDetailMinWidthDp && panel == null
        val readable = maxWidth.value >= AdaptiveSpec.MediumMinWidth
        when {
            tabletop != null -> TabletopLayout(state, viewer, stage, tabletop, openFromCatalog)
            foldedFullscreen != null -> FoldedFullscreenLayout(state, viewer, stage, foldedFullscreen, openFromCatalog)
            wide && !fullscreen -> ListDetailLayout(state, viewerState.error, viewer, stage, compactHeight, onRefresh, onLeave, openFromCatalog)
            else -> SinglePaneLayout(state, viewerState.error, viewer, stage, readable, compactHeight, onRefresh, onOpen, onClose, onLeave, panel)
        }
    }
    if (textEntry) AlertDialog(onDismissRequest = { textEntry = false; keyboardText = "" }, title = { Text("Type on remote screen") }, text = {
        OutlinedTextField(keyboardText, { keyboardText = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
    }, confirmButton = { TextButton(onClick = { sendTyped() }, enabled = canInput && keyboardText.isNotEmpty()) { Text("Send text") } }, dismissButton = { TextButton(onClick = { textEntry = false; keyboardText = "" }) { Text("Cancel") } })
}

/** Everything the viewer chrome and the stage read, so the stage can live in a [movableContentOf] and still see fresh values. */
private class ViewerContext(
    val target: ScreenTarget?,
    val access: ScreenAccess?,
    val environment: SavedEnvironment?,
    val connected: Boolean,
    val transportConnected: Boolean,
    val ready: Boolean,
    val canInput: Boolean,
    val status: StreamViewerStatus,
    val loading: Boolean,
    val error: String?,
    val controller: StreamController,
    val reconnect: () -> Unit,
    val fullscreen: Boolean,
    val onFullscreen: (Boolean) -> Unit,
    val showControls: Boolean,
    val toggleControls: () -> Unit,
    val openText: () -> Unit,
    val viewerContent: (@Composable (StreamController, (StreamViewerStatus) -> Unit) -> Unit)?,
    val onStatus: (StreamViewerStatus) -> Unit,
    val typedText: String,
    val onTypedText: (String) -> Unit,
    val sendTyped: () -> Unit,
) {
    val statusText: String get() = viewerStatusText(connected, canInput, status)
    val canReconnect: Boolean get() = transportConnected && !loading
}

private fun connectedForCatalog(state: ClientState) = state.connection == ConnectionStatus.Connected && (state.canRead || state.canWrite)
private val ScreenTarget.key: String get() = when (this) {
    is ScreenTarget.Browser -> "browser:$threadId:$tabId"
    is ScreenTarget.Device -> "device:$threadId:$hostId:$deviceId"
}
private val ScreenTarget.title: String get() = when (this) { is ScreenTarget.Browser -> title.ifBlank { "Browser" }; is ScreenTarget.Device -> name.ifBlank { "Device" } }
private val ScreenTarget.subtitle: String get() = when (this) { is ScreenTarget.Browser -> url; is ScreenTarget.Device -> "${platform.uppercase(Locale.ROOT)} · $hostId" }

/** The stream, with its error and connecting overlays. Fills its parent; the same node moves between layouts. */
@Composable
private fun ViewerStage(v: ViewerContext) {
    val target = v.target ?: return
    // This node stays at the same position when chrome/system bars change: fullscreen never reloads the viewer.
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (v.access != null && v.environment != null) {
            if (v.viewerContent != null) v.viewerContent.invoke(v.controller, v.onStatus)
            else StreamWebView(target, v.access, v.environment, v.connected, v.controller, v.onStatus, Modifier.fillMaxSize())
        }
        val error = v.error ?: if (!v.connected) "Reconnect to resume this screen." else when (v.status.phase) {
            StreamPhase.Error, StreamPhase.Unauthorized, StreamPhase.Gone, StreamPhase.Stopped -> v.status.detail ?: "Screen stream stopped. Reconnect to try again."
            else -> null
        }
        if (error != null) Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.VideocamOff, null, tint = Color.White)
            Text(error, Modifier.padding(vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = v.reconnect, enabled = v.canReconnect) { Text("Reconnect stream") }
        } else if (v.loading || v.access == null || v.status.phase == StreamPhase.Connecting) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text("Connecting screen…", Modifier.padding(top = 12.dp), color = Color.White)
            }
        }
    }
}

/** Fullscreen controls drawn over the stage: exit, hide/show controls and the control bar. */
@Composable
private fun BoxScope.FullscreenOverlay(v: ViewerContext) {
    val target = v.target ?: return
    Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
        TfTooltip("Exit full screen") { FilledTonalIconButton(onClick = { v.onFullscreen(false) }) { Icon(Icons.Default.FullscreenExit, "Exit full screen") } }
    }
    Box(Modifier.align(Alignment.TopStart).padding(12.dp)) {
        val toggleLabel = if (v.showControls) "Hide screen controls" else "Show screen controls"
        TfTooltip(toggleLabel) { FilledTonalIconButton(onClick = v.toggleControls) { Icon(if (v.showControls) Icons.Default.VisibilityOff else Icons.Default.Tune, toggleLabel) } }
    }
    if (v.showControls) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .94f)) {
        ScreenControls(target, v.ready, v.canInput, v.status, v.controller, v.openText)
    }
}

/** Today's layout: the catalog, or the viewer once a target is open. Phones (and fullscreen) always use it. */
@Composable
private fun SinglePaneLayout(
    state: ClientState, error: String?, v: ViewerContext, stage: @Composable (ViewerContext) -> Unit, readable: Boolean, compactHeight: Boolean,
    onRefresh: () -> Unit, onOpen: (ScreenTarget) -> Unit, onClose: () -> Unit, onLeave: (() -> Unit)?, panel: SidePanelHost? = null,
) {
    val c = Tf.colors
    val target = v.target
    // A short window gives the stream the height: one 48dp bar that also carries the status and Reconnect, and only the controls below.
    val compact = compactHeight && target != null
    // In the docked side panel the tab bar names the catalog and carries Refresh (same label and enabled rule); the catalog has no header
    // row. An open stream keeps its own header, 48dp, directly under the tab bar, starting with the arrow back to the catalog.
    val catalogInPanel = panel != null && target == null
    PanelActionEffect(panel, catalogInPanel, TfIcons.Refresh, "Refresh screen sessions", connectedForCatalog(state) && !state.screens.loading, onRefresh)
    Column(Modifier.fillMaxSize()) {
        if (!v.fullscreen && !catalogInPanel) SessionsHeader(
            title = target?.title ?: "Screen sessions", subtitle = if (compact) v.statusText else target?.subtitle ?: "Browser tabs and attached devices",
            back = if (target != null) BackAction("Back to screen sessions", onClose) else onLeave?.let { BackAction("Back to conversations", it) },
            modifier = if (target == null && readable) Modifier.maxContentWidth(CatalogReadableWidth) else Modifier.fillMaxWidth(),
            minHeight = if (compactHeight || panel != null) ViewerBarCompactHeight else 64.dp,
        ) {
            if (compact && v.access != null) TfIconButton(Icons.Default.Refresh, "Reconnect stream", v.reconnect, enabled = v.canReconnect, tint = c.text)
            if (target != null) TfIconButton(Icons.Default.Fullscreen, "Enter full screen", { v.onFullscreen(true) }, enabled = v.access != null, tint = c.text)
            else TfIconButton(TfIcons.Refresh, "Refresh screen sessions", onRefresh, enabled = connectedForCatalog(state) && !state.screens.loading, iconSize = 22.dp)
        }
        if (!v.fullscreen && !catalogInPanel) HorizontalDivider(color = c.line)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (target == null) ScreenCatalogList(state.screens, error, state.selectedThreadId != null, onOpen, Modifier.fillMaxSize(), readable = readable)
            else Box(Modifier.fillMaxSize()) {
                stage(v)
                if (v.fullscreen) FullscreenOverlay(v)
            }
        }
        if (target != null && !v.fullscreen) {
            HorizontalDivider(color = c.line)
            Surface(color = c.s1) {
                Column {
                    if (!compact) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(if (v.status.phase == StreamPhase.Streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                        Text(v.statusText, Modifier.padding(start = 8.dp).weight(1f), style = MaterialTheme.typography.labelSmall)
                        if (v.access != null) TfIconButton(Icons.Default.Refresh, "Reconnect stream", v.reconnect, enabled = v.canReconnect)
                    }
                    ScreenControls(target, v.ready, v.canInput, v.status, v.controller, v.openText)
                }
            }
        }
    }
}

/** From 840dp: the catalog stays on the left with the open target highlighted, the viewer fills the right. */
@Composable
private fun ListDetailLayout(
    state: ClientState, error: String?, v: ViewerContext, stage: @Composable (ViewerContext) -> Unit, compactHeight: Boolean,
    onRefresh: () -> Unit, onLeave: (() -> Unit)?, onChoose: (ScreenTarget) -> Unit,
) {
    val c = Tf.colors
    val target = v.target
    val barHeight = if (compactHeight) ViewerBarCompactHeight else ViewerBarHeight
    val paneState = rememberTwoPaneState(CatalogPaneWidth)
    TfTwoPane(
        paneState, CatalogPaneWidth,
        start = {
            Column(Modifier.fillMaxSize().background(c.bg)) {
                SessionsHeader("Screen sessions", "Browser tabs and attached devices", onLeave?.let { BackAction("Back to conversations", it) }, Modifier.fillMaxWidth(), if (compactHeight) ViewerBarCompactHeight else 64.dp) {
                    TfIconButton(TfIcons.Refresh, "Refresh screen sessions", onRefresh, enabled = connectedForCatalog(state) && !state.screens.loading, iconSize = 22.dp)
                }
                HorizontalDivider(color = c.line)
                ScreenCatalogList(state.screens, error, state.selectedThreadId != null, onChoose, Modifier.weight(1f).fillMaxWidth(), selectedKey = target?.key)
            }
        },
        end = {
            if (target == null) ViewerEmptyState(state.selectedThreadId != null, connectedForCatalog(state) && !state.screens.loading, onRefresh)
            else Column(Modifier.fillMaxSize()) {
                ViewerTopBar(v, barHeight)
                HorizontalDivider(color = c.line)
                Box(Modifier.weight(1f).fillMaxWidth()) { stage(v) }
                HorizontalDivider(color = c.line)
                Surface(color = c.s1) {
                    Box(Modifier.fillMaxWidth().height(barHeight), contentAlignment = Alignment.Center) {
                        ScreenControls(target, v.ready, v.canInput, v.status, v.controller, v.openText, verticalPadding = 0.dp)
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(), minStartWidth = CatalogPaneWidth, maxStartWidth = CatalogPaneWidth, collapseBelow = null, locked = true,
    )
}

/**
 * Tabletop (horizontal half-opened fold crossing the viewer): only the stream above the hinge, nothing interactive under it, and the
 * status header, the target's controls, inline typing and the session chips below. [split] is in dp: stream, hinge band, controls.
 */
@Composable
private fun TabletopLayout(state: ClientState, v: ViewerContext, stage: @Composable (ViewerContext) -> Unit, split: HingeSplit, onChoose: (ScreenTarget) -> Unit) {
    val c = Tf.colors
    val target = v.target ?: return
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(split.first.dp).background(Color.Black)) {
            stage(v)
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = CircleShape, color = Color.Black.copy(alpha = .72f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(7.dp).background(if (v.status.phase == StreamPhase.Streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                    Text(v.statusText, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // TooltipBox swallows BoxScope parent data, so the alignment goes on a plain Box around it.
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                if (v.fullscreen) TfTooltip("Exit full screen") {
                    FilledTonalIconButton(onClick = { v.onFullscreen(false) }) { Icon(Icons.Default.FullscreenExit, "Exit full screen") }
                } else TfTooltip("Enter full screen") {
                    FilledTonalIconButton(onClick = { v.onFullscreen(true) }, enabled = v.access != null) { Icon(Icons.Default.Fullscreen, "Enter full screen") }
                }
            }
        }
        // The hinge: nothing interactive and nothing readable under it.
        Spacer(Modifier.fillMaxWidth().height(split.band.dp).background(Color.Black))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).background(if (v.status.phase == StreamPhase.Streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text(target.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${v.statusText} · ${target.subtitle}", style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (v.access != null) TfIconButton(Icons.Default.Refresh, "Reconnect stream", v.reconnect, enabled = v.canReconnect, tint = c.text)
            }
            val specs = screenControlSpecs(target, v.ready, v.canInput, v.status)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (spec in specs) if (!spec.control.isType) TabletopControl(spec, v, Modifier.weight(1f))
            }
            specs.firstOrNull { it.control.isType }?.let { typing ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(v.typedText, v.onTypedText, Modifier.weight(1f), enabled = typing.enabled, label = { Text("Text") }, placeholder = { Text(typing.control.description) }, maxLines = 2)
                    Button(onClick = v.sendTyped, Modifier.height(56.dp), enabled = v.canInput && v.typedText.isNotEmpty()) { Text("Send") }
                }
            }
            SessionChips(state, v, onChoose, vertical = false)
        }
    }
}

/** One control as a 56dp button with its caption. The semantics keep the control's usual accessibility label. */
@Composable
private fun TabletopControl(spec: ScreenControlSpec, v: ViewerContext, modifier: Modifier) {
    val c = Tf.colors
    val control = spec.control
    val press = { control.command?.let { v.controller.send(it) }; Unit }
    Surface(
        onClick = press, modifier = modifier.focusRing(RoundedCornerShape(16.dp)).handPointer(spec.enabled).semantics { contentDescription = control.description }, enabled = spec.enabled,
        shape = RoundedCornerShape(16.dp), color = c.s1, border = BorderStroke(1.dp, c.line),
    ) {
        Column(Modifier.heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            control.icon?.let { Icon(it, null, Modifier.size(22.dp), tint = if (spec.enabled) c.text else c.muted) }
            Text(control.label, style = MaterialTheme.typography.labelMedium, color = if (spec.enabled) c.text else c.muted, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

/** The open sessions as chips; choosing one switches like choosing a catalog row. */
@Composable
private fun SessionChips(state: ClientState, v: ViewerContext, onChoose: (ScreenTarget) -> Unit, vertical: Boolean) {
    val targets = state.screens.targets
    if (targets.isEmpty()) return
    val chips: @Composable (ScreenTarget, Modifier) -> Unit = { next, chipModifier ->
        FilterChip(
            selected = next.key == v.target?.key, onClick = { onChoose(next) }, modifier = chipModifier.height(48.dp),
            label = { Text(next.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(if (next is ScreenTarget.Browser) Icons.Default.Language else Icons.Default.Smartphone, null, Modifier.size(18.dp)) },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Screen sessions")
        if (vertical) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { targets.forEach { chips(it, Modifier.fillMaxWidth()) } }
        else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { targets.forEach { chips(it, Modifier) } }
    }
}

/**
 * Fullscreen beside a vertical fold: the stream fills the end half, the controls and the session chips fill the start half, and
 * nothing is placed under the hinge. [split] is in dp: start half, hinge band, end half. The physical halves do not mirror in RTL.
 */
@Composable
private fun FoldedFullscreenLayout(state: ClientState, v: ViewerContext, stage: @Composable (ViewerContext) -> Unit, split: HingeSplit, onChoose: (ScreenTarget) -> Unit) {
    val c = Tf.colors
    val target = v.target ?: return
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.fillMaxSize().background(Color.Black)) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(Modifier.width(split.first.dp).fillMaxHeight().background(c.bg).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).background(if (v.status.phase == StreamPhase.Streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(target.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(v.statusText, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (v.access != null) TfIconButton(Icons.Default.Refresh, "Reconnect stream", v.reconnect, enabled = v.canReconnect, tint = c.text)
                    }
                    ScreenControls(target, v.ready, v.canInput, v.status, v.controller, v.openText)
                    Box(Modifier.padding(horizontal = 16.dp)) { SessionChips(state, v, onChoose, vertical = true) }
                }
            }
            Spacer(Modifier.width(split.band.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                stage(v)
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        TfTooltip("Exit full screen") {
                            FilledTonalIconButton(onClick = { v.onFullscreen(false) }) { Icon(Icons.Default.FullscreenExit, "Exit full screen") }
                        }
                    }
                }
            }
        }
    }
}

private class BackAction(val description: String, val onClick: () -> Unit)

@Composable
private fun SessionsHeader(title: String, subtitle: String, back: BackAction?, modifier: Modifier, minHeight: Dp = 64.dp, trailing: @Composable RowScope.() -> Unit) {
    val c = Tf.colors
    Row(modifier.heightIn(min = minHeight).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) TfIconButton(TfIcons.Back, back.description, back.onClick, iconSize = 22.dp, tint = c.text)
        Column(Modifier.weight(1f).padding(start = if (back != null) 4.dp else 12.dp)) {
            Text(title, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** The 56dp bar above the stream in list-detail: status dot, title, the real status text, Reconnect and Fullscreen. */
@Composable
private fun ViewerTopBar(v: ViewerContext, height: Dp) {
    val c = Tf.colors
    val target = v.target ?: return
    Row(Modifier.fillMaxWidth().height(height).background(c.s1).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).background(if (v.status.phase == StreamPhase.Streaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(target.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(v.statusText, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (v.access != null) TfIconButton(Icons.Default.Refresh, "Reconnect stream", v.reconnect, enabled = v.canReconnect, tint = c.text)
        TfIconButton(Icons.Default.Fullscreen, "Enter full screen", { v.onFullscreen(true) }, enabled = v.access != null, tint = c.text)
    }
}

@Composable
private fun ViewerEmptyState(hasThread: Boolean, canRefresh: Boolean, onRefresh: () -> Unit) {
    val c = Tf.colors
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Devices, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                // Without a conversation the list beside this pane already says what to do; repeating its sentence here would show it twice.
                if (hasThread) "Choose a browser tab or device to view its screen." else "No conversation selected",
                Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center,
            )
            if (hasThread) OutlinedButton(onClick = onRefresh, Modifier.padding(top = 16.dp), enabled = canRefresh) { Text("Refresh sessions") }
        }
    }
}

/** Session cards per row for a catalog [widthDp] wide: two from 600 to 839dp (a stretched phone list otherwise), one elsewhere. */
internal fun screenCatalogColumns(widthDp: Float): Int = if (widthDp >= AdaptiveSpec.MediumMinWidth && widthDp < AdaptiveSpec.ExpandedMinWidth) 2 else 1

// The punch list says 760dp, but the page header above is capped at CatalogReadableWidth (a header row PB does not own), and a list wider than
// its header would not line up at 761 to 839dp. 640dp matches the header and changes nothing at the 673dp fold.
private val CatalogGridMaxWidth = CatalogReadableWidth

/**
 * The catalog of browsers and devices. The column count follows the width this composable is given ([screenCatalogColumns]); [readable]
 * (a single pane from 600dp) keeps a single column at [CatalogReadableWidth]. Section labels and notices always span the row.
 */
@Composable
internal fun ScreenCatalogList(catalog: ScreenCatalog, error: String?, hasThread: Boolean, open: (ScreenTarget) -> Unit, modifier: Modifier, selectedKey: String? = null, readable: Boolean = false) {
    BoxWithConstraints(modifier) {
        val columns = screenCatalogColumns(maxWidth.value)
        val inner = when {
            columns > 1 -> Modifier.maxContentWidth(CatalogGridMaxWidth).fillMaxHeight()
            readable -> Modifier.maxContentWidth(CatalogReadableWidth).fillMaxHeight()
            else -> Modifier.fillMaxSize()
        }
        ScreenCatalogColumn(catalog, error, hasThread, open, inner, selectedKey, columns)
    }
}

@Composable
private fun ScreenCatalogColumn(catalog: ScreenCatalog, error: String?, hasThread: Boolean, open: (ScreenTarget) -> Unit, modifier: Modifier, selectedKey: String?, columns: Int) {
    val grid = columns > 1
    LazyColumn(
        modifier, contentPadding = if (grid) PaddingValues(horizontal = 20.dp, vertical = 16.dp) else PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 10.dp),
    ) {
        if (catalog.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        (catalog.error ?: error)?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        if (!hasThread) item { Text("Choose a conversation first to see its browser tabs and attached devices.", Modifier.padding(vertical = 24.dp)) }
        if (catalog.loaded && !catalog.loading && catalog.targets.isEmpty() && hasThread) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Devices, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No screen sessions yet", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                Text("Open a browser or attach a device in T3 Code, then refresh here.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (catalog.browsers.isNotEmpty()) item { SectionLabel("Browsers", Modifier.padding(top = 12.dp, start = 4.dp)) }
        if (!grid) {
            items(catalog.browsers, key = { "browser:${it.threadId}:${it.tabId}" }) { ScreenSessionRow(it, open, selectedKey?.let { selected -> selected == it.key }) }
        } else {
            items(catalog.browsers.chunked(columns), key = { row -> "browser:${row.first().threadId}:${row.first().tabId}" }) { row -> ScreenSessionGridRow(row, columns, open, selectedKey) }
        }
        if (catalog.devices.isNotEmpty()) item { SectionLabel("Devices", Modifier.padding(top = 18.dp, start = 4.dp)) }
        if (!grid) {
            items(catalog.devices, key = { "device:${it.hostId}:${it.deviceId}" }) { ScreenSessionRow(it, open, selectedKey?.let { selected -> selected == it.key }) }
        } else {
            items(catalog.devices.chunked(columns), key = { row -> "device:${row.first().hostId}:${row.first().deviceId}" }) { row -> ScreenSessionGridRow(row, columns, open, selectedKey) }
        }
    }
}

/** One row of the grid: equal-height cards (reading and Tab order stay row by row); a short last row leaves the remaining column empty. */
@Composable
private fun ScreenSessionGridRow(row: List<ScreenTarget>, columns: Int, open: (ScreenTarget) -> Unit, selectedKey: String?) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { ScreenSessionRow(it, open, selectedKey?.let { selected -> selected == it.key }, Modifier.weight(1f).fillMaxHeight(), singleLineSubtitle = true) }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** [isSelected] null: a plain row (single pane). Otherwise the row reports `selected` and the open one is highlighted. */
@Composable
private fun ScreenSessionRow(target: ScreenTarget, open: (ScreenTarget) -> Unit, isSelected: Boolean? = null, modifier: Modifier = Modifier, singleLineSubtitle: Boolean = false) {
    val c = Tf.colors
    val highlighted = isSelected == true
    Surface(
        onClick = { open(target) },
        modifier = modifier.then(if (isSelected == null) Modifier else Modifier.semantics(mergeDescendants = true) { selected = isSelected }).focusRing().handPointer(),
        // Selected like the Machines list's card: same fill, 2dp accent outline.
        shape = RoundedCornerShape(16.dp), color = c.s1, border = if (highlighted) BorderStroke(2.dp, c.accent) else BorderStroke(1.dp, c.line),
    ) {
        // Propagates the Surface's minimum height, so a card stretched to its grid row's height keeps its content centred.
        Box(propagateMinConstraints = true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).background(c.s2, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (target is ScreenTarget.Browser) Icons.Default.Language else Icons.Default.Smartphone, null, Modifier.size(20.dp), tint = c.text)
                }
                Column(Modifier.weight(1f)) {
                    Text(target.title, style = TrefoilType.rowTitle, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(target.subtitle, style = if (target is ScreenTarget.Browser) TrefoilType.mono else TrefoilType.meta, color = c.muted, maxLines = if (singleLineSubtitle) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(TfIcons.ChevronRight, "View screen", Modifier.size(20.dp), tint = c.muted)
            }
        }
    }
}

@Composable
private fun ScreenControls(target: ScreenTarget, ready: Boolean, canInput: Boolean, status: StreamViewerStatus, controller: StreamController, text: () -> Unit, verticalPadding: Dp = 2.dp) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = verticalPadding), verticalAlignment = Alignment.CenterVertically) {
        for (spec in screenControlSpecs(target, ready, canInput, status)) {
            val control = spec.control
            val icon = control.icon
            val press = { if (control.isType) text() else control.command?.let { controller.send(it) }; Unit }
            if (icon != null) TfIconButton(icon, control.description, press, enabled = spec.enabled)
            else TextButton(onClick = press, enabled = spec.enabled) { Text(control.label) }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

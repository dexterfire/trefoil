package com.tajchert.trefoil.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ScreenTarget
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation

/** Layout-only decisions for the screen sessions screen. Pure functions, no state and no remote I/O. */

/** Container width from which the catalog and the viewer sit side by side. */
internal val ListDetailMinWidthDp = AdaptiveSpec.ExpandedMinWidth

/** Width of the catalog pane in list-detail. */
internal val CatalogPaneWidth = 320.dp

/** Readable cap of the single-pane catalog between 600 and 839dp. */
internal val CatalogReadableWidth = 640.dp

/** A split part shorter (or narrower) than this is not worth splitting for. */
internal const val MinSplitPartDp = 160f

/** Viewer bar heights: regular and compact window height. */
internal val ViewerBarHeight = 56.dp
internal val ViewerBarCompactHeight = 48.dp

/** Where a horizontal hinge cuts the viewer: stream above (`top`), inert `band`, controls below (`bottom`), all in dp. */
internal data class HingeSplit(val first: Float, val band: Float, val second: Float)

/**
 * Tabletop: a horizontal fold that crosses the viewer while a target is open and is half-opened, or flat but separating (two
 * screens with a physical hinge between them): either way the stream must not be drawn across it, fullscreen included.
 * [viewerTop]..[viewerBottom] is the viewer's vertical extent in window dp (the fold is in window dp too). Null when the hinge
 * does not cross the viewer or when either part would be shorter than [MinSplitPartDp].
 */
internal fun tabletopSplit(fold: Fold?, viewerTop: Float, viewerBottom: Float, hasTarget: Boolean): HingeSplit? {
    if (fold == null || !hasTarget || fold.orientation != FoldOrientation.Horizontal || !(fold.halfOpened || fold.separating)) return null
    return hingeSplit(fold, viewerTop, viewerBottom)
}

/**
 * Fullscreen next to a vertical fold: a vertical fold that is separating or half-opened and crosses the viewer, while a target
 * is open in fullscreen. `first` is the start half (controls and chips), `second` the end half (the stream). Null otherwise.
 */
internal fun foldedFullscreenSplit(fold: Fold?, viewerLeft: Float, viewerRight: Float, hasTarget: Boolean, fullscreen: Boolean): HingeSplit? {
    if (fold == null || !hasTarget || !fullscreen || fold.orientation != FoldOrientation.Vertical || !(fold.separating || fold.halfOpened)) return null
    return hingeSplit(fold, viewerLeft, viewerRight)
}

private fun hingeSplit(fold: Fold, from: Float, to: Float): HingeSplit? {
    if (!(fold.start > from && fold.end < to) || fold.end < fold.start) return null
    val first = fold.start - from
    val second = to - fold.end
    return if (first >= MinSplitPartDp && second >= MinSplitPartDp) HingeSplit(first, fold.end - fold.start, second) else null
}

/** What the viewer's status line says today; the same text in every layout. */
internal fun viewerStatusText(connected: Boolean, canInput: Boolean, status: StreamViewerStatus): String =
    if (!connected) "Disconnected" else if (canInput) "Live · controls connected" else if (status.phase == StreamPhase.Streaming) "Live · view only" else "${status.phase}"

/** Every control the screen shows for a target, in order. [description] is the accessibility label, [label] the short caption. */
internal enum class ScreenControl(val description: String, val label: String, val icon: ImageVector?) {
    DeviceBack("Device back", "Back", Icons.AutoMirrored.Filled.ArrowBack),
    DeviceHome("Device home", "Home", Icons.Default.Home),
    DeviceAppSwitcher("Device app switcher", "App switcher", Icons.Default.Apps),
    DeviceRotate("Rotate remote device", "Rotate", Icons.Default.ScreenRotation),
    DeviceType("Type on remote device", "Type", Icons.Default.Keyboard),
    BrowserBack("Browser back", "Back", Icons.AutoMirrored.Filled.ArrowBack),
    BrowserForward("Browser forward", "Forward", Icons.AutoMirrored.Filled.ArrowForward),
    BrowserReload("Reload remote page", "Reload", Icons.Default.Refresh),
    BrowserType("Type on remote browser", "Type", Icons.Default.Keyboard),
    Release("Release", "Release", null),
    TakeControl("Take control", "Take control", null);

    val isType: Boolean get() = this == DeviceType || this == BrowserType

    /** The command a press sends; null for the text entry controls, which open text entry instead. */
    val command: StreamCommand? get() = when (this) {
        DeviceBack -> StreamCommand.Back
        DeviceHome -> StreamCommand.Home
        DeviceAppSwitcher -> StreamCommand.AppSwitcher
        DeviceRotate -> StreamCommand.Rotate
        BrowserBack -> StreamCommand.BrowserBack
        BrowserForward -> StreamCommand.BrowserForward
        BrowserReload -> StreamCommand.Reload
        Release -> StreamCommand.ReleaseControl
        TakeControl -> StreamCommand.TakeControl
        DeviceType, BrowserType -> null
    }
}

internal data class ScreenControlSpec(val control: ScreenControl, val enabled: Boolean)

/** The controls for [target] with the enabled rules the screen has always used. */
internal fun screenControlSpecs(target: ScreenTarget, ready: Boolean, canInput: Boolean, status: StreamViewerStatus): List<ScreenControlSpec> =
    if (target is ScreenTarget.Device) listOf(
        ScreenControlSpec(ScreenControl.DeviceBack, canInput && target.platform == "android"),
        ScreenControlSpec(ScreenControl.DeviceHome, canInput),
        ScreenControlSpec(ScreenControl.DeviceAppSwitcher, canInput),
        ScreenControlSpec(ScreenControl.DeviceRotate, canInput && target.platform == "ios"),
        ScreenControlSpec(ScreenControl.DeviceType, canInput && target.platform == "android" && !target.serveSimOnly),
    ) else listOf(
        ScreenControlSpec(ScreenControl.BrowserBack, canInput),
        ScreenControlSpec(ScreenControl.BrowserForward, canInput),
        ScreenControlSpec(ScreenControl.BrowserReload, canInput),
        ScreenControlSpec(ScreenControl.BrowserType, canInput),
        if (canInput) ScreenControlSpec(ScreenControl.Release, true) else ScreenControlSpec(ScreenControl.TakeControl, ready && status.canOperate),
    )

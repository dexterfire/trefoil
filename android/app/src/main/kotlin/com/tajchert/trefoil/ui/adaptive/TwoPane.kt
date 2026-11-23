package com.tajchert.trefoil.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.Tf

/** Width and collapsed flag of a [TfTwoPane] start pane; saved across configuration changes by [rememberTwoPaneState]. */
@Stable
internal class TwoPaneState(startWidth: Dp, collapsed: Boolean = false) {
    var startWidth by mutableStateOf(startWidth)
    var collapsed by mutableStateOf(collapsed)

    companion object {
        val Saver = listSaver<TwoPaneState, Any>(save = { listOf(it.startWidth.value, it.collapsed) }, restore = { TwoPaneState((it[0] as Float).dp, it[1] as Boolean) })
    }
}

@Composable
internal fun rememberTwoPaneState(defaultStartWidth: Dp, collapsed: Boolean = false): TwoPaneState =
    rememberSaveable(saver = TwoPaneState.Saver) { TwoPaneState(defaultStartWidth, collapsed) }

/**
 * List-detail layout: [start] at the state's width (clamped to [minStartWidth]..[maxStartWidth], and so that [end] keeps at least
 * [minEndWidth]), [end] fills the rest. The divider drags (24dp target), resets to [defaultStartWidth] on double-tap/double-click,
 * resizes with arrow keys and collapses on Enter/Space or on a release below [collapseBelow] (null disables collapsing). A collapsed
 * start pane is hidden with its divider; expand it from your own control (`state.collapsed = false`). [locked] keeps the hairline but
 * disables all resizing (folds, non-resizable lists). [hingeWidth] > 0 puts the panes either side of a separating hinge with nothing
 * drawn under it. [onCollapsedChange] runs after the divider changed `state.collapsed`.
 *
 * When the container cannot fit `minStartWidth + hingeWidth + minEndWidth`, the layout auto-collapses: [end] takes the whole width
 * and the start pane is hidden as if collapsed, without touching `state.collapsed` or the saved width, so it returns when the
 * container grows. A hidden start pane (collapsed or auto-collapsed) stays composed (keeps scroll and saveable state) but is not
 * placed, cannot take focus and is cleared from the semantics tree.
 *
 * Needs a bounded width: inside a horizontally scrolling parent it fails fast with an [IllegalArgumentException].
 */
@Composable
internal fun TfTwoPane(
    state: TwoPaneState, defaultStartWidth: Dp, start: @Composable () -> Unit, end: @Composable () -> Unit, modifier: Modifier = Modifier,
    minStartWidth: Dp = AdaptiveSpec.ListDragMin.dp, maxStartWidth: Dp = AdaptiveSpec.ListDragMax.dp,
    collapseBelow: Dp? = AdaptiveSpec.ListCollapseBelow.dp, locked: Boolean = false, hingeWidth: Dp = 0.dp, showDivider: Boolean = true,
    dividerLabel: String = "Resize panes", onCollapsedChange: (Boolean) -> Unit = {},
    minEndWidth: Dp = AdaptiveSpec.TwoPaneEndMinWidth.dp, dividerTraversalIndex: Float? = null,
) = BoxWithConstraints(modifier) {
    require(constraints.hasBoundedWidth) { "TfTwoPane needs a bounded width; do not place it in a horizontally scrolling parent" }
    val hingeGap = hingeWidth.coerceAtLeast(0.dp)
    val fitsStart = maxWidth - hingeGap - minEndWidth.coerceAtLeast(0.dp)
    val autoCollapsed = fitsStart < minStartWidth
    val maxStart = maxOf(minStartWidth, minOf(maxStartWidth, fitsStart))
    val hidden = state.collapsed || autoCollapsed
    val shown = if (hidden) 0.dp else state.startWidth.coerceIn(minStartWidth, maxStart)
    val hinge = if (shown > 0.dp) hingeGap else 0.dp
    val divider = showDivider && shown > 0.dp && hinge == 0.dp
    val focusManager = LocalFocusManager.current
    var startHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hidden) { if (hidden && startHasFocus) focusManager.clearFocus(force = true) }
    val startModifier = Modifier.onFocusChanged { startHasFocus = it.hasFocus }.then(
        if (hidden) Modifier.clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else Modifier,
    )
    Layout(content = {
        Box(startModifier) { start() }
        Box { end() }
        if (divider) PaneDivider(state, defaultStartWidth, minStartWidth, maxStart, collapseBelow, locked, dividerLabel, onCollapsedChange, dividerTraversalIndex)
    }) { measurables, constraints ->
        val total = constraints.maxWidth
        val hingePx = hinge.roundToPx().coerceIn(0, total)
        val startPx = shown.roundToPx().coerceIn(0, total - hingePx)
        val endPx = total - startPx - hingePx
        fun fixed(width: Int) = Constraints(width, width, constraints.minHeight, constraints.maxHeight)
        val startPlaceable = if (startPx > 0) measurables[0].measure(fixed(startPx)) else null
        val endPlaceable = measurables[1].measure(fixed(endPx))
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else maxOf(startPlaceable?.height ?: 0, endPlaceable.height)
        val dividerPlaceable = measurables.getOrNull(2)?.measure(Constraints(0, total, height, height))
        layout(total, height) {
            startPlaceable?.placeRelative(0, 0)
            endPlaceable.placeRelative(startPx + hingePx, 0)
            dividerPlaceable?.placeRelative(startPx - dividerPlaceable.width / 2, 0)
        }
    }
}

@Composable
private fun PaneDivider(
    state: TwoPaneState, default: Dp, min: Dp, max: Dp, collapseBelow: Dp?, locked: Boolean, label: String, onCollapsedChange: (Boolean) -> Unit,
    order: Float? = null,
) {
    val c = Tf.colors
    if (locked) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
        return
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val latestCollapsed by rememberUpdatedState(onCollapsedChange)
    // Unclamped width during a drag: below the collapse threshold the pane stays at its minimum until release.
    var dragWidth by remember { mutableStateOf(0.dp) }
    var widthBeforeDrag by remember { mutableStateOf(0.dp) }
    fun setWidth(width: Dp) { state.startWidth = width.coerceIn(min, max) }
    fun collapse() {
        if (collapseBelow == null || state.collapsed) return
        state.collapsed = true; latestCollapsed(true)
    }
    val density = LocalDensity.current
    val drag = rememberDraggableState { deltaPx -> dragWidth += with(density) { deltaPx.toDp() }; setWidth(dragWidth) }
    Box(
        Modifier.width(AdaptiveSpec.DividerTouchWidth.dp).fillMaxHeight()
            .semantics {
                contentDescription = label
                // Read between the panes it separates when the caller orders its panes with traversal indices.
                if (order != null) traversalIndex = order
                progressBarRangeInfo = ProgressBarRangeInfo(state.startWidth.coerceIn(min, max).value, min.value..max.value)
                setProgress { target -> setWidth(target.dp); true }
                customActions = listOfNotNull(
                    CustomAccessibilityAction("Reset width") { setWidth(default); true },
                    collapseBelow?.let { CustomAccessibilityAction("Hide pane") { collapse(); true } },
                )
            }
            .horizontalResizePointer()
            .hoverable(interaction)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = AdaptiveSpec.DividerKeyStep.dp * if (rtl) -1f else 1f
                when (event.key) {
                    Key.DirectionLeft -> { setWidth(state.startWidth.coerceIn(min, max) - step); true }
                    Key.DirectionRight -> { setWidth(state.startWidth.coerceIn(min, max) + step); true }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> { collapse(); collapseBelow != null }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(default, min, max) { detectTapGestures(onDoubleTap = { setWidth(default) }) }
            .draggable(drag, Orientation.Horizontal, interactionSource = interaction, reverseDirection = rtl,
                onDragStarted = { widthBeforeDrag = state.startWidth.coerceIn(min, max); dragWidth = widthBeforeDrag },
                onDragStopped = { if (collapseBelow != null && dragWidth < collapseBelow) { state.startWidth = widthBeforeDrag; collapse() } }),
        contentAlignment = Alignment.Center,
    ) {
        // At rest a hairline and a quiet handle show the pane can be resized; hover darkens and lengthens the handle, keyboard focus
        // rings it in the accent, and a drag turns the whole line into the accent so the moving edge is easy to follow.
        Box(Modifier.width(if (dragged) 2.dp else 1.dp).fillMaxHeight().background(if (dragged) c.accent else c.line))
        if (focused && !dragged) Box(Modifier.size(12.dp, 56.dp).background(c.bg, RoundedCornerShape(6.dp)).border(2.dp, c.accent, RoundedCornerShape(6.dp)))
        Box(
            Modifier.size(4.dp, when { dragged -> 56.dp; hovered || focused -> 44.dp; else -> 40.dp })
                .background(when { dragged || focused -> c.accent; hovered -> c.muted; else -> c.s3 }, RoundedCornerShape(2.dp)),
        )
    }
}

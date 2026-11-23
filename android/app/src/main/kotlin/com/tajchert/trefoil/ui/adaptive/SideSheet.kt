package com.tajchert.trefoil.ui.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.rememberAnimationsOff

/** Width of a modal side sheet on a flat window. */
internal const val SideSheetWidthDp = 360f

/**
 * Width of a modal side sheet in a window [windowWidthDp] wide. On a vertical fold that separates or is half-open the sheet takes
 * exactly the end-side screen half (LTR: right of the hinge, RTL: left of it) so it never straddles the hinge; otherwise it is
 * [desiredDp] wide, never wider than the window.
 */
internal fun sideSheetWidth(windowWidthDp: Float, fold: Fold?, rtl: Boolean, desiredDp: Float = SideSheetWidthDp): Float {
    if (fold != null && fold.orientation == FoldOrientation.Vertical && (fold.separating || fold.halfOpened)) {
        val half = if (rtl) fold.start else windowWidthDp - fold.end
        if (half > 0f) return minOf(half, windowWidthDp)
    }
    return minOf(desiredDp, windowWidthDp)
}

/**
 * A modal sheet on the end edge: its own window with a scrim, [SideSheetWidthDp] wide ([sideSheetWidth] on a fold), 28dp radius on the
 * two start corners. Slides in from the end edge unless animations are off. Dismisses on scrim tap, Back, Escape and the Close
 * button, which also takes the initial focus so keyboard and TalkBack users start inside the sheet. [title] is the pane title
 * TalkBack announces when it opens. [content] brings its own heading; the Close button sits over the top end corner.
 */
@Composable
internal fun TfModalSideSheet(onDismiss: () -> Unit, title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Tf.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val window = windowSizeDp()
    val width = sideSheetWidth(window.width.value, LocalFold.current, rtl).dp
    val animate = !rememberAnimationsOff()
    val visible = remember { MutableTransitionState(!animate).apply { targetState = true } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Box(
            Modifier.fillMaxSize().background(c.scrim)
                // pointerInput, not clickable: a clickable merges the whole sheet into one accessibility node. Only taps outside
                // the sheet's own span dismiss; taps on the sheet are left to its content.
                .pointerInput(onDismiss, rtl, width) {
                    val sheetPx = width.toPx()
                    detectTapGestures { tap -> if (if (rtl) tap.x > sheetPx else tap.x < size.width - sheetPx) onDismiss() }
                }
                .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onDismiss(); true } else false },
        ) {
            AnimatedVisibility(
                visibleState = visible, modifier = Modifier.align(Alignment.CenterEnd),
                enter = if (animate) slideInHorizontally { full -> if (rtl) -full else full } else EnterTransition.None,
            ) {
                Surface(
                    modifier.width(width).fillMaxHeight()
                        .semantics { paneTitle = title; isTraversalGroup = true },
                    shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp), color = c.sheet, contentColor = c.text, tonalElevation = 0.dp,
                ) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Vertical + WindowInsetsSides.End))) {
                        content()
                        // TfIconButton hands its modifier to the button inside its tooltip box, so the alignment goes on a wrapper.
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) {
                            TfIconButton(icon = TfIcons.Close, label = "Close", onClick = onDismiss, modifier = Modifier.size(48.dp).focusRequester(focus))
                        }
                    }
                }
            }
        }
    }
}

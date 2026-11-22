package com.tajchert.trefoil.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Root bounds of the snackbar on screen, or null while none is showing. */
internal val LocalSnackbarBounds = staticCompositionLocalOf<State<Rect?>> { mutableStateOf(null) }

/**
 * How far a floating action at [own] must rise to clear [snackbar] by [gap], like the View system's
 * FloatingActionButton.Behavior: only a snackbar that overlaps it horizontally and reaches its bottom pushes it.
 */
internal fun snackbarLift(own: Rect?, snackbar: Rect?, gap: Float): Float {
    if (own == null || snackbar == null || snackbar.height <= 0f) return 0f
    val overlapsHorizontally = snackbar.left < own.right && snackbar.right > own.left
    val reachesIt = snackbar.top < own.bottom + gap && snackbar.bottom > own.top
    return if (overlapsHorizontally && reachesIt) own.bottom + gap - snackbar.top else 0f
}

/** Slides a bottom-anchored floating action up while a snackbar would cover it, and back down when it leaves. */
@Composable
internal fun Modifier.avoidSnackbar(gap: Dp = 8.dp): Modifier {
    val snackbar by LocalSnackbarBounds.current
    var own by remember { mutableStateOf<Rect?>(null) }
    val gapPx = with(LocalDensity.current) { gap.toPx() }
    val lift by animateFloatAsState(snackbarLift(own, snackbar, gapPx), tween(250, easing = FastOutSlowInEasing), label = "snackbarLift")
    // Measured before the offset, so the lift never feeds back into the bounds it is computed from.
    return onGloballyPositioned { own = it.boundsInRoot() }.offset { IntOffset(0, -lift.roundToInt()) }
}

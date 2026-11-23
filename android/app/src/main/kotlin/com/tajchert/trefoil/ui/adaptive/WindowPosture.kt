package com.tajchert.trefoil.ui.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.map

/**
 * Maps a folding feature to a [Fold] in window dp. Bounds and window size are in px, relative to the window.
 * Returns null for features that do not span the window along the hinge or that lie (partly) outside it.
 */
internal fun foldOf(
    left: Int, top: Int, right: Int, bottom: Int, windowWidthPx: Int, windowHeightPx: Int, density: Float,
    vertical: Boolean, halfOpened: Boolean, separating: Boolean,
): Fold? {
    if (density <= 0f || windowWidthPx <= 0 || windowHeightPx <= 0) return null
    if (left < 0 || top < 0 || right > windowWidthPx || bottom > windowHeightPx || right < left || bottom < top) return null
    val spans = if (vertical) top == 0 && bottom == windowHeightPx else left == 0 && right == windowWidthPx
    if (!spans) return null
    val (start, end) = if (vertical) left to right else top to bottom
    return Fold(if (vertical) FoldOrientation.Vertical else FoldOrientation.Horizontal, start / density, end / density, separating, halfOpened)
}

internal fun FoldingFeature.toFold(windowWidthPx: Int, windowHeightPx: Int, density: Float): Fold? = bounds.let {
    foldOf(it.left, it.top, it.right, it.bottom, windowWidthPx, windowHeightPx, density, orientation == FoldingFeature.Orientation.VERTICAL,
        state == FoldingFeature.State.HALF_OPENED, isSeparating)
}

/** The fold spanning the current window, or null (no fold, previews, tests without an Activity). Provided by [ProvideWindowFold]. */
internal val LocalFold = staticCompositionLocalOf<Fold?> { null }

/**
 * Collects the window's folding features while the lifecycle is at least STARTED and provides the first spanning one as [LocalFold].
 * Call once near the root (MainActivity). Without an Activity or in inspection mode it provides null.
 */
@Composable
internal fun ProvideWindowFold(content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val fold = if (activity == null || LocalInspectionMode.current) null else rememberWindowFold(activity)
    CompositionLocalProvider(LocalFold provides fold, content)
}

@Composable
private fun rememberWindowFold(activity: Activity): Fold? {
    val flow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
            val density = activity.resources.displayMetrics.density
            info.displayFeatures.filterIsInstance<FoldingFeature>().firstNotNullOfOrNull { it.toFold(bounds.width(), bounds.height(), density) }
        }
    }
    val fold by flow.collectAsStateWithLifecycle(initialValue = null)
    return fold
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

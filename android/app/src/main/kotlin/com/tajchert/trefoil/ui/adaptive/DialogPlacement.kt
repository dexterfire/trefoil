package com.tajchert.trefoil.ui.adaptive

/** A rectangle in window dp. */
internal data class DialogRegion(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** Space kept between a centred dialog and the edges of the region it sits in. */
internal const val DialogMarginDp = 24f

/**
 * The part of the window a centred dialog sits in, or null when the whole window is used (no fold, or a flat one: nothing changes).
 *
 * On a separating or half-open fold the dialog must not straddle the hinge:
 *  - vertical fold (book posture): the end-side half (LTR: right of the hinge, [rtl]: left of it);
 *  - horizontal fold (tabletop): the top half while the keyboard is open ([imeVisible], the keyboard fills the bottom half),
 *    otherwise the taller half, the top one when they are equal.
 * A degenerate half (hinge at or beyond the window edge) falls back to the whole window.
 */
internal fun dialogRegion(windowWidth: Float, windowHeight: Float, fold: Fold?, rtl: Boolean, imeVisible: Boolean): DialogRegion? {
    if (fold == null || !(fold.separating || fold.halfOpened)) return null
    val region = when (fold.orientation) {
        FoldOrientation.Vertical ->
            if (rtl) DialogRegion(0f, 0f, fold.start.coerceIn(0f, windowWidth), windowHeight)
            else fold.end.coerceIn(0f, windowWidth).let { DialogRegion(it, 0f, windowWidth - it, windowHeight) }
        FoldOrientation.Horizontal -> {
            val top = DialogRegion(0f, 0f, windowWidth, fold.start.coerceIn(0f, windowHeight))
            val bottom = fold.end.coerceIn(0f, windowHeight).let { DialogRegion(0f, it, windowWidth, windowHeight - it) }
            if (imeVisible || top.height >= bottom.height) top else bottom
        }
    }
    return region.takeIf { it.width > 0f && it.height > 0f }
}

/** Widest the dialog may be: [requested], and inside [region] at most the region's width less the margin on both sides. */
internal fun dialogMaxWidthDp(requested: Float, region: DialogRegion?): Float =
    if (region == null) requested else minOf(requested, (region.width - 2 * DialogMarginDp).coerceAtLeast(0f))

/**
 * Tallest the dialog may be: [AdaptiveSpec.DialogMaxHeightFraction] of the window (or [requested] when that is smaller), and inside a
 * [region] at most its height less the margin above and below.
 */
internal fun dialogMaxHeightDp(windowHeight: Float, region: DialogRegion?, requested: Float?): Float {
    val base = (windowHeight * AdaptiveSpec.DialogMaxHeightFraction).let { if (requested != null) minOf(it, requested) else it }
    return if (region == null) base else minOf(base, (region.height - 2 * DialogMarginDp).coerceAtLeast(0f))
}

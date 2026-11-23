package com.tajchert.trefoil.ui.adaptive

import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec as S

/** Window width classes of brief 5.2 (window, not container). */
internal enum class WidthClass { Compact, Medium, Expanded, Large, ExtraLarge }

internal fun widthClass(widthDp: Float): WidthClass = when {
    widthDp >= S.ExtraLargeMinWidth -> WidthClass.ExtraLarge
    widthDp >= S.LargeMinWidth -> WidthClass.Large
    widthDp >= S.ExpandedMinWidth -> WidthClass.Expanded
    widthDp >= S.MediumMinWidth -> WidthClass.Medium
    else -> WidthClass.Compact
}

/** Vertical: the hinge runs top to bottom (book); Horizontal: left to right (tabletop / laptop). */
internal enum class FoldOrientation { Vertical, Horizontal }

/**
 * A fold or hinge that spans the window. [start]..[end] is the hinge band in window dp on the axis it splits
 * (x for [FoldOrientation.Vertical], y for [FoldOrientation.Horizontal]); a flat fold without a physical gap has start == end.
 */
internal data class Fold(val orientation: FoldOrientation, val start: Float, val end: Float, val separating: Boolean, val halfOpened: Boolean) {
    val width: Float get() = end - start
}

internal data class WindowSpec(val widthDp: Float, val heightDp: Float, val fold: Fold? = null)

/** Where a side panel (ports, screens, …) opens: over the whole window, in place of the list, or as a third pane. */
internal enum class SidePanelMode { FullScreen, ReplacesList, ThirdPane }

/**
 * What the shell lays out for a window (brief 5.2). All widths in dp.
 *
 * Flat window (no fold, a flat non-separating fold, a horizontal fold): [foldListWidth] is the list width that puts the pane edge on
 * a vertical flat fold (null when there is none or it would leave the list outside the drag range); when set it is also
 * [defaultListWidth] and the user can still drag. [hingeWidth] is 0 and [splitFold] null.
 *
 * Split window ([splitFold] non-null): a vertical fold that is separating or half-opened in a two-pane window always splits the
 * window into two physical regions, left `0..fold.start` and right `fold.end..width`, with nothing drawn over [hingeWidth].
 * The left region holds the rail (dropped when it would leave the list under [AdaptiveSpec.ListDragMin]) and the list, which fills
 * it ([foldListWidth] == [defaultListWidth] == [minListWidth] == [maxListWidth]); the right region holds the conversation. An open side
 * panel always replaces the list ([SidePanelMode.ReplacesList]): left = rail + conversation, right = side panel filling the region
 * ([sidePanelWidth] == its min == its max == the right region). [dividerLocked] is true and [listCollapsible] false. When either
 * region would be narrower than [AdaptiveSpec.ListDragMin] even without the rail (no shipping device), the fold is treated as absent.
 *
 * [dividerLocked] means the user cannot drag the divider. [listCollapsible] false means the list cannot be hidden (hide its collapse
 * control; [resolvePanes] ignores `listCollapsed`). [tabletopFold] is the half-opened horizontal fold that splits the screen-session
 * viewer, null otherwise.
 */
internal data class ShellPolicy(
    val windowWidth: Float, val widthClass: WidthClass, val compactHeight: Boolean, val rail: Boolean, val twoPane: Boolean,
    val defaultListWidth: Float, val minListWidth: Float, val maxListWidth: Float,
    val foldListWidth: Float?, val dividerLocked: Boolean, val hingeWidth: Float,
    val sidePanelMode: SidePanelMode, val sidePanelWidth: Float = S.SidePanelWidth, val sidePanelMinWidth: Float = S.SidePanelMinWidth,
    val sidePanelMaxWidth: Float = S.SidePanelMaxWidth,
    val tabletopFold: Fold? = null,
    val splitFold: Fold? = null,
    val listCollapsible: Boolean = true,
) {
    val railWidth: Float get() = if (rail) S.RailWidth else 0f
    val tabletop: Boolean get() = tabletopFold != null
}

/** The vertical separating or half-opened fold that splits a two-pane window, or null (none, flat, or a region under the list minimum). */
private fun splitFoldOf(fold: Fold?, windowWidth: Float, twoPane: Boolean): Fold? = fold?.takeIf {
    twoPane && it.orientation == FoldOrientation.Vertical && (it.separating || it.halfOpened) && it.end >= it.start &&
        it.start >= S.ListDragMin && windowWidth - it.end >= S.ListDragMin
}

internal fun shellPolicy(window: WindowSpec): ShellPolicy {
    val w = window.widthDp
    val widthClass = widthClass(w)
    val compactHeight = window.heightDp < S.CompactHeightBelow
    val twoPane = w >= S.TwoPaneMinWidth && (!compactHeight || w >= S.TwoPaneCompactHeightMinWidth)
    val fold = window.fold
    val tabletopFold = fold?.takeIf { it.orientation == FoldOrientation.Horizontal && it.halfOpened }
    val split = splitFoldOf(fold, w, twoPane)
    if (split != null) {
        val rail = !compactHeight && w >= S.RailMinWidth && split.start - S.RailWidth >= S.ListDragMin
        val list = split.start - if (rail) S.RailWidth else 0f
        val right = w - split.end
        return ShellPolicy(
            windowWidth = w, widthClass = widthClass, compactHeight = compactHeight, rail = rail, twoPane = true,
            defaultListWidth = list, minListWidth = list, maxListWidth = list, foldListWidth = list, dividerLocked = true,
            hingeWidth = split.width, sidePanelMode = SidePanelMode.ReplacesList,
            sidePanelWidth = right, sidePanelMinWidth = right, sidePanelMaxWidth = right,
            tabletopFold = tabletopFold, splitFold = split, listCollapsible = false,
        )
    }
    val rail = !compactHeight && w >= S.RailMinWidth
    val railWidth = if (rail) S.RailWidth else 0f
    val baseList = when {
        !rail -> (w * S.ListNoRailFraction).coerceIn(S.ListNoRailMin, S.ListNoRailMax)
        widthClass == WidthClass.ExtraLarge -> S.ListRailExtraLarge
        w >= S.ExpandedMinWidth -> S.ListRailExpanded
        else -> S.ListRailMedium
    }
    // Only a flat, non-separating vertical fold reaches here (a split-worthy one returned above or is treated as absent).
    val flat = fold?.takeIf { it.orientation == FoldOrientation.Vertical && twoPane && !it.separating && !it.halfOpened }
    val foldList = flat?.let { it.start - railWidth }?.takeIf { it in S.ListDragMin..S.ListDragMax }
    val defaultList = foldList ?: baseList
    // A compact-height or single-pane window never docks the panel: an open tab takes the window (the keyboard needs the height).
    val sidePanelMode = if (compactHeight || !twoPane) SidePanelMode.FullScreen else when (widthClass) {
        WidthClass.ExtraLarge -> SidePanelMode.ThirdPane
        WidthClass.Expanded, WidthClass.Large -> SidePanelMode.ReplacesList
        else -> SidePanelMode.FullScreen
    }
    return ShellPolicy(
        windowWidth = w, widthClass = widthClass, compactHeight = compactHeight, rail = rail, twoPane = twoPane,
        defaultListWidth = defaultList,
        minListWidth = minOf(S.ListDragMin, defaultList),
        maxListWidth = maxOf(defaultList, minOf(S.ListDragMax, w - railWidth - S.ConversationMinWidth)),
        foldListWidth = foldList,
        dividerLocked = false,
        hingeWidth = 0f,
        sidePanelMode = sidePanelMode,
        // Docked beside the conversation it scales with the window: 360 on an unfolded foldable, 520 on a tablet in landscape.
        sidePanelWidth = if (sidePanelMode == SidePanelMode.ReplacesList)
            ((w - railWidth) * S.SidePanelFraction).coerceIn(S.SidePanelMinWidth, S.SidePanelWidth) else S.SidePanelWidth,
        tabletopFold = tabletopFold,
    )
}

/** Where the hinge gap of [PaneWidths] sits in the left-to-right order. */
internal enum class HingeAt { None, BeforeConversation, BeforeSidePanel }

/**
 * Resolved pane widths in dp; they always sum to the window width and are never negative. Zero means the pane is not shown.
 * Left-to-right (start-to-end) order: rail, list, hinge if [hingeAt] is [HingeAt.BeforeConversation], conversation, hinge if
 * [hingeAt] is [HingeAt.BeforeSidePanel], side panel. On a split window ([ShellPolicy.splitFold]) [hingeAt] is never None, even for a
 * zero-width half-opened fold; elsewhere it is None and [hinge] is 0.
 */
internal data class PaneWidths(
    val rail: Float, val list: Float, val hinge: Float, val conversation: Float, val sidePanel: Float,
    val hingeAt: HingeAt = if (hinge > 0f) HingeAt.BeforeConversation else HingeAt.None,
) {
    val total: Float get() = rail + list + hinge + conversation + sidePanel
}

/**
 * Actual pane widths for [policy] given the user's dragged [listWidth], whether the list is collapsed and whether a side panel is open.
 * A single-pane window gives everything to the one visible destination ([PaneWidths.conversation], or [PaneWidths.sidePanel] when open).
 * A split window ([ShellPolicy.splitFold]) ignores [listWidth] and [listCollapsed]: left region = rail + list (or rail + conversation
 * with the side panel open), right region = conversation (or the side panel). Otherwise, when everything cannot fit next to a
 * [AdaptiveSpec.ConversationMinWidth] conversation, the list shrinks to its minimum, then gives way to the side panel, and only then does
 * the side panel shrink toward its minimum.
 */
internal fun resolvePanes(policy: ShellPolicy, listWidth: Float, listCollapsed: Boolean, sidePanelOpen: Boolean): PaneWidths {
    val w = policy.windowWidth.coerceAtLeast(0f)
    if (!policy.twoPane || (sidePanelOpen && policy.sidePanelMode == SidePanelMode.FullScreen))
        return if (sidePanelOpen) PaneWidths(0f, 0f, 0f, 0f, w) else PaneWidths(0f, 0f, 0f, w, 0f)
    val split = policy.splitFold
    if (split != null) {
        val left = split.start.coerceIn(0f, w)
        val hinge = split.end.coerceIn(left, w) - left
        val right = w - left - hinge
        val rail = policy.railWidth.coerceAtMost(left)
        return if (sidePanelOpen) PaneWidths(rail, 0f, hinge, left - rail, right, HingeAt.BeforeSidePanel)
        else PaneWidths(rail, left - rail, hinge, right, 0f, HingeAt.BeforeConversation)
    }
    val rail = policy.railWidth.coerceAtMost(w)
    val avail = w - rail
    val minConversation = S.ConversationMinWidth
    var list = when {
        (listCollapsed && policy.listCollapsible) || (sidePanelOpen && policy.sidePanelMode == SidePanelMode.ReplacesList) -> 0f
        else -> listWidth.coerceIn(policy.minListWidth, policy.maxListWidth).coerceIn(0f, avail)
    }
    if (!sidePanelOpen) return PaneWidths(rail, list, 0f, avail - list, 0f)
    var side = policy.sidePanelWidth.coerceIn(policy.sidePanelMinWidth, policy.sidePanelMaxWidth).coerceIn(0f, avail)
    fun conversation() = avail - list - side
    if (conversation() < minConversation && list > 0f) list = (list - (minConversation - conversation())).coerceAtLeast(minOf(policy.minListWidth, list))
    if (conversation() < minConversation) list = 0f
    if (conversation() < minConversation) side = (avail - minConversation).coerceIn(minOf(policy.sidePanelMinWidth, avail), side)
    return PaneWidths(rail, list, 0f, conversation(), side)
}

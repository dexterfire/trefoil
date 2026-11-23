package com.tajchert.trefoil.ui.adaptive

/**
 * Every adaptive layout number (dp) in one place (brief 5.2). Tune here only; nothing else may hard-code these.
 * Plain floats so the pure policy in [shellPolicy] stays free of Compose; wrap with `.dp` at call sites.
 */
internal object AdaptiveSpec {
    // Window width classes: Compact < 600, Medium 600-839, Expanded 840-1199, Large 1200-1599, ExtraLarge >= 1600.
    const val MediumMinWidth = 600f
    const val ExpandedMinWidth = 840f
    const val LargeMinWidth = 1200f
    const val ExtraLargeMinWidth = 1600f

    /** Below this window height: no rail, 48dp headers, single-row composer. */
    const val CompactHeightBelow = 480f

    /** Two panes need width >= [TwoPaneMinWidth] and either a non-compact height or width >= [TwoPaneCompactHeightMinWidth]. */
    const val TwoPaneMinWidth = 600f
    const val TwoPaneCompactHeightMinWidth = 720f

    /** Rail needs a non-compact height and width >= [RailMinWidth]. */
    const val RailMinWidth = 780f
    const val RailWidth = 80f

    /** List width without a rail: this fraction of the window, clamped to [ListNoRailMin]..[ListNoRailMax]. */
    const val ListNoRailFraction = .5f
    const val ListNoRailMin = 320f
    const val ListNoRailMax = 420f

    /** List width with a rail: below Expanded, Expanded..Large, ExtraLarge. */
    const val ListRailMedium = 300f
    const val ListRailExpanded = 340f
    const val ListRailExtraLarge = 380f

    /** User drag range of the list; a release below [ListCollapseBelow] collapses it. */
    const val ListDragMin = 280f
    const val ListDragMax = 480f
    const val ListCollapseBelow = 200f

    /** Divider keyboard step, and the width of its drag target. */
    const val DividerKeyStep = 16f
    const val DividerTouchWidth = 24f

    /**
     * Narrowest conversation next to a list or side panel. Not in brief 5.2 (F0 choice): it caps how far the list can be
     * dragged and decides when an open side panel squeezes the list out.
     */
    const val ConversationMinWidth = 360f

    /**
     * Default narrowest end pane of a [TfTwoPane] (F0 choice): below `minStartWidth + hinge + this` the start pane auto-collapses.
     * Equal to [ListDragMin] so a 600dp window still fits the shell's 320 list + 280 conversation; pass
     * [ConversationMinWidth] where the end pane needs more.
     */
    const val TwoPaneEndMinWidth = 280f

    /** Side panel (ports, screens, …): third pane at ExtraLarge, replaces the list at Expanded..Large, full window below. */
    const val SidePanelWidth = 520f
    /** While the panel replaces the list its default width is this share of the space beside the rail, within min..[SidePanelWidth]. */
    const val SidePanelFraction = 0.45f
    const val SidePanelMinWidth = 360f
    const val SidePanelMaxWidth = 640f

    /** Reading widths inside the conversation. */
    const val ConversationColumnMax = 880f
    const val ProseMax = 680f
    const val UserBubbleMax = 560f

    /** Dialogs replace bottom sheets from this window width; per-dialog max sizes below. */
    const val DialogMinWindowWidth = 600f
    const val DialogNewThreadWidth = 640f
    const val DialogModelPickerWidth = 680f
    const val DialogModelPickerHeight = 560f
    const val DialogSshWidth = 560f
    const val DialogHostKeyWidth = 520f
    const val DialogPairWidth = 500f
    /** Dialog height cap as a fraction of the window height. */
    const val DialogMaxHeightFraction = .9f
}

package com.tajchert.trefoil.ui.adaptive

import org.junit.Assert.*
import org.junit.Test

class WindowLayoutTest {
    private fun policy(w: Float, h: Float, fold: Fold? = null) = shellPolicy(WindowSpec(w, h, fold))
    private fun vertical(at: Float, gap: Float = 0f, separating: Boolean = false, halfOpened: Boolean = false) =
        Fold(FoldOrientation.Vertical, at - gap / 2, at + gap / 2, separating, halfOpened)
    private fun assertSums(p: ShellPolicy, panes: PaneWidths) = assertEquals(p.windowWidth, panes.total, .001f)

    @Test fun phone() = with(policy(400f, 800f)) {
        assertEquals(WidthClass.Compact, widthClass); assertFalse(twoPane); assertFalse(rail); assertFalse(compactHeight)
        assertEquals(SidePanelMode.FullScreen, sidePanelMode)
        assertEquals(PaneWidths(0f, 0f, 0f, 400f, 0f), resolvePanes(this, defaultListWidth, false, false))
        assertEquals(PaneWidths(0f, 0f, 0f, 0f, 400f), resolvePanes(this, defaultListWidth, false, true))
    }

    @Test fun phoneLandscape() = with(policy(880f, 400f)) {
        assertEquals(WidthClass.Expanded, widthClass); assertTrue(compactHeight); assertTrue(twoPane); assertFalse(rail)
        assertEquals(SidePanelMode.FullScreen, sidePanelMode)
        assertEquals(420f, defaultListWidth); assertEquals(280f, minListWidth); assertEquals(480f, maxListWidth)
        assertEquals(PaneWidths(0f, 420f, 0f, 460f, 0f), resolvePanes(this, defaultListWidth, false, false))
    }

    @Test fun foldPortrait() = with(policy(673f, 841f)) {
        assertEquals(WidthClass.Medium, widthClass); assertTrue(twoPane); assertFalse(rail)
        assertEquals(336.5f, defaultListWidth); assertEquals(336.5f, maxListWidth); assertNull(foldListWidth); assertFalse(dividerLocked)
        assertEquals(SidePanelMode.FullScreen, sidePanelMode)
    }

    @Test fun foldLandscape() = with(policy(841f, 673f)) {
        assertEquals(WidthClass.Expanded, widthClass); assertTrue(twoPane); assertTrue(rail); assertEquals(80f, railWidth)
        assertEquals(340f, defaultListWidth); assertEquals(401f, maxListWidth)
        assertEquals(PaneWidths(80f, 340f, 0f, 421f, 0f), resolvePanes(this, defaultListWidth, false, false))
    }

    @Test fun tabletPortrait() = with(policy(800f, 1280f)) {
        assertEquals(WidthClass.Medium, widthClass); assertTrue(rail); assertEquals(300f, defaultListWidth); assertEquals(360f, maxListWidth)
        assertEquals(SidePanelMode.FullScreen, sidePanelMode)
    }

    @Test fun tabletLandscape() = with(policy(1280f, 800f)) {
        assertEquals(WidthClass.Large, widthClass); assertTrue(rail); assertEquals(340f, defaultListWidth); assertEquals(480f, maxListWidth)
        assertEquals(SidePanelMode.ReplacesList, sidePanelMode)
    }

    @Test fun desktop() = with(policy(1920f, 1080f)) {
        assertEquals(WidthClass.ExtraLarge, widthClass); assertTrue(rail); assertEquals(380f, defaultListWidth)
        assertEquals(SidePanelMode.ThirdPane, sidePanelMode)
    }

    @Test fun widthClassBoundaries() {
        assertEquals(WidthClass.Compact, widthClass(599f)); assertEquals(WidthClass.Medium, widthClass(600f))
        assertEquals(WidthClass.Medium, widthClass(839f)); assertEquals(WidthClass.Expanded, widthClass(840f))
        assertEquals(WidthClass.Expanded, widthClass(1199f)); assertEquals(WidthClass.Large, widthClass(1200f))
        assertEquals(WidthClass.Large, widthClass(1599f)); assertEquals(WidthClass.ExtraLarge, widthClass(1600f))
    }

    @Test fun twoPaneAndRailBoundaries() {
        assertFalse(policy(599f, 800f).twoPane); assertTrue(policy(600f, 800f).twoPane)
        assertFalse(policy(719f, 400f).twoPane); assertTrue(policy(720f, 400f).twoPane)
        assertFalse(policy(779f, 800f).rail); assertTrue(policy(780f, 800f).rail)
        assertTrue(policy(1000f, 479f).compactHeight); assertFalse(policy(1000f, 479f).rail)
        assertFalse(policy(1000f, 480f).compactHeight); assertTrue(policy(1000f, 480f).rail)
        assertFalse(policy(600f, 479f).twoPane); assertTrue(policy(600f, 480f).twoPane)
    }

    @Test fun listWidthBoundaries() {
        assertEquals(320f, policy(600f, 800f).defaultListWidth)
        assertEquals(300f, policy(780f, 800f).defaultListWidth); assertEquals(300f, policy(839f, 800f).defaultListWidth)
        assertEquals(340f, policy(840f, 800f).defaultListWidth); assertEquals(340f, policy(1599f, 900f).defaultListWidth)
        assertEquals(380f, policy(1600f, 900f).defaultListWidth)
        assertEquals(SidePanelMode.FullScreen, policy(839f, 800f).sidePanelMode); assertEquals(SidePanelMode.ReplacesList, policy(840f, 800f).sidePanelMode)
        assertEquals(SidePanelMode.ReplacesList, policy(1599f, 800f).sidePanelMode); assertEquals(SidePanelMode.ThirdPane, policy(1600f, 800f).sidePanelMode)
    }

    @Test fun flatVerticalFoldSnapsButStaysDraggable() = with(policy(673f, 841f, vertical(336.5f))) {
        assertEquals(336.5f, foldListWidth); assertEquals(336.5f, defaultListWidth); assertFalse(dividerLocked); assertEquals(0f, hingeWidth)
        assertEquals(PaneWidths(0f, 300f, 0f, 373f, 0f), resolvePanes(this, 300f, false, false))
    }

    @Test fun halfOpenedVerticalFoldSplitsTheWindowAtTheFold() = with(policy(673f, 841f, vertical(336.5f, halfOpened = true))) {
        assertTrue(dividerLocked); assertFalse(listCollapsible); assertNotNull(splitFold); assertEquals(SidePanelMode.ReplacesList, sidePanelMode)
        assertEquals(PaneWidths(0f, 336.5f, 0f, 336.5f, 0f, HingeAt.BeforeConversation), resolvePanes(this, 300f, false, false))
        // Side panel at Medium width: still the right region, not full-window.
        assertEquals(PaneWidths(0f, 0f, 0f, 336.5f, 336.5f, HingeAt.BeforeSidePanel), resolvePanes(this, 300f, false, true))
    }

    @Test fun separatingFoldKeepsTheHingeInEveryState() = with(policy(841f, 673f, vertical(420.5f, gap = 20f, separating = true))) {
        assertEquals(330.5f, foldListWidth); assertTrue(dividerLocked); assertEquals(20f, hingeWidth); assertTrue(rail); assertFalse(listCollapsible)
        val panes = resolvePanes(this, 400f, false, false)
        assertEquals(PaneWidths(80f, 330.5f, 20f, 410.5f, 0f, HingeAt.BeforeConversation), panes); assertSums(this, panes)
        // Collapse is disabled in this posture: the list stays.
        assertEquals(panes, resolvePanes(this, 400f, true, false))
        // Side panel: rail + conversation on the left, panel fills the right region.
        val side = resolvePanes(this, 400f, true, true)
        assertEquals(PaneWidths(80f, 0f, 20f, 330.5f, 410.5f, HingeAt.BeforeSidePanel), side); assertSums(this, side)
    }

    @Test fun separatingFoldOutsideTheListRangeStillSplits() = with(policy(1280f, 800f, vertical(640f, gap = 10f, separating = true))) {
        // 635 - 80 = 555 > 480: the list fills the left region anyway.
        assertEquals(PaneWidths(80f, 555f, 10f, 635f, 0f, HingeAt.BeforeConversation), resolvePanes(this, 340f, false, false))
        assertEquals(PaneWidths(80f, 0f, 10f, 555f, 635f, HingeAt.BeforeSidePanel), resolvePanes(this, 340f, false, true))
    }

    @Test fun separatingFoldDropsTheRailWhenTheListWouldBeTooNarrow() = with(policy(800f, 1280f, vertical(330f, separating = true))) {
        assertFalse(rail); assertEquals(330f, defaultListWidth)
        assertEquals(PaneWidths(0f, 330f, 0f, 470f, 0f, HingeAt.BeforeConversation), resolvePanes(this, 300f, false, false))
    }

    @Test fun wideOffCentreHingeFallsBackToAFlatWindow() {
        // Review case: rail 80 + list 320 + hinge 390 left a 10dp conversation. The right region (10) is under 280: no fold.
        val p = policy(800f, 1280f, Fold(FoldOrientation.Vertical, 400f, 790f, separating = true, halfOpened = false))
        assertNull(p.splitFold); assertEquals(0f, p.hingeWidth); assertFalse(p.dividerLocked); assertTrue(p.listCollapsible)
        assertEquals(p, policy(800f, 1280f))
        val panes = resolvePanes(p, 1000f, false, false)
        assertEquals(PaneWidths(80f, 360f, 0f, 360f, 0f), panes); assertSums(p, panes)
    }

    @Test fun separatingFoldInASinglePaneWindowIsIgnored() = with(policy(599f, 800f, vertical(299.5f, separating = true))) {
        assertFalse(twoPane); assertNull(splitFold)
        assertEquals(PaneWidths(0f, 0f, 0f, 599f, 0f), resolvePanes(this, 300f, false, false))
    }

    @Test fun foldOutsideTheDragRangeDoesNotSnap() = with(policy(1280f, 800f, vertical(640f))) {
        assertNull(foldListWidth); assertEquals(340f, defaultListWidth)
    }

    @Test fun halfOpenedHorizontalFoldIsTabletop() {
        val hinge = Fold(FoldOrientation.Horizontal, 400f, 400f, separating = true, halfOpened = true)
        with(policy(673f, 841f, hinge)) { assertTrue(tabletop); assertEquals(hinge, tabletopFold); assertFalse(dividerLocked); assertNull(foldListWidth) }
        assertFalse(policy(673f, 841f, hinge.copy(halfOpened = false)).tabletop)
    }

    @Test fun sidePanelAt841ReplacesTheListAndKeepsTheConversationReadable() = with(policy(841f, 673f)) {
        val panes = resolvePanes(this, defaultListWidth, false, true)
        assertEquals(PaneWidths(80f, 0f, 0f, 401f, 360f), panes); assertSums(this, panes)
    }

    @Test fun dockedSidePanelScalesWithTheWindow() {
        assertEquals(360f, policy(841f, 673f).sidePanelWidth); assertEquals(414f, policy(1000f, 800f).sidePanelWidth, 0.01f)
        assertEquals(520f, policy(1280f, 800f).sidePanelWidth); assertEquals(520f, policy(1920f, 1080f).sidePanelWidth)
    }

    @Test fun sidePanelAt1280ReplacesTheList() = with(policy(1280f, 800f)) {
        assertEquals(PaneWidths(80f, 0f, 0f, 680f, 520f), resolvePanes(this, 420f, false, true))
    }

    @Test fun sidePanelAt1920IsAThirdPane() = with(policy(1920f, 1080f)) {
        assertEquals(PaneWidths(80f, 380f, 0f, 940f, 520f), resolvePanes(this, defaultListWidth, false, true))
        assertEquals(PaneWidths(80f, 0f, 0f, 1320f, 520f), resolvePanes(this, defaultListWidth, true, true))
    }

    @Test fun sidePanelWinsOverTheListWhenAThirdPaneCannotFit() {
        val p = policy(1600f, 900f).copy(sidePanelWidth = 640f)
        // 1600 - 80 rail - 640 panel = 880: a 480 list leaves 400 for the conversation, fine.
        assertEquals(PaneWidths(80f, 480f, 0f, 400f, 640f), resolvePanes(p, 480f, false, true))
        val narrow = p.copy(windowWidth = 1300f)
        // 1300 - 80 - 640 = 580: list shrinks to 280 → conversation 300 < 360, so the list goes.
        val panes = resolvePanes(narrow, 480f, false, true)
        assertEquals(PaneWidths(80f, 0f, 0f, 580f, 640f), panes); assertSums(narrow, panes)
    }

    @Test fun phoneSizedSidePanelCoversTheWindow() = with(policy(800f, 1280f)) {
        assertEquals(PaneWidths(0f, 0f, 0f, 0f, 800f), resolvePanes(this, 300f, false, true))
    }

    @Test fun draggedListIsClamped() = with(policy(1280f, 800f)) {
        assertEquals(480f, resolvePanes(this, 900f, false, false).list)
        assertEquals(280f, resolvePanes(this, 100f, false, false).list)
    }

    @Test fun everyWindowFoldAndStateResolvesToAValidLayout() {
        val widths = (600..2000 step 37).map { it.toFloat() } + listOf(673f, 720f, 780f, 800f, 839f, 840f, 841f, 1199f, 1200f, 1280f, 1599f, 1600f, 1920f, 2000f)
        val heights = listOf(400f, 673f, 1080f)
        val listWidths = listOf(-50f, 0f, 150f, 280f, 340f, 420f, 480f, 900f)
        var checked = 0
        for (w in widths) for (h in heights) {
            val folds = mutableListOf<Fold?>(null)
            for (centre in listOf(.5f, .3f, .7f, .2f, .9f)) for (gap in listOf(0f, 20f, 60f, 200f, 390f)) for (orientation in FoldOrientation.entries)
                for (separating in listOf(false, true)) for (halfOpened in listOf(false, true)) {
                    val axis = if (orientation == FoldOrientation.Vertical) w else h
                    val start = (axis * centre - gap / 2).coerceIn(0f, axis); val end = (start + gap).coerceAtMost(axis)
                    folds += Fold(orientation, start, end, separating, halfOpened)
                }
            for (fold in folds) {
                val p = policy(w, h, fold)
                val splitting = fold != null && fold.orientation == FoldOrientation.Vertical && (fold.separating || fold.halfOpened)
                // A splitting fold in a two-pane window splits it unless a region is under the list minimum (documented fallback).
                val expectSplit = splitting && p.twoPane && fold!!.start >= AdaptiveSpec.ListDragMin && w - fold.end >= AdaptiveSpec.ListDragMin
                assertEquals("split $w x $h $fold", expectSplit, p.splitFold != null)
                for (listWidth in listWidths) for (collapsed in listOf(false, true)) for (sideOpen in listOf(false, true)) {
                    val r = resolvePanes(p, listWidth, collapsed, sideOpen)
                    val ctx = "$w x $h $fold list=$listWidth collapsed=$collapsed side=$sideOpen -> $r"
                    listOf(r.rail, r.list, r.hinge, r.conversation, r.sidePanel).forEach { assertTrue("negative: $ctx", it >= 0f) }
                    assertEquals(ctx, w, r.total, .01f)
                    if (r.list > 0f) assertTrue("list range: $ctx", r.list >= p.minListWidth - .01f && r.list <= p.maxListWidth + .01f)
                    val split = p.splitFold
                    if (split != null) {
                        // Nothing overlaps the hinge: the gap sits exactly on the fold band, between the left and right regions.
                        val hingeStart = r.rail + r.list + if (r.hingeAt == HingeAt.BeforeSidePanel) r.conversation else 0f
                        assertNotEquals(ctx, HingeAt.None, r.hingeAt)
                        assertEquals(ctx, split.start, hingeStart, .01f); assertEquals(ctx, split.width, r.hinge, .01f)
                        assertTrue("list kept: $ctx", sideOpen || r.list > 0f)
                        assertTrue("left region >= list min: $ctx", r.list + r.conversation.takeIf { sideOpen }.orZero() >= AdaptiveSpec.ListDragMin - .01f)
                        // Exception: in a split window the conversation fills its region, which may be under ConversationMinWidth (673 fold: 336.5).
                        continue
                    }
                    assertEquals(ctx, 0f, r.hinge); assertEquals(ctx, HingeAt.None, r.hingeAt)
                    if (r.conversation == 0f) { assertTrue("only a full-window side panel hides the conversation: $ctx", sideOpen && r.sidePanel == w); continue }
                    val arrangementMin = when {
                        !p.twoPane -> 0f
                        r.sidePanel > 0f -> p.railWidth + p.sidePanelMinWidth + AdaptiveSpec.ConversationMinWidth
                        r.list > 0f -> p.railWidth + p.defaultListWidth + AdaptiveSpec.ConversationMinWidth
                        else -> p.railWidth + AdaptiveSpec.ConversationMinWidth
                    }
                    if (w >= arrangementMin) assertTrue("conversation min: $ctx", r.conversation >= AdaptiveSpec.ConversationMinWidth - .01f)
                    // Exception: a window narrower than its arrangement minimum (600-ish with the default list) still never squeezes the
                    // conversation below what the default list leaves.
                    else assertTrue("conversation floor: $ctx", r.conversation >= w - p.railWidth - p.defaultListWidth - .01f)
                    checked++
                }
            }
        }
        assertTrue(checked > 10_000)
    }

    private fun Float?.orZero() = this ?: 0f
}

/* "Load earlier turns" windowing adapted from T3 Code's MessagesTimeline / ThreadFeed history control (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

/** User turns shown when a thread opens, and how many more each "Load earlier turns" tap reveals. */
internal const val TURN_WINDOW_PAGE = 10

/**
 * The visible tail of a timeline. [hiddenCount] entries (covering [hiddenTurns] user turns) precede [visible];
 * the "Load earlier turns" row shows only while [hasEarlier].
 */
internal data class TimelineWindow(val visible: List<ConversationEntry>, val hiddenCount: Int, val hiddenTurns: Int) {
    val hasEarlier: Boolean get() = hiddenCount > 0
}

/**
 * A user turn starts at a user prompt. Consecutive (stacked) prompts belong to the turn the first one opened, so a window
 * never separates them.
 */
internal val ConversationEntry.startsUserTurn: Boolean get() = this is ConversationEntry.Message && message.role == "user" && !stacked

/**
 * Client-side window over an already loaded timeline: the last [visibleTurns] user turns. A window starts at a turn
 * boundary, so it never cuts inside a turn; entries before the first prompt go with the first turn. [keepFrom] is an
 * entry index that must stay visible (the live turn, the "Working for" header): the window grows back to the turn holding
 * it.
 */
internal fun windowConversationTimeline(entries: List<ConversationEntry>, visibleTurns: Int, keepFrom: Int? = null): TimelineWindow {
    val all = TimelineWindow(entries, 0, 0)
    val starts = entries.indices.filter { entries[it].startsUserTurn }
    if (starts.size <= visibleTurns.coerceAtLeast(1)) return all
    var start = starts[starts.size - visibleTurns.coerceAtLeast(1)]
    if (keepFrom != null && keepFrom < start) start = starts.lastOrNull { it <= keepFrom } ?: 0
    val hiddenTurns = starts.count { it < start }
    if (hiddenTurns == 0) return all
    return TimelineWindow(entries.subList(start, entries.size), start, hiddenTurns)
}

/**
 * The earliest entry that belongs to live work: the "Working for" header position, the active run's first entry, or a
 * streaming message. Null when nothing is live.
 */
internal fun liveTimelineIndex(entries: List<ConversationEntry>, activeRunId: String?, headerIndex: Int?): Int? {
    val runStart = activeRunId?.let { run -> entries.indexOfFirst { it.belongsToRun(run) }.takeIf { it >= 0 } }
    val streaming = entries.indexOfFirst { it is ConversationEntry.Message && it.message.streaming }.takeIf { it >= 0 }
    return listOfNotNull(headerIndex, runStart, streaming).minOrNull()
}

private fun ConversationEntry.belongsToRun(runId: String): Boolean = when (this) {
    is ConversationEntry.Message -> message.runId == runId
    is ConversationEntry.Activities -> activities.any { it.runId == runId }
    is ConversationEntry.HtmlRender -> activity.runId == runId
    is ConversationEntry.Work -> this.runId == runId
}

/**
 * Where a list row at [anchorIndex] (below the "Load earlier turns" row) lands after the window grows from [before] to
 * [after]: the revealed entries are inserted above it, and the control row goes away once nothing is hidden. Rows that
 * are not timeline entries (the "Working for" header, trailing live rows) sit inside or after both windows, so they
 * shift by the same amount.
 */
internal fun prependedAnchorIndex(anchorIndex: Int, before: TimelineWindow, after: TimelineWindow): Int =
    anchorIndex + (before.hiddenCount - after.hiddenCount) - (if (before.hasEarlier && !after.hasEarlier) 1 else 0)

/**
 * The "Load earlier turns" window state. [requested] is the window size (initial page, taps, and any widening live work
 * forced) as of when the thread loaded with [seenTurns] turns; every turn that arrives later widens the window by one, so
 * a new prompt never pushes the oldest visible turn out. Null [seenTurns] means the thread has not loaded yet.
 */
internal data class TurnWindowState(val requested: Int = TURN_WINDOW_PAGE, val seenTurns: Int? = null) {
    private fun growth(turnCount: Int) = (turnCount - (seenTurns ?: turnCount)).coerceAtLeast(0)

    fun visibleTurns(turnCount: Int): Int = requested + growth(turnCount)

    /**
     * Records the shown [window] over [turnCount] turns: the first load sets [seenTurns], a rewind lowers it (so later
     * prompts widen from the shorter thread instead of sliding), and turns that live work pulled in become the floor,
     * so the window does not snap back when that work ends.
     */
    fun settled(turnCount: Int, window: TimelineWindow): TurnWindowState {
        val seen = when {
            turnCount <= 0 -> seenTurns // an empty refresh says nothing about the thread
            seenTurns == null -> turnCount
            else -> minOf(seenTurns, turnCount)
        }
        val next = copy(seenTurns = seen)
        val shown = turnCount - window.hiddenTurns
        return next.copy(requested = maxOf(requested, shown - next.growth(turnCount)))
    }

    /** One tap: [TURN_WINDOW_PAGE] more turns than are shown now (which may already exceed [requested]). */
    fun expanded(turnCount: Int, window: TimelineWindow): TurnWindowState =
        settled(turnCount, window).let { it.copy(requested = it.requested + TURN_WINDOW_PAGE) }
}

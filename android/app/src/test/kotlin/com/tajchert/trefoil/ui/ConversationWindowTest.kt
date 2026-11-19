package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationWindowTest {
    /** [turns] turns of prompt, tool work and answer, run `r<n>`; ordinals keep the authoritative order. */
    private fun thread(turns: Int, preamble: Boolean = false, activeLast: Boolean = false): List<ConversationEntry> {
        val messages = mutableListOf<ConversationMessage>()
        val activities = mutableListOf<ToolActivity>()
        if (preamble) activities += ToolActivity("created", "Thread created", kind = "thread_created", ordinal = -1)
        repeat(turns) { i ->
            val live = activeLast && i == turns - 1
            messages += ConversationMessage("u$i", "user", "Prompt $i", runId = "r$i", ordinal = i * 3)
            activities += ToolActivity("t$i", "Command", kind = "command_execution", status = if (live) "running" else "completed", runId = "r$i", ordinal = i * 3 + 1)
            if (!live) messages += ConversationMessage("a$i", "assistant", "Answer $i", runId = "r$i", ordinal = i * 3 + 2)
        }
        return conversationTimeline(messages, activities)
    }

    private fun TimelineWindow.firstPrompt() = (visible.first { it.startsUserTurn } as ConversationEntry.Message).message.id

    @Test fun emptyTimelineHidesNothing() {
        val window = windowConversationTimeline(emptyList(), TURN_WINDOW_PAGE)
        assertFalse(window.hasEarlier)
        assertTrue(window.visible.isEmpty())
    }

    @Test fun exactlyTenTurnsShowEverythingWithoutTheControl() {
        val entries = thread(10, preamble = true)
        val window = windowConversationTimeline(entries, TURN_WINDOW_PAGE)
        assertFalse(window.hasEarlier)
        assertEquals(entries, window.visible)
    }

    @Test fun elevenTurnsHideTheFirstTurnIncludingItsPreamble() {
        val entries = thread(11, preamble = true)
        val window = windowConversationTimeline(entries, TURN_WINDOW_PAGE)
        assertTrue(window.hasEarlier)
        assertEquals(1, window.hiddenTurns)
        assertEquals(4, window.hiddenCount) // thread_created + u0, t0, a0
        assertEquals("u1", window.firstPrompt())
        assertEquals(10, window.visible.count { it.startsUserTurn })
        assertEquals(entries.takeLast(window.visible.size), window.visible)
    }

    @Test fun expandingPagesTenTurnsAtATimeUntilEverythingShows() {
        val entries = thread(25)
        val first = windowConversationTimeline(entries, TURN_WINDOW_PAGE)
        assertEquals(15, first.hiddenTurns)
        assertEquals("u15", first.firstPrompt())
        val second = windowConversationTimeline(entries, TURN_WINDOW_PAGE * 2)
        assertEquals(5, second.hiddenTurns)
        assertEquals("u5", second.firstPrompt())
        val third = windowConversationTimeline(entries, TURN_WINDOW_PAGE * 3)
        assertFalse(third.hasEarlier)
        assertEquals(entries, third.visible)
    }

    @Test fun windowNeverCutsInsideATurnOrBetweenStackedPrompts() {
        val messages = (0 until 12).flatMap { i ->
            listOf(ConversationMessage("u$i", "user", "Prompt $i", ordinal = i * 3), ConversationMessage("a$i", "assistant", "Answer $i", ordinal = i * 3 + 2))
        } + ConversationMessage("u1b", "user", "Also this", ordinal = 4) // stacked after u1
        val entries = conversationTimeline(messages, emptyList())
        val window = windowConversationTimeline(entries, TURN_WINDOW_PAGE)
        assertEquals("u2", window.firstPrompt())
        val wider = windowConversationTimeline(entries, 11)
        assertEquals("u1", wider.firstPrompt())
        assertTrue(wider.visible.any { it.key == "message:u1b" })
        assertTrue(window.visible.first().startsUserTurn)
    }

    @Test fun liveTurnAtTheEndStaysVisible() {
        val entries = thread(12, activeLast = true)
        val header = activeTurnHeaderIndex(entries, "r11")
        val window = windowConversationTimeline(entries, TURN_WINDOW_PAGE, liveTimelineIndex(entries, "r11", header))
        assertEquals(2, window.hiddenTurns)
        assertEquals("activity:t11", window.visible.last().key)
        assertTrue(header!! - window.hiddenCount in 0..window.visible.size)
    }

    @Test fun liveWorkBeforeTheWindowPullsItsTurnBackIn() {
        val entries = thread(15)
        // A run still streaming in turn 3 (for example while later prompts queue) keeps its turn on screen.
        val live = entries.indexOfFirst { it.key == "activity:t3" }
        val window = windowConversationTimeline(entries, TURN_WINDOW_PAGE, keepFrom = live)
        assertEquals("u3", window.firstPrompt())
        assertEquals(3, window.hiddenTurns)
        assertEquals(entries.indexOfFirst { it.key == "message:u3" }, liveTimelineIndex(entries, "r3", null))
        assertNull(liveTimelineIndex(entries, null, null))
    }

    @Test fun anchorShiftsByRevealedEntriesAndLosesTheControlRowWhenAllShow() {
        val entries = thread(25)
        val first = windowConversationTimeline(entries, TURN_WINDOW_PAGE)
        val second = windowConversationTimeline(entries, TURN_WINDOW_PAGE * 2)
        val third = windowConversationTimeline(entries, TURN_WINDOW_PAGE * 3)
        // Row 1 is the first visible entry (row 0 is the control).
        assertEquals(1 + 30, prependedAnchorIndex(1, first, second))
        assertEquals(1 + 15 - 1, prependedAnchorIndex(1, second, third))
    }

    /** Renders [entries] with [state] the way the conversation does, then settles the state on what was shown. */
    private fun show(state: TurnWindowState, entries: List<ConversationEntry>, keepFrom: Int? = null): Pair<TurnWindowState, TimelineWindow> {
        val turns = entries.count { it.startsUserTurn }
        val window = windowConversationTimeline(entries, state.visibleTurns(turns), keepFrom)
        return state.settled(turns, window) to window
    }

    @Test fun newPromptsWidenTheWindowInsteadOfDroppingTheOldestVisibleTurn() {
        assertEquals(TURN_WINDOW_PAGE, TurnWindowState().visibleTurns(0))
        val (opened, first) = show(TurnWindowState(), thread(11))
        assertEquals(11, opened.seenTurns)
        assertEquals("u1", first.firstPrompt())
        // Two prompts arrive: the window widens by two, so u1 stays the oldest visible turn.
        val (_, grown) = show(opened, thread(13))
        assertEquals("u1", grown.firstPrompt())
        assertEquals(1, grown.hiddenTurns)
        // An empty refresh does not reset what the thread loaded with.
        assertEquals(opened, show(opened, emptyList()).first)
    }

    @Test fun tapRevealsAPageBeyondTurnsLiveWorkAlreadyPulledIn() {
        val entries = thread(25)
        val live = entries.indexOfFirst { it.key == "message:u5" } // live run in turn 5: 20 turns shown, not 10
        val (state, window) = show(TurnWindowState(), entries, keepFrom = live)
        assertEquals("u5", window.firstPrompt())
        val tapped = state.expanded(25, window)
        assertEquals(30, tapped.visibleTurns(25))
        assertFalse(windowConversationTimeline(entries, tapped.visibleTurns(25), live).hasEarlier)
    }

    @Test fun windowKeepsTurnsLiveWorkPulledInAfterThatWorkEnds() {
        val entries = thread(25)
        val (state, _) = show(TurnWindowState(), entries, keepFrom = entries.indexOfFirst { it.key == "message:u5" })
        val (_, settled) = show(state, entries, keepFrom = null) // the run finished
        assertEquals("u5", settled.firstPrompt())
    }

    @Test fun rewindThenNewPromptsDoNotSlideTheWindow() {
        val (opened, first) = show(TurnWindowState(), thread(11))
        assertEquals("u1", first.firstPrompt())
        val (rewound, all) = show(opened, thread(8))
        assertEquals(8, rewound.seenTurns)
        assertFalse(all.hasEarlier)
        // Four prompts after the rewind: u0 was visible and stays visible.
        val (_, after) = show(rewound, thread(12))
        assertFalse(after.hasEarlier)
        assertEquals("u0", after.firstPrompt())
    }
}

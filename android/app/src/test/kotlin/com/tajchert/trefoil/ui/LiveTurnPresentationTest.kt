package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.model.ToolActivity
import org.junit.Assert.*
import org.junit.Test

class LiveTurnPresentationTest {
    private fun user(id: String, ordinal: Int, runId: String? = null) = ConversationMessage(id, "user", "prompt", ordinal = ordinal, runId = runId)
    private fun assistant(id: String, ordinal: Int, runId: String, streaming: Boolean = false) = ConversationMessage(id, "assistant", "reply", ordinal = ordinal, runId = runId, streaming = streaming)
    private fun command(id: String, ordinal: Int, runId: String, status: String = "completed") =
        ToolActivity(id, "Run", kind = "command_execution", status = status, input = "./gradlew test", runId = runId, ordinal = ordinal)
    private fun agentItem(id: String, ordinal: Int, runId: String, subagentId: String?, status: String = "running") =
        ToolActivity(id, "Subagent", kind = "subagent", status = status, runId = runId, ordinal = ordinal, subagentId = subagentId)
    private fun agent(id: String, status: String, runId: String = "r2") = Subagent(id, "thread", runId = runId, status = status, driver = "codex", title = "Agent $id")
    private fun timeline(messages: List<ConversationMessage>, activities: List<ToolActivity>, activeRunId: String? = null, runs: List<ConversationRun> = emptyList()) =
        foldConversationTimeline(conversationTimeline(messages, activities), activeRunId, runs)

    @Test fun workingHeaderGoesRightAfterTheLatestPromptOrAtTheTop() {
        val settled = listOf(ConversationRun("r1", "completed", startedAt = "2026-01-01T10:00:00Z", completedAt = "2026-01-01T10:01:00Z"))
        val entries = timeline(listOf(user("u1", 1, "r1"), assistant("a1", 3, "r1"), user("u2", 4, "r2"), assistant("a2", 6, "r2")),
            listOf(command("c1", 2, "r1"), command("c2", 5, "r2", "running")), activeRunId = "r2", runs = settled)
        val index = activeTurnHeaderIndex(entries, "r2")!!
        assertEquals("message:u2", entries[index - 1].key)
        assertEquals("activity:c2", entries[index].key)
        assertNull(activeTurnHeaderIndex(entries, null))
        assertEquals(0, activeTurnHeaderIndex(timeline(emptyList(), listOf(command("c", 1, "r2"))), "r2"))
        assertEquals(0, activeTurnHeaderIndex(emptyList(), "r2"))
    }

    @Test fun headerKeyMatchesTheFoldKeySoASettlingTurnSwapsInPlace() {
        val entries = timeline(listOf(user("u", 1, "r"), assistant("a", 3, "r")), listOf(command("c", 2, "r")),
            runs = listOf(ConversationRun("r", "completed", startedAt = "2026-01-01T10:00:00Z", completedAt = "2026-01-01T10:00:30Z")))
        val fold = entries.single { it is ConversationEntry.Work }
        assertEquals(workingHeaderKey("r"), fold.key)
        assertEquals(1, entries.indexOf(fold))
    }

    @Test fun thinkingShowsOnlyWhileActiveIdleAndNotAlreadyShowingLiveWork() {
        val idleTail = timeline(listOf(user("u", 1, "r"), assistant("a", 3, "r")), listOf(command("c", 2, "r")), activeRunId = "r")
        assertEquals("Thinking", thinkingRowLabel(idleTail, "r", emptyList(), streaming = false, hasRequests = false))
        assertNull(thinkingRowLabel(idleTail, null, emptyList(), streaming = false, hasRequests = false))
        assertNull(thinkingRowLabel(idleTail, "r", emptyList(), streaming = true, hasRequests = false))
        assertNull(thinkingRowLabel(idleTail, "r", emptyList(), streaming = false, hasRequests = true))
        val liveTail = timeline(listOf(user("u", 1, "r")), listOf(command("c", 2, "r", "running")), activeRunId = "r")
        assertNull(thinkingRowLabel(liveTail, "r", emptyList(), streaming = false, hasRequests = false))
        // A running item of another (stale) run is not a live row of this turn.
        val staleTail = timeline(listOf(user("u", 1, "r")), listOf(command("c", 2, "old", "running")), activeRunId = "r")
        assertEquals("Thinking", thinkingRowLabel(staleTail, "r", emptyList(), streaming = false, hasRequests = false))
        // Reasoning without text is not rendered, so it is not a live row either.
        val blankThought = timeline(listOf(user("u", 1, "r")), listOf(ToolActivity("t", "Reasoning", kind = "reasoning", status = "running", runId = "r", ordinal = 2)), activeRunId = "r")
        assertEquals("Thinking", thinkingRowLabel(blankThought, "r", emptyList(), streaming = false, hasRequests = false))
        assertEquals("Thinking", thinkingRowLabel(emptyList(), "r", emptyList(), streaming = false, hasRequests = false))
    }

    @Test fun liveSubagentsAtTheRunTailSayWaiting() {
        val agents = listOf(agent("s1", "running"), agent("s2", "running"), agent("s3", "completed"))
        val tail = timeline(listOf(user("u", 1, "r2")), listOf(agentItem("i1", 2, "r2", "s1"), agentItem("i2", 3, "r2", "s2"), agentItem("i3", 4, "r2", "s3")), activeRunId = "r2")
        assertEquals("Waiting on 2 subagents", thinkingRowLabel(tail, "r2", agents, streaming = false, hasRequests = false))
        val one = timeline(listOf(user("u", 1, "r2")), listOf(agentItem("i1", 2, "r2", "s1")), activeRunId = "r2")
        assertEquals("Waiting on a subagent", thinkingRowLabel(one, "r2", agents, streaming = false, hasRequests = false))
        // The live record wins over the item's own status; a finished agent leaves the turn thinking.
        assertEquals("Thinking", thinkingRowLabel(one, "r2", listOf(agent("s1", "completed")), streaming = false, hasRequests = false))
        val afterWork = timeline(listOf(user("u", 1, "r2")), listOf(agentItem("i1", 2, "r2", "s1"), command("c", 3, "r2")), activeRunId = "r2")
        assertEquals("Thinking", thinkingRowLabel(afterWork, "r2", agents, streaming = false, hasRequests = false))
    }

    @Test fun subagentItemsLinkToTheirRecordOrFallBackToTheItem() {
        val record = agent("s1", "running")
        assertSame(record, subagentForActivity(agentItem("i", 1, "r2", "s1"), listOf(record)))
        val fallback = subagentForActivity(agentItem("i", 1, "r2", "missing", status = "completed").copy(title = "Explore code", startedAt = "2026-01-01T10:00:00Z"), emptyList())
        assertEquals("missing", fallback.id)
        assertEquals("Explore code", subagentRowTitle(fallback))
        assertEquals("completed", fallback.status)
        assertEquals("Subagent", subagentRowTitle(subagentForActivity(agentItem("i", 1, "r2", null).copy(title = "subagent"), emptyList())))
        // Follow-up items for the same agent render one row.
        assertEquals(listOf("s1"), subagentsForGroup(listOf(agentItem("a", 1, "r2", "s1"), agentItem("b", 2, "r2", "s1")), listOf(record)).map { it.id })
    }

    @Test fun rosterAgentsWithoutInlineItemsBecomeOrphanRows() {
        val agents = listOf(agent("inline", "running"), agent("roster", "running"), agent("done", "completed"))
        val roster = subagentRoster(listOf(ConversationRun("r2", "running", startedAt = "2026-01-01T10:00:00Z")), agents)
        val entries = timeline(listOf(user("u", 1, "r2")), listOf(agentItem("i", 2, "r2", "inline")), activeRunId = "r2")
        assertEquals(setOf("roster", "done"), orphanSubagents(entries, roster, turnActive = true).map { it.id }.toSet())
        assertEquals(listOf("roster"), orphanSubagents(entries, roster, turnActive = false).map { it.id })
        assertTrue(orphanSubagents(entries, null, turnActive = true).isEmpty())
    }

    @Test fun subagentRowShowsActivityStatusAndElapsedLikeT3() {
        val now = java.time.Instant.parse("2026-01-01T10:00:43Z").toEpochMilli()
        val running = subagentTimelineRow(agent("s", "running").copy(progress = "Running cd /repo && ./gradlew lint", startedAt = "2026-01-01T10:00:00Z", childThreadId = "child"), now)
        assertEquals("Running cd /repo && ./gradlew lint", running.detail)
        assertEquals("Running", running.statusLabel)
        assertTrue(running.showStatus)
        assertEquals("43s", running.elapsed)
        assertEquals("child", running.openThreadId)
        assertEquals(SubagentTone.Working, running.tone)
        val quiet = subagentTimelineRow(agent("s", "pending"), now)
        assertNull(quiet.detail)
        assertFalse(quiet.showStatus) // the status fills the second line instead
        assertEquals("Queued", quiet.statusLabel)
        assertNull(quiet.elapsed)
        assertNull(quiet.openThreadId)
        val failed = subagentTimelineRow(agent("s", "failed").copy(result = "Build failed", startedAt = "2026-01-01T10:00:00Z", completedAt = "2026-01-01T10:02:05Z"), now)
        assertTrue(failed.failed)
        assertEquals("Build failed", failed.detail)
        assertEquals("2m 05s", failed.elapsed)
        assertEquals("Stopped", subagentTimelineStatusLabel("interrupted"))
        assertEquals("Idle · resumable", subagentTimelineStatusLabel("idle"))
    }

    @Test fun timersFollowT3Formats() {
        assertEquals("0s", workingTimerLabel(-5))
        assertEquals("59s", workingTimerLabel(59_999))
        assertEquals("1m 11s", workingTimerLabel(71_400))
        assertEquals("1h 2m 3s", workingTimerLabel(3_723_000))
        assertEquals("43s", formatAgentElapsed(43_900))
        assertEquals("2m 05s", formatAgentElapsed(125_000))
        assertEquals("1h 02m", formatAgentElapsed(3_723_000))
    }

    @Test fun layoutCombinesHeaderOrphansAndThinking() {
        val agents = listOf(agent("roster", "running"))
        val roster = subagentRoster(listOf(ConversationRun("r2", "running")), agents)
        val entries = timeline(listOf(user("u", 1, "r2")), emptyList(), activeRunId = "r2")
        val layout = liveTurnLayout(entries, "r2", roster, agents, streaming = false, hasRequests = false)
        assertEquals(1, layout.headerIndex)
        assertEquals(listOf("roster"), layout.orphanSubagents.map { it.id })
        assertEquals("Thinking", layout.thinkingLabel)
        val idle = liveTurnLayout(entries, null, null, emptyList(), streaming = false, hasRequests = false)
        assertEquals(LiveTurnLayout(null, emptyList(), null), idle)
    }
}

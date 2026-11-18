package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.PendingBackgroundTask
import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkPresentationTest {
    private fun subagent(id: String, description: String? = null, child: String? = null) = PendingBackgroundTask(id, "subagent", description, child)

    @Test fun titlesFollowT3() {
        assertNull(presentPendingBackgroundWork(emptyList()))
        assertEquals("Waiting on a subagent", presentPendingBackgroundWork(listOf(subagent("a")))!!.title)
        assertEquals("Waiting · Stream A", presentPendingBackgroundWork(listOf(subagent("a", "Subagent: Stream A")))!!.title)
        assertEquals("Running a command", presentPendingBackgroundWork(listOf(PendingBackgroundTask("c", "command")))!!.title)
        assertEquals("Waiting · npm test", presentPendingBackgroundWork(listOf(PendingBackgroundTask("c", "command", "npm test", awaited = true)))!!.title)
        val mixed = presentPendingBackgroundWork(listOf(PendingBackgroundTask("c1", "command", "a"), subagent("s1", "Stream A", "child-a"),
            PendingBackgroundTask("c2", "command", "b"), subagent("s2"), subagent("s3"), PendingBackgroundTask("x", "unknown")))!!
        assertEquals("Waiting on 3 subagents, 2 commands and 1 background task", mixed.title)
        assertTrue(mixed.waiting)
        assertEquals(listOf("s1", "s2", "s3", "c1", "c2", "x"), mixed.items.map { it.taskId })
        assertEquals(BackgroundWorkItemUi("s1", "subagent", "Stream A", "child-a"), mixed.items[0])
        assertEquals("subagent", mixed.items[1].label)
        val running = presentPendingBackgroundWork(listOf(PendingBackgroundTask("c", "command"), PendingBackgroundTask("m", "monitor")))!!
        assertEquals("Running 1 command and 1 monitor", running.title)
        assertFalse(running.waiting)
    }

    @Test fun statusShowsOnlyWithoutALiveThinkingRowOrRequest() {
        val tasks = listOf(subagent("a", "Stream A"), subagent("b", "Stream B"))
        assertEquals("Waiting on 2 subagents", backgroundWorkStatus(tasks, thinkingLabel = null, hasRequests = false)!!.title)
        assertEquals("Waiting on 2 subagents: Stream A, Stream B", backgroundWorkStatus(tasks, null, false)!!.accessibilityLabel)
        assertNull(backgroundWorkStatus(tasks, thinkingLabel = "Thinking", hasRequests = false))
        assertNull(backgroundWorkStatus(tasks, thinkingLabel = null, hasRequests = true))
        assertNull(backgroundWorkStatus(emptyList(), null, false))
    }

    @Test fun endedTurnShowsNoThinkingRow() {
        assertEquals("Thinking", thinkingRowLabel(emptyList(), "r", emptyList(), streaming = false, hasRequests = false))
        assertNull(thinkingRowLabel(emptyList(), "r", emptyList(), streaming = false, hasRequests = false, turnEnded = true))
    }
}

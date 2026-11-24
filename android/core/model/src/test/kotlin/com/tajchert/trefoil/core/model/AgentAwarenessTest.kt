package com.tajchert.trefoil.core.model

import com.tajchert.trefoil.core.model.AgentAlertKind.Approval
import com.tajchert.trefoil.core.model.AgentAlertKind.Failed
import com.tajchert.trefoil.core.model.AgentAlertKind.Finished
import com.tajchert.trefoil.core.model.AgentAlertKind.Input
import com.tajchert.trefoil.core.model.AwarenessPhase.Completed
import com.tajchert.trefoil.core.model.AwarenessPhase.Running
import com.tajchert.trefoil.core.model.AwarenessPhase.Starting
import com.tajchert.trefoil.core.model.AwarenessPhase.WaitingForApproval
import com.tajchert.trefoil.core.model.AwarenessPhase.WaitingForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAwarenessTest {
    private fun thread(id: String = "t", status: String = "idle", run: String? = null) =
        ThreadSummary(id, "p", "Title $id", status = status, activityRunStatus = run)

    private fun child(id: String, parent: String, kind: String? = "approval", updatedAt: String? = null) = ThreadSummary(
        id, "p", "child", status = "running", activityRunStatus = "running", parentThreadId = parent,
        relationshipToParent = "subagent", creationSource = "mcp", pendingRequestKind = kind, updatedAt = updatedAt,
    )

    private fun phase(row: ThreadSummary, others: List<ThreadSummary> = emptyList()) = threadAwareness(row, listOf(row) + others, 0)

    @Test fun phaseFromRunStatus() {
        val cases = listOf(
            "preparing" to Starting, "starting" to Starting, "running" to Running, "waiting" to Running,
            "completed" to Completed, "failed" to AwarenessPhase.Failed,
            "idle" to null, "queued" to null, "interrupted" to null, "cancelled" to null, "rolled_back" to null, "brand_new" to null,
        )
        for ((status, expected) in cases) {
            assertEquals(status, expected, phase(thread(status = status)))
            // activityRunStatus wins over status.
            assertEquals(status, expected, phase(thread(status = "idle", run = status)))
        }
        assertEquals(Running, phase(thread(status = "completed", run = "running")))
    }

    @Test fun ownRequestOutranksRunStatus() {
        val base = thread(status = "completed")
        assertEquals(WaitingForInput, phase(base.copy(pendingRequestKind = "user_input")))
        assertEquals(WaitingForApproval, phase(base.copy(pendingRequestKind = "command_approval")))
        assertEquals(WaitingForApproval, phase(base.copy(pendingRequestKind = "anything_new")))
        assertEquals(Completed, phase(base.copy(pendingRequestKind = "auth_refresh")))
    }

    @Test fun unlistedArchivedAndDeletedThreadsHaveNoPhase() {
        val base = thread(status = "running")
        listOf(
            base.copy(relationshipToParent = "subagent"), base.copy(sideChat = true), base.copy(workflowStep = true),
            base.copy(archivedAt = "2026-11-01T00:00:00Z"), base.copy(archived = true), base.copy(deletedAt = "2026-11-01T00:00:00Z"),
        ).forEach { assertNull(it.toString(), phase(it)) }
        assertEquals(Running, phase(base.copy(relationshipToParent = "fork")))
    }

    @Test fun backgroundWorkAndMonitorWakeHoldCompletion() {
        val done = thread(status = "completed")
        assertEquals(Running, phase(done.copy(completionHeldBackgroundWork = true)))
        assertEquals(Running, phase(done.copy(monitorHoldsWakeCompletion = true)))
        assertEquals(Completed, phase(done.copy(latestRunBackgroundWake = true)))
    }

    @Test fun stopRequestedCompletionIsSilent() {
        assertNull(phase(thread(status = "completed").copy(latestRunStopRequested = true)))
        assertEquals(AwarenessPhase.Failed, phase(thread(status = "failed").copy(latestRunStopRequested = true)))
    }

    @Test fun settledOverrideHidesTerminalAndMonitorHeldWorkButNotBackgroundHeldOrLiveWork() {
        fun settled(row: ThreadSummary) = phase(row.copy(settledOverride = "settled"))
        assertNull(settled(thread(status = "completed")))
        assertNull(settled(thread(status = "failed")))
        assertNull(settled(thread(status = "completed").copy(monitorHoldsWakeCompletion = true)))
        assertEquals(Running, settled(thread(status = "completed").copy(completionHeldBackgroundWork = true)))
        assertEquals(Running, settled(thread(status = "running")))
        assertEquals(WaitingForApproval, settled(thread(status = "completed").copy(pendingRequestKind = "approval")))
        assertEquals(Completed, phase(thread(status = "completed").copy(settledOverride = "unsettled")))
    }

    @Test fun snoozedCompletionIsSilentButFailureAndRequestsAreNot() {
        val snoozed = thread(status = "completed").copy(
            snoozedAt = "2026-11-24T10:00:00Z", snoozedUntil = "2026-11-24T12:00:00Z", latestRunCompletedAt = "2026-11-24T11:00:00Z",
        )
        assertNull(phase(snoozed))
        assertNull(phase(snoozed.copy(snoozedAt = null)))
        // Completed before the snooze was set, after it expired, or without a completion time: still news.
        assertEquals(Completed, phase(snoozed.copy(latestRunCompletedAt = "2026-11-24T09:00:00Z")))
        assertEquals(Completed, phase(snoozed.copy(latestRunCompletedAt = "2026-11-24T12:00:00Z")))
        assertEquals(Completed, phase(snoozed.copy(latestRunCompletedAt = null)))
        assertEquals(Completed, phase(snoozed.copy(snoozedUntil = "not a date")))
        // A waiting guest message wakes it.
        assertEquals(Completed, phase(snoozed.copy(pendingGuestMessageCount = 1)))
        assertEquals(AwarenessPhase.Failed, phase(snoozed.copy(status = "failed")))
        assertEquals(WaitingForApproval, phase(snoozed.copy(pendingRequestKind = "approval")))
        assertEquals(WaitingForInput, phase(snoozed.copy(pendingRequestKind = "user_input", pendingRequestCreatedAt = "2026-11-24T09:00:00Z")))
    }

    @Test fun waitingChildMapsToTopLevelAncestor() {
        val parent = thread("parent", status = "completed")
        assertEquals(WaitingForApproval, phase(parent, listOf(child("c", "parent"))))
        // Through a side chat and a nested subagent.
        val side = ThreadSummary("side", "p", "side", parentThreadId = "parent", sideChat = true)
        assertEquals(WaitingForApproval, phase(parent, listOf(side, child("mid", "side", kind = null), child("leaf", "mid"))))
        // The child itself is never listed, and an orphan or a cycle maps nowhere.
        assertNull(phase(child("c", "parent"), listOf(parent)))
        assertEquals(Completed, phase(parent, listOf(child("orphan", "missing"))))
        assertEquals(Completed, phase(parent, listOf(child("a", "b", kind = null), child("b", "a"))))
    }

    @Test fun childRequestEligibilityFollowsSubagentAwaitsUser() {
        val parent = thread("parent", status = "running")
        val base = child("c", "parent")
        val cases = listOf(
            base to WaitingForApproval,
            base.copy(pendingRequestKind = "auth_refresh") to Running,
            base.copy(pendingRequestKind = "user_input") to Running,
            base.copy(pendingRequestKind = "user_input", pendingRequestUserOnly = true) to WaitingForInput,
            base.copy(creationSource = "provider") to Running,
            base.copy(activityRunStatus = null) to Running,
            base.copy(archivedAt = "2026-11-01T00:00:00Z") to Running,
            base.copy(relationshipToParent = null, sideChat = true) to Running,
            base.copy(pendingRequestKind = null) to Running,
        )
        cases.forEach { (row, expected) -> assertEquals(row.toString(), expected, phase(parent, listOf(row))) }
        // Own request outranks the child's.
        assertEquals(WaitingForInput, phase(parent.copy(pendingRequestKind = "user_input"), listOf(base)))
    }

    @Test fun newestWaitingChildDecides() {
        val parent = thread("parent", status = "running")
        val approval = child("a", "parent", updatedAt = "2026-11-24T10:00:00Z")
        val input = child("b", "parent", updatedAt = "2026-11-24T11:00:00Z").copy(pendingRequestKind = "user_input", pendingRequestUserOnly = true)
        assertEquals(WaitingForInput, phase(parent, listOf(approval, input)))
        assertEquals(WaitingForApproval, phase(parent, listOf(approval.copy(updatedAt = "2026-11-24T12:00:00Z"), input)))
    }

    // --- agentAlertStep ---

    private val env = "env"

    /** Runs observations in order, each a list of rows at a time, returning every step. */
    private fun run(vararg observations: Pair<Long, List<ThreadSummary>>, start: AgentAlertTrack = baseline(emptyList())): List<AgentAlertStep> {
        var track = start
        return observations.map { (now, rows) -> agentAlertStep(track, env, connected = true, fresh = true, threads = rows, now = now).also { track = it.track } }
    }

    private fun baseline(rows: List<ThreadSummary>) = agentAlertStep(AgentAlertTrack(), env, true, true, rows, 0).track

    private fun AgentAlertStep.kinds() = alerts.map { it.threadId to it.kind }

    @Test fun firstFreshSnapshotIsAdoptedSilently() {
        val rows = listOf(thread("a", "running"), thread("b").copy(pendingRequestKind = "approval"), thread("c", "completed"))
        val step = agentAlertStep(AgentAlertTrack(), env, connected = true, fresh = true, threads = rows, now = 0)
        assertEquals(emptyList<AgentAlert>(), step.alerts)
        assertEquals(mapOf("a" to Running, "b" to WaitingForApproval, "c" to Completed), step.track.phases)
        assertEquals(setOf("a"), step.track.worked)
        assertEquals(env, step.track.environmentId)
        // Already waiting at adoption does not alert later either.
        assertEquals(emptyList<AgentAlert>(), agentAlertStep(step.track, env, true, true, rows, 1).alerts)
    }

    @Test fun staleSnapshotBeforeAdoptionDoesNothing() {
        val track = AgentAlertTrack(env)
        val step = agentAlertStep(track, env, connected = true, fresh = false, threads = listOf(thread("a").copy(pendingRequestKind = "approval")), now = 0)
        assertEquals(AgentAlertStep(track), step)
    }

    @Test fun moveIntoAttentionAlertsOnce() {
        val idle = thread("a")
        val approval = idle.copy(pendingRequestKind = "approval")
        val input = idle.copy(pendingRequestKind = "user_input")
        val steps = run(1L to listOf(approval), 2L to listOf(input), 3L to listOf(approval), 4L to listOf(idle), 5L to listOf(input))
        assertEquals(listOf("a" to Approval), steps[0].kinds())
        assertEquals(AgentAlert(env, "a", Approval, "Title a", "p"), steps[0].alerts.single())
        assertEquals(emptyList<Any>(), steps[1].kinds()); assertEquals(emptySet<String>(), steps[1].clear)
        assertEquals(emptyList<Any>(), steps[2].kinds())
        assertEquals(setOf("a"), steps[3].clear)
        assertEquals(listOf("a" to Input), steps[4].kinds())
    }

    @Test fun childRequestAlertsOnParent() {
        val parent = thread("parent", "running")
        val steps = run(1L to listOf(parent), 2L to listOf(parent, child("c", "parent")), 3L to listOf(parent, child("c", "parent", kind = null)))
        assertEquals(listOf("parent" to Approval), steps[1].kinds())
        assertEquals(setOf("parent"), steps[2].clear)
    }

    @Test fun completionWaitsForConfirmDelay() {
        val running = thread("a", "running"); val done = thread("a", "completed")
        val steps = run(1_000L to listOf(running), 2_000L to listOf(done), 4_000L to listOf(done), 7_000L to listOf(done), 8_000L to listOf(done))
        assertEquals(emptyList<Any>(), steps[1].kinds()); assertEquals(7_000L, steps[1].recheckAtMillis)
        assertEquals(mapOf("a" to 2_000L), steps[1].track.pendingCompletions)
        assertEquals(7_000L, steps[2].recheckAtMillis)
        assertEquals(listOf("a" to Finished), steps[3].kinds()); assertNull(steps[3].recheckAtMillis)
        assertTrue("a" !in steps[3].track.worked)
        assertEquals(emptyList<Any>(), steps[4].kinds())
    }

    @Test fun completionThatResumesBeforeConfirmIsDropped() {
        val running = thread("a", "running"); val done = thread("a", "completed")
        val steps = run(0L to listOf(running), 1_000L to listOf(done), 2_000L to listOf(running), 7_000L to listOf(running), 8_000L to listOf(done), 13_000L to listOf(done))
        assertEquals(emptyMap<String, Long>(), steps[2].track.pendingCompletions)
        assertNull(steps[3].recheckAtMillis)
        assertEquals(13_000L, steps[4].recheckAtMillis)
        assertEquals(listOf("a" to Finished), steps[5].kinds())
    }

    @Test fun terminalAlertsNeedWorkSinceBaseline() {
        // Completed or failed without being seen working stays silent.
        val steps = run(0L to listOf(thread("a", "idle"), thread("b", "idle")), 1L to listOf(thread("a", "completed"), thread("b", "failed")), 10_000L to listOf(thread("a", "completed"), thread("b", "failed")))
        assertEquals(emptyList<Any>(), steps[1].kinds() + steps[2].kinds())
    }

    @Test fun failureAlertsImmediatelyAndOnce() {
        val steps = run(0L to listOf(thread("a", "starting")), 1L to listOf(thread("a", "failed")), 2L to listOf(thread("a", "failed")), 10_000L to listOf(thread("a", "completed")))
        assertEquals(listOf("a" to Failed), steps[1].kinds())
        assertEquals(emptyList<Any>(), steps[2].kinds()); assertEquals(emptySet<String>(), steps[2].clear)
        // Failed -> Completed without new work: the failure clears, no Finished.
        assertEquals(emptyList<Any>(), steps[3].kinds()); assertEquals(setOf("a"), steps[3].clear)
    }

    @Test fun failureDuringUnconfirmedCompletionAlertsFailed() {
        val steps = run(0L to listOf(thread("a", "running")), 1L to listOf(thread("a", "completed")), 2L to listOf(thread("a", "failed")))
        assertEquals(listOf("a" to Failed), steps[2].kinds())
        assertEquals(emptyMap<String, Long>(), steps[2].track.pendingCompletions)
    }

    @Test fun newRunClearsFinishedAndAlertsAgainLater() {
        val running = thread("a", "running"); val done = thread("a", "completed")
        val steps = run(0L to listOf(running), 1L to listOf(done), 6_000L to listOf(done), 7_000L to listOf(running), 8_000L to listOf(done), 13_000L to listOf(done))
        assertEquals(listOf("a" to Finished), steps[2].kinds())
        assertEquals(setOf("a"), steps[3].clear)
        assertEquals(listOf("a" to Finished), steps[5].kinds())
    }

    @Test fun newAlertReplacesOldWithoutClear() {
        val running = thread("a", "running"); val done = thread("a", "completed")
        val steps = run(0L to listOf(running), 1L to listOf(done), 6_000L to listOf(done), 7_000L to listOf(done.copy(pendingRequestKind = "approval")))
        assertEquals(listOf("a" to Approval), steps[3].kinds())
        assertEquals(emptySet<String>(), steps[3].clear)
        assertEquals(mapOf("a" to Approval), steps[3].track.alerted)
    }

    @Test fun alertClearsWhenThreadIsArchivedSettledOrGone() {
        val waiting = thread("a").copy(pendingRequestKind = "approval")
        val failed = thread("b", "failed")
        val start = run(0L to listOf(waiting.copy(pendingRequestKind = null), thread("b", "running")), 1L to listOf(waiting, failed)).last()
        assertEquals(mapOf("a" to Approval, "b" to Failed), start.track.alerted)
        fun next(rows: List<ThreadSummary>) = agentAlertStep(start.track, env, true, true, rows, 2)
        assertEquals(setOf("a"), next(listOf(waiting.copy(archivedAt = "2026-11-24T00:00:00Z"), failed)).clear)
        assertEquals(setOf("b"), next(listOf(waiting, failed.copy(settledOverride = "settled"))).clear)
        assertEquals(setOf("a", "b"), next(emptyList()).clear)
    }

    @Test fun disconnectOrEnvironmentSwitchResetsAndClears() {
        val waiting = thread("a").copy(pendingRequestKind = "approval")
        val alerted = run(0L to listOf(thread("a")), 1L to listOf(waiting)).last().track
        val offline = agentAlertStep(alerted, env, connected = false, fresh = true, threads = listOf(waiting), now = 2)
        assertEquals(AgentAlertTrack(env), offline.track); assertEquals(setOf("a"), offline.clear); assertEquals(emptyList<AgentAlert>(), offline.alerts)
        // The reconnect's stale cache does nothing; its fresh snapshot is adopted silently, even if a thread is waiting.
        assertEquals(AgentAlertStep(AgentAlertTrack(env)), agentAlertStep(offline.track, env, true, false, listOf(waiting), 3))
        val readopted = agentAlertStep(offline.track, env, true, true, listOf(waiting), 4)
        assertEquals(emptyList<AgentAlert>(), readopted.alerts); assertEquals(mapOf("a" to WaitingForApproval), readopted.track.phases)

        val switched = agentAlertStep(alerted, "other", connected = true, fresh = false, threads = listOf(waiting), now = 2)
        assertEquals(AgentAlertTrack("other"), switched.track); assertEquals(setOf("a"), switched.clear)
        val switchedFresh = agentAlertStep(alerted, "other", connected = true, fresh = true, threads = listOf(waiting), now = 2)
        assertEquals(emptyList<AgentAlert>(), switchedFresh.alerts); assertEquals(setOf("a"), switchedFresh.clear)
        assertEquals(mapOf("a" to WaitingForApproval), switchedFresh.track.phases)
        assertEquals(AgentAlertTrack(null), agentAlertStep(alerted, null, true, true, listOf(waiting), 2).track)
    }

    @Test fun alertsFollowThreadListOrderAndRecheckIsEarliestDue() {
        val rows0 = listOf(thread("x", "running"), thread("y"), thread("z", "running"))
        val rows1 = listOf(thread("z", "completed"), thread("y").copy(pendingRequestKind = "user_input"), thread("x", "failed"))
        val rows2 = listOf(thread("z", "completed"), thread("y").copy(pendingRequestKind = "user_input"), thread("x", "failed"), thread("w", "running"))
        val steps = run(0L to rows0, 1_000L to rows1, 2_000L to rows2)
        assertEquals(listOf("y" to Input, "x" to Failed), steps[1].kinds())
        assertEquals(6_000L, steps[1].recheckAtMillis)
        assertEquals(6_000L, steps[2].recheckAtMillis)
    }

    @Test fun unknownStatusesAndUnlistedRowsAreTolerated() {
        val rows = listOf(thread("a", "warp_speed"), child("c", "a", kind = null).copy(status = "?"), thread("a", "running"))
        val step = run(0L to rows).single()
        assertEquals(emptyList<AgentAlert>(), step.alerts)
        assertEquals(mapOf<String, AwarenessPhase>(), step.track.phases)
    }
}

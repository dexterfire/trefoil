package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MachineWorkingCountTest {
    private fun thread(id: String, status: String) = ThreadSummary(id, "p", id, status)

    @Test fun idleThreadsWithPastRunsDoNotCount() {
        val threads = (1..104).map { thread("t$it", "idle").copy(latestRunId = "run-$it", activeProviderThreadId = "prov-$it") }
        assertEquals(0, machineWorkingCount(threads))
    }

    @Test fun finishedArchivedUnknownAndAwaitingThreadsDoNotCount() {
        val threads = listOf(
            thread("done", "completed"),
            thread("failed", "failed"),
            thread("archived", "running").copy(archived = true),
            thread("archivedAt", "running").copy(archivedAt = "2026-11-17T10:00:00Z"),
            thread("unknown", "something-new"),
            thread("approval", "running").copy(pendingRequestKind = "approval"),
        )
        assertEquals(0, machineWorkingCount(threads))
    }

    @Test fun activelyRunningThreadsCount() {
        val threads = listOf(
            thread("run", "running"),
            thread("start", "starting"),
            thread("activity", "idle").copy(activityRunStatus = "running"),
            thread("idle", "idle"),
        )
        assertEquals(3, machineWorkingCount(threads))
    }
}

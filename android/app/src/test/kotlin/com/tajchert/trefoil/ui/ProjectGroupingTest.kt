package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGroupingTest {

    private fun thread(
        id: String,
        projectId: String,
        title: String = id,
        createdAt: String? = "2026-01-01T00:00:00Z",
        pendingRequestKind: String? = null,
        status: String = "idle",
        latestRunId: String? = null,
        latestRunCompletedAt: String? = null,
        latestUserAuthoredMessageAt: String? = null,
    ) = ThreadSummary(
        id = id, projectId = projectId, title = title, status = status,
        createdAt = createdAt, pendingRequestKind = pendingRequestKind,
        latestRunId = latestRunId, latestRunCompletedAt = latestRunCompletedAt,
        latestUserAuthoredMessageAt = latestUserAuthoredMessageAt,
    )

    private val projects = listOf(
        Project("a", "Alpha", "/a"),
        Project("b", "Beta", "/b"),
        Project("c", "Gamma", "/c"),
    )

    @Test fun groupsThreadsByTheirProject() {
        val groups = projectGroups(
            listOf(thread("t1", "a"), thread("t2", "a"), thread("t3", "b")),
            projects, otherOrder = ProjectOrder.Server,
        )
        assertEquals(listOf("a", "b"), groups.map { it.key })
        assertEquals(listOf("t1", "t2"), groups[0].threads.map { it.id })
        assertEquals(listOf("t3"), groups[1].threads.map { it.id })
    }

    @Test fun dropsProjectsWithNoThreads() {
        val groups = projectGroups(listOf(thread("t1", "b")), projects, otherOrder = ProjectOrder.Server)
        assertEquals(listOf("b"), groups.map { it.key })
    }

    @Test fun threadsOfRemovedProjectsLandInTheOrphanBucket() {
        val groups = projectGroups(listOf(thread("t1", "a"), thread("gone", "deleted-project")), projects, otherOrder = ProjectOrder.Server)
        assertEquals(listOf("a", NoProjectKey), groups.map { it.key })
        assertEquals(listOf("gone"), groups.last().threads.map { it.id })
        assertNull(groups.last().project)
        assertEquals(NoProjectTitle, groups.last().title)
    }

    @Test fun orphanBucketIsOmittedWhenEveryThreadHasAProject() {
        val groups = projectGroups(listOf(thread("t1", "a")), projects, otherOrder = ProjectOrder.Server)
        assertTrue(groups.none { it.key == NoProjectKey })
    }

    @Test fun orphanBucketAlwaysSortsLast() {
        val groups = projectGroups(
            listOf(thread("t1", "c"), thread("t2", "a"), thread("orphan", "gone")),
            projects, otherOrder = ProjectOrder.Alphabetical,
        )
        assertEquals(NoProjectKey, groups.last().key)
    }

    @Test fun pinnedProjectsComeFirstInTheirOwnBand() {
        val groups = projectGroups(
            listOf(thread("t1", "a"), thread("t2", "b"), thread("t3", "c")),
            projects, pinned = setOf("c", "b"),
            pinnedOrder = ProjectOrder.Alphabetical, otherOrder = ProjectOrder.Alphabetical,
        )
        assertEquals(listOf("b", "c", "a"), groups.map { it.key })
    }

    @Test fun pinnedAndUnpinnedBandsTakeSeparateOrderRules() {
        val threads = listOf(
            thread("za", "a", latestRunCompletedAt = "2026-01-01T03:00:00Z"),
            thread("pc", "c", latestRunCompletedAt = "2026-01-01T01:00:00Z"),
        )
        val groups = projectGroups(
            threads, projects, pinned = setOf("a"),
            pinnedOrder = ProjectOrder.Alphabetical, otherOrder = ProjectOrder.Server,
        )
        assertEquals(listOf("a", "b", "c").filter { id -> groups.any { it.key == id } }, groups.map { it.key })
    }

    @Test fun activityOrderSortsProjectsByTheirNewestThreadActivity() {
        val groups = projectGroups(
            listOf(
                thread("a1", "a", latestRunCompletedAt = "2026-01-01T01:00:00Z"),
                thread("b1", "b", latestRunCompletedAt = "2026-01-01T05:00:00Z"),
                thread("c1", "c", latestRunCompletedAt = "2026-01-01T03:00:00Z"),
            ),
            projects, otherOrder = ProjectOrder.Activity,
        )
        assertEquals(listOf("b", "c", "a"), groups.map { it.key })
    }

    @Test fun alphabeticalOrderIgnoresCase() {
        val mixed = listOf(Project("x", "banana", "/x"), Project("y", "Apple", "/y"))
        val groups = projectGroups(
            listOf(thread("t1", "x"), thread("t2", "y")),
            mixed, otherOrder = ProjectOrder.Alphabetical,
        )
        assertEquals(listOf("y", "x"), groups.map { it.key })
    }

    @Test fun threadOrderByActivityPutsTheMostRecentFirst() {
        val groups = projectGroups(
            listOf(
                thread("old", "a", latestRunCompletedAt = "2026-01-01T01:00:00Z"),
                thread("new", "a", latestRunCompletedAt = "2026-01-01T09:00:00Z"),
            ),
            projects, otherOrder = ProjectOrder.Server, threadOrder = ThreadOrder.Activity,
        )
        assertEquals(listOf("new", "old"), groups[0].threads.map { it.id })
    }

    @Test fun threadOrderByCreatedIgnoresLaterActivity() {
        val groups = projectGroups(
            listOf(
                thread("older", "a", createdAt = "2026-01-01T01:00:00Z", latestRunCompletedAt = "2026-01-01T09:00:00Z"),
                thread("newer", "a", createdAt = "2026-01-01T05:00:00Z"),
            ),
            projects, otherOrder = ProjectOrder.Server, threadOrder = ThreadOrder.Created,
        )
        assertEquals(listOf("newer", "older"), groups[0].threads.map { it.id })
    }

    @Test fun countsSplitNeedsYouAndWorking() {
        val groups = projectGroups(
            listOf(
                thread("needs", "a", pendingRequestKind = "approval"),
                thread("works", "a", status = "running", latestRunId = "run"),
                thread("idle", "a"),
            ),
            projects, otherOrder = ProjectOrder.Server,
        )
        assertEquals(3, groups[0].threads.size)
        assertEquals(1, groups[0].needsYou)
        assertEquals(1, groups[0].working)
    }

    @Test fun chipsSliceTheGroupWithoutChangingItsOrder() {
        val group = projectGroups(
            listOf(
                thread("needs", "a", pendingRequestKind = "approval", createdAt = "2026-01-01T05:00:00Z"),
                thread("idle", "a", createdAt = "2026-01-01T01:00:00Z"),
            ),
            projects, otherOrder = ProjectOrder.Server,
        ).single()
        assertEquals(listOf("needs", "idle"), group.visibleThreads(ThreadFilter.All).map { it.id })
        assertEquals(listOf("needs"), group.visibleThreads(ThreadFilter.NeedsYou).map { it.id })
        assertTrue(group.visibleThreads(ThreadFilter.Working).isEmpty())
    }

    @Test fun filterCountsCoverEveryGroupOnce() {
        val groups = projectGroups(
            listOf(
                thread("n", "a", pendingRequestKind = "approval"),
                thread("w", "b", status = "running", latestRunId = "run"),
                thread("o", "gone"),
            ),
            projects, otherOrder = ProjectOrder.Server,
        )
        val counts = projectGroupsFilterCounts(groups)
        assertEquals(3, counts[ThreadFilter.All])
        assertEquals(1, counts[ThreadFilter.NeedsYou])
        assertEquals(1, counts[ThreadFilter.Working])
    }

    @Test fun subagentAndSideChatChildrenStayHiddenFromGroups() {
        val child = thread("child", "a").copy(parentThreadId = "parent", relationshipToParent = "subagent")
        val side = thread("side", "a").copy(parentThreadId = "parent", sideChat = true)
        val groups = projectGroups(listOf(thread("parent", "a"), child, side), projects, otherOrder = ProjectOrder.Server)
        assertEquals(listOf("parent"), groups.single().threads.map { it.id })
    }

    @Test fun archivedThreadsStayOutOfGroups() {
        val archived = thread("arch", "a").copy(archivedAt = "2026-01-01T00:00:00Z")
        val groups = projectGroups(listOf(thread("live", "a"), archived), projects, otherOrder = ProjectOrder.Server)
        assertEquals(listOf("live"), groups.single().threads.map { it.id })
    }

    @Test fun groupsAreStableAgainstInputOrder() {
        val rows = listOf(
            thread("x1", "a", latestRunCompletedAt = "2026-01-01T02:00:00Z"),
            thread("y1", "b", latestRunCompletedAt = "2026-01-01T04:00:00Z"),
            thread("x2", "a", latestRunCompletedAt = "2026-01-01T06:00:00Z"),
        )
        val forward = projectGroups(rows, projects, otherOrder = ProjectOrder.Server)
        val reversed = projectGroups(rows.reversed(), projects, otherOrder = ProjectOrder.Server)
        assertEquals(forward.map { it.key }, reversed.map { it.key })
        assertEquals(forward.map { g -> g.threads.map { it.id } }, reversed.map { g -> g.threads.map { it.id } })
    }
}

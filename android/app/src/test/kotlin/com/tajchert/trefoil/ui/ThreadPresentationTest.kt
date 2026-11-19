package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ThreadSummary
import org.junit.Assert.*
import org.junit.Test

class ThreadPresentationTest {
    @Test fun fullWireShapedRosterMatchesDesktopCountsWithoutListingChildrenOrSideChats() {
        val rows = (0 until 3).map { ThreadSummary("open-$it", "p", "Open $it", relationshipToParent = if (it == 2) "fork" else null, parentThreadId = if (it == 2) "open-0" else null) } +
            (0 until 2).map { ThreadSummary("working-$it", "p", "Work $it", "running", latestRunId = "run-$it") } +
            ThreadSummary("snoozed", "p", "Snoozed", snoozedUntil = "2099-01-01T00:00:00Z") +
            (0 until 25).map { ThreadSummary("settled-$it", "p", "Settled $it", "completed", settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z") } +
            (0 until 56).map { ThreadSummary("child-$it", "p", "Child $it", relationshipToParent = "subagent", parentThreadId = "open-0") } +
            ThreadSummary("side-chat", "p", "Hidden chat", sideChat = true, parentThreadId = "open-0") +
            ThreadSummary("deleted", "p", "Deleted", deletedAt = "2026-11-06T09:00:00Z")
        assertEquals(mapOf("Threads" to 3, "Working" to 2, "Snoozed" to 1, "Settled" to 25),
            threadShelves(rows).associate { it.title to it.threads.size })
    }

    @Test fun freshRequestsAndFailuresWakeSnoozeButExistingQuestionsAndCompletionDoNot() {
        val now = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        val snoozed = ThreadSummary("s", "p", "Snoozed", "completed", snoozedAt = "2026-11-07T10:00:00Z", snoozedUntil = "2026-11-08T10:00:00Z")
        fun shelf(thread: ThreadSummary) = threadShelves(listOf(thread), now).single().title
        assertEquals("Snoozed", shelf(snoozed))
        assertEquals("Snoozed", shelf(snoozed.copy(pendingRequestKind = "user_input", pendingRequestCreatedAt = "2026-11-07T09:00:00Z")))
        assertEquals("Threads", shelf(snoozed.copy(pendingRequestKind = "user_input", pendingRequestCreatedAt = "2026-11-07T11:00:00Z")))
        assertEquals("Threads", shelf(snoozed.copy(pendingRequestKind = "approval")))
        assertEquals("Threads", shelf(snoozed.copy(pendingGuestMessageCount = 1)))
        assertEquals("Threads", shelf(snoozed.copy(status = "failed", latestRunId = "run", latestRunCompletedAt = "2026-11-07T11:00:00Z")))
        assertEquals("Snoozed", shelf(snoozed.copy(status = "failed", latestRunId = "run", latestRunCompletedAt = "2026-11-07T09:00:00Z")))
    }

    @Test fun workingMembershipIncludesIdleHeldWorkButLeavesActionablePlansInInbox() {
        val held = ThreadSummary("held", "p", "Held", "completed", completionHeldBackgroundWork = true)
        assertEquals("Working", threadShelves(listOf(held)).single().title)
        assertEquals("Threads", threadShelves(listOf(held.copy(status = "failed"))).single().title)
        assertEquals("Working", threadShelves(listOf(held.copy(status = "idle", completionHeldBackgroundWork = false, latestRunId = "r"))).single().title)
        val plan = held.copy(latestRunId = "r", completionHeldBackgroundWork = false, interactionMode = "plan", hasActionableProposedPlan = true)
        assertEquals("Threads", threadShelves(listOf(plan)).single().title)
    }

    @Test fun childApprovalsWakeParentAndLeaveWorkingWithoutListingChild() {
        val parent = ThreadSummary("parent", "p", "Parent", "running", latestRunId = "r", snoozedUntil = "2099-01-01T00:00:00Z")
        val child = ThreadSummary("child", "p", "Child", "running", relationshipToParent = "subagent", parentThreadId = "parent", pendingRequestKind = "approval")
        assertEquals("Threads", threadShelves(listOf(parent, child)).single().title)
        assertEquals("Snoozed", threadShelves(listOf(parent, child.copy(creationSource = "provider"))).single().title)
        assertEquals("Snoozed", threadShelves(listOf(parent, child.copy(activityRunStatusPresent = true))).single().title)
        assertEquals("Snoozed", threadShelves(listOf(parent, child.copy(pendingRequestKind = "user_input"))).single().title)
    }

    @Test fun metadataUpdatesDoNotReorderInboxAndExplicitNullAuthoredTimeDoesNotUseWakeMessageTime() {
        val rows = listOf(
            ThreadSummary("older", "p", "Older", createdAt = "2026-11-05T09:00:00Z", updatedAt = "2099-01-01T00:00:00Z"),
            ThreadSummary("newer", "p", "Newer", createdAt = "2026-11-06T09:00:00Z"))
        assertEquals(listOf("newer", "older"), threadShelves(rows).single().threads.map { it.id })
        val work = rows.map { it.copy(status = "running", latestRunRequestedAt = if (it.id == "older") "2099-01-01T00:00:00Z" else "2000-01-01T00:00:00Z", latestUserAuthoredMessageAtPresent = true) }
        assertEquals(listOf("newer", "older"), threadShelves(work).single().threads.map { it.id })
        assertEquals(listOf("older", "newer"), threadShelves(work.map { it.copy(latestUserAuthoredMessageAtPresent = false) }).single().threads.map { it.id })
    }
    @Test fun ownApprovalKeepsPriorityOverSideChatQuestion() {
        val parent = ThreadSummary("parent", "p", "Parent", pendingRequestKind = "approval")
        val sideChat = ThreadSummary("chat", "p", "Chat", sideChat = true, parentThreadId = "parent", pendingRequestKind = "user_input")
        val row = threadShelves(listOf(parent, sideChat)).single().threads.single()
        assertEquals("Needs approval", threadIndicator(row).label)
    }
    @Test fun t3SettlementPrecedesPinAndAttentionAndNeverInfersFromTimestamp() {
        val rows = listOf(
            ThreadSummary("settled-pin", "p", "Pinned settled", settledOverride = "settled", pinnedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("settled-request", "p", "Request settled", settledOverride = "settled", pendingRequestKind = "approval"),
            ThreadSummary("timestamp-only", "p", "Open", settledAt = "2026-11-06T09:00:00Z"))
        val shelves = threadShelves(rows).associate { it.title to it.threads.map { thread -> thread.id }.toSet() }
        assertEquals(setOf("settled-pin", "settled-request"), shelves["Settled"])
        assertEquals(setOf("timestamp-only"), shelves["Threads"])
        assertNull(shelves["Pinned"])
    }

    @Test fun blockedRunningThreadIsWorkingEvenWhenItsIndicatorSaysWaiting() {
        val thread = ThreadSummary("waiting", "p", "Wait for child", "running", blockedOnThreadWait = true)
        assertEquals("Waiting", threadIndicator(thread).label)
        assertEquals("Working", threadShelves(listOf(thread)).single().title)
    }
    @Test fun shelvesKeepPinnedWorkingSettledAndArchivedDistinct() {
        val rows = listOf(
            ThreadSummary("active", "p", "Active"),
            ThreadSummary("working", "p", "Working", "running"),
            ThreadSummary("settled", "p", "Settled", settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("pinned", "p", "Pinned", "running", pinnedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("archive", "p", "Archive", archived = true),
        )
        val shelves = threadShelves(rows)
        assertEquals(listOf("Pinned", "Threads", "Working", "Settled", "Archived"), shelves.map { it.title })
        assertEquals(listOf("pinned", "active", "working", "settled", "archive"), shelves.flatMap { it.threads }.map { it.id })
    }
    @Test fun pinnedOrderAndInboxRecencyAreStable() {
        val rows = listOf(
            ThreadSummary("older", "p", "Older", updatedAt = "2026-11-05T09:00:00Z"),
            ThreadSummary("newer", "p", "Newer", updatedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("pinB", "p", "B", pinnedAt = "2026-11-06T09:00:00Z", pinOrderKey = "b"),
            ThreadSummary("pinA", "p", "A", pinnedAt = "2026-11-06T09:00:00Z", pinOrderKey = "a"),
        )
        assertEquals(listOf("pinA", "pinB", "newer", "older"), threadShelves(rows).flatMap { it.threads }.map { it.id })
        assertEquals(threadShelves(rows), threadShelves(rows.reversed()))
    }
    @Test fun attentionOverridesRunningAndSettled() {
        val thread = ThreadSummary("a", "p", "A", "running", settledAt = "2026-11-06T09:00:00Z", pendingRequestKind = "approval")
        assertEquals("Needs approval", threadIndicator(thread).label)
        assertEquals("Threads", threadShelves(listOf(thread)).single().title)
    }
    @Test fun snoozedThreadsReturnWhenTheirSavedTimeExpires() {
        val thread = ThreadSummary("s", "p", "Snoozed", snoozedUntil = "2026-11-06T10:00:00Z")
        val deadline = java.time.Instant.parse(thread.snoozedUntil).toEpochMilli()
        assertEquals("Snoozed", threadShelves(listOf(thread), deadline - 1).single().title)
        assertEquals("Threads", threadShelves(listOf(thread), deadline).single().title)
        assertEquals("Waiting", threadIndicator(thread.copy(status = "running", blockedOnThreadWait = true)).label)
    }
    @Test fun reportedThreadCountsAppearInSeparateShelves() {
        val threads = listOf(ThreadSummary("work", "p", "Working", "running"),
            ThreadSummary("snooze", "p", "Snoozed", snoozedUntil = "2099-01-01T00:00:00Z")) +
            (0 until 24).map { ThreadSummary("settled-$it", "p", "Settled $it", settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z") } +
            (0 until 4).map { ThreadSummary("open-$it", "p", "Open $it") }
        assertEquals(mapOf("Threads" to 4, "Working" to 1, "Snoozed" to 1, "Settled" to 24),
            threadShelves(threads).associate { it.title to it.threads.size })
    }
    @Test fun sectionsPullOpenRequestsUpButLeaveSettledAndSnoozedWhereT3PutsThem() {
        val rows = listOf(
            ThreadSummary("ask", "p", "Ask", "running", pendingRequestKind = "approval"),
            ThreadSummary("pinned-ask", "p", "Pinned ask", pendingRequestKind = "user_input", pinnedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("pinned-work", "p", "Pinned work", "running", pinnedAt = "2026-11-06T09:00:00Z"),
            ThreadSummary("work", "p", "Work", "running"),
            ThreadSummary("open", "p", "Open", "completed"),
            ThreadSummary("settled-ask", "p", "Settled ask", settledOverride = "settled", pendingRequestKind = "approval"),
            ThreadSummary("snoozed", "p", "Snoozed", snoozedUntil = "2099-01-01T00:00:00Z"),
            ThreadSummary("archive", "p", "Archive", archived = true))
        val shelves = threadShelves(rows)
        val sections = threadSections(shelves)
        assertEquals(listOf("Needs you", "Pinned", "Working", "Recent", "Snoozed", "Settled", "Archived"), sections.map { it.title })
        assertEquals(mapOf("Needs you" to setOf("ask", "pinned-ask"), "Pinned" to setOf("pinned-work"), "Working" to setOf("work"), "Recent" to setOf("open"),
            "Snoozed" to setOf("snoozed"), "Settled" to setOf("settled-ask"), "Archived" to setOf("archive")),
            sections.associate { it.title to it.threads.map { thread -> thread.id }.toSet() })
        assertEquals(listOf("Settled", "Archived"), sections.filter { it.folded }.map { it.title })
        assertEquals(sections.flatMap { it.threads }.map { it.id }.sorted(), shelves.flatMap { it.threads }.map { it.id }.sorted())
        assertEquals(listOf("pinned-work", "work"), threadSections(shelves, ThreadFilter.Working).single().threads.map { it.id })
        assertEquals(mapOf(ThreadFilter.All to 7, ThreadFilter.NeedsYou to 2, ThreadFilter.Working to 2), threadFilterCounts(shelves))
    }
    @Test fun rowMarksAndTimesFollowStatus() {
        val now = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        val working = ThreadSummary("w", "p", "W", "running", activityRunStartedAt = "2026-11-07T11:57:00Z", activityRunStartedAtPresent = true, updatedAt = "2026-11-07T11:59:00Z")
        assertEquals(RowMark.Working, threadRowMark(working))
        assertEquals("3m", threadRowTime(working, now))
        val done = ThreadSummary("d", "p", "D", "completed", updatedAt = "2026-11-07T11:47:00Z")
        assertEquals(RowMark.Done, threadRowMark(done))
        assertEquals("13m", threadRowTime(done, now))
        assertEquals(RowMark.Error, threadRowMark(done.copy(status = "failed")))
        assertEquals(RowMark.None, threadRowMark(done.copy(status = "idle")))
        assertEquals(RowMark.None, threadRowMark(done.copy(pendingRequestKind = "approval")))
    }
    @Test fun rowWaitingOnSubagentsNeverShowsDone() {
        // The parent's turn finished but a subagent still runs: T3 parks it at Waiting, never Completed.
        val held = ThreadSummary("h", "p", "H", "completed", latestRunId = "run", latestRunCompletedAt = "2026-11-07T11:50:00Z",
            completionHeldBackgroundWork = true)
        assertEquals("waiting", threadIndicator(held).kind)
        assertEquals(RowMark.Waiting, threadRowMark(held))
        assertTrue(threadIsWorking(held))
        assertEquals("Working", threadSections(threadShelves(listOf(held))).single().title)
        // Once the shell drops the hold, the same row is done.
        assertEquals(RowMark.Done, threadRowMark(held.copy(completionHeldBackgroundWork = false)))
        // A failed run with leftover background work stays an error.
        assertEquals(RowMark.Error, threadRowMark(held.copy(status = "failed")))
        // A run blocked on another thread waits; a stale flag on a finished run does not.
        val blocked = ThreadSummary("b", "p", "B", "running", activityRunStatus = "running", blockedOnThreadWait = true, latestRunCompletedAt = "2026-11-07T11:00:00Z")
        assertEquals(RowMark.Waiting, threadRowMark(blocked))
        for (run in listOf("preparing", "queued", "starting", "waiting", "streaming")) {
            assertEquals(run, RowMark.Waiting, threadRowMark(blocked.copy(activityRunStatus = run)))
        }
        assertEquals(RowMark.Done, threadRowMark(blocked.copy(status = "completed", activityRunStatus = null)))
    }
    @Test fun shellLayoutBreakpoints() {
        assertEquals(ShellLayout.Phone, shellLayout(411f))
        assertEquals(ShellLayout.TwoPane, shellLayout(600f))
        assertEquals(ShellLayout.TwoPane, shellLayout(840f))
        assertEquals(ShellLayout.Rail, shellLayout(1280f))
        assertEquals("Mac", railLabel("Mac mini"))
    }
    @Test fun invalidAndFutureTimesAreSafe() {
        assertEquals("", relativeThreadTime("invalid", 0))
        assertEquals("now", relativeThreadTime("2026-11-06T09:00:00Z", 0))
    }

    private fun waiting(id: String, kind: String = "approval", title: String = "Thread $id") = ThreadSummary(id, "p", title, "running", pendingRequestKind = kind)
    private fun quiet(id: String) = ThreadSummary(id, "p", "Thread $id", "completed")

    @Test fun threadsToAnnounceSkipsKnownAndOpenThreads() {
        val needs = listOf(waiting("known"), waiting("open"), waiting("new"))
        assertEquals(listOf("new"), threadsToAnnounce(setOf("known"), needs, "open").map { it.id })
        assertEquals(emptyList<String>(), threadsToAnnounce(setOf("known", "open", "new"), needs, null).map { it.id })
    }

    @Test fun needsYouThreadsSkipArchivedSettledAndSnoozedThreads() {
        val now = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
        val threads = listOf(waiting("live"), waiting("archived").copy(archived = true), waiting("settled").copy(settledOverride = "settled", settledAt = "2026-11-06T09:00:00Z"),
            waiting("snoozed").copy(pendingRequestKind = null, snoozedUntil = "2099-01-01T00:00:00Z"), quiet("quiet"))
        assertEquals(listOf("live"), needsYouThreads(threads, now).map { it.id })
    }

    @Test fun attentionStepAdoptsTheFirstFreshSnapshotSilentlyAndThenFiresOnEntryOnly() {
        val a = waiting("a"); val b = waiting("b"); val c = waiting("c", "user_input")
        var track = AttentionTrack()
        fun step(env: String? = "env", connected: Boolean = true, fresh: Boolean = true, threads: List<ThreadSummary>, open: String? = null): List<String> {
            val result = attentionStep(track, env, connected, fresh, threads, open); track = result.track; return result.announce.map { it.id }
        }
        assertEquals(emptyList<String>(), step(fresh = false, threads = listOf(a)))
        assertNull(track.needsYouIds)
        assertEquals(emptyList<String>(), step(threads = listOf(a)))
        assertEquals(setOf("a"), track.needsYouIds)
        assertEquals(emptyList<String>(), step(threads = listOf(a, quiet("x"))))
        assertEquals(listOf("b"), step(threads = listOf(a, b)))
        assertEquals(emptyList<String>(), step(threads = listOf(a, b)))
        // The open thread never fires, and does not fire later either.
        assertEquals(emptyList<String>(), step(threads = listOf(a, b, c), open = "c"))
        assertEquals(emptyList<String>(), step(threads = listOf(a, b, c)))
        // Leaving and re-entering fires again.
        assertEquals(emptyList<String>(), step(threads = listOf(a, c)))
        assertEquals(listOf("b"), step(threads = listOf(a, b, c)))
    }

    @Test fun attentionStepForgetsTheBaselineOnReconnectAndEnvironmentSwitch() {
        val a = waiting("a"); val b = waiting("b")
        var track = attentionStep(AttentionTrack(), "env", true, true, listOf(a), null).track
        // Disconnect, then reconnect with a stale cache and a fresh snapshot: nothing fires for threads that started waiting meanwhile.
        track = attentionStep(track, "env", false, false, listOf(a), null).track
        assertNull(track.needsYouIds)
        assertTrue(attentionStep(track, "env", true, false, listOf(a), null).also { track = it.track }.announce.isEmpty())
        assertTrue(attentionStep(track, "env", true, true, listOf(a, b), null).also { track = it.track }.announce.isEmpty())
        assertEquals(setOf("a", "b"), track.needsYouIds)
        // Switching environment starts over even when already connected and fresh.
        val switched = attentionStep(track, "other", true, true, listOf(b), null)
        assertTrue(switched.announce.isEmpty()); assertEquals("other", switched.track.environmentId); assertEquals(setOf("b"), switched.track.needsYouIds)
    }

    @Test fun attentionMessageNamesTheKindOrTheCount() {
        assertNull(attentionMessage(emptyList()))
        assertEquals(AttentionMessage("Fix login needs approval", "a"), attentionMessage(listOf(waiting("a", title = "Fix login"))))
        assertEquals(AttentionMessage("Fix login needs input", "a"), attentionMessage(listOf(waiting("a", "user_input", "Fix login"))))
        assertEquals(AttentionMessage("Fix login needs attention", "a"), attentionMessage(listOf(waiting("a", "auth_refresh", "Fix login"))))
        assertEquals("Untitled conversation needs approval", attentionMessage(listOf(waiting("a", title = "")))?.text)
        assertEquals(AttentionMessage("2 threads need you", null, listOf("a", "b")), attentionMessage(listOf(waiting("a"), waiting("b"))))
        assertTrue(attentionMessage(listOf(waiting("a", title = "x".repeat(200))))!!.text.length < 90)
    }

    @Test fun queuedMessageIsDroppedOnceItsThreadsAreOpenOrNoLongerWaiting() {
        val one = attentionMessage(listOf(waiting("a")))!!
        val many = attentionMessage(listOf(waiting("a"), waiting("b")))!!
        assertTrue(attentionStillRelevant(one, listOf(waiting("a")), null))
        assertFalse(attentionStillRelevant(one, listOf(waiting("a")), "a"))
        assertFalse(attentionStillRelevant(one, listOf(waiting("b")), null))
        assertTrue(attentionStillRelevant(many, listOf(waiting("b")), "a"))
        assertFalse(attentionStillRelevant(many, emptyList(), null))
    }

    @Test fun backDescriptionCountsWaitingThreads() {
        assertEquals("Back to threads", backDescription(0))
        assertEquals("Back to threads, 1 thread needs you", backDescription(1))
        assertEquals("Back to threads, 3 threads need you", backDescription(3))
    }
}

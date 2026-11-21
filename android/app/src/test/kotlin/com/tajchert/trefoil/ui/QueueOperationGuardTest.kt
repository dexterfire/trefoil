package com.tajchert.trefoil.ui

import org.junit.Assert.*
import org.junit.Test

class QueueOperationGuardTest {
    private var now = 0L
    private val guard = QueueOperationGuard(StopGuard({ now }, holdMs = 10_000))
    private val before = listOf<Any>(true, listOf("a"))
    private val after = listOf<Any>(false, listOf("a"))

    @Test fun aSecondTapWhileAnOperationIsInFlightIsRefused() {
        assertTrue(guard.tryBegin("e:t", before))
        assertFalse(guard.tryBegin("e:t", before))
        assertEquals(setOf("e:t"), guard.pending.value)
        assertTrue("another thread is independent", guard.tryBegin("e:u", before))
    }

    @Test fun theGuardWaitsForBothTheOutcomeAndARefreshedQueue() {
        guard.tryBegin("e:t", before)
        guard.onState("e:t", after) // the projection changed before the outcome arrived
        assertEquals(setOf("e:t"), guard.pending.value)
        guard.completed("e:t", before) // outcome in, queue not refreshed yet
        assertEquals(setOf("e:t"), guard.pending.value)
        guard.onState("e:t", after)
        assertEquals(emptySet<String>(), guard.pending.value)
        assertTrue(guard.tryBegin("e:t", after))
    }

    @Test fun anOutcomeAfterTheQueueAlreadyRefreshedReleasesAtOnce() {
        guard.tryBegin("e:t", before)
        guard.onState("e:t", after)
        guard.completed("e:t", after)
        assertEquals(emptySet<String>(), guard.pending.value)
    }

    @Test fun aFailureReleasesImmediately() {
        guard.tryBegin("e:t", before)
        guard.failed("e:t")
        assertTrue(guard.tryBegin("e:t", before))
    }

    @Test fun theDeadlineIsASafetyNetWhenTheQueueNeverChanges() {
        guard.tryBegin("e:t", before)
        guard.completed("e:t", before)
        now += 9_999; guard.expire("e:t")
        assertFalse(guard.tryBegin("e:t", before))
        now += 1; guard.expire("e:t")
        assertTrue(guard.tryBegin("e:t", before))
    }
}

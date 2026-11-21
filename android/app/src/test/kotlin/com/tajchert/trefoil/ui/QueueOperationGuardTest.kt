package com.tajchert.trefoil.ui

import org.junit.Assert.*
import org.junit.Test

class QueueOperationGuardTest {
    private var now = 0L
    private val guard = QueueOperationGuard(StopGuard({ now }, holdMs = 10_000))
    private val before = listOf<Any>(true, listOf("a"))
    private val after = listOf<Any>(false, listOf("a"))

    @Test fun aSecondTapWhileAnOperationIsInFlightIsRefused() {
        assertNotNull(guard.tryBegin("e:t", before))
        assertNull(guard.tryBegin("e:t", before))
        assertEquals(setOf("e:t"), guard.pending.value)
        assertNotNull("another thread is independent", guard.tryBegin("e:u", before))
    }

    @Test fun theGuardWaitsForBothTheOutcomeAndARefreshedQueue() {
        val token = guard.tryBegin("e:t", before)!!
        guard.onState("e:t", after) // the projection changed before the outcome arrived
        assertEquals(setOf("e:t"), guard.pending.value)
        guard.completed("e:t", token, before) // outcome in, queue not refreshed yet
        assertEquals(setOf("e:t"), guard.pending.value)
        guard.onState("e:t", after)
        assertEquals(emptySet<String>(), guard.pending.value)
        assertNotNull(guard.tryBegin("e:t", after))
    }

    @Test fun anOutcomeAfterTheQueueAlreadyRefreshedReleasesAtOnce() {
        val token = guard.tryBegin("e:t", before)!!
        guard.onState("e:t", after)
        guard.completed("e:t", token, after)
        assertEquals(emptySet<String>(), guard.pending.value)
    }

    @Test fun anOutcomeWhileAnotherThreadIsSelectedWaitsForItsOwnThreadsState() {
        val token = guard.tryBegin("e:t", before)!!
        guard.completed("e:t", token, null) // another thread's queue is on screen: never compare against it
        assertEquals(setOf("e:t"), guard.pending.value)
        guard.onState("e:t", after)
        assertEquals(emptySet<String>(), guard.pending.value)
    }

    @Test fun aFailureReleasesImmediately() {
        val token = guard.tryBegin("e:t", before)!!
        guard.failed("e:t", token)
        assertNotNull(guard.tryBegin("e:t", before))
    }

    @Test fun theDeadlineIsASafetyNetWhenTheQueueNeverChanges() {
        val token = guard.tryBegin("e:t", before)!!
        guard.completed("e:t", token, before)
        now += 9_999; guard.expire("e:t", token)
        assertNull(guard.tryBegin("e:t", before))
        now += 1; guard.expire("e:t", token)
        assertNotNull(guard.tryBegin("e:t", before))
    }

    @Test fun aLateCallbackFromAnExpiredOperationCannotReleaseTheNextOne() {
        val first = guard.tryBegin("e:t", before)!!
        now += 10_000; guard.expire("e:t", first) // the first RPC is still in flight past the hold
        val second = guard.tryBegin("e:t", before)!!
        guard.failed("e:t", first)
        guard.completed("e:t", first, after)
        assertEquals("the second operation still holds the controls", setOf("e:t"), guard.pending.value)
        guard.failed("e:t", second)
        assertEquals(emptySet<String>(), guard.pending.value)
    }
}

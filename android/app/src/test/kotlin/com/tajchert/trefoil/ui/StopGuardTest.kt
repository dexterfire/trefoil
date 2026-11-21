package com.tajchert.trefoil.ui

import org.junit.Assert.*
import org.junit.Test

class StopGuardTest {
    private var now = 1_000L
    private val guard = StopGuard({ now }, holdMs = 10_000)

    @Test fun aSecondStopOnTheSameThreadIsRefusedWhileOneIsInFlight() {
        assertTrue(guard.tryBegin("e:a"))
        assertFalse(guard.tryBegin("e:a"))
        assertEquals(setOf("e:a"), guard.pending.value)
    }

    @Test fun threadsAreIndependent() {
        assertTrue(guard.tryBegin("e:a"))
        assertTrue(guard.tryBegin("e:b"))
        assertEquals(setOf("e:a", "e:b"), guard.pending.value)
        guard.release("e:a")
        assertEquals(setOf("e:b"), guard.pending.value)
    }

    @Test fun theHoldEndsAtTheDeadlineEvenWithoutAnExplicitExpiry() {
        guard.tryBegin("e:a")
        now += 9_999
        assertFalse(guard.tryBegin("e:a"))
        now += 1
        assertTrue(guard.tryBegin("e:a"))
    }

    @Test fun expireOnlyDropsAnElapsedHold() {
        guard.tryBegin("e:a")
        guard.expire("e:a")
        assertEquals(setOf("e:a"), guard.pending.value)
        now += 10_000
        guard.expire("e:a")
        assertEquals(emptySet<String>(), guard.pending.value)
    }

    @Test fun releaseAllowsTheNextStopAndIsIdempotent() {
        guard.tryBegin("e:a")
        guard.release("e:a"); guard.release("e:a")
        assertTrue(guard.tryBegin("e:a"))
    }
}

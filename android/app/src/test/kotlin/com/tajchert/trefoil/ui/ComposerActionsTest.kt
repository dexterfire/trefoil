package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ComposerActionsTest {
    private val idle = ClientState(selectedThreadId = "t", canWrite = true, connection = ConnectionStatus.Connected)
    private val running = idle.copy(activeRunId = "run", canStop = true)

    @Test fun idleThreadSendsAndNeverStops() {
        assertEquals(ComposerButton(ComposerPrimary.Send, true), resolveComposerButton(idle, false, "hi", false, false))
        assertEquals(ComposerButton(ComposerPrimary.Send, false), resolveComposerButton(idle, false, " ", false, false))
    }

    @Test fun emptyComposerStopsWhateverIsRunningIncludingBackgroundWork() {
        assertEquals(ComposerButton(ComposerPrimary.Stop, true), resolveComposerButton(running, false, "", false, false))
        val backgroundOnly = idle.copy(canStop = true, backgroundWork = listOf(PendingBackgroundTask("b", "subagent", "Stream A")))
        assertEquals(ComposerButton(ComposerPrimary.Stop, true), resolveComposerButton(backgroundOnly, false, "  ", false, false))
    }

    @Test fun stopIsDisabledWhilePendingOrReadOnly() {
        assertFalse(resolveComposerButton(running, false, "", false, stopPending = true).enabled)
        assertFalse(resolveComposerButton(running.copy(canWrite = false), false, "", false, false).enabled)
    }

    @Test fun textWhileRunningSteersWhenSupportedOtherwiseQueues() {
        assertEquals(ComposerButton(ComposerPrimary.Steer, true), resolveComposerButton(running.copy(canSteer = true), false, "next", false, false))
        assertEquals(ComposerButton(ComposerPrimary.Queue, true), resolveComposerButton(running, false, "next", false, false))
        assertEquals(FollowUpMode.Steer, ComposerPrimary.Steer.followUp)
        assertEquals(FollowUpMode.Queue, ComposerPrimary.Queue.followUp)
        assertNull(ComposerPrimary.Send.followUp)
        assertTrue(resolveComposerButton(running, false, "next", false, false).offersAlternate)
        assertFalse(resolveComposerButton(idle, false, "next", false, false).offersAlternate)
    }

    @Test fun followUpSendWaitsForConfirmationAndReadOnly() {
        assertFalse(resolveComposerButton(running, false, "next", waiting = true, stopPending = false).enabled)
        assertFalse(resolveComposerButton(running.copy(canWrite = false), false, "next", false, false).enabled)
    }

    @Test fun draftNeverStopsOrQueues() {
        assertEquals(ComposerButton(ComposerPrimary.Send, false), resolveComposerButton(running, true, "", false, false))
        assertEquals(ComposerButton(ComposerPrimary.Send, true), resolveComposerButton(running, true, "go", false, false))
        assertEquals("What should the agent do?", composerPlaceholder(running, true))
    }

    @Test fun placeholdersFollowTheRunAndSteering() {
        assertEquals("Reply…", composerPlaceholder(idle, false))
        assertEquals("Queue a message…", composerPlaceholder(running, false))
        assertEquals("Steer or queue a message…", composerPlaceholder(running.copy(canSteer = true), false))
    }

    @Test fun sendIsAcceptedWhenTheMessageIsQueuedWithoutAnyTimelineItem() {
        val queued = QueuedMessage("q2", "qm2", "Then open the PR.")
        val state = running.copy(queuedMessages = listOf(queued))
        assertTrue(sendAccepted("Then open the PR.", state, messageIdsAtSend = emptyList(), queuedIdsAtSend = emptyList()))
        assertTrue(sendAccepted(" Then open the PR.\n", state, emptyList(), emptyList()))
    }

    @Test fun sendIsNotAcceptedByWhatWasThereBeforeTheSend() {
        val before = QueuedMessage("q1", "qm1", "Same text")
        val old = ConversationMessage("old", "user", "Same text")
        val state = running.copy(queuedMessages = listOf(before), messages = listOf(old))
        assertFalse(sendAccepted("Same text", state, listOf("old"), listOf("qm1")))
        // A duplicate of the same text that arrives later is a new message id.
        assertTrue(sendAccepted("Same text", state.copy(queuedMessages = state.queuedMessages + QueuedMessage("q2", "qm2", "Same text")), listOf("old"), listOf("qm1")))
        assertTrue(sendAccepted("Same text", state.copy(messages = state.messages + ConversationMessage("new", "user", "Same text")), listOf("old"), listOf("qm1")))
    }

    @Test fun assistantTextNeverConfirmsASend() {
        assertFalse(sendAccepted("hi", running.copy(messages = listOf(ConversationMessage("a", "assistant", "hi"))), emptyList(), emptyList()))
    }

    @Test fun sendNowNeedsWritesAndEitherSteeringOrAnIdleThread() {
        assertTrue(canSendQueuedNow(idle))
        assertFalse(canSendQueuedNow(running))
        assertTrue(canSendQueuedNow(running.copy(canSteer = true)))
        assertFalse(canSendQueuedNow(idle.copy(canWrite = false)))
    }

    @Test fun queuePausedFollowsTheHeldFlagOrAHeldRow() {
        assertFalse(idle.queuePaused)
        assertTrue(idle.copy(queueHeld = true).queuePaused)
        assertTrue(idle.copy(queuedMessages = listOf(QueuedMessage("q", "m", "t", held = true))).queuePaused)
    }
}

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
        assertTrue(sendAccepted("Then open the PR.", state, idsAtSend = emptyList()))
        assertTrue(sendAccepted(" Then open the PR.\n", state, emptyList()))
    }

    @Test fun sendIsNotAcceptedByWhatWasThereBeforeTheSend() {
        val before = QueuedMessage("q1", "qm1", "Same text")
        val old = ConversationMessage("old", "user", "Same text")
        val state = running.copy(queuedMessages = listOf(before), messages = listOf(old))
        val ids = listOf("old", "qm1")
        assertFalse(sendAccepted("Same text", state, ids))
        // A duplicate of the same text that arrives later is a new message id.
        assertTrue(sendAccepted("Same text", state.copy(queuedMessages = state.queuedMessages + QueuedMessage("q2", "qm2", "Same text")), ids))
        assertTrue(sendAccepted("Same text", state.copy(messages = state.messages + ConversationMessage("new", "user", "Same text")), ids))
    }

    @Test fun anOlderQueuedMessageMovingIntoTheTimelineDoesNotConfirmANewSend() {
        // "qm1" was queued at submission; it later becomes a timeline message with the same id.
        val moved = running.copy(messages = listOf(ConversationMessage("qm1", "user", "again")))
        assertFalse(sendAccepted("again", moved, idsAtSend = listOf("qm1")))
        // And a message that was in the timeline at submission never matches a queued row with that id.
        val back = running.copy(queuedMessages = listOf(QueuedMessage("q", "tl1", "again")))
        assertFalse(sendAccepted("again", back, idsAtSend = listOf("tl1")))
    }

    @Test fun assistantTextNeverConfirmsASend() {
        assertFalse(sendAccepted("hi", running.copy(messages = listOf(ConversationMessage("a", "assistant", "hi"))), emptyList()))
    }

    @Test fun queuePausedFollowsTheHeldFlagOrAHeldRow() {
        assertFalse(idle.queuePaused)
        assertTrue(idle.copy(queueHeld = true).queuePaused)
        assertTrue(idle.copy(queuedMessages = listOf(QueuedMessage("q", "m", "t", held = true))).queuePaused)
    }

    @Test fun threadBusyMirrorsTheSessionsQueueableRule() {
        assertFalse(idle.threadBusy)
        assertTrue(running.threadBusy)
        listOf("preparing", "starting", "running", "waiting").forEach { status ->
            assertTrue(status, idle.copy(runs = listOf(ConversationRun("r", status))).threadBusy)
        }
        assertFalse(idle.copy(runs = listOf(ConversationRun("r", "completed"))).threadBusy)
        val shell = { status: String?, run: String? -> idle.copy(threads = listOf(ThreadSummary("t", "p", "T", status = status ?: "idle", activityRunStatus = run))) }
        assertTrue(shell(null, "preparing").threadBusy)
        assertTrue(shell(null, "starting").threadBusy)
        assertTrue(shell("starting", null).threadBusy)
        assertTrue(shell("preparing", null).threadBusy)
        assertFalse(shell("idle", "completed").threadBusy)
        // Another thread's shell never makes this one busy.
        assertFalse(idle.copy(threads = listOf(ThreadSummary("other", "p", "O", activityRunStatus = "starting"))).threadBusy)
    }

    @Test fun aSettingUpRunLooksRunningSoTheComposerQueuesInsteadOfSendingImmediately() {
        val settingUp = idle.copy(threads = listOf(ThreadSummary("t", "p", "T", activityRunStatus = "starting")))
        assertEquals(ComposerPrimary.Queue, resolveComposerButton(settingUp, false, "next", false, false).action)
        assertEquals("Queue a message…", composerPlaceholder(settingUp, false))
    }

    @Test fun routeSendChecksTheLiveStateAtTapTime() {
        assertEquals(SendRoute.Plain, routeSend(idle, false, null))
        assertEquals(SendRoute.FollowUp(FollowUpMode.Queue), routeSend(running, false, null))
        assertEquals(SendRoute.FollowUp(FollowUpMode.Queue), routeSend(running, false, FollowUpMode.Queue))
        assertEquals(SendRoute.FollowUp(FollowUpMode.Steer), routeSend(running.copy(canSteer = true), false, FollowUpMode.Steer))
        // The run ended after the label was chosen: never an immediate send under a Queue/Steer label.
        assertEquals(SendRoute.Refused, routeSend(idle, false, FollowUpMode.Queue))
        assertEquals(SendRoute.Refused, routeSend(idle.copy(canSteer = true), false, FollowUpMode.Steer))
        // Steering stopped being supported.
        assertEquals(SendRoute.Refused, routeSend(running, false, FollowUpMode.Steer))
        // A new-thread draft is never a follow-up.
        assertEquals(SendRoute.Plain, routeSend(running, true, null))
        assertEquals(SendRoute.Refused, routeSend(running, true, FollowUpMode.Queue))
    }

    @Test fun submissionIdCarriesEnvironmentAndThread() {
        val state = running.copy(environment = SavedEnvironment("env", "Env", "https://e.test", "t"))
        val a = newSubmission(state, "x", nonce = "n1")
        assertEquals("env:t:n1", a.id)
        assertEquals("env", a.environmentId); assertEquals("t", a.threadId)
        assertNotEquals(newSubmission(state, "x").id, newSubmission(state, "x").id)
        assertEquals("env:t", stopKey("env", "t"))
    }
}

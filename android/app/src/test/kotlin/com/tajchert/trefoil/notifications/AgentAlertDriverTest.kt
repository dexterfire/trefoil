package com.tajchert.trefoil.notifications

import com.tajchert.trefoil.core.model.AgentAlert
import com.tajchert.trefoil.core.model.AgentAlertKind
import com.tajchert.trefoil.core.model.AgentAlertStep
import com.tajchert.trefoil.core.model.AgentAlertTrack
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.ThreadSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The driver with a fake step function and a fake sink: nothing here depends on the awareness rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentAlertDriverTest {
    private class FakeSink(var allowed: Boolean = true) : AgentAlertSink {
        val posts = mutableListOf<Pair<AgentAlert, String?>>()
        val cancels = mutableListOf<Pair<String, String>>()
        var cancelAlls = 0
        override fun canPost() = allowed
        override fun post(alert: AgentAlert, projectTitle: String?) { posts += alert to projectTitle }
        override fun cancel(environmentId: String, threadId: String) { cancels += environmentId to threadId }
        override fun cancelAll() { cancelAlls++ }
    }

    private class StepCall(val track: AgentAlertTrack, val environmentId: String?, val connected: Boolean, val fresh: Boolean, val threads: List<ThreadSummary>, val now: Long)

    private class Harness(scope: TestScope, enabled: Boolean = true, allowed: Boolean = true) {
        val environment = SavedEnvironment("env1", "Machine", "https://fixture.invalid", "fixture")
        val state = MutableStateFlow(ClientState(environment = environment, connection = ConnectionStatus.Connected, canRead = true, shellLive = true))
        val setting = MutableStateFlow(enabled)
        val sink = FakeSink(allowed)
        val calls = mutableListOf<StepCall>()
        /** What the next step returns; reads the call so a test can vary by input. */
        var respond: (StepCall) -> AgentAlertStep = { AgentAlertStep(it.track) }
        val driver = AgentAlertDriver(scope.backgroundScope, state, sink, setting, clock = { scope.testScheduler.currentTime }) { track, env, connected, fresh, threads, now ->
            StepCall(track, env, connected, fresh, threads, now).also { calls += it }.let(respond)
        }
        init { scope.runCurrent() }
    }

    private fun TestScope.harness(enabled: Boolean = true, allowed: Boolean = true) = Harness(this, enabled, allowed)
    private fun alert(thread: String = "t1", kind: AgentAlertKind = AgentAlertKind.Approval, project: String = "p1", env: String = "env1") =
        AgentAlert(env, thread, kind, "Title of $thread", project)

    @Test fun cancelsLeftoverNotificationsOnStart() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(1, harness().sink.cancelAlls)
    }

    @Test fun postsAlertsInTheBackgroundWithTheProjectTitle() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { AgentAlertStep(it.track, alerts = listOf(alert())) }
        h.state.value = h.state.value.copy(projects = listOf(Project("p1", "Trefoil")), threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertEquals(listOf(alert() to "Trefoil"), h.sink.posts.distinct())
    }

    @Test fun unknownProjectPostsWithoutProjectTitle() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { AgentAlertStep(it.track, alerts = listOf(alert(project = "gone"))) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "gone", "x")))
        runCurrent()
        assertNull(h.sink.posts.last().second)
    }

    @Test fun foregroundDropsAlertsButTheTrackAdvances() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        val advanced = AgentAlertTrack("env1", worked = setOf("marker"))
        h.respond = { AgentAlertStep(advanced, alerts = listOf(alert())) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertTrue(h.sink.posts.isEmpty())
        // The next observation starts from the advanced track, and going to the background does not replay the dropped alert.
        h.respond = { AgentAlertStep(it.track) }
        h.driver.setForeground(false)
        runCurrent()
        assertEquals(advanced, h.calls.last().track)
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun settingOffDropsAlertsAndRemovesShownOnes() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(enabled = true)
        h.driver.setForeground(false)
        val before = h.sink.cancelAlls
        h.setting.value = false
        runCurrent()
        assertEquals(before + 1, h.sink.cancelAlls)
        h.respond = { AgentAlertStep(it.track, alerts = listOf(alert())) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun disabledSystemNotificationsPostNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(allowed = false)
        h.driver.setForeground(false)
        h.respond = { AgentAlertStep(it.track, alerts = listOf(alert())) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun clearAlwaysCancelsOnTheWatchedMachine() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(enabled = false)
        h.respond = { AgentAlertStep(AgentAlertTrack("env1")) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("a", "p1", "x")))
        runCurrent()
        h.respond = { AgentAlertStep(it.track, clear = setOf("t1", "t2")) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("b", "p1", "x")))
        runCurrent()
        assertTrue(h.sink.cancels.containsAll(listOf("env1" to "t1", "env1" to "t2")))
    }

    @Test fun resetClearCancelsThePreviousMachinesNotifications() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.respond = { AgentAlertStep(AgentAlertTrack("env1")) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("a", "p1", "x")))
        runCurrent()
        val other = SavedEnvironment("env2", "Other", "https://other.invalid", "fixture")
        h.respond = { AgentAlertStep(AgentAlertTrack("env2"), clear = setOf("t1")) }
        h.state.value = h.state.value.copy(environment = other)
        runCurrent()
        assertTrue(h.sink.cancels.contains("env1" to "t1"))
        assertEquals("env2", h.calls.last().environmentId)
    }

    @Test fun stepReceivesConnectedAndFreshFromTheClientState() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        assertTrue(h.calls.last().connected); assertTrue(h.calls.last().fresh)
        // The selected conversation refreshing its detail (canRead false) leaves the live shell fresh: other threads still alert.
        h.state.value = h.state.value.copy(canRead = false, selectedThreadId = "open", threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertTrue(h.calls.last().connected); assertTrue(h.calls.last().fresh)
        h.state.value = h.state.value.copy(shellLive = false)
        runCurrent()
        assertTrue(h.calls.last().connected); assertTrue(!h.calls.last().fresh)
        h.state.value = h.state.value.copy(connection = ConnectionStatus.Reconnecting)
        runCurrent()
        assertTrue(!h.calls.last().connected)
    }

    @Test fun observesAgainAtTheRecheckTimeOnly() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.respond = { call -> AgentAlertStep(call.track, recheckAtMillis = if (call.now < 5_000) 5_000L else null) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        val before = h.calls.size
        advanceTimeBy(4_999)
        assertEquals(before, h.calls.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(before + 1, h.calls.size)
        assertEquals(5_000L, h.calls.last().now)
        advanceTimeBy(60_000)
        assertEquals(before + 1, h.calls.size)
    }

    @Test fun recheckTickReadsTheLatestStateAndPresenceAtTheDeadline() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { call -> if (call.now >= 5_000) AgentAlertStep(call.track, alerts = listOf(alert(kind = AgentAlertKind.Finished)))
            else AgentAlertStep(call.track, recheckAtMillis = 5_000L) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "old")))
        runCurrent()
        advanceTimeBy(3_000); runCurrent()
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "new")))
        runCurrent()
        val before = h.calls.size
        advanceTimeBy(2_000); runCurrent()
        // Exactly one observation at the deadline (the replaced tick never fires), and it sees the newest rows.
        assertEquals(before + 1, h.calls.size)
        assertEquals("new", h.calls.last().threads.single().title)
        assertEquals(AgentAlertKind.Finished, h.sink.posts.single().first.kind)
    }

    @Test fun recheckTickInTheForegroundPostsNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { call -> if (call.now >= 5_000) AgentAlertStep(call.track, alerts = listOf(alert(kind = AgentAlertKind.Finished)))
            else AgentAlertStep(call.track, recheckAtMillis = 5_000L) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        advanceTimeBy(4_000); runCurrent()
        h.driver.setForeground(true)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(h.calls.any { it.now >= 5_000 })
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun recheckTickAfterNotificationsWereBlockedPostsNothing() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { call -> if (call.now >= 5_000) AgentAlertStep(call.track, alerts = listOf(alert(kind = AgentAlertKind.Finished)))
            else AgentAlertStep(call.track, recheckAtMillis = 5_000L) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        h.sink.allowed = false
        advanceTimeBy(5_000); runCurrent()
        assertTrue(h.calls.any { it.now >= 5_000 })
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun recheckAlertPostsInTheBackground() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { call -> if (call.now >= 5_000) AgentAlertStep(call.track, alerts = listOf(alert(kind = AgentAlertKind.Finished)))
            else AgentAlertStep(call.track, recheckAtMillis = 5_000L) }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertTrue(h.sink.posts.isEmpty())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(AgentAlertKind.Finished, h.sink.posts.single().first.kind)
    }

    /** A completion held from [start] (epoch millis) that confirms at 5 s. */
    private fun holdingStep(start: Long = 0L): (StepCall) -> AgentAlertStep = { call ->
        if (call.now >= 5_000) AgentAlertStep(call.track.copy(pendingCompletions = emptyMap()), alerts = listOf(alert(kind = AgentAlertKind.Finished)))
        else AgentAlertStep(call.track.copy(pendingCompletions = mapOf("t1" to start)), recheckAtMillis = 5_000L)
    }

    @Test fun aCompletionFirstSeenInTheForegroundNeverPostsAfterBackgrounding() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.respond = holdingStep()
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        advanceTimeBy(3_000); runCurrent()
        h.driver.setForeground(false)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(h.calls.any { it.now >= 5_000 })
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun aCompletionFirstSeenWithTheSettingOffNeverPostsAfterItIsTurnedOn() = runTest(UnconfinedTestDispatcher()) {
        val h = harness(enabled = false)
        h.driver.setForeground(false)
        h.respond = holdingStep()
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        h.setting.value = true; runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(h.calls.any { it.now >= 5_000 })
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun aCompletionHeldInTheBackgroundPosts() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = holdingStep()
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(AgentAlertKind.Finished, h.sink.posts.single().first.kind)
    }

    @Test fun aTerminalAlertWhoseRunChangedWhileTheStepRanIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        val evaluated = ThreadSummary("t1", "p1", "x", latestRunId = "r1")
        val marker = AgentAlertTrack("evaluated")
        h.respond = { call ->
            if (call.threads.single().latestRunId == "r1") {
                // The live state moves to another run while this step runs.
                h.state.value = h.state.value.copy(threads = listOf(evaluated.copy(latestRunId = "r2")))
                AgentAlertStep(marker, alerts = listOf(alert(kind = AgentAlertKind.Failed)))
            } else AgentAlertStep(call.track)
        }
        h.state.value = h.state.value.copy(threads = listOf(evaluated))
        runCurrent()
        assertTrue(h.sink.posts.isEmpty())
        // The next observation saw the new run with the track from before the dropped step.
        assertEquals("r2", h.calls.last().threads.single().latestRunId)
        assertTrue(h.calls.last().track != marker)
    }

    @Test fun aTerminalAlertIsDroppedWhenTheShellStoppedBeingFresh() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.respond = { call ->
            if (call.fresh) {
                h.state.value = h.state.value.copy(shellLive = false)
                AgentAlertStep(call.track, alerts = listOf(alert(kind = AgentAlertKind.Finished)))
            } else AgentAlertStep(call.track)
        }
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertTrue(h.sink.posts.isEmpty())
    }

    @Test fun shownThreadInTheForegroundCancelsItsNotification() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.state.value = h.state.value.copy(selectedThreadId = "t1")
        runCurrent()
        assertTrue(h.sink.cancels.isEmpty())
        h.driver.setThreadShown(true)
        runCurrent()
        assertEquals(listOf("env1" to "t1"), h.sink.cancels)
        // Only a change of what is shown cancels again.
        h.state.value = h.state.value.copy(threads = listOf(ThreadSummary("t1", "p1", "x")))
        runCurrent()
        assertEquals(1, h.sink.cancels.size)
        h.state.value = h.state.value.copy(selectedThreadId = "t2")
        runCurrent()
        assertEquals("env1" to "t2", h.sink.cancels.last())
    }

    @Test fun threadShownInTheBackgroundDoesNotCancel() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        h.driver.setForeground(false)
        h.driver.setThreadShown(true)
        h.state.value = h.state.value.copy(selectedThreadId = "t1")
        runCurrent()
        assertTrue(h.sink.cancels.isEmpty())
        h.driver.setForeground(true)
        runCurrent()
        assertEquals(listOf("env1" to "t1"), h.sink.cancels)
    }

    @Test fun cancelAllGoesToTheSink() = runTest(UnconfinedTestDispatcher()) {
        val h = harness()
        val before = h.sink.cancelAlls
        h.driver.cancelAll()
        assertEquals(before + 1, h.sink.cancelAlls)
    }
}

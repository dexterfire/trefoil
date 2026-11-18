package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PendingBackgroundTask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class BackgroundWorkProjectionTest {
    private fun fixture(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun projection(runs: String, extra: String = "") = fixture("""{"thread":{"id":"t","activeProviderThreadId":"pt"},"runs":[$runs]$extra}""")
    private val roster = ""","providerThreads":[
        {"id":"pt","providerInstanceId":"claude","pendingBackgroundTasks":[
          {"taskId":"turn-item:delegated-task:a","kind":"subagent","description":"  Stream A: boundary ingestion (OSM) ","childThreadId":"thread:delegated-task:a"},
          {"taskId":"dev","kind":"command","description":"npm run dev"},
          {"taskId":"odd","kind":"future_kind"},{"taskId":"","kind":"subagent"}]},
        {"id":"other","pendingBackgroundTasks":[{"taskId":"stale","kind":"subagent"}]}]"""

    @Test fun settledRunListsTheActiveProviderRosterAndToleratesUnknownKinds() {
        for (status in listOf("completed", "waiting", "interrupted", "failed", "cancelled")) {
            val work = pendingBackgroundWork(projection("""{"id":"r1","ordinal":1,"status":"$status"}""", roster))
            assertEquals(status, listOf(
                PendingBackgroundTask("turn-item:delegated-task:a", "subagent", "Stream A: boundary ingestion (OSM)", "thread:delegated-task:a"),
                PendingBackgroundTask("dev", "command", "npm run dev"),
                PendingBackgroundTask("odd", "background_task"),
            ), work)
        }
    }

    @Test fun liveOrUnsettledRunsListNothing() {
        for (status in listOf("preparing", "starting", "running")) {
            assertEquals(emptyList<PendingBackgroundTask>(), pendingBackgroundWork(projection(
                """{"id":"r1","ordinal":1,"status":"completed"},{"id":"r2","ordinal":2,"status":"$status"}""", roster)))
        }
        assertTrue(pendingBackgroundWork(projection("""{"id":"r1","ordinal":1,"status":"rolled_back"}""", roster)).isEmpty())
        assertTrue(pendingBackgroundWork(projection("""{"id":"r1","ordinal":1,"status":"queued"}""", roster)).isEmpty())
        // A held queue entry is skipped, so the completed run before it is the latest.
        assertEquals(3, pendingBackgroundWork(projection(
            """{"id":"r1","ordinal":1,"status":"completed"},{"id":"r2","ordinal":2,"status":"queued","queueHeld":true}""", roster)).size)
        assertTrue(pendingBackgroundWork(fixture("""{}""")).isEmpty())
    }

    @Test fun activeBackgroundTurnItemsJoinByNativeIdAndSkipPersistentToolsAndRolledBackRuns() {
        val items = ""","turnItems":[
            {"id":"i1","type":"subagent","status":"running","runId":"r1","title":null,"prompt":"Stream B: tiles","childThreadId":"child-b"},
            {"id":"i2","type":"command_execution","status":"waiting","runId":"r1","input":"  tail -f log  ","monitor":true},
            {"id":"i3","type":"dynamic_tool","status":"pending","runId":"r1","toolName":"t3_thread_wait"},
            {"id":"i4","type":"dynamic_tool","status":"running","runId":"r1","input":{"persistent":true}},
            {"id":"i5","type":"subagent","status":"running","runId":"r0"},
            {"id":"i6","type":"subagent","status":"completed","runId":"r1"},
            {"id":"i7","type":"file_change","status":"running","runId":"r1"},
            {"id":"i8","type":"subagent","status":"running","runId":"r1","nativeItemRef":{"nativeId":"dev"}}]"""
        val work = pendingBackgroundWork(projection("""{"id":"r0","ordinal":1,"status":"rolled_back"},{"id":"r1","ordinal":2,"status":"completed"}""", roster + items))
        assertEquals(listOf("turn-item:delegated-task:a", "dev", "odd", "i1", "i2", "i3"), work.map { it.taskId })
        assertEquals(PendingBackgroundTask("i1", "subagent", "Stream B: tiles", "child-b"), work[3])
        assertEquals(PendingBackgroundTask("i2", "monitor", "tail -f log"), work[4])
        assertEquals(PendingBackgroundTask("i3", "background_task", "t3_thread_wait"), work[5])
    }

    @Test fun pullRequestWatchesHoldUntilChecksFinishAndListWithoutRuns() {
        val prs = """{"id":"t","pullRequests":[
            {"host":"GitHub.com","repository":"Acme/App","number":7,"source":"agent","watch":{"checksPending":true},"snapshot":{"state":"open"}},
            {"host":"github.com","repository":"acme/app","number":8,"watch":{"checksPending":false}},
            {"host":"github.com","repository":"acme/app","number":9,"watch":{},"snapshot":{"state":"merged"}},
            {"host":"github.com","repository":"acme/app","number":10,"source":"stack-dismissed","watch":{}},
            {"host":"github.com","repository":"acme/app","number":11}]}"""
        val work = pendingBackgroundWork(fixture("""{"thread":$prs,"runs":[]}"""))
        assertEquals(listOf(
            PendingBackgroundTask("pull-request-watch:github.com/acme/app#7", "monitor", "Watching pull request #7", awaited = true),
            PendingBackgroundTask("pull-request-watch:github.com/acme/app#8", "monitor", "Watching pull request #8"),
        ), work)
    }

    @Test fun waitingRunTurnEndsWhenItsLatestProviderTurnSettled() {
        fun runs(turnStatus: String, runStatus: String = "waiting") = conversationRuns(fixture("""{"runs":[{"id":"r","status":"$runStatus","activeAttemptId":"a2"}],
            "providerTurns":[{"id":"t1","runAttemptId":"a2","ordinal":1,"status":"running"},{"id":"t2","runAttemptId":"a2","ordinal":2,"status":"$turnStatus"},
            {"id":"x","runAttemptId":"a1","ordinal":3,"status":"running"}]}""")).single().turnEnded
        assertTrue(runs("completed"))
        assertTrue(runs("interrupted", runStatus = "running"))
        assertFalse(runs("running"))
        assertFalse(runs("pending"))
        assertFalse(runs("completed", runStatus = "completed"))
        assertFalse(conversationRuns(fixture("""{"runs":[{"id":"r","status":"waiting","activeAttemptId":"a2"}]}""")).single().turnEnded)
    }
}

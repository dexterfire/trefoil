package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.PendingBackgroundTask
import com.tajchert.trefoil.core.model.QueuedMessage
import com.tajchert.trefoil.core.model.Subagent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ThreadQueueProjectionTest {
  private fun projection(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject
  private fun session(turns: String?) = """
    "thread":{"id":"t","activeProviderThreadId":"pt"},
    "providerThreads":[{"id":"pt","appThreadId":"t","providerSessionId":"ps"}],
    "providerSessions":[{"id":"ps","status":"running","capabilities":{${turns?.let { "\"turns\":$it" } ?: ""}}}]"""
  private fun active(status: String = "running", turns: String? = """{"supportsActiveSteering":true}""", items: String = "[]", userText: String = "go") = projection("""{${session(turns)},
    "messages":[{"id":"m-1","role":"user","text":"$userText"}],
    "runs":[{"id":"r","status":"$status","ordinal":1,"userMessageId":"m-1","activeAttemptId":"a"}],
    "providerTurns":[{"runAttemptId":"a","status":"running"}],
    "visibleTurnItems":$items}""")

  @Test fun queuedMessagesFollowT3OrderTextAndWakeRules() {
    val value = projection("""{"messages":[
        {"id":"m-a","role":"user","text":"first"},
        {"id":"m-b","role":"user","text":"typed","context":{"guest":{"text":"guest words"}}},
        {"id":"m-wake","role":"user","text":"done","delegatedCompletion":{"taskId":"x"}},
        {"id":"m-note","role":"user","text":"note","notification":{"kind":"pr"}}],
      "runs":[
        {"id":"done","status":"completed","ordinal":1,"userMessageId":"m-0"},
        {"id":"q-a","status":"queued","ordinal":2,"queuePosition":1,"userMessageId":"m-a"},
        {"id":"q-b","status":"queued","ordinal":3,"queuePosition":0,"userMessageId":"m-b","queueHeld":true},
        {"id":"q-wake","status":"queued","ordinal":4,"userMessageId":"m-wake"},
        {"id":"q-note","status":"queued","ordinal":5,"userMessageId":"m-note"},
        {"id":"q-lost","status":"queued","ordinal":6,"userMessageId":"m-gone"}]}""")
    assertEquals(listOf(
      QueuedMessage("q-b", "m-b", "guest words", canSendNow = true),
      QueuedMessage("q-a", "m-a", "first", canSendNow = true),
      QueuedMessage("q-lost", "m-gone", QUEUED_MESSAGE_FALLBACK, canSendNow = true),
    ), queuedMessages(value))
    assertTrue(queueHeld(value))
  }

  @Test fun heldWakeHoldsTheQueueEvenWhenNotListed() {
    val value = projection("""{"messages":[{"id":"m-wake","role":"user","text":"done","delegatedCompletion":{}}],
      "runs":[{"id":"q-wake","status":"queued","ordinal":1,"userMessageId":"m-wake","queueHeld":true}]}""")
    assertTrue(queuedMessages(value).isEmpty()); assertTrue(queueHeld(value))
    assertFalse(queueHeld(projection("""{"runs":[{"id":"r","status":"completed","queueHeld":true}]}""")))
  }

  @Test fun canSteerFollowsProviderCapabilities() {
    assertTrue(canSteer(active()))
    assertTrue(canSteer(active(turns = """{"supportsActiveSteering":false,"supportsSteeringByInterruptRestart":true}""")))
    assertFalse(canSteer(active(turns = """{"supportsActiveSteering":false,"supportsSteeringByInterruptRestart":false}""")))
    assertFalse("unknown capabilities never steer a running turn", canSteer(active(turns = null)))
    assertTrue("a setup-phase run with unknown capabilities may steer", canSteer(active(status = "starting", turns = null)))
    assertFalse(canSteer(active(status = "waiting")))
    assertFalse(canSteer(projection("""{"runs":[{"id":"r","status":"completed"}]}""")))
  }

  @Test fun compactionBlocksSteeringUnlessTheProviderSteersThroughIt() {
    val compacting = """[{"position":1,"item":{"id":"c","type":"compaction","status":"running","runId":"r"}}]"""
    assertFalse(canSteer(active(items = compacting)))
    assertTrue(canSteer(active(items = compacting, turns = """{"supportsActiveSteering":true,"activeSteeringDuringCompaction":true}""")))
    val compactCommand = """[{"position":1,"item":{"id":"u","type":"user_message","text":"/compact","attachments":[],"status":"completed","runId":"r"}}]"""
    assertFalse("an unfinished /compact turn blocks", canSteer(active(items = compactCommand)))
    val compactFinished = """[{"position":1,"item":{"id":"u","type":"user_message","text":"/compact","attachments":[],"runId":"r"}},
      {"position":2,"item":{"id":"c","type":"compaction","status":"completed","runId":"r"}}]"""
    assertTrue(canSteer(active(items = compactFinished)))
  }

  @Test fun canSendNowFollowsT3QueueEligibility() {
    fun queued(value: JsonObject, freshContext: Boolean = false) = canSendQueuedNow(value, projection("""{"id":"q","status":"queued","freshContext":$freshContext}"""))
    assertTrue("idle threads start the queued run", queued(projection("""{"thread":{"id":"t"},"runs":[]}""")))
    assertFalse(queued(projection("""{"thread":{"id":"t","archivedAt":"2026-01-01T00:00:00Z"},"runs":[]}""")))
    assertFalse(queued(projection("""{"thread":{"id":"t","deletedAt":"2026-01-01T00:00:00Z"},"runs":[]}""")))
    assertTrue(queued(active()))
    assertFalse("promotion ignores the composer's compaction block only", queued(active(turns = """{"supportsActiveSteering":false}""")))
    val compacting = """[{"position":1,"item":{"id":"c","type":"compaction","status":"running","runId":"r"}}]"""
    assertFalse(canSteer(active(items = compacting))); assertTrue(queued(active(items = compacting)))
    assertFalse("a fresh-context message cannot steer", queued(active(), freshContext = true))
    assertTrue("a /compact turn is skipped instead", queued(active(turns = null, userText = "/compact"), freshContext = true))
  }

  @Test fun canStopCoversRunsBackgroundWorkAndActiveSubagents() {
    fun agent(status: String) = Subagent(id = "s", threadId = "t", runId = "r", origin = "app_owned", driver = "codex", status = status)
    assertFalse(canStopThread(null, emptyList(), emptyList()))
    assertTrue(canStopThread("r", emptyList(), emptyList()))
    assertTrue(canStopThread(null, listOf(PendingBackgroundTask("cmd", "command", null)), emptyList()))
    assertTrue(canStopThread(null, emptyList(), listOf(agent("running"))))
    assertFalse(canStopThread(null, emptyList(), listOf(agent("completed"))))
  }

  @Test fun stopRunTargetPrefersTheLiveRunThenBackgroundWork() {
    assertEquals("r2", stopRunTarget(projection("""{"runs":[{"id":"r1","status":"completed","ordinal":1},{"id":"r2","status":"waiting","ordinal":2}]}""")))
    val background = projection("""{"thread":{"id":"t"},"runs":[{"id":"r1","status":"completed","ordinal":1},{"id":"q","status":"queued","ordinal":2,"queueHeld":true}],
      "turnItems":[{"id":"cmd","type":"command_execution","status":"running","runId":"r1"}]}""")
    assertEquals("r1", stopRunTarget(background))
    val watchOnly = projection("""{"thread":{"id":"t","pullRequests":[{"host":"github.com","repository":"o/r","number":7,"watch":{"checksPending":true}}]},
      "runs":[{"id":"r1","status":"completed","ordinal":1}],"turnItems":[]}""")
    assertNull(stopRunTarget(watchOnly))
    val unwatch = pullRequestUnwatches(watchOnly).single()
    assertEquals("""{"host":"github.com","repository":"o/r","number":7,"watching":false}""", unwatch.toString())
    val links = projection("""{"thread":{"id":"t","pullRequests":[
      {"host":"github.com","repository":"o/r","number":1,"watch":{},"snapshot":{"state":"merged"}},
      {"host":"github.com","repository":"o/r","number":2,"watch":{},"source":"stack-dismissed"},
      {"host":"github.com","repository":"o/r","number":3}]}}""")
    assertEquals("a merged link with a live watch is still unwatched", listOf("1"), pullRequestUnwatches(links).map { it["number"].toString() })
  }
}

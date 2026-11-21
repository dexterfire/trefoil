package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Steer/queue follow-ups, queued-run commands and thread Stop over the fixture socket; never real threads. */
class FollowUpQueueTest {
  private val descriptor = """{"environmentId":"env-1","label":"Test","orchestrationProtocolVersion":2,"capabilities":{}}"""
  private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

  private fun session(steering: Boolean) = """
    "thread":{"id":"parent","activeProviderThreadId":"pt-1"},
    "providerThreads":[{"id":"pt-1","appThreadId":"parent","providerSessionId":"ps-1"}],
    "providerSessions":[{"id":"ps-1","status":"running","capabilities":{"turns":{"supportsActiveSteering":$steering,"supportsSteeringByInterruptRestart":false}}}]"""
  private val messages = """"messages":[{"id":"m-1","role":"user","text":"go"},{"id":"m-q","role":"user","text":"later please"}]"""
  private fun busy(steering: Boolean = true) = """{${session(steering)},$messages,
    "runs":[{"id":"run-1","status":"running","ordinal":1,"userMessageId":"m-1","activeAttemptId":"att-1"},
            {"id":"q-1","status":"queued","ordinal":2,"userMessageId":"m-q"}],
    "providerTurns":[{"id":"turn-1","runAttemptId":"att-1","status":"running","ordinal":1}],
    "turnItems":[],"runtimeRequests":[],"subagents":[]}"""
  /** An unheld queued run is about to start, so T3 reports no background work behind it: [queued] false omits it. */
  private fun idle(held: Boolean = false, queued: Boolean = true, extra: String = """"turnItems":[],"subagents":[]""") = """{${session(true)},$messages,
    "runs":[{"id":"run-1","status":"completed","ordinal":1,"userMessageId":"m-1"}${if (queued) """,
            {"id":"q-1","status":"queued","ordinal":2,"userMessageId":"m-q","queueHeld":$held}""" else ""}],
    "providerTurns":[],"runtimeRequests":[],$extra}"""
  private val activeSubagents = """[
    {"id":"native","threadId":"parent","runId":"run-1","origin":"provider_native","driver":"claudeAgent","childThreadId":"child-native",
     "nativeTaskRef":{"driver":"claudeAgent","nativeId":"toolu_1","strength":"strong"},"prompt":"p","status":"running"},
    {"id":"task","threadId":"parent","runId":"run-1","origin":"app_owned","driver":"codex","childThreadId":"child","nativeTaskRef":null,"prompt":"p","status":"running"},
    {"id":"done","threadId":"parent","runId":"run-1","origin":"app_owned","driver":"codex","childThreadId":"child-done","nativeTaskRef":null,"prompt":"p","status":"completed"}]"""
  /** Parent run finished; only its subagents still work (the user's gap). */
  private val subagentsOnly = idle(queued = false, extra = """"turnItems":[],"subagents":$activeSubagents""")
  private val backgroundOnly = idle(queued = false, extra = """"turnItems":[{"id":"cmd-1","type":"command_execution","status":"running","runId":"run-1","title":"npm test"}],"subagents":[]""")
  private val unstoppableSubagent = idle(queued = false, extra = """"turnItems":[],"subagents":[{"id":"codex-native","threadId":"parent","runId":"run-1","origin":"provider_native","driver":"codex","prompt":"p","status":"running"}]""")
  private val childProjection = """{"thread":{"id":"child"},"runs":[{"id":"child-run-1","status":"completed"},{"id":"child-run-2","status":"running"}]}"""

  private class Harness(val client: T3Client, val dispatched: Channel<JsonObject>, val reads: Channel<String>) {
    fun sent(): List<JsonObject> = generateSequence { dispatched.tryReceive().getOrNull() }.toList()
  }

  private fun connected(projection: String, threadStop: Boolean? = null, reject: Set<String> = emptySet(), block: suspend Harness.() -> Unit) = runBlocking {
    val server = MockWebServer(); server.start()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val client = T3Client(scope)
    val dispatched = Channel<JsonObject>(Channel.UNLIMITED); val reads = Channel<String>(Channel.UNLIMITED)
    val capabilities = threadStop?.let { """{"threadStop":$it}""" } ?: "{}"
    val listener = object : WebSocketListener() {
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
      override fun onMessage(webSocket: WebSocket, text: String) {
        val frame = EffectRpc.decode(text).single(); if (frame.string("_tag") != "Request") return
        val id = frame.string("id")
        fun success(value: String) = webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""")
        when (frame.string("tag")) {
          "server.getConfig" -> success("""{"providers":[],"environment":{"capabilities":$capabilities}}""")
          "orchestration.subscribeShell" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}},{"kind":"synchronized"}]}""")
          "orchestration.subscribeThread" -> webSocket.send("""{"_tag":"Chunk","requestId":"$id","values":[{"kind":"snapshot","projection":$projection},{"kind":"synchronized"}]}""")
          "orchestration.getThreadProjection" -> { val threadId = frame.obj("payload").string("threadId"); reads.trySend(threadId); if (threadId == "child") success(childProjection) }
          "orchestration.dispatchCommand" -> {
            val payload = frame.obj("payload"); dispatched.trySend(payload)
            if (payload.string("type") in reject) webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"OrchestrationCommandRejected"}}]}}""")
            else success("""{"sequence":9}""")
          }
        }
      }
    }
    try {
      server.enqueue(json(descriptor)); server.enqueue(json("""{"authenticated":true,"permissions":["orchestration:operate"]}""")); server.enqueue(json("""{"ticket":"ticket"}""")); server.enqueue(MockResponse().withWebSocketUpgrade(listener))
      client.connect(SavedEnvironment("env-1", "Test", server.url("/").toString(), "secret"))
      withTimeout(5000) { client.state.first { it.canWrite } }; client.selectThread("parent")
      withTimeout(5000) { client.state.first { it.canWrite && it.selectedThreadId == "parent" && it.runs.isNotEmpty() } }
      Harness(client, dispatched, reads).block()
    } finally { client.disconnect(); scope.cancel(); runCatching { server.shutdown() } }
  }

  private fun JsonObject.flag(key: String) = this[key]?.jsonPrimitive?.booleanOrNull
  private fun assertMessage(payload: JsonObject, dispatchMode: String, deliveryIntent: String?) {
    assertEquals("message.dispatch", payload.string("type")); assertEquals("parent", payload.string("threadId"))
    assertEquals(dispatchMode, payload.obj("dispatchMode").string("type"))
    assertEquals(deliveryIntent, payload["deliveryIntent"]?.jsonPrimitive?.content)
    assertEquals("user", payload.string("createdBy")); assertEquals("mobile", payload.string("creationSource"))
    assertEquals("hi", payload.string("text")); assertTrue(payload.string("messageId").isNotBlank())
  }

  @Test fun busySteerableThreadProjectsQueueSteerAndStop() = connected(busy()) {
    val state = client.state.value
    assertTrue(state.canSteer); assertTrue(state.canStop); assertFalse(state.queueHeld)
    assertEquals(listOf(QueuedMessage("q-1", "m-q", "later please")), state.queuedMessages)
  }

  @Test fun steerStartsImmediatelyWithSteerIntent() = connected(busy()) {
    client.sendMessage("hi", FollowUpMode.Steer)
    assertMessage(sent().single(), "start_immediately", "steer")
  }

  @Test fun queueWaitsBehindTheActiveRunWithoutIntent() = connected(busy()) {
    client.sendMessage("hi", FollowUpMode.Queue)
    assertMessage(sent().single(), "queue_after_active", null)
  }

  @Test fun idleThreadStartsImmediatelyInEitherMode() = connected(idle()) {
    assertFalse(client.state.value.canSteer); assertFalse(client.state.value.canStop)
    client.sendMessage("hi", FollowUpMode.Steer); client.sendMessage("hi", FollowUpMode.Queue)
    sent().also { assertEquals(2, it.size) }.forEach { assertMessage(it, "start_immediately", null) }
  }

  @Test fun steerIsRefusedLocallyWhenTheTurnCannotSteer() = connected(busy(steering = false)) {
    assertFalse(client.state.value.canSteer); assertTrue(client.state.value.canStop)
    assertTrue(runCatching { client.sendMessage("hi", FollowUpMode.Steer) }.exceptionOrNull() is IllegalStateException)
    assertTrue(sent().isEmpty()); assertTrue(client.state.value.canWrite)
    client.sendMessage("hi", FollowUpMode.Queue)
    assertMessage(sent().single(), "queue_after_active", null)
  }

  @Test fun stopWithThreadStopSendsOneThreadStop() = connected(busy(), threadStop = true) {
    client.stopThread()
    val stop = sent().single()
    assertEquals("thread.stop", stop.string("type")); assertEquals("parent", stop.string("threadId"))
    assertEquals(setOf("type", "commandId", "threadId"), stop.keys)
  }

  @Test fun stopWithoutThreadStopInterruptsTheLiveRunHoldingTheQueue() = connected(busy()) {
    client.stopThread()
    val interrupt = sent().single()
    assertEquals("run.interrupt", interrupt.string("type")); assertEquals("run-1", interrupt.string("runId"))
    assertEquals(true, interrupt.flag("holdQueue"))
  }

  @Test fun backgroundWorkOnlyStopInterruptsTheLatestRun() = connected(backgroundOnly) {
    val state = client.state.value
    assertNull(state.activeRunId); assertEquals(listOf("cmd-1"), state.backgroundWork.map { it.taskId }); assertTrue(state.canStop)
    client.stopThread()
    val interrupt = sent().single()
    assertEquals("run.interrupt", interrupt.string("type")); assertEquals("run-1", interrupt.string("runId"))
    assertEquals(true, interrupt.flag("holdQueue"))
  }

  @Test fun subagentsOutlivingTheRunCanBeStopped() = connected(subagentsOnly) {
    val state = client.state.value
    assertNull(state.activeRunId); assertTrue(state.backgroundWork.isEmpty()); assertTrue(state.canStop)
  }

  @Test fun subagentOnlyStopWithThreadStopSendsOneThreadStop() = connected(subagentsOnly, threadStop = true) {
    client.stopThread()
    val stop = sent().single()
    assertEquals("thread.stop", stop.string("type")); assertEquals("parent", stop.string("threadId"))
    assertTrue("thread.stop needs no child read", reads.tryReceive().isFailure)
  }

  @Test fun subagentOnlyStopWithoutThreadStopStopsEachStoppableAgentOnce() = connected(subagentsOnly) {
    client.stopThread()
    val sent = sent()
    assertEquals(listOf("subagent.stop", "subagent.stop", "run.interrupt"), sent.map { it.string("type") })
    assertEquals(listOf("native", "task"), sent.take(2).map { it.string("subagentId") })
    assertTrue(sent.take(2).all { it.string("threadId") == "parent" && it.string("runId") == "run-1" })
    assertEquals("child", sent[2].string("threadId")); assertEquals("child-run-2", sent[2].string("runId")); assertEquals(true, sent[2].flag("holdQueue"))
    assertEquals(3, sent.map { it.string("commandId") }.toSet().size)
  }

  @Test fun rejectedAgentStopStillStopsTheOthersThenReports() = connected(subagentsOnly, reject = setOf("subagent.stop")) {
    assertTrue(runCatching { client.stopThread() }.exceptionOrNull() is IllegalStateException)
    assertEquals(listOf("subagent.stop", "subagent.stop", "run.interrupt"), sent().map { it.string("type") })
    assertTrue(client.state.value.canWrite)
  }

  @Test fun unstoppableSubagentOnlyFailsClearlyAndSendsNothing() = connected(unstoppableSubagent) {
    assertTrue(client.state.value.canStop)
    val error = runCatching { client.stopThread() }.exceptionOrNull()
    assertTrue(error?.message.orEmpty().contains("cannot be stopped"))
    assertTrue(sent().isEmpty())
  }

  @Test fun nothingRunningStopFailsAndSendsNothing() = connected(idle()) {
    assertTrue(runCatching { client.stopThread() }.isFailure)
    assertTrue(sent().isEmpty())
  }

  @Test fun queuedRunCommands() = connected(busy()) {
    client.cancelQueuedMessage("q-1")
    client.steerQueuedMessage("q-1")
    val (cancel, promote) = sent()
    assertEquals("queued-run.cancel", cancel.string("type")); assertEquals("q-1", cancel.string("runId"))
    assertEquals(setOf("type", "commandId", "threadId", "runId"), cancel.keys)
    assertEquals("queued-message.promote-to-steer", promote.string("type"))
    assertEquals("q-1", promote.string("queuedRunId")); assertEquals("run-1", promote.string("targetRunId"))
    assertTrue(runCatching { client.cancelQueuedMessage("missing") }.isFailure)
    assertTrue(runCatching { client.resumeQueue() }.isFailure)
    assertTrue(sent().isEmpty())
  }

  @Test fun promoteIsRefusedWhenTheTurnCannotSteer() = connected(busy(steering = false)) {
    assertTrue(runCatching { client.steerQueuedMessage("q-1") }.isFailure)
    assertTrue(sent().isEmpty())
  }

  @Test fun idleSendNowStartsTheQueuedRunAndHeldQueueResumes() = connected(idle(held = true)) {
    assertTrue(client.state.value.queueHeld); assertEquals(listOf(true), client.state.value.queuedMessages.map { it.held })
    client.steerQueuedMessage("q-1")
    client.resumeQueue()
    val (start, resume) = sent()
    assertEquals("queued-run.start", start.string("type")); assertEquals("q-1", start.string("runId"))
    assertEquals("queue.resume", resume.string("type")); assertEquals(setOf("type", "commandId", "threadId"), resume.keys)
  }

  @Test fun disconnectClearsSteerAndStop() = connected(busy()) {
    client.disconnect()
    assertFalse(client.state.value.canSteer); assertFalse(client.state.value.canStop)
  }
}

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

class PromptSuggestionSessionTest {
    private data class Request(val ws: WebSocket, val id: String, val payload: JsonObject)

    private class Fixture(@Volatile var settings: String) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        val suggestions = Channel<Request>(Channel.UNLIMITED)
        val interrupts = Channel<String>(Channel.UNLIMITED)
        val configStream = Channel<Request>(Channel.UNLIMITED)
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"env","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}""")
                    request.path == "/api/auth/websocket-ticket/rpc" -> json("""{"ticket":"rpc"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            if (frame.string("_tag") == "Interrupt") { interrupts.trySend(frame.string("requestId")); return }
                            if (frame.string("_tag") != "Request") return
                            val id = frame.string("id")
                            when (frame.string("tag")) {
                                "server.getConfig" -> complete(webSocket, id, """{"providers":[],"environment":{"capabilities":{}},"settings":$settings}""")
                                "subscribeServerConfig" -> configStream.trySend(Request(webSocket, id, frame.obj("payload")))
                                "orchestration.subscribeShell" -> chunk(webSocket, id, """{"kind":"snapshot","snapshot":{"projects":[{"id":"p","title":"P","workspaceRoot":"/w"}],"threads":[{"id":"t1","projectId":"p","title":"One"},{"id":"t2","projectId":"p","title":"Two"}]}},{"kind":"synchronized"}""")
                                "orchestration.subscribeThread" -> {
                                    val threadId = frame.obj("payload").string("threadId")
                                    chunk(webSocket, id, """{"kind":"snapshot","projection":{"thread":{"id":"$threadId"},"messages":[],"runs":[],"turnItems":[],"runtimeRequests":[]}},{"kind":"synchronized"}""")
                                }
                                "orchestration.subscribePromptSuggestion" -> suggestions.trySend(Request(webSocket, id, frame.obj("payload")))
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        suspend fun connect() {
            client.connect(SavedEnvironment("env", "Fixture", server.url("/").toString(), "bearer-secret"))
            withTimeout(5000) { client.state.first { it.canWriteThreads } }
        }
        suspend fun open(threadId: String) {
            client.selectThread(threadId)
            withTimeout(5000) { client.state.first { it.selectedThreadId == threadId && it.canWrite } }
        }
        suspend fun nextSuggestionStream(): Request = withTimeout(5000) { suggestions.receive() }
        suspend fun awaitSuggestion(expected: PromptSuggestion?) = withTimeout(5000) { client.state.first { it.promptSuggestion == expected } }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun complete(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":$value}}""") }
            fun chunk(ws: WebSocket, id: String, value: String) { ws.send("""{"_tag":"Chunk","requestId":"$id","values":[$value]}""") }
        }
    }

    @Test fun settingOffNeverSubscribes() = runBlocking<Unit> {
        for (settings in listOf("{}", """{"promptSuggestionsEnabled":false}""", """{"promptSuggestionsEnabled":"true"}""", """{"promptSuggestionsEnabled":1}""")) {
            Fixture(settings).use { f ->
                f.connect(); f.open("t1")
                delay(150)
                assertTrue(settings, f.suggestions.tryReceive().isFailure)
                assertNull(f.client.state.value.promptSuggestion)
            }
        }
    }

    @Test fun settingOnSubscribesTheSelectedThreadAndPublishesItsValues() = runBlocking<Unit> {
        Fixture("""{"promptSuggestionsEnabled":true}""").use { f ->
            f.connect()
            delay(100)
            assertTrue("no thread, no stream", f.suggestions.tryReceive().isFailure)
            f.open("t1")
            val stream = f.nextSuggestionStream()
            assertEquals(buildJsonObject { put("threadId", "t1") }, stream.payload)
            Fixture.chunk(stream.ws, stream.id, """{"runId":"run-1","text":"Run the tests","extra":{"additive":true}}""")
            f.awaitSuggestion(PromptSuggestion("run-1", "Run the tests"))
            Fixture.chunk(stream.ws, stream.id, "null")
            f.awaitSuggestion(null)
            Fixture.chunk(stream.ws, stream.id, """{"runId":"run-2","text":"Ship it"}""")
            f.awaitSuggestion(PromptSuggestion("run-2", "Ship it"))
            // A malformed item is no suggestion, never a session failure.
            Fixture.chunk(stream.ws, stream.id, """{"runId":7,"text":"x"}""")
            f.awaitSuggestion(null)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertNull(f.client.state.value.error)
        }
    }

    @Test fun threadSwitchInterruptsAndClearsAndIgnoresLateItems() = runBlocking<Unit> {
        Fixture("""{"promptSuggestionsEnabled":true}""").use { f ->
            f.connect(); f.open("t1")
            val first = f.nextSuggestionStream()
            Fixture.chunk(first.ws, first.id, """{"runId":"run-1","text":"Old thread"}""")
            f.awaitSuggestion(PromptSuggestion("run-1", "Old thread"))
            f.client.selectThread("t2")
            assertNull(f.client.state.value.promptSuggestion)
            withTimeout(5000) { while (f.interrupts.receive() != first.id) {} }
            val second = f.nextSuggestionStream()
            assertEquals("t2", second.payload.string("threadId"))
            Fixture.chunk(first.ws, first.id, """{"runId":"run-1","text":"Late"}""")
            delay(150)
            assertNull(f.client.state.value.promptSuggestion)
            Fixture.chunk(second.ws, second.id, """{"runId":"run-9","text":"New thread"}""")
            f.awaitSuggestion(PromptSuggestion("run-9", "New thread"))
        }
    }

    @Test fun disconnectClearsAndReconnectSubscribesAgain() = runBlocking<Unit> {
        Fixture("""{"promptSuggestionsEnabled":true}""").use { f ->
            f.connect(); f.open("t1")
            val stream = f.nextSuggestionStream()
            Fixture.chunk(stream.ws, stream.id, """{"runId":"run-1","text":"Suggestion"}""")
            f.awaitSuggestion(PromptSuggestion("run-1", "Suggestion"))
            f.client.disconnect()
            assertNull(f.client.state.value.promptSuggestion)
            f.connect()
            withTimeout(5000) { f.client.state.first { it.selectedThreadId == "t1" && it.canWrite } }
            assertEquals("t1", f.nextSuggestionStream().payload.string("threadId"))
        }
    }

    @Test fun streamFailureIsSilentAndNotRetried() = runBlocking<Unit> {
        Fixture("""{"promptSuggestionsEnabled":true}""").use { f ->
            f.connect(); f.open("t1")
            val stream = f.nextSuggestionStream()
            Fixture.chunk(stream.ws, stream.id, """{"runId":"run-1","text":"Suggestion"}""")
            f.awaitSuggestion(PromptSuggestion("run-1", "Suggestion"))
            stream.ws.send("""{"_tag":"Exit","requestId":"${stream.id}","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"Unknown request tag"}]}}""")
            f.awaitSuggestion(null)
            delay(150)
            val state = f.client.state.value
            assertEquals(ConnectionStatus.Connected, state.connection); assertNull(state.error); assertTrue(state.canWrite)
            assertTrue("no retry", f.suggestions.tryReceive().isFailure)
            // A config snapshot that keeps the setting on does not restart the ended stream either.
            val config = withTimeout(5000) { f.configStream.receive() }
            Fixture.chunk(config.ws, config.id, """{"type":"snapshot","config":{"settings":{"promptSuggestionsEnabled":true}}}""")
            delay(150)
            assertTrue("no retry on snapshot", f.suggestions.tryReceive().isFailure)
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
        }
    }

    @Test fun configStreamTogglesTheSubscription() = runBlocking<Unit> {
        Fixture("{}").use { f ->
            f.connect(); f.open("t1")
            val config = withTimeout(5000) { f.configStream.receive() }
            delay(100)
            assertTrue(f.suggestions.tryReceive().isFailure)
            Fixture.chunk(config.ws, config.id, """{"type":"snapshot","config":{"settings":{"promptSuggestionsEnabled":true}}}""")
            val stream = f.nextSuggestionStream()
            assertEquals("t1", stream.payload.string("threadId"))
            Fixture.chunk(stream.ws, stream.id, """{"runId":"run-1","text":"Suggestion"}""")
            f.awaitSuggestion(PromptSuggestion("run-1", "Suggestion"))
            Fixture.chunk(config.ws, config.id, """{"type":"settingsUpdated","payload":{"settings":{"promptSuggestionsEnabled":false}}}""")
            f.awaitSuggestion(null)
            withTimeout(5000) { while (f.interrupts.receive() != stream.id) {} }
            Fixture.chunk(stream.ws, stream.id, """{"runId":"run-1","text":"Late"}""")
            delay(150)
            assertNull(f.client.state.value.promptSuggestion)
            Fixture.chunk(config.ws, config.id, """{"type":"settingsUpdated","payload":{"settings":{"promptSuggestionsEnabled":true}}}""")
            assertEquals("t1", f.nextSuggestionStream().payload.string("threadId"))
        }
    }

    @Test fun decodesItemsAndSetting() {
        fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
        assertEquals(PromptSuggestion("r", "t"), promptSuggestion(obj("""{"runId":"r","text":"t","more":1}""")))
        assertNull(promptSuggestion(obj("{}")))
        assertNull(promptSuggestion(obj("""{"runId":"r","text":"  "}""")))
        assertNull(promptSuggestion(obj("""{"runId":"r","text":null}""")))
        assertTrue(promptSuggestionsSetting(obj("""{"promptSuggestionsEnabled":true}""")))
        assertFalse(promptSuggestionsSetting(null))
        assertFalse(promptSuggestionsSetting(obj("""{"promptSuggestionsEnabled":"true"}""")))
        assertFalse(promptSuggestionsSetting(obj("""{"promptSuggestionsEnabled":{}}""")))
    }
}

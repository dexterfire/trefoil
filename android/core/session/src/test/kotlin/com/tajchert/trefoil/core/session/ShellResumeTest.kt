package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ShellResumeTest {
    private val origin = ShellOrigin("env", "1.2.3", 40)
    /** Rows as the client hands them over: compared by identity, so equal but different lists never resume. */
    private val rows = ShellRows(mutableListOf<Any>(), mutableListOf<Any>())
    private fun ShellResume.record(item: JsonObject) = applied(item, rows)
    private fun item(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun snapshot(sequence: Long) = item("""{"kind":"snapshot","snapshot":{"snapshotSequence":$sequence,"projects":[],"threads":[]}}""")
    private fun delta(sequence: Long) = item("""{"kind":"thread.removed","sequence":$sequence,"location":"active","threadId":"t"}""")

    @Test fun originNeedsTheServerBuildOfTheSameEnvironment() {
        assertEquals(origin, shellOrigin("env", item("""{"environment":{"environmentId":"env","serverVersion":" 1.2.3 ","databaseMigration":40}}""")))
        assertEquals(ShellOrigin("env", "1.2.3", null), shellOrigin("env", item("""{"environment":{"serverVersion":"1.2.3"}}""")))
        assertNull(shellOrigin("env", item("""{"providers":[]}""")))
        assertNull(shellOrigin("env", item("""{"environment":{"serverVersion":""}}""")))
        assertNull(shellOrigin("env", item("""{"environment":{"environmentId":"other","serverVersion":"1.2.3"}}""")))
        assertNull(shellOrigin("env", item("""{"environment":{"serverVersion":"1.2.3","databaseMigration":"40"}}""")))
    }

    @Test fun inputCarriesTheCursorOnlyWhenResuming() {
        val fresh = shellSubscribeInput(null)
        assertNull(fresh["afterSequence"])
        assertEquals(true, fresh["requestCompletionMarker"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, fresh["omitIdleSubagents"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, fresh["omitOlderSettled"]?.jsonPrimitive?.booleanOrNull)
        assertNull(fresh["httpSnapshotFallback"])
        assertEquals(12L, shellSubscribeInput(12)["afterSequence"]?.jsonPrimitive?.longOrNull)
    }

    @Test fun cursorFollowsAuthoritativeSnapshotsAndDeltasButNotEnrichment() {
        val resume = ShellResume { 1_000 }
        assertNull(resume.begin("env", origin, rows, retainedEnvironments = setOf("env")))
        resume.record(snapshot(10))
        assertEquals(10L, resume.cursor("env")?.sequence)
        resume.record(delta(14)); resume.record(delta(12))
        assertEquals(14L, resume.cursor("env")?.sequence)
        resume.record(item("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/r"],"snapshot":{"snapshotSequence":99,"projects":[],"threads":[]}}"""))
        resume.record(item("""{"kind":"synchronized"}"""))
        assertEquals(14L, resume.cursor("env")?.sequence)
        assertEquals(14L, resume.begin("env", origin, rows, retainedEnvironments = setOf("env")))
        resume.record(snapshot(3))
        assertEquals(3L, resume.cursor("env")?.sequence)
    }

    @Test fun anythingUnaccountedDropsTheCursor() {
        val resume = ShellResume { 1_000 }
        fun started(): ShellResume { resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(10)); return resume }
        started().record(item("""{"kind":"thread.updated","location":"active","thread":{"id":"t"}}"""))
        assertNull(resume.cursor("env"))
        started().record(item("""{"kind":"snapshot","snapshot":{"projects":[],"threads":[]}}"""))
        assertNull(resume.cursor("env"))
        started().record(item("""{"kind":"snapshot-required"}"""))
        assertNull(resume.cursor("env"))
        resume.begin("env", null, rows, setOf("env")); resume.record(snapshot(10))
        assertNull(resume.cursor("env"))
    }

    @Test fun cursorBelongsToTheExactRowsItWasRecordedWith() {
        val resume = ShellResume { 1_000 }
        resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(10))
        val changed = ShellRows(rows.projects, mutableListOf<Any>("enriched"))
        resume.applied(item("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":[],"snapshot":{"snapshotSequence":99,"projects":[],"threads":[]}}"""), changed)
        assertNull("Rows from before the last tracked item", resume.begin("env", origin, rows, setOf("env")))
        resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(10))
        resume.applied(item("""{"kind":"synchronized"}"""), changed)
        assertEquals(10L, resume.begin("env", origin, changed, setOf("env")))
        resume.forget("env")
        assertNull(resume.begin("env", origin, changed, setOf("env")))
    }

    @Test fun resumesOnlyRetainedRowsOfTheSameRecentOrigin() {
        var now = 1_000L
        val resume = ShellResume { now }
        fun cursorAt(sequence: Long) { resume.begin("env", origin, rows, setOf("env")); resume.record(snapshot(sequence)) }
        cursorAt(10)
        assertNull(resume.begin("env", origin, ShellRows(mutableListOf<Any>(), mutableListOf<Any>()), retainedEnvironments = setOf("env")))
        cursorAt(10)
        assertNull(resume.begin("env", origin.copy(serverVersion = "1.2.4"), rows, setOf("env")))
        cursorAt(10)
        assertNull(resume.begin("env", origin.copy(databaseMigration = 41), rows, setOf("env")))
        cursorAt(10)
        assertNull(resume.begin("env", null, rows, setOf("env")))
        cursorAt(10)
        now += SHELL_RESUME_MAX_AGE_MS
        assertNull(resume.begin("env", origin, rows, setOf("env")))
        cursorAt(10)
        resume.begin("other", origin.copy(environmentId = "other"), rows, setOf("other"))
        assertNull("Evicted rows take their cursor along", resume.cursor("env"))
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = T3Client(scope)
        val server = MockWebServer()
        val shells = Channel<Triple<WebSocket, String, JsonObject>>(Channel.UNLIMITED)
        val acks = Channel<String>(Channel.UNLIMITED)
        val interrupts = Channel<String>(Channel.UNLIMITED)
        @Volatile var environmentId = "env"
        @Volatile var serverVersion: String? = "1.2.3"
        @Volatile var sessionDown = false
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/.well-known/t3/environment" -> json("""{"environmentId":"$environmentId","orchestrationProtocolVersion":2}""")
                    request.path == "/api/auth/session" && sessionDown -> MockResponse().setResponseCode(503)
                    request.path == "/api/auth/session" -> json("""{"authenticated":true,"permissions":["orchestration:read","orchestration:operate"]}""")
                    request.path.orEmpty().startsWith("/api/auth/websocket-ticket") -> json("""{"ticket":"fixture"}""")
                    request.path.orEmpty().startsWith("/ws?") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            val frame = EffectRpc.decode(text).single()
                            val id = frame.string("id")
                            when (frame.string("_tag")) {
                                "Ack" -> { acks.trySend(frame.string("requestId")); return }
                                "Interrupt" -> { interrupts.trySend(frame.string("requestId")); return }
                                "Request" -> Unit
                                else -> return
                            }
                            when (frame.string("tag")) {
                                "server.getConfig" -> {
                                    val environment = serverVersion?.let { """"environment":{"environmentId":"$environmentId","serverVersion":"$it","databaseMigration":40,"capabilities":{}},""" }.orEmpty()
                                    webSocket.send("""{"_tag":"Exit","requestId":"$id","exit":{"_tag":"Success","value":{$environment"providers":[]}}}""")
                                }
                                "orchestration.subscribeShell" -> shells.trySend(Triple(webSocket, id, frame.obj("payload")))
                            }
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
        }
        fun environment() = SavedEnvironment(environmentId, "Fixture", server.url("/").toString(), "fixture-token")
        suspend fun connect(): Triple<WebSocket, String, JsonObject> { client.connect(environment()); return nextShell() }
        suspend fun nextShell() = withTimeout(5000) { shells.receive() }
        suspend fun chunk(stream: Triple<WebSocket, String, JsonObject>, values: String) {
            stream.first.send("""{"_tag":"Chunk","requestId":"${stream.second}","values":[$values]}""")
            withTimeout(5000) { while (acks.receive() != stream.second) {} }
        }
        fun rows() = client.state.value.threads.sortedBy { it.id } to client.state.value.projects.sortedBy { it.id }
        override fun close() { client.close(); scope.cancel(); server.shutdown() }
        companion object {
            fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            fun thread(id: String, title: String = id, status: String = "idle") = """{"id":"$id","projectId":"p","title":"$title","status":"$status"}"""
            fun shell(sequence: Int, projects: String, threads: String) =
                """{"kind":"snapshot","snapshot":{"snapshotSequence":$sequence,"projects":[$projects],"threads":[$threads],"archivedThreads":[]}}"""
            const val project = """{"id":"p","title":"Project","workspaceRoot":"/fixture/p"}"""
        }
    }

    private val Triple<WebSocket, String, JsonObject>.afterSequence get() = third["afterSequence"]?.jsonPrimitive?.longOrNull

    @Test fun reconnectReplaysMissedDeltasIntoTheRowsAFreshSnapshotGives() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            assertNull(first.afterSequence)
            f.chunk(first, """${Fixture.shell(10, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")},${Fixture.thread("c")}")}, {"kind":"synchronized"}""")
            f.chunk(first, """{"kind":"thread.updated","sequence":12,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            assertTrue(f.client.state.value.canWrite)
            f.client.disconnect()

            val resumed = f.connect()
            assertEquals(12L, resumed.afterSequence)
            assertEquals(3, f.client.state.value.threads.size)
            // The resume answer: identity enrichment, the coalesced replay, then the completion marker.
            f.chunk(resumed, """{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/fixture/p"],"snapshot":{"snapshotSequence":17,"projects":[{"id":"p","title":"Renamed","workspaceRoot":"/fixture/p"}],"threads":[],"archivedThreads":[]}}""")
            f.chunk(resumed, """{"kind":"thread.removed","sequence":13,"location":"active","threadId":"b"},
                {"kind":"thread.updated","sequence":14,"location":"active","thread":${Fixture.thread("c", "C1", "running")}},
                {"kind":"project.updated","sequence":15,"project":{"id":"p","title":"Renamed","workspaceRoot":"/fixture/p"}},
                {"kind":"project.updated","sequence":16,"project":{"id":"q","title":"New","workspaceRoot":"/fixture/q"}},
                {"kind":"thread.updated","sequence":17,"location":"active","thread":${Fixture.thread("d")}}""")
            assertFalse("Writes wait for the completion marker", f.client.state.value.canWrite)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertTrue(f.client.state.value.canWrite)
            val afterResume = f.rows()

            // What the server would have answered without a cursor; a new build never resumes.
            f.client.disconnect(); f.serverVersion = "1.2.4"
            val fresh = f.connect()
            assertNull(fresh.afterSequence)
            f.chunk(fresh, """${Fixture.shell(17, """{"id":"p","title":"Renamed","workspaceRoot":"/fixture/p"},{"id":"q","title":"New","workspaceRoot":"/fixture/q"}""",
                "${Fixture.thread("a", "A1")},${Fixture.thread("c", "C1", "running")},${Fixture.thread("d")}")}, {"kind":"synchronized"}""")
            assertEquals(f.rows(), afterResume)
            f.client.disconnect()
            assertEquals(17L, f.connect().afterSequence)
        }
    }

    @Test fun serverThatCannotReplayOrIgnoresTheCursorAnswersAnAuthoritativeSnapshot() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")}")}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val resumed = f.connect()
            assertEquals(10L, resumed.afterSequence)
            f.chunk(resumed, Fixture.shell(2_000, Fixture.project, Fixture.thread("z")))
            assertEquals(listOf("z"), f.client.state.value.threads.map { it.id })
            assertFalse(f.client.state.value.canWrite)
            f.chunk(resumed, """{"kind":"synchronized"}""")
            assertTrue(f.client.state.value.canWrite)
            f.client.disconnect()
            assertEquals(2_000L, f.connect().afterSequence)
        }
    }

    @Test fun userRefreshDropsTheCursorForAFullSnapshotThatSeedsTheNextResume() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.chunk(first, """{"kind":"thread.updated","sequence":12,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            val refresh = async { f.client.refreshThreads() }
            val full = f.nextShell()
            assertNull("A refresh never resumes from the cursor", full.afterSequence)
            assertEquals(first.second, withTimeout(5000) { f.interrupts.receive() })
            f.chunk(full, """${Fixture.shell(20, Fixture.project, Fixture.thread("a", "A2", "running"))}, {"kind":"synchronized"}""")
            withTimeout(5000) { refresh.await() }
            assertEquals("A2", f.client.state.value.threads.single().title)
            // Late frames of the replaced stream move neither the rows nor the cursor.
            first.first.send("""{"_tag":"Chunk","requestId":"${first.second}","values":[{"kind":"thread.updated","sequence":30,"location":"active","thread":${Fixture.thread("a", "Stale")}}]}""")
            f.client.disconnect()
            assertEquals(20L, f.connect().afterSequence)
            assertEquals("A2", f.client.state.value.threads.single().title)
        }
    }

    @Test fun lostConnectionMidReplayResumesFromTheLastAppliedDelta() = runBlocking {
        Fixture().use { f ->
            val first = f.connect()
            f.chunk(first, """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val resumed = f.connect()
            f.chunk(resumed, """{"kind":"thread.updated","sequence":11,"location":"active","thread":${Fixture.thread("a", "A1")}}""")
            resumed.first.close(1011, null)
            withTimeout(5000) { while (f.client.state.value.connection != ConnectionStatus.Error) yield() }
            assertFalse(f.client.state.value.canWrite)
            f.client.reconnect()
            val again = f.nextShell()
            assertEquals(11L, again.afterSequence)
            assertEquals("A1", f.client.state.value.threads.single().title)
        }
    }

    @Test fun otherEnvironmentsAndServersWithoutABuildNeverResume() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), Fixture.shell(10, Fixture.project, Fixture.thread("a")))
            f.client.disconnect(); f.environmentId = "another"
            val other = f.connect()
            assertNull(other.afterSequence)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.chunk(other, Fixture.shell(50, Fixture.project, Fixture.thread("x")))
            f.client.disconnect(); f.environmentId = "env"
            assertEquals("Each environment keeps its own cursor", 10L, f.connect().afterSequence)
            assertEquals(listOf("a"), f.client.state.value.threads.map { it.id })
            f.client.forgetEnvironment("another")
            f.client.disconnect(); f.environmentId = "another"
            assertNull("Forgotten rows cannot resume", f.connect().afterSequence)

            f.client.disconnect(); f.environmentId = "env"; f.serverVersion = null
            val legacy = f.connect()
            assertNull(legacy.afterSequence)
            f.chunk(legacy, Fixture.shell(60, Fixture.project, Fixture.thread("a")))
            f.client.disconnect(); f.serverVersion = "1.2.3"
            assertNull("Rows from an unnamed build carry no cursor", f.connect().afterSequence)
        }
    }

    @Test fun emptyRowsAfterForgettingNeverResumeTheOldCursor() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(100, Fixture.project, "${Fixture.thread("a")},${Fixture.thread("b")}")}, {"kind":"synchronized"}""")
            f.client.disconnect()
            // Forget and re-pair the same environment id; the first attempt fails before the shell subscription.
            f.client.forgetEnvironment("env")
            f.sessionDown = true
            assertTrue(runCatching { f.client.connect(f.environment()) }.isFailure)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.sessionDown = false
            val retry = f.connect()
            assertNull("Empty rows must load a snapshot", retry.afterSequence)
            assertFalse(f.client.state.value.canWrite)
            f.chunk(retry, """${Fixture.shell(120, Fixture.project, Fixture.thread("c"))}, {"kind":"synchronized"}""")
            assertEquals(listOf("c"), f.client.state.value.threads.map { it.id })
            assertTrue(f.client.state.value.canWrite)
        }
    }

    @Test fun evictedEnvironmentStartsFromASnapshot() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(100, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            for (id in listOf("e1", "e2", "e3", "e4")) {
                f.client.disconnect(); f.environmentId = id
                f.chunk(f.connect(), """${Fixture.shell(5, Fixture.project, Fixture.thread(id))}, {"kind":"synchronized"}""")
            }
            f.client.disconnect(); f.environmentId = "env"
            assertNull(f.connect().afterSequence)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.client.disconnect(); f.environmentId = "e4"
            assertEquals(5L, f.connect().afterSequence)
        }
    }

    @Test fun shellLoadedTellsAnEmptyListFromOneStillLoading() = runBlocking {
        Fixture().use { f ->
            assertFalse(f.client.state.value.shellLoaded)
            val first = f.connect()
            assertFalse("A fresh environment has no snapshot yet", f.client.state.value.shellLoaded)
            f.chunk(first, """{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/fixture/p"],"snapshot":{"snapshotSequence":3,"projects":[${Fixture.project}],"threads":[],"archivedThreads":[]}}""")
            assertFalse("Project enrichment is not a thread snapshot", f.client.state.value.shellLoaded)
            f.chunk(first, Fixture.shell(10, Fixture.project, ""))
            assertTrue("An empty snapshot is loaded, not loading", f.client.state.value.shellLoaded)
            assertTrue(f.client.state.value.threads.isEmpty())
            f.client.disconnect()
            assertTrue("Retained rows stay loaded while offline", f.client.state.value.shellLoaded)
            f.connect()
            assertTrue("Reconnecting restores the retained rows", f.client.state.value.shellLoaded)
            assertFalse(f.client.state.value.canWrite)

            f.client.disconnect()
            f.client.forgetEnvironment("env")
            assertFalse(f.client.state.value.shellLoaded)
            f.environmentId = "another"
            f.connect()
            assertFalse("Another environment's rows are not this one's", f.client.state.value.shellLoaded)
            f.client.disconnect(); f.environmentId = "env"
            f.connect()
            assertFalse("A forgotten environment starts over", f.client.state.value.shellLoaded)
            assertTrue(f.client.state.value.threads.isEmpty())
        }
    }

    @Test fun snapshotRequiredResubscribesForASocketSnapshot() = runBlocking {
        Fixture().use { f ->
            f.chunk(f.connect(), """${Fixture.shell(10, Fixture.project, Fixture.thread("a"))}, {"kind":"synchronized"}""")
            f.client.disconnect()
            val resumed = f.connect()
            assertEquals(10L, resumed.afterSequence)
            resumed.first.send("""{"_tag":"Chunk","requestId":"${resumed.second}","values":[{"kind":"snapshot-required"}]}""")
            val fresh = f.nextShell()
            assertNull(fresh.afterSequence)
            assertEquals(resumed.second, withTimeout(5000) { f.interrupts.receive() })
            assertEquals(ConnectionStatus.Connected, f.client.state.value.connection)
            assertFalse(f.client.state.value.canWrite)
            f.chunk(fresh, """${Fixture.shell(30, Fixture.project, Fixture.thread("b"))}, {"kind":"synchronized"}""")
            assertEquals(listOf("b"), f.client.state.value.threads.map { it.id })
            assertTrue(f.client.state.value.canWrite)
        }
    }
}

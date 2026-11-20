package com.tajchert.trefoil.core.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

private class FakePersistence(var stored: String? = null) : ComposerDraftPersistence {
    val saves = mutableListOf<String?>()
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun load(): String? { gate?.await(); return stored }
    override suspend fun save(json: String?) { saves += json; stored = json }
}

class ComposerDraftStoreTest {
    private val reply = ComposerDraftKey.Reply("env", "t1")
    private val newA = ComposerDraftKey.NewThread("env", "pA")
    private val newB = ComposerDraftKey.NewThread("env", "pB")
    private var now = 1_000L

    private fun TestScope.store(persistence: FakePersistence) =
        ComposerDraftStore(persistence, backgroundScope, clock = { now }, debounceMillis = 300).also { runCurrent() }

    @Test fun debounceCoalescesRapidEdits() = runTest {
        val p = FakePersistence(); val s = store(p)
        s.update(reply, "h"); advanceTimeBy(100); s.update(reply, "he"); advanceTimeBy(100); s.update(reply, "hello")
        advanceTimeBy(299); runCurrent()
        assertTrue(p.saves.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(1, p.saves.size)
        assertEquals("hello", ComposerDraftStore.decode(p.saves.single()!!)[reply]?.text)
    }

    @Test fun flushWritesPendingRightAwayAndOnlyOnce() = runTest {
        val p = FakePersistence(); val s = store(p)
        s.update(reply, "hello")
        s.flush()
        assertEquals(1, p.saves.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals("the cancelled debounce does not write again", 1, p.saves.size)
        s.flush()
        assertEquals("nothing pending", 1, p.saves.size)
    }

    @Test fun onlyDraftsWithContentAreListed() = runTest {
        val s = store(FakePersistence())
        s.update(newA, "   ", modelInstanceId = "codex", model = "gpt", runtimeMode = "full-access")
        assertTrue(s.drafts.value.isEmpty())
        assertEquals("settings-only draft is kept for the sheet", "full-access", s.get(newA)?.runtimeMode)
        s.update(newA, "", null)
        assertNull("blank text without settings removes it", s.get(newA))
        s.update(reply, "draft", model = "ignored")
        assertEquals(setOf(reply), s.drafts.value.keys)
        assertNull("replies carry no settings", s.get(reply)?.model)
        s.update(reply, "  ")
        assertTrue(s.drafts.value.isEmpty())
    }

    @Test fun clearEnvironmentKeepsOthers() = runTest {
        val s = store(FakePersistence())
        val other = ComposerDraftKey.Reply("env2", "t1")
        s.update(reply, "a"); s.update(newA, "b"); s.update(other, "c")
        s.clearEnvironment("env")
        assertEquals(setOf<ComposerDraftKey>(other), s.drafts.value.keys)
    }

    @Test fun projectMoveNeverOverwritesAndKeepsSourcesThatMustStay() = runTest {
        val newC = ComposerDraftKey.NewThread("env", "pC")
        val s = store(FakePersistence())
        s.update(newA, "prompt", model = "gpt"); s.update(newB, "older")
        assertFalse("an existing destination is never overwritten", s.move(newA, newB))
        assertEquals("older", s.get(newB)?.text); assertEquals("prompt", s.get(newA)?.text)
        now = 2_000
        assertTrue(s.move(newA, newC))
        assertNull(s.get(newA))
        assertEquals("prompt", s.get(newC)?.text); assertEquals("gpt", s.get(newC)?.model); assertEquals(2_000L, s.get(newC)?.updatedAtMillis)
        assertTrue("a draft that existed before the sheet is copied", s.move(newC, newA, keepSource = true))
        assertEquals(setOf<ComposerDraftKey>(newA, newB, newC), s.drafts.value.keys)
        assertFalse(s.move(ComposerDraftKey.NewThread("env", "none"), ComposerDraftKey.NewThread("env", "x")))
    }

    @Test fun moveWaitsForTheLoad() = runTest {
        val p = FakePersistence().apply { gate = CompletableDeferred() }
        val s = store(p)
        s.update(newA, "typed")
        assertFalse("the destination is unknown until loaded", s.move(newA, newB))
        assertFalse(s.loaded.value)
        p.gate!!.complete(Unit); runCurrent()
        assertTrue(s.loaded.value)
        assertTrue(s.move(newA, newB))
    }

    @Test fun undoRestoresOnlyAnUntouchedKey() = runTest {
        val s = store(FakePersistence())
        s.update(newA, "prompt"); val stored = s.get(newA)!!
        val undo = s.discard(newA)!!
        assertTrue(s.drafts.value.isEmpty())
        now = 5_000
        assertTrue(s.restore(undo))
        assertEquals(stored, s.drafts.value[newA])
        assertFalse("already restored", s.restore(undo))
        val second = s.discard(newA)!!
        s.update(newA, "replacement")
        assertFalse("a replacement draft wins", s.restore(second))
        assertEquals("replacement", s.get(newA)?.text)
        s.clear(newA)
        assertFalse("written and cleared since: still not the discarded state", s.restore(second))
        assertNull(s.discard(newA))
    }

    @Test fun settingsOnlyDraftsAreObservableAsRecords() = runTest {
        val s = store(FakePersistence())
        s.update(newA, "", model = "gpt")
        assertTrue(s.drafts.value.isEmpty())
        assertEquals("gpt", s.records.value[newA]?.model)
        s.updateText(newA, "now with text")
        assertEquals("text edits keep the settings", "gpt", s.get(newA)?.model)
    }

    @Test fun staleSendDoesNotClearAnotherEditorsText() = runTest {
        val s = store(FakePersistence())
        s.update(reply, "newer text from another editor")
        assertNull("the stored text is not what is sent", s.revisionIfText(reply, "old text"))
        val revision = s.revisionIfText(reply, "  newer text from another editor\n")
        assertNotNull("a trimmed submission matches", revision)
        assertTrue(s.clearIfUnchanged(reply, revision!!))
    }

    @Test fun corruptOrUnknownBlobStartsEmpty() = runTest {
        for (blob in listOf("{not json", """{"v":2,"drafts":[]}""", """{"v":1,"drafts":[{"kind":"other","environmentId":"e","text":"x","updatedAtMillis":1}]}""")) {
            val p = FakePersistence(blob); val s = store(p)
            assertTrue(blob, s.drafts.value.isEmpty())
            s.update(reply, "fresh"); s.flush()
            assertEquals("fresh", ComposerDraftStore.decode(p.stored!!)[reply]?.text)
        }
    }

    @Test fun roundTripsAcrossRestart() = runTest {
        val p = FakePersistence(); val s = store(p)
        s.update(reply, "reply text\nsecond line"); s.update(newA, "prompt", "codex", "gpt-5", "approval-required", "high")
        s.flush()
        assertTrue(p.stored!!.contains("\"v\":1"))
        val restarted = store(p)
        assertEquals(s.drafts.value, restarted.drafts.value)
        s.clear(reply); s.clear(newA); s.flush()
        assertNull("empty store clears the blob", p.stored)
    }

    @Test fun textIsCappedAt100k() = runTest {
        val p = FakePersistence(); val s = store(p)
        s.update(reply, "x".repeat(150_000))
        assertEquals(ComposerDraftStore.MAX_TEXT, s.get(reply)!!.text.length)
        s.flush()
        assertEquals(ComposerDraftStore.MAX_TEXT, ComposerDraftStore.decode(p.stored!!)[reply]!!.text.length)
    }

    @Test fun editsBeforeLoadWinAndNeverDropStoredDrafts() = runTest {
        val seeded = FakePersistence(); val seed = store(seeded)
        seed.update(reply, "stored"); seed.update(newA, "stored A"); seed.update(newB, "stored B"); seed.flush()
        val p = FakePersistence(seeded.stored).apply { gate = CompletableDeferred() }
        val s = store(p)
        s.update(reply, "typed early"); s.clear(newB)
        advanceTimeBy(1_000); runCurrent()
        assertTrue("no write before the load", p.saves.isEmpty())
        p.gate!!.complete(Unit); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals("typed early", s.get(reply)?.text)
        assertEquals("stored A", s.get(newA)?.text)
        assertNull(s.get(newB))
        assertEquals(setOf<ComposerDraftKey>(reply, newA), ComposerDraftStore.decode(p.stored!!).keys)
    }

    @Test fun failedSaveStaysPendingForTheNextFlush() = runTest {
        var fail = true
        val p = object : ComposerDraftPersistence {
            var stored: String? = null
            override suspend fun load(): String? = null
            override suspend fun save(json: String?) { if (fail) error("disk full"); stored = json }
        }
        val s = ComposerDraftStore(p, backgroundScope, clock = { now }).also { runCurrent() }
        s.update(reply, "hello"); s.flush()
        assertNull(p.stored)
        fail = false; s.flush()
        assertEquals("hello", ComposerDraftStore.decode(p.stored!!)[reply]?.text)
    }

    @Test fun acceptedSendKeepsTextTypedWhileItWasInFlight() = runTest {
        val s = store(FakePersistence())
        s.update(reply, "first")
        val atSubmit = s.revision(reply)
        s.update(reply, "first follow-up")
        assertFalse(s.clearIfUnchanged(reply, atSubmit))
        assertEquals("first follow-up", s.get(reply)?.text)
        val unchanged = s.revision(reply)
        assertTrue(s.clearIfUnchanged(reply, unchanged))
        assertNull(s.get(reply))
        s.update(reply, "typed after clear")
        assertFalse("a clear also moves the revision", s.clearIfUnchanged(reply, unchanged))
    }

    @Test fun clearsBeforeTheLoadAreWrittenEvenWithoutOtherEdits() = runTest {
        val seeded = FakePersistence(); val seed = store(seeded)
        seed.update(reply, "stored"); seed.update(newA, "stored A"); seed.update(ComposerDraftKey.Reply("env2", "t"), "other"); seed.flush()
        val p = FakePersistence(seeded.stored).apply { gate = CompletableDeferred() }
        val s = store(p)
        s.clear(reply); s.clearEnvironment("env2")
        p.gate!!.complete(Unit); runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(setOf<ComposerDraftKey>(newA), ComposerDraftStore.decode(p.stored!!).keys)
        assertEquals("a restart does not bring them back", setOf<ComposerDraftKey>(newA), store(p).drafts.value.keys)
    }

    @Test fun corruptPayloadIsReplaced() = runTest {
        val p = object : ComposerDraftPersistence {
            var stored: String? = null
            override suspend fun load(): String? = throw CorruptComposerDraftsException()
            override suspend fun save(json: String?) { stored = json }
        }
        val s = ComposerDraftStore(p, backgroundScope, clock = { now }).also { runCurrent() }
        s.update(reply, "fresh"); s.flush()
        assertEquals("fresh", ComposerDraftStore.decode(p.stored!!)[reply]?.text)
    }

    @Test fun unreadableStorageIsRetriedOnceAndNeverOverwritten() = runTest {
        var failures = 2
        val saves = mutableListOf<String?>()
        val valid = ComposerDraftStore.encode(mapOf(newA to ComposerDraft(newA, "stored A", updatedAtMillis = 1)))
        val p = object : ComposerDraftPersistence {
            override suspend fun load(): String? { if (failures-- > 0) throw java.io.IOException("keystore busy"); return valid }
            override suspend fun save(json: String?) { saves += json }
        }
        val s = ComposerDraftStore(p, backgroundScope, clock = { now }).also { runCurrent() }
        s.update(reply, "typed"); s.flush()
        assertTrue("both reads failed: the file is left alone", saves.isEmpty())
        assertEquals("edits still work in memory", "typed", s.get(reply)?.text)
        s.update(reply, "more"); s.flush()
        assertTrue(saves.isEmpty())
    }

    @Test fun unreadableStorageRecoversOnTheRetry() = runTest {
        var failures = 1
        val valid = ComposerDraftStore.encode(mapOf(newA to ComposerDraft(newA, "stored A", updatedAtMillis = 1)))
        val p = object : ComposerDraftPersistence {
            var stored: String? = null
            override suspend fun load(): String? { if (failures-- > 0) throw IllegalStateException("keystore busy"); return valid }
            override suspend fun save(json: String?) { stored = json }
        }
        val s = ComposerDraftStore(p, backgroundScope, clock = { now }).also { runCurrent() }
        s.update(reply, "typed"); s.flush()
        assertEquals(setOf<ComposerDraftKey>(reply, newA), ComposerDraftStore.decode(p.stored!!).keys)
        assertEquals("stored A", s.get(newA)?.text)
    }

    @Test fun launchRecordIsPersistedBeforeReturningAndSurvivesRestart() = runTest {
        val p = FakePersistence(); val s = store(p)
        s.update(newA, "prompt", model = "gpt"); s.update(newB, "other prompt")
        val revision = s.revision(newA)
        assertTrue(s.recordLaunch(newA, "thread-1", "command-1", "prompt"))
        assertEquals("written without waiting for the debounce", PendingLaunch("thread-1", "command-1", "prompt"), ComposerDraftStore.decode(p.stored!!)[newA]?.pendingLaunch)
        assertTrue(s.recordLaunch(newB, "thread-2", "command-2", "other prompt"))
        assertEquals("recording is not an edit", revision, s.revision(newA))
        val restarted = store(p)
        assertEquals(PendingLaunch("thread-1", "command-1", "prompt"), restarted.get(newA)?.pendingLaunch)
        restarted.update(newA, "prompt", model = "gpt-5")
        assertEquals("edits keep the launch identity", "command-1", restarted.get(newA)?.pendingLaunch?.commandId)
        restarted.update(newB, "other prompt, edited")
        restarted.reconcileLaunches("env", setOf("thread-1", "thread-2"))
        assertNull("unchanged text: the thread carries it", restarted.get(newA))
        assertEquals("edited text stays as a fresh draft", "other prompt, edited", restarted.get(newB)?.text)
        assertNull(restarted.get(newB)?.pendingLaunch)
    }

    @Test fun launchRecordKeepsTheFirstPayloadPerCommand() = runTest {
        val s = store(FakePersistence())
        s.update(newA, "A")
        s.recordLaunch(newA, "thread-1", "command-1", "A")
        s.update(newA, "B")
        s.recordLaunch(newA, "thread-1", "command-1", "B")
        assertEquals("the server replays A for these ids", "A", s.get(newA)?.pendingLaunch?.text)
        s.settleLaunched("env", "thread-1")
        assertEquals("B was not delivered: it stays, without the identity", "B", s.get(newA)?.text)
        assertNull(s.get(newA)?.pendingLaunch)
    }

    @Test fun launchRecordWithoutADraftAndFailedWrite() = runTest {
        var fail = false
        val p = object : ComposerDraftPersistence {
            var stored: String? = null
            override suspend fun load(): String? = null
            override suspend fun save(json: String?) { if (fail) error("disk full"); stored = json }
        }
        val s = ComposerDraftStore(p, backgroundScope, clock = { now }).also { runCurrent() }
        assertTrue(s.recordLaunch(newA, "thread-1", "command-1", "typed in the conversation"))
        assertEquals("typed in the conversation", s.get(newA)?.text)
        fail = true
        assertFalse("reported, so the caller can decide", s.recordLaunch(newB, "thread-2", "command-2", "x"))
        s.dropLaunch(newA, "other-command")
        assertNotNull(s.get(newA)?.pendingLaunch)
        s.dropLaunch(newA, "command-1")
        assertNull(s.get(newA)?.pendingLaunch); assertEquals("typed in the conversation", s.get(newA)?.text)
    }

    @Test fun settleLaunchedAndMoveDropTheIdentity() = runTest {
        val newC = ComposerDraftKey.NewThread("env", "pC")
        val s = store(FakePersistence())
        s.update(newA, "prompt"); s.recordLaunch(newA, "thread-1", "command-1", "prompt")
        assertTrue(s.move(newA, newC, keepSource = false))
        assertNotNull("a draft with a pending launch stays on its project", s.get(newA)?.pendingLaunch)
        assertNull("the copy carries no identity", s.get(newC)?.pendingLaunch)
        s.settleLaunched("env", "unrelated")
        assertNotNull(s.get(newA))
        s.settleLaunched("env", "thread-1")
        assertNull(s.get(newA))
        assertEquals("prompt", s.get(newC)?.text)
    }
}

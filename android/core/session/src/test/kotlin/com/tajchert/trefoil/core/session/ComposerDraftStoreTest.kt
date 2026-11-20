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

    @Test fun projectMoveReplacesTargetAndOptionallyKeepsSource() = runTest {
        val s = store(FakePersistence())
        s.update(newA, "prompt", model = "gpt"); s.update(newB, "older")
        now = 2_000
        s.move(newA, newB)
        assertNull(s.get(newA))
        assertEquals("prompt", s.get(newB)?.text); assertEquals("gpt", s.get(newB)?.model); assertEquals(2_000L, s.get(newB)?.updatedAtMillis)
        s.move(newB, newA, keepSource = true)
        assertEquals(setOf<ComposerDraftKey>(newA, newB), s.drafts.value.keys)
    }

    @Test fun clearAndRestoreUndo() = runTest {
        val s = store(FakePersistence())
        s.update(newA, "prompt"); val discarded = s.get(newA)!!
        s.clear(newA); assertTrue(s.drafts.value.isEmpty())
        now = 5_000
        s.restore(discarded)
        assertEquals(discarded, s.drafts.value[newA])
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
}

package com.tajchert.trefoil.security

import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-modify-write rules for the view model's vault writes (fixtures only, no real machines). */
class EnvironmentWritesTest {
    /**
     * A plain store with the vault's contract: reads fail while [failingReads] is positive (a keystore hiccup), writes
     * fail while [failingWrites] is, and a successful read or write becomes [lastKnown]. No retry of its own.
     */
    private class FakeStore(var saved: List<SavedEnvironment>) : EnvironmentStore {
        var failingReads = 0
        var failingWrites = 0
        var writes = 0
        override var lastKnown: List<SavedEnvironment>? = null
        override fun read(): List<SavedEnvironment> {
            if (failingReads > 0) { failingReads--; throw IllegalStateException("unreadable") }
            return saved.also { lastKnown = it }
        }
        override fun readHostKeys(): List<SshHostKey> = emptyList()
        override fun writeHostKeys(keys: List<SshHostKey>) = Unit
        override fun write(environments: List<SavedEnvironment>) {
            if (failingWrites > 0) { failingWrites--; throw IllegalStateException("not written") }
            writes++; saved = environments; lastKnown = environments
        }
        override fun <T> locked(block: () -> T): T = synchronized(this) { block() }
    }

    private val alpha = SavedEnvironment("alpha", "Alpha", "https://alpha.invalid", "alpha-old")
    private val beta = SavedEnvironment("beta", "Beta", "https://beta.invalid", "beta-token")
    private val paired = SavedEnvironment("gamma", "Gamma", "https://gamma.invalid", "gamma-token")
    private val store = FakeStore(listOf(alpha, beta))
    private val writes = EnvironmentWrites(store)

    /** The usage widget renews alpha in the vault behind the view model's back. */
    private fun widgetRenewsAlpha() = store.update { saved -> saved.map { if (it.id == "alpha") it.copy(accessToken = "alpha-new") else it } }

    private val renameBeta: (List<SavedEnvironment>) -> List<SavedEnvironment> =
        { saved -> saved.map { if (it.id == "beta") it.copy(name = "Beta 2") else it } }

    @Test fun aWriteAppliesToWhatTheVaultHoldsNow() {
        writes.load()
        widgetRenewsAlpha()
        val stored = writes.write(change = renameBeta)
        assertEquals(listOf(alpha.copy(accessToken = "alpha-new"), beta.copy(name = "Beta 2")), stored)
        assertEquals(stored, store.saved)
    }

    @Test fun oneFailedReadIsRetriedOnTheVault() {
        writes.load()
        widgetRenewsAlpha()
        store.failingReads = 1
        writes.write(change = renameBeta)
        assertEquals("The retry read the widget's token", "alpha-new", store.saved.first { it.id == "alpha" }.accessToken)
    }

    @Test fun aReadFailingAtWriteTimeAppliesTheChangeToTheLoadedList() {
        writes.load()
        widgetRenewsAlpha()
        store.failingReads = 2
        val stored = writes.write(change = renameBeta)
        // The widget's write in this process refreshed the known list, so even its token survives; never emptied.
        assertEquals(listOf(alpha.copy(accessToken = "alpha-new"), beta.copy(name = "Beta 2")), stored)
        assertEquals(stored, store.saved)
    }

    @Test fun aNewPairingMayReplaceAVaultNeverReadableInThisProcess() {
        store.failingReads = 1
        assertTrue(runCatching { writes.load() }.isFailure)
        assertNull(store.lastKnown)
        store.failingReads = 2
        val stored = writes.write(newPairing = true) { saved -> saved.filterNot { it.id == paired.id } + paired }
        assertEquals(listOf(paired), stored)
        assertEquals(listOf(paired), store.saved)
        assertEquals("The write loaded the list", listOf(paired), store.lastKnown)
    }

    @Test fun anyOtherWriteFailsWhileTheListWasNeverLoaded() {
        store.failingReads = 1
        assertTrue(runCatching { writes.load() }.isFailure)
        store.failingReads = 2
        val error = runCatching { writes.write(change = renameBeta) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(0, store.writes)
        assertEquals(listOf(alpha, beta), store.saved)
        assertNull(store.lastKnown)
    }

    @Test fun aSuccessfulWriteLoadsTheListForLaterFailedReads() {
        store.failingReads = 1
        assertTrue(runCatching { writes.load() }.isFailure)
        writes.write(change = renameBeta)
        store.failingReads = 2
        val stored = writes.write { saved -> saved.filterNot { it.id == "alpha" } }
        assertEquals(listOf(beta.copy(name = "Beta 2")), stored)
    }

    @Test fun aListLoadedByAnyCallerInThisProcessIsKnownToEveryOther() {
        // An earlier view model (or the widget) read the vault; a later one whose own start-up read failed shares it.
        EnvironmentWrites(store).load()
        store.failingReads = 3
        assertTrue(runCatching { writes.load() }.isFailure)
        val stored = writes.write(newPairing = true) { saved -> saved.filterNot { it.id == paired.id } + paired }
        assertEquals("A pairing never replaces machines known in this process", listOf(alpha, beta, paired), stored)
    }

    @Test fun aFailedWriteLeavesWhatWasReadAsTheKnownList() {
        store.failingWrites = 1
        assertTrue(runCatching { writes.write(change = renameBeta) }.isFailure)
        assertEquals("What is still on disk", listOf(alpha, beta), store.lastKnown)
        store.failingReads = 2
        val stored = writes.write(newPairing = true) { saved -> saved.filterNot { it.id == paired.id } + paired }
        assertEquals(listOf(alpha, beta, paired), stored)
    }
}

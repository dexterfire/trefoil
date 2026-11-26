package com.tajchert.trefoil.security

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.session.CachedShell
import com.tajchert.trefoil.core.session.CachedShellCursor
import com.tajchert.trefoil.core.session.ShellCacheCodec
import com.tajchert.trefoil.core.session.ShellCacheStore
import com.tajchert.trefoil.core.session.ShellCacheTicket
import com.tajchert.trefoil.core.session.ShellCacheWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedShellCacheStoreTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val dir = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "shell-cache-test")
    /** A software AES key: Robolectric has no Android Keystore; the production store differs only in where the key lives. */
    private val keys = ProcessKeyCache { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    private val cipher = ShellCacheCipher(keys)
    private val store = EncryptedShellCacheStore(dir, cipher)
    private val shell = CachedShell("env",
        listOf(Project("p", "Secret project", "/w/p")),
        listOf(ThreadSummary("a", "p", "Confidential title", latestMessage = "private message text")),
        CachedShellCursor("1.2.3", 40, 12, 1_000), 2_000)

    @After fun cleanUp() { dir.deleteRecursively() }

    private fun files() = dir.listFiles().orEmpty().map { it.name }.sorted()
    private fun entryFile(environmentId: String = "env") = File(dir, EncryptedShellCacheStore.fileName(environmentId))
    private fun test(block: suspend () -> Unit) = runBlocking { withTimeout(20_000) { block() } }
    private suspend fun EncryptedShellCacheStore.put(entry: CachedShell) = write(entry, ticket(entry.environmentId))

    @Test fun roundTripIsEncryptedAndNamedWithoutContent() = test {
        store.put(shell)
        assertEquals(shell.toString(), store.read("env").toString())
        val read = store.read("env")!!
        assertEquals(shell.projects, read.projects)
        assertEquals(shell.threads, read.threads)
        assertEquals(shell.cursor, read.cursor)
        assertEquals(2_000L, read.savedAtMillis)
        val raw = entryFile().readBytes().decodeToString(throwOnInvalidSequence = false)
        for (secret in listOf("Confidential", "private message", "Secret project", "env\""))
            assertFalse("Plaintext on disk", raw.contains(secret))
        assertEquals(listOf(EncryptedShellCacheStore.fileName("env")), files())
        assertTrue(files().single().matches(Regex("[0-9a-f]{64}\\.bin")))
    }

    @Test fun everyWriteUsesAFreshNonce() = test {
        // Not the platform PRNG: the cipher must draw a new nonce for every seal, and the envelope must carry it.
        // Production draws these bytes from SecureRandom; the source is scripted here so the check is deterministic.
        //
        // A second write over the same entry cannot be exercised under Robolectric on Windows: AtomicFile's rename of
        // the finished .new file over an existing base fails there, the store reports that write as uncommitted and
        // keeps the old file (aWriteWhoseRenameFailedIsNotCommitted covers that path). The freshness invariant is
        // therefore asserted where the nonce is produced: the cipher.
        val drawn = mutableListOf<ByteArray>()
        val scriptedCipher = ShellCacheCipher(keys) {
            ByteArray(12) { index -> (drawn.size * 16 + index).toByte() }.also { drawn += it }
        }
        val scripted = EncryptedShellCacheStore(dir, scriptedCipher)
        assertTrue("the first write commits", scripted.put(shell))
        val stored = entryFile().readBytes()
        assertEquals("one nonce per seal", 1, drawn.size)
        assertArrayEquals(drawn[0], stored.copyOfRange(1, 13))

        val aad = "trefoil.shell-cache.v1:env".encodeToByteArray()
        val payload = ByteArray(32) { it.toByte() }
        val one = scriptedCipher.seal(payload, aad)
        val two = scriptedCipher.seal(payload, aad)
        assertEquals("one nonce per seal", 3, drawn.size)
        assertArrayEquals(drawn[1], one.copyOfRange(1, 13))
        assertArrayEquals(drawn[2], two.copyOfRange(1, 13))
        assertFalse("consecutive seals never share a nonce", drawn[1].contentEquals(drawn[2]))
    }

    @Test fun aWriteWhoseRenameFailedIsNotCommitted() = test {
        val ticket = store.ticket("env")
        // A non-empty directory where the entry belongs: AtomicFile's rename fails, logs and returns normally.
        val blocker = entryFile().apply { mkdirs() }
        File(blocker, "occupied").writeText("x")
        assertFalse("A rename that failed is not a stored entry", store.write(shell, ticket))
        blocker.deleteRecursively()
        assertTrue("The same checkpoint is retried, not skipped as committed", store.write(shell, ticket))
        assertEquals(shell.threads, store.read("env")!!.threads)
    }

    @Test fun missingEntryReadsAsNothing() = test {
        assertNull(store.read("env"))
        assertFalse(dir.exists())
    }

    @Test fun aCorruptFileIsDiscarded() = test {
        store.put(shell)
        val bytes = entryFile().readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        entryFile().writeBytes(bytes)
        assertNull(store.read("env"))
        assertFalse("A corrupt file is deleted", entryFile().exists())

        entryFile().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read("env"))
        assertFalse(entryFile().exists())
    }

    @Test fun aFileOfAnotherFormatVersionIsDiscarded() = test {
        val other = ShellCacheCodec.encode(shell)!!.decodeToString().replace("\"version\":${ShellCacheCodec.VERSION}", "\"version\":${ShellCacheCodec.VERSION + 1}").encodeToByteArray()
        dir.mkdirs()
        entryFile().writeBytes(cipher.seal(other, "trefoil.shell-cache.v1:env".encodeToByteArray()))
        assertNull(store.read("env"))
        assertFalse(entryFile().exists())
    }

    @Test fun aFileMovedToAnotherEnvironmentDoesNotDecrypt() = test {
        store.put(shell)
        entryFile().copyTo(entryFile("other"))
        assertNull(store.read("other"))
        assertNotNull(store.read("env"))
    }

    @Test fun aFileUnderAnotherKeyIsDiscarded() = test {
        store.put(shell)
        val rotated = EncryptedShellCacheStore(dir, ShellCacheCipher(ProcessKeyCache { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }))
        assertNull(rotated.read("env"))
        assertFalse(entryFile().exists())
    }

    @Test fun aKeystoreFailureKeepsTheFile() = test {
        store.put(shell)
        val unavailable = EncryptedShellCacheStore(dir, ShellCacheCipher(ProcessKeyCache { throw java.security.KeyStoreException("unavailable") }))
        assertNull(unavailable.read("env"))
        assertTrue(entryFile().exists())
        assertNotNull(store.read("env"))
    }

    @Test fun wipeAndRetainOnlyRemoveEntries() = test {
        store.put(shell)
        store.put(shell.copy(environmentId = "second"))
        store.put(shell.copy(environmentId = "third"))
        File(dir, EncryptedShellCacheStore.fileName("gone") + ".bak").writeBytes(byteArrayOf(1))
        store.delete("env")
        assertNull(store.read("env"))
        assertNotNull(store.read("second"))
        store.retainOnly(setOf("third"))
        assertEquals(listOf(EncryptedShellCacheStore.fileName("third")), files())
        assertNotNull(store.read("third"))
        store.retainOnly(emptySet())
        assertTrue(files().isEmpty())
    }

    @Test fun anEntryOverTheCapIsSkippedAndTheEarlierEntryStays() = test {
        store.put(shell)
        val small = EncryptedShellCacheStore(dir, cipher, maxBytes = 64)
        assertFalse(small.put(shell.copy(savedAtMillis = 3_000)))
        assertEquals("The earlier entry is still usable", 2_000L, store.read("env")?.savedAtMillis)
    }

    @Test fun anOversizedFileIsDiscardedWithoutReadingItWhole() = test {
        var keyLoads = 0
        val counted = ShellCacheCipher(ProcessKeyCache { keyLoads++; KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() })
        val small = EncryptedShellCacheStore(dir, counted, maxBytes = 1_024)
        dir.mkdirs()
        // A well-formed envelope header followed by far more than the cap: dropped while reading, before any decryption.
        entryFile().outputStream().use { out -> val block = ByteArray(64 * 1024) { 1 }; repeat(64) { out.write(block) } }
        assertNull(small.read("env"))
        assertEquals("Never handed to the cipher", 0, keyLoads)
        assertFalse("An oversized file is deleted", entryFile().exists())
        // Right at the cap still reads.
        assertTrue(small.put(shell))
        assertNotNull(small.read("env"))
    }

    @Test fun theDirectoryIsResolvedOnTheIoDispatcherOnFirstUse() = test {
        val caller = Thread.currentThread()
        var resolvedOn: Thread? = null
        val lazy = EncryptedShellCacheStore({ resolvedOn = Thread.currentThread(); dir }, cipher)
        assertNull("Constructing touches no file", resolvedOn)
        assertNull(lazy.read("env"))
        assertNotNull(resolvedOn)
        assertNotSame("Resolved inside the store's I/O context", caller, resolvedOn)
    }

    /** A writer whose writes wait for [gate] after taking their ticket: a flush queued behind the file lock. */
    private class Gated(private val delegate: ShellCacheStore) : ShellCacheStore by delegate {
        val gate = CompletableDeferred<Unit>()
        val waiting = CompletableDeferred<Unit>()
        override suspend fun write(shell: CachedShell, ticket: ShellCacheTicket): Boolean { waiting.complete(Unit); gate.await(); return delegate.write(shell, ticket) }
    }

    @Test fun aClearedModelsLateFlushNeverRecreatesAForgottenMachinesFile() = test {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val rows = java.util.concurrent.ConcurrentHashMap(mapOf("env" to shell))
            val old = Gated(store)
            val cleared = ShellCacheWriter(old, scope, { rows[it] })
            val successor = ShellCacheWriter(store, scope, { rows[it] })
            val late = cleared.close("env")
            old.waiting.await()
            rows.remove("env")
            successor.wipe("env")
            old.gate.complete(Unit)
            late.join()
            assertFalse("The forgotten machine's file stays deleted", entryFile().exists())
            assertNull(store.read("env"))
            // A machine saved again later is cached again.
            rows["env"] = shell
            successor.flush("env").join()
            assertTrue(entryFile().exists())
        } finally { scope.cancel() }
    }

    @Test fun anOlderCaptureNeverOverwritesANewerOne() = test {
        val older = store.ticket("env")
        val newer = store.ticket("env")
        assertTrue(store.write(shell.copy(savedAtMillis = 2), newer))
        assertFalse(store.write(shell.copy(savedAtMillis = 1), older))
        assertEquals(2L, store.read("env")?.savedAtMillis)
        val beforeDelete = store.ticket("env")
        store.delete("env")
        assertFalse("A read taken before a delete is stale", store.valid("env", beforeDelete))
        assertFalse(store.write(shell, beforeDelete))
        assertFalse(entryFile().exists())
    }
}

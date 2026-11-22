package com.tajchert.trefoil.security

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.session.CachedShell
import com.tajchert.trefoil.core.session.CachedShellCursor
import com.tajchert.trefoil.core.session.ShellCacheCodec
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

    @Test fun roundTripIsEncryptedAndNamedWithoutContent() = test {
        store.write(shell)
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
        store.write(shell)
        val first = entryFile().readBytes()
        store.write(shell.copy(savedAtMillis = 2_001))
        val second = entryFile().readBytes()
        assertFalse(first.copyOfRange(1, 13).contentEquals(second.copyOfRange(1, 13)))
    }

    @Test fun missingEntryReadsAsNothing() = test {
        assertNull(store.read("env"))
        assertFalse(dir.exists())
    }

    @Test fun aCorruptFileIsDiscarded() = test {
        store.write(shell)
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
        val other = ShellCacheCodec.encode(shell)!!.decodeToString().replace("\"version\":1", "\"version\":2").encodeToByteArray()
        dir.mkdirs()
        entryFile().writeBytes(cipher.seal(other, "trefoil.shell-cache.v1:env".encodeToByteArray()))
        assertNull(store.read("env"))
        assertFalse(entryFile().exists())
    }

    @Test fun aFileMovedToAnotherEnvironmentDoesNotDecrypt() = test {
        store.write(shell)
        entryFile().copyTo(entryFile("other"))
        assertNull(store.read("other"))
        assertNotNull(store.read("env"))
    }

    @Test fun aFileUnderAnotherKeyIsDiscarded() = test {
        store.write(shell)
        val rotated = EncryptedShellCacheStore(dir, ShellCacheCipher(ProcessKeyCache { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }))
        assertNull(rotated.read("env"))
        assertFalse(entryFile().exists())
    }

    @Test fun aKeystoreFailureKeepsTheFile() = test {
        store.write(shell)
        val unavailable = EncryptedShellCacheStore(dir, ShellCacheCipher(ProcessKeyCache { throw java.security.KeyStoreException("unavailable") }))
        assertNull(unavailable.read("env"))
        assertTrue(entryFile().exists())
        assertNotNull(store.read("env"))
    }

    @Test fun wipeAndRetainOnlyRemoveEntries() = test {
        store.write(shell)
        store.write(shell.copy(environmentId = "second"))
        store.write(shell.copy(environmentId = "third"))
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

    @Test fun anEntryOverTheCapIsNotWrittenAndReplacesNothing() = test {
        store.write(shell)
        val small = EncryptedShellCacheStore(dir, cipher, maxBytes = 64)
        small.write(shell.copy(savedAtMillis = 3_000))
        assertFalse("The older entry no longer describes the rows", entryFile().exists())
        assertNull(store.read("env"))
    }
}

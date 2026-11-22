package com.tajchert.trefoil.security

import android.content.Context
import android.util.AtomicFile
import com.tajchert.trefoil.core.session.CachedShell
import com.tajchert.trefoil.core.session.ShellCacheCodec
import com.tajchert.trefoil.core.session.ShellCacheSequencer
import com.tajchert.trefoil.core.session.ShellCacheStore
import com.tajchert.trefoil.core.session.ShellCacheTicket
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AES-GCM sealing of shell cache files with the key [keys] loads. Every [seal] lets the cipher pick a fresh random nonce
 * (the Keystore requires randomized encryption), so a nonce is never reused; [aad] binds a file to its environment.
 */
internal class ShellCacheCipher(private val keys: ProcessKeyCache) {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        keys.use { cipher.init(Cipher.ENCRYPT_MODE, it) }
        cipher.updateAAD(aad)
        return SecretEnvelope.encode(cipher.iv, cipher.doFinal(plaintext))
    }

    /** [IllegalArgumentException] for a malformed envelope and [AEADBadTagException] for a failed tag: both mean the file is corrupt. */
    fun open(envelope: ByteArray, aad: ByteArray): ByteArray {
        val (nonce, encrypted) = SecretEnvelope.decode(envelope)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        keys.use { cipher.init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, nonce)) }
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }
}

/**
 * Encrypted shell cache (projects and thread summaries, which include thread titles and latest message text): one
 * [AtomicFile] per environment in `noBackupFilesDir/shell-cache.v1/`, named by the SHA-256 of the environment id, holding
 * a [SecretEnvelope] of the [ShellCacheCodec] JSON under its own Keystore AES-GCM key (the [EnvironmentVault] pattern,
 * adapted from Flocket's KeystoreSecretCipher, Apache-2.0). A corrupt file, one larger than the cap, of another format
 * version or for another environment is deleted and reads as null; a Keystore or I/O failure reads as null and keeps the
 * file. Writes commit only under a [ShellCacheTicket] its [ShellCacheSequencer] still accepts, checked under the same lock
 * as the files; the process shares one instance ([get]), so every writer is ordered against every delete. The directory
 * is resolved lazily on the I/O dispatcher. Never logs, and no content reaches a file name or exception message.
 */
class EncryptedShellCacheStore internal constructor(
    directory: () -> File,
    private val cipher: ShellCacheCipher,
    private val maxBytes: Int = ShellCacheCodec.MAX_ENCODED_BYTES,
) : ShellCacheStore {
    internal constructor(dir: File, cipher: ShellCacheCipher, maxBytes: Int = ShellCacheCodec.MAX_ENCODED_BYTES) : this({ dir }, cipher, maxBytes)

    companion object {
        /** One lock per process: instances share the same files. */
        private val LOCK = Any()
        private val KEY = ProcessKeyCache { keystoreKey("trefoil.shell-cache.v1") }
        /** Version byte, GCM nonce and tag around the ciphertext ([SecretEnvelope]). */
        private const val ENVELOPE_OVERHEAD = 1 + 12 + 16
        @Volatile private var shared: EncryptedShellCacheStore? = null

        /** The process-wide store; nothing touches the disk until its first operation. */
        fun get(context: Context): EncryptedShellCacheStore = shared ?: synchronized(LOCK) {
            val app = context.applicationContext
            shared ?: EncryptedShellCacheStore({ File(app.noBackupFilesDir, "shell-cache.v1") }, ShellCacheCipher(KEY)).also { shared = it }
        }

        internal fun fileName(environmentId: String): String =
            MessageDigest.getInstance("SHA-256").digest(environmentId.encodeToByteArray()).joinToString("") { "%02x".format(it) } + ".bin"

        private fun aad(environmentId: String) = "trefoil.shell-cache.v1:$environmentId".encodeToByteArray()
    }

    /** Resolved on first use, inside [Dispatchers.IO]. */
    private val dir: File by lazy(directory)
    private val sequencer = ShellCacheSequencer()

    private fun file(environmentId: String) = AtomicFile(File(dir, fileName(environmentId)))

    override fun ticket(environmentId: String) = sequencer.ticket(environmentId)
    override fun valid(environmentId: String, ticket: ShellCacheTicket) = sequencer.valid(environmentId, ticket)

    override suspend fun read(environmentId: String): CachedShell? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val file = file(environmentId)
            // openRead restores the backup of an interrupted write; checking baseFile.exists() first would skip that. The
            // read stops past the cap, so an oversized file never becomes one allocation before authentication.
            val bytes = try { file.openRead().use { readAtMost(it, maxBytes + ENVELOPE_OVERHEAD) } }
                catch (_: FileNotFoundException) { return@synchronized null } catch (_: Exception) { return@synchronized null }
            if (bytes == null) { file.delete(); return@synchronized null }
            val plaintext = try { cipher.open(bytes, aad(environmentId)) }
            catch (_: IllegalArgumentException) { file.delete(); return@synchronized null }
            catch (_: AEADBadTagException) { file.delete(); return@synchronized null }
            // The Keystore may be briefly unavailable: keep the file for the next start.
            catch (_: Exception) { return@synchronized null }
            ShellCacheCodec.decode(plaintext, environmentId) ?: run { file.delete(); null }
        }
    }

    /** All of [input], or null when it holds more than [limit] bytes. */
    private fun readAtMost(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }

    override suspend fun write(shell: CachedShell, ticket: ShellCacheTicket): Boolean = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val id = shell.environmentId
            if (!sequencer.mayCommit(id, ticket)) return@synchronized false
            // Too large to cache: skipped, and the earlier entry stays usable.
            val encoded = ShellCacheCodec.encode(shell, maxBytes) ?: return@synchronized false
            val sealed = cipher.seal(encoded, aad(id))
            dir.mkdirs()
            val file = file(id)
            val stream = file.startWrite()
            try { stream.write(sealed); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
            sequencer.committed(id, ticket)
            true
        }
    }

    override suspend fun delete(environmentId: String) = withContext(Dispatchers.IO) {
        synchronized(LOCK) { sequencer.invalidate(environmentId); file(environmentId).delete() }
    }

    override suspend fun retainOnly(environmentIds: Set<String>) = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            sequencer.invalidateAllExcept(environmentIds)
            val keep = environmentIds.mapTo(HashSet()) { fileName(it) }
            dir.listFiles()?.forEach { candidate ->
                val base = candidate.name.removeSuffix(".bak").removeSuffix(".new")
                if (base !in keep) candidate.delete()
            }
            Unit
        }
    }
}

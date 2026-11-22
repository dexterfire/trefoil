package com.tajchert.trefoil.security

import android.content.Context
import android.util.AtomicFile
import com.tajchert.trefoil.core.session.CachedShell
import com.tajchert.trefoil.core.session.ShellCacheCodec
import com.tajchert.trefoil.core.session.ShellCacheStore
import java.io.File
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
 * adapted from Flocket's KeystoreSecretCipher, Apache-2.0). A corrupt file, one of another format version or for another
 * environment is deleted and reads as null; a Keystore or I/O failure reads as null and keeps the file. Never logs, and no
 * content reaches a file name or exception message.
 */
class EncryptedShellCacheStore internal constructor(
    private val dir: File,
    private val cipher: ShellCacheCipher,
    private val maxBytes: Int = ShellCacheCodec.MAX_ENCODED_BYTES,
) : ShellCacheStore {
    companion object {
        /** One lock per process: instances share the same files. */
        private val LOCK = Any()
        private val KEY = ProcessKeyCache { keystoreKey("trefoil.shell-cache.v1") }
        @Volatile private var shared: EncryptedShellCacheStore? = null

        /** The process-wide store. */
        fun get(context: Context): EncryptedShellCacheStore = shared ?: synchronized(LOCK) {
            shared ?: EncryptedShellCacheStore(File(context.applicationContext.noBackupFilesDir, "shell-cache.v1"), ShellCacheCipher(KEY)).also { shared = it }
        }

        internal fun fileName(environmentId: String): String =
            MessageDigest.getInstance("SHA-256").digest(environmentId.encodeToByteArray()).joinToString("") { "%02x".format(it) } + ".bin"

        private fun aad(environmentId: String) = "trefoil.shell-cache.v1:$environmentId".encodeToByteArray()
    }

    private fun file(environmentId: String) = AtomicFile(File(dir, fileName(environmentId)))

    override suspend fun read(environmentId: String): CachedShell? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val file = file(environmentId)
            // readFully restores the backup of an interrupted write; checking baseFile.exists() first would skip that.
            val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return@synchronized null } catch (_: Exception) { return@synchronized null }
            val plaintext = try { cipher.open(bytes, aad(environmentId)) }
            catch (_: IllegalArgumentException) { file.delete(); return@synchronized null }
            catch (_: AEADBadTagException) { file.delete(); return@synchronized null }
            // The Keystore may be briefly unavailable: keep the file for the next start.
            catch (_: Exception) { return@synchronized null }
            ShellCacheCodec.decode(plaintext, environmentId) ?: run { file.delete(); null }
        }
    }

    override suspend fun write(shell: CachedShell) = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val file = file(shell.environmentId)
            val encoded = ShellCacheCodec.encode(shell, maxBytes)
            // Too large to cache: an older, smaller entry would show rows that are no longer current.
            if (encoded == null) { file.delete(); return@synchronized }
            val sealed = cipher.seal(encoded, aad(shell.environmentId))
            dir.mkdirs()
            val stream = file.startWrite()
            try { stream.write(sealed); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }

    override suspend fun delete(environmentId: String) = withContext(Dispatchers.IO) {
        synchronized(LOCK) { file(environmentId).delete() }
    }

    override suspend fun retainOnly(environmentIds: Set<String>) = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val keep = environmentIds.mapTo(HashSet()) { fileName(it) }
            dir.listFiles()?.forEach { candidate ->
                val base = candidate.name.removeSuffix(".bak").removeSuffix(".new")
                if (base !in keep) candidate.delete()
            }
            Unit
        }
    }
}

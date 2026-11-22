package com.tajchert.trefoil.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.model.SshHostKey
import java.io.File
import java.security.InvalidKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Saved machines and SSH host keys; one process-wide lock covers every instance. */
interface EnvironmentStore {
    /** The saved machines; a successful read also becomes [lastKnown]. */
    fun read(): List<SavedEnvironment>
    fun readHostKeys(): List<SshHostKey>
    fun writeHostKeys(keys: List<SshHostKey>)
    /** Replaces the saved machines (and, once written, [lastKnown]); read-modify-write callers do it inside [locked]. */
    fun write(environments: List<SavedEnvironment>)
    /**
     * The machine list last read from or written to this vault by any caller in this process, or null while it has never
     * been readable; read it inside [locked].
     */
    val lastKnown: List<SavedEnvironment>?
    /** Runs [block] under the process-wide lock that every instance's machine writes take (view model, widget worker). */
    fun <T> locked(block: () -> T): T
    /** Atomic read-modify-write of the saved machines; a failed read fails the update and nothing is written. */
    fun update(transform: (List<SavedEnvironment>) -> List<SavedEnvironment>) = locked {
        val current = read()
        val next = transform(current)
        if (next != current) write(next)
    }
}

/** Keystore AES-GCM pattern adapted from Flocket's KeystoreSecretCipher (Apache-2.0). */
class EnvironmentVault(context: Context) : EnvironmentStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "environments.v1"))
    private val hostKeysFile = AtomicFile(File(context.noBackupFilesDir, "ssh-host-keys.v1"))
    private val hostKeysSerializer = ListSerializer(SshHostKey.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedEnvironment.serializer())
    override fun read(): List<SavedEnvironment> = synchronized(LOCK) {
        (readEncrypted(file)?.let { json.decodeFromString(serializer, it) } ?: emptyList()).also { lastRead = it }
    }
    override val lastKnown: List<SavedEnvironment>? get() = synchronized(LOCK) { lastRead }
    @Synchronized override fun readHostKeys(): List<SshHostKey> = readEncrypted(hostKeysFile)?.let { json.decodeFromString(hostKeysSerializer, it) } ?: emptyList()
    @Synchronized override fun writeHostKeys(keys: List<SshHostKey>) = writeEncrypted(hostKeysFile, json.encodeToString(hostKeysSerializer, keys))
    private fun readEncrypted(source: AtomicFile): String? {
        if (!source.baseFile.exists()) return null
        val (nonce, encrypted) = SecretEnvelope.decode(source.readFully())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        KEY.use { cipher.init(Cipher.DECRYPT_MODE, it, GCMParameterSpec(128, nonce)) }
        return cipher.doFinal(encrypted).decodeToString()
    }
    override fun write(environments: List<SavedEnvironment>) = synchronized(LOCK) {
        writeEncrypted(file, json.encodeToString(serializer, environments)); lastRead = environments
    }
    override fun <T> locked(block: () -> T): T = synchronized(LOCK) { block() }
    private fun writeEncrypted(destination: AtomicFile, plaintext: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        KEY.use { cipher.init(Cipher.ENCRYPT_MODE, it) }
        val bytes = SecretEnvelope.encode(cipher.iv, cipher.doFinal(plaintext.encodeToByteArray()))
        val stream = destination.startWrite()
        try { stream.write(bytes); destination.finishWrite(stream) } catch (e: Exception) { destination.failWrite(stream); throw e }
    }
    private companion object {
        /** One lock per process: instances share the same file. */
        val LOCK = Any()
        /** [lastKnown], shared by every instance in this process; guarded by [LOCK]. */
        var lastRead: List<SavedEnvironment>? = null
        /** Loaded once per process: the machine and host-key files (and every instance) share it, so a start-up read opens the Keystore once. */
        val KEY = ProcessKeyCache { keystoreKey("trefoil.environments.v1") }
    }
}

/** The AES-GCM key under [alias] in the Android Keystore, created on first use. */
internal fun keystoreKey(alias: String): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
    }.generateKey()
}

/**
 * A Keystore key handle loaded at most once per process (a Keystore load is a binder round trip). A key the Keystore
 * rejects ([InvalidKeyException], e.g. invalidated) is dropped and loaded once more, so a stale handle never outlives a
 * replaced entry; a failed load is not cached.
 */
internal class ProcessKeyCache(private val load: () -> SecretKey) {
    @Volatile private var cached: SecretKey? = null

    fun get(): SecretKey = cached ?: synchronized(this) { cached ?: load().also { cached = it } }

    /** Runs [block] with the key; a key rejected as invalid is reloaded and [block] runs once more. */
    fun <T> use(block: (SecretKey) -> T): T {
        val key = get()
        return try { block(key) } catch (_: InvalidKeyException) {
            synchronized(this) { if (cached === key) cached = null }
            block(get())
        }
    }
}

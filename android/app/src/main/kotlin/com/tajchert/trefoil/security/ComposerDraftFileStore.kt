package com.tajchert.trefoil.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tajchert.trefoil.core.session.ComposerDraftPersistence
import com.tajchert.trefoil.core.session.ComposerDraftStore
import com.tajchert.trefoil.core.session.CorruptComposerDraftsException
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * Encrypted local composer drafts (`composer-drafts.v1` in `noBackupFilesDir`), with its own Keystore
 * AES-GCM key; the same pattern as [EnvironmentVault], adapted from Flocket's KeystoreSecretCipher (Apache-2.0).
 * A missing file reads as null; a malformed envelope or failed tag check is [CorruptComposerDraftsException];
 * Keystore and I/O failures propagate so the store does not overwrite a file it could not read. Never logs content.
 * Shared process-wide through [get].
 */
class ComposerDraftFileStore(context: Context) : ComposerDraftPersistence {
    companion object {
        @Volatile private var store: ComposerDraftStore? = null
        /** The process-wide draft store: it outlives view models so its load and final write complete, and every screen shares it. */
        fun get(context: Context): ComposerDraftStore = store ?: synchronized(LOCK) {
            store ?: ComposerDraftStore(ComposerDraftFileStore(context.applicationContext), CoroutineScope(SupervisorJob() + Dispatchers.IO)).also { store = it }
        }
        /** One lock per process: instances share the same file. */
        private val LOCK = Any()
    }

    private val file = AtomicFile(File(context.noBackupFilesDir, "composer-drafts.v1"))
    private val alias = "trefoil.composer-drafts.v1"

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            // readFully restores the backup of an interrupted write; checking baseFile.exists() first would skip that.
            val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return@synchronized null }
            val (nonce, encrypted) = try { SecretEnvelope.decode(bytes) } catch (e: IllegalArgumentException) { throw CorruptComposerDraftsException(e) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            // A failed tag check with the current key can never succeed; Keystore and I/O failures propagate as transient.
            try { cipher.doFinal(encrypted).decodeToString() } catch (e: AEADBadTagException) { throw CorruptComposerDraftsException(e) }
        }
    }

    override suspend fun save(json: String?) = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            if (json == null) { file.delete(); return@synchronized }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val bytes = SecretEnvelope.encode(cipher.iv, cipher.doFinal(json.encodeToByteArray()))
            val stream = file.startWrite()
            try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}

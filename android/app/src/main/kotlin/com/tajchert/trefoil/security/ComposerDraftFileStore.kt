package com.tajchert.trefoil.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.tajchert.trefoil.core.session.ComposerDraftPersistence
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypted local composer drafts (`composer-drafts.v1` in `noBackupFilesDir`), with its own Keystore
 * AES-GCM key; the same pattern as [EnvironmentVault], adapted from Flocket's KeystoreSecretCipher (Apache-2.0).
 * A missing, corrupt or undecryptable file reads as empty; failures are swallowed and never log content.
 */
class ComposerDraftFileStore(context: Context) : ComposerDraftPersistence {
    private val file = AtomicFile(File(context.noBackupFilesDir, "composer-drafts.v1"))
    private val alias = "trefoil.composer-drafts.v1"

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            try {
                if (!file.baseFile.exists()) return@synchronized null
                val (nonce, encrypted) = SecretEnvelope.decode(file.readFully())
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
                cipher.doFinal(encrypted).decodeToString()
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
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

    private companion object {
        /** One lock per process: instances share the same file. */
        val LOCK = Any()
    }
}

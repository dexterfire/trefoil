package com.tajchert.trefoil.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tajchert.trefoil.core.session.DpopKey
import com.tajchert.trefoil.core.session.JcaDpopKey
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * This installation's DPoP proof key: EC P-256 in Android Keystore, sign-only and non-exportable. Created on first
 * DPoP use, never backed up. Losing it (app data cleared) makes DPoP-bound tokens useless, so those environments pair again.
 */
object DeviceDpopKey {
    private const val ALIAS = "trefoil.dpop.v1"
    @Volatile private var cached: DpopKey? = null

    @Synchronized fun loadOrCreate(): DpopKey {
        cached?.let { return it }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
        val key = if (entry != null) JcaDpopKey(entry.privateKey, entry.certificate.publicKey as ECPublicKey) else {
            val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
            JcaDpopKey(pair.private, pair.public as ECPublicKey)
        }
        return key.also { cached = it }
    }
}

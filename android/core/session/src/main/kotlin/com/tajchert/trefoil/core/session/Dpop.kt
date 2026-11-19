package com.tajchert.trefoil.core.session

import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/**
 * ES256 proof key for DPoP (RFC 9449). [sign] returns the raw 64-byte `r‖s` signature over the JWS signing input,
 * the format T3 verifies. Implementations never expose the private key; the app backs it with Android Keystore.
 */
interface DpopKey {
    val publicJwk: DpopPublicJwk
    fun sign(signingInput: ByteArray): ByteArray
}

/** Public P-256 JWK; coordinates are 32-byte base64url values. */
data class DpopPublicJwk(val x: String, val y: String) {
    /** RFC 7638 thumbprint over `crv`, `kty`, `x`, `y` in that order, as T3 computes it for token binding. */
    val thumbprint: String get() = Dpop.base64Url(Dpop.sha256("""{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""))

    companion object {
        fun of(key: ECPublicKey): DpopPublicJwk = DpopPublicJwk(Dpop.base64Url(Dpop.fixed32(key.w.affineX)), Dpop.base64Url(Dpop.fixed32(key.w.affineY)))
    }
}

/** JCA signer: an Android Keystore private key (non-exportable) in the app, a software key in tests. */
class JcaDpopKey(private val privateKey: PrivateKey, publicKey: ECPublicKey) : DpopKey {
    override val publicJwk: DpopPublicJwk = DpopPublicJwk.of(publicKey)
    override fun sign(signingInput: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey); update(signingInput); Dpop.derToRaw(sign())
    }
}

/** DPoP proof JWTs in T3's shape (`packages/shared/src/dpop.ts`). Proofs are credentials: never log them. */
object Dpop {
    /** `htu`: the request URL without query and fragment (T3 `normalizeDpopHtu`). */
    fun htu(url: HttpUrl): String = url.newBuilder().query(null).fragment(null).build().toString()

    /** `ath`: base64url SHA-256 of the access token (or WebSocket ticket) the proof accompanies. */
    fun ath(accessToken: String): String = base64Url(sha256(accessToken))

    /**
     * A single-use proof for one request. [accessToken] adds `ath` (omit it for the token exchange itself).
     * [issuedAtSeconds] should already include any server clock offset.
     */
    fun proof(key: DpopKey, method: String, url: HttpUrl, accessToken: String?, issuedAtSeconds: Long, jti: String = UUID.randomUUID().toString()): String {
        val jwk = key.publicJwk
        val header = buildJsonObject {
            put("typ", "dpop+jwt"); put("alg", "ES256")
            put("jwk", buildJsonObject { put("kty", "EC"); put("crv", "P-256"); put("x", jwk.x); put("y", jwk.y) })
        }
        val payload = buildJsonObject {
            put("htm", method.uppercase()); put("htu", htu(url)); put("jti", jti); put("iat", issuedAtSeconds)
            if (accessToken != null) put("ath", ath(accessToken))
        }
        val signingInput = base64Url(header.toString().encodeToByteArray()) + "." + base64Url(payload.toString().encodeToByteArray())
        val signature = key.sign(signingInput.encodeToByteArray())
        require(signature.size == 64) { "DPoP signature must be raw r||s" }
        return signingInput + "." + base64Url(signature)
    }

    internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    internal fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())

    internal fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray().let { if (it.size > 32 && it[0] == 0.toByte()) it.copyOfRange(it.size - 32, it.size) else it }
        require(bytes.size <= 32) { "P-256 coordinate is too long" }
        return ByteArray(32 - bytes.size) + bytes
    }

    /** JCA ECDSA signatures are DER `SEQUENCE { INTEGER r, INTEGER s }`; JWS ES256 wants fixed 32-byte `r‖s`. */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var index = 0
        fun length(): Int {
            val first = der[index++].toInt() and 0xff
            if (first < 0x80) return first
            var value = 0
            repeat(first and 0x7f) { value = (value shl 8) or (der[index++].toInt() and 0xff) }
            return value
        }
        fun integer(): ByteArray {
            require(der[index++] == 0x02.toByte()) { "Invalid ECDSA signature" }
            val size = length()
            val value = der.copyOfRange(index, index + size); index += size
            return fixed32(BigInteger(1, value))
        }
        require(der[index++] == 0x30.toByte()) { "Invalid ECDSA signature" }
        length()
        return integer() + integer()
    }
}

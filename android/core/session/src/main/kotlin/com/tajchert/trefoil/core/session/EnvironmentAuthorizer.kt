package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.AccessTokenType
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.protocol.EffectRpc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/** A DPoP environment without a usable device key: auth-definite, the token cannot be presented again. */
class DpopKeyUnavailable : IllegalStateException("This device's sign-in key is unavailable. Pair again.")

/**
 * Credential headers for a saved environment: `Authorization: Bearer <token>`, or `Authorization: DPoP <token>` plus a
 * fresh single-use `DPoP` proof bound to the method, URL and token (T3 `EnvironmentAuth.authenticateRequest`).
 * T3 rejects a DPoP-bound token sent as bearer and a bearer token sent as DPoP, so the scheme follows the saved
 * [SavedEnvironment.tokenType]. [dpopKey] is only invoked for DPoP work, so bearer-only use never touches the keystore.
 */
class EnvironmentAuthorizer(
    private val dpopKey: (() -> DpopKey)? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    /** Server clock minus device clock per host (scheme, host, port), learned from a DPoP `time_window` rejection's `Date` header. */
    private val serverOffsetMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun hostKey(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    val supportsDpop: Boolean get() = dpopKey != null

    fun authorize(builder: Request.Builder, environment: SavedEnvironment, method: String, url: HttpUrl): Request.Builder = when (environment.tokenType) {
        AccessTokenType.Bearer -> builder.header("Authorization", "Bearer ${environment.accessToken}")
        AccessTokenType.DPoP -> builder.header("Authorization", "DPoP ${environment.accessToken}").header("DPoP", proof(method, url, environment.accessToken))
    }

    /** A proof for [url]; [accessToken] adds `ath` (the access token, or the WebSocket ticket for the upgrade). */
    fun proof(method: String, url: HttpUrl, accessToken: String?): String {
        val key = try { dpopKey?.invoke() } catch (_: Exception) { null } ?: throw DpopKeyUnavailable()
        return Dpop.proof(key, method, url, accessToken, Math.floorDiv(clockMillis() + (serverOffsetMillis[hostKey(url)] ?: 0L), 1000L))
    }

    /**
     * Inspects a 401 carrying T3's DPoP challenge. Returns true when one retry with a fresh proof can succeed: a
     * `replay` (the proof's jti was already used) or a `time_window` miss, after adopting the server's clock. Any
     * other DPoP 401 is left to [EnvironmentTokenRenewer] (one renewal); a refused renewal is auth-definite: pair again.
     */
    fun shouldRetry(response: Response): Boolean {
        if (response.code != 401) return false
        if (response.headers("WWW-Authenticate").none { it.trim().startsWith("DPoP", ignoreCase = true) }) return false
        val body = runCatching { EffectRpc.json.parseToJsonElement(response.peekBody(16_384).string()) as? JsonObject }.getOrNull()
        return when ((body?.get("dpopFailureReason") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "replay" -> true
            "time_window" -> {
                val serverMillis = response.headers.getDate("Date")?.time ?: return false
                serverOffsetMillis[hostKey(response.request.url)] = serverMillis - clockMillis()
                true
            }
            else -> false
        }
    }
}

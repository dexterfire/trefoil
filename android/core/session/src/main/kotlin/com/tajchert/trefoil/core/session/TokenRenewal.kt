package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.AccessTokenType
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.protocol.EffectRpc
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The host definitely refused to renew (session expired or revoked, key not bound): auth-definite, pair again. */
class TokenRenewalRejected : IllegalStateException("This device's sign-in expired or was revoked; pair again.")

/** Renewal did not reach a definite answer (network, timeout, server error): keep the session and try again later. */
class TokenRenewalUnavailable(cause: Throwable? = null) :
    IllegalStateException("Could not renew this device's sign-in. Check the network and try again.", cause)

/** The token fields of [renewed] on this saved environment; routes, name and SSH settings stay this copy's. */
fun SavedEnvironment.withTokenOf(renewed: SavedEnvironment): SavedEnvironment =
    copy(accessToken = renewed.accessToken, tokenType = renewed.tokenType, accessTokenExpiresAtMillis = renewed.accessTokenExpiresAtMillis)

/**
 * Renews DPoP sessions from a plain pairing link (T3 `EnvironmentAuth.renewAccessToken`): `POST /oauth/token` token
 * exchange with the current access token, even an expired one, as `subject_token` of the access-token type, a proof from
 * the same key and no scope. Each renewal moves the session's expiry 30 days out and returns a fresh 1-hour token for the
 * same session, so live sockets keep their session and earlier tokens of it stay renewable.
 *
 * Renewals of one environment are serialized: a caller that waited behind another's renewal reuses its result instead of
 * renewing again. [onRenewed] persists a renewal (encrypted vault); `replaces` holds the earlier tokens of the same
 * session, so a store only overwrites a token of that session, never one from a newer pairing. Bearer environments are
 * returned unchanged.
 */
class EnvironmentTokenRenewer(
    dpopKey: (() -> DpopKey)? = null,
    http: OkHttpClient = OkHttpClient(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onRenewed: suspend (renewed: SavedEnvironment, replaces: Set<String>) -> Unit = { _, _ -> },
    timeoutMillis: Long = 15_000,
) {
    /** Proof and header builder; shared with the clients so a learned server clock offset serves every request. */
    val authorizer = EnvironmentAuthorizer(dpopKey, clockMillis)
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
    private class Latest(val environment: SavedEnvironment, val replaces: Set<String>)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val latest = ConcurrentHashMap<String, Latest>()
    /** When a renewal last failed without a definite answer; an unexpired token is not renewed again before [RETRY_AFTER_MS]. */
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The newest token known for [environment]'s session: a renewal made through any caller, or [environment] itself. */
    fun current(environment: SavedEnvironment): SavedEnvironment =
        latest[environment.id]?.takeIf { environment.accessToken in it.replaces }?.let { environment.withTokenOf(it.environment) } ?: environment

    /** True for a DPoP token within [RENEW_BEFORE_EXPIRY_MS] of its expiry (or past it). An unknown expiry renews only on rejection. */
    fun renewalDue(environment: SavedEnvironment, now: Long = clockMillis()): Boolean =
        environment.tokenType == AccessTokenType.DPoP && environment.accessTokenExpiresAtMillis?.let { it - RENEW_BEFORE_EXPIRY_MS <= now } == true

    fun isExpired(environment: SavedEnvironment): Boolean = environment.accessTokenExpiresAtMillis?.let { it <= clockMillis() } == true

    /** Drops what this renewer knows of [environmentId]'s session (forgotten or paired again). */
    fun forget(environmentId: String) { latest.remove(environmentId); failedAt.remove(environmentId) }

    /**
     * A usable token for [environment], renewing at [base] (an identity-verified route) when it is due, or when
     * [rejectedToken] is still the newest token (the host refused it). Throws [TokenRenewalRejected],
     * [TokenRenewalUnavailable] or [DpopKeyUnavailable].
     */
    suspend fun fresh(environment: SavedEnvironment, base: HttpUrl, rejectedToken: String? = null): SavedEnvironment {
        if (environment.tokenType != AccessTokenType.DPoP) return environment
        return locks.getOrPut(environment.id) { Mutex() }.withLock {
            val current = current(environment)
            val now = clockMillis()
            if (current.accessToken != rejectedToken) {
                if (!renewalDue(current, now)) return@withLock current
                // Due but still valid, and a renewal just failed: keep using it rather than retrying on every request.
                if (!isExpired(current) && failedAt[environment.id]?.let { now - it in 0 until RETRY_AFTER_MS } == true) return@withLock current
            }
            val renewed = try { current.withTokenOf(renew(current, base)) } catch (error: TokenRenewalUnavailable) {
                failedAt[environment.id] = clockMillis(); throw error
            }
            failedAt.remove(environment.id)
            val chain = (latest[environment.id]?.takeIf { environment.accessToken in it.replaces }?.replaces.orEmpty() +
                environment.accessToken + current.accessToken).toList().takeLast(MAX_CHAIN).toSet()
            latest[environment.id] = Latest(renewed, chain + renewed.accessToken)
            // The host already moved the session; a cancelled caller must not lose the new token.
            withContext(NonCancellable) {
                try { onRenewed(renewed, chain) } catch (error: CancellationException) { throw error } catch (_: Exception) {
                    // Not persisted: the in-memory token still works, and the stored one still renews the same session.
                }
            }
            renewed
        }
    }

    private suspend fun renew(current: SavedEnvironment, base: HttpUrl): SavedEnvironment {
        val tokenUrl = base.newBuilder().encodedPath("/oauth/token").query(null).fragment(null).build()
        val form = FormBody.Builder().add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange")
            .add("subject_token", current.accessToken).add("subject_token_type", ACCESS_TOKEN_TYPE)
            .add("requested_token_type", ACCESS_TOKEN_TYPE).build()
        // The proof (no `ath`) is a keystore signature, so build it off the caller's thread.
        suspend fun request() = withContext(Dispatchers.IO) {
            Request.Builder().url(tokenUrl).post(form).header("DPoP", authorizer.proof("POST", tokenUrl, null)).build()
        }
        // A cancelled caller (a route probe that gave up) cancels the blocking call, so the renewal lock is released
        // promptly. The call runs non-cancellable otherwise: a response already read is returned, and stored, even when
        // the caller was cancelled meanwhile, because the host has already moved the session.
        suspend fun exchange(request: Request): Pair<Int, JsonObject?> {
            val call = http.newCall(request)
            val abort = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                // Two steps on purpose: returning from an IO hop straight into a cancelled caller would drop the result.
                return withContext(NonCancellable) { withContext(Dispatchers.IO) {
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) return@use response.code to if (authorizer.shouldRetry(response)) RETRY else null
                            response.code to (EffectRpc.json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject)
                        }
                    } catch (error: IOException) {
                        if (call.isCanceled()) throw CancellationException("Token renewal cancelled").apply { initCause(error) }
                        throw TokenRenewalUnavailable(error)
                    } catch (error: kotlinx.serialization.SerializationException) { throw TokenRenewalUnavailable(error) }
                } }
            } finally { abort.cancel() }
        }
        var (status, body) = exchange(request())
        // `replay` or clock skew: one retry with a fresh proof, as for any DPoP request.
        if (body === RETRY) { val retried = exchange(request()); status = retried.first; body = retried.second }
        if (status == 400 || status == 401 || status == 403) throw TokenRenewalRejected()
        if (status !in 200..299 || body == null || body === RETRY) throw TokenRenewalUnavailable()
        val token = (body["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val expiresIn = (body["expires_in"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        if ((body["token_type"] as? JsonPrimitive)?.content != "DPoP" || token.isNullOrBlank() || expiresIn == null || expiresIn <= 0) throw TokenRenewalUnavailable()
        return current.copy(accessToken = token, tokenType = AccessTokenType.DPoP, accessTokenExpiresAtMillis = clockMillis() + (expiresIn * 1000).toLong())
    }

    companion object {
        /** T3 client runtime `DPOP_ACCESS_TOKEN_REFRESH_SKEW_MS`. */
        const val RENEW_BEFORE_EXPIRY_MS = 60_000L
        private const val RETRY_AFTER_MS = 5_000L
        private const val MAX_CHAIN = 8
        private const val ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
        private val RETRY = JsonObject(emptyMap())
    }
}

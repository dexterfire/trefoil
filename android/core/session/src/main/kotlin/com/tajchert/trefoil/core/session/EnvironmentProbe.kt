package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.AccessTokenType
import com.tajchert.trefoil.core.model.EnvironmentRoute
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.PairingException
import com.tajchert.trefoil.core.model.RouteKind
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.core.protocol.EffectRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Read-only reachability check for a saved environment: the public descriptor, then
 * `GET /api/auth/session` with the saved token (bearer, or DPoP with a fresh proof). It opens no WebSocket, requests no
 * ticket and sends no command. The shared URL policy runs first, so a blocked address is
 * never contacted, and the token is only sent after the descriptor proves the same identity.
 */
class EnvironmentProbe(
    private val timeoutMillis: Long = 8_000,
    http: OkHttpClient = OkHttpClient(),
    /** This device's DPoP proof key, for environments paired with a DPoP-bound token. */
    dpopKey: (() -> DpopKey)? = null,
    /** Renews an expired or rejected DPoP token before deciding; share the client's so renewals are serialized. */
    private val tokens: EnvironmentTokenRenewer = EnvironmentTokenRenewer(dpopKey),
) {
    private val authorizer = tokens.authorizer
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()

    /** Result of [probe] over several routes; [route] is the route that decided it (null when none did). */
    data class RouteProbe(val result: MachineProbe, val route: EnvironmentRoute?)

    /** [baseUrl] overrides the saved address, e.g. with an SSH tunnel's ephemeral loopback port. */
    suspend fun probe(environment: SavedEnvironment, baseUrl: String = environment.baseUrl): MachineProbe = probeAt(environment, baseUrl, timeoutMillis)

    /**
     * Probes [routes] in order (`Ssh` placeholders are skipped; the caller opens SSH itself). The first `Online`
     * wins and `AuthFailed` stops, since the token belongs to the host. An identity mismatch or blocked address
     * moves on but is reported as `NeedsAttention` when no route is online. Local and Tailnet routes get at most
     * 3 s each; an empty plan is `Unknown`.
     */
    suspend fun probe(environment: SavedEnvironment, routes: List<EnvironmentRoute>): RouteProbe {
        var attention: EnvironmentRoute? = null
        var tried = false
        for (route in routes) {
            if (route.kind == RouteKind.Ssh) continue
            tried = true
            val limit = if (route.kind == RouteKind.Lan || route.kind == RouteKind.Tailnet) minOf(timeoutMillis, LOCAL_TIMEOUT_MS) else timeoutMillis
            when (probeAt(environment, route.baseUrl, limit)) {
                MachineProbe.Online -> return RouteProbe(MachineProbe.Online, route)
                MachineProbe.AuthFailed -> return RouteProbe(MachineProbe.AuthFailed, route)
                MachineProbe.NeedsAttention -> if (attention == null) attention = route
                else -> Unit
            }
        }
        return when {
            attention != null -> RouteProbe(MachineProbe.NeedsAttention, attention)
            tried -> RouteProbe(MachineProbe.Unreachable, null)
            else -> RouteProbe(MachineProbe.Unknown, null)
        }
    }

    private suspend fun probeAt(environment: SavedEnvironment, baseUrl: String, timeoutMillis: Long): MachineProbe {
        val base = try { validatedEnvironmentUrl(baseUrl, environment.allowInsecureHttp) } catch (_: PairingException) { return MachineProbe.NeedsAttention }
        return withTimeoutOrNull(timeoutMillis) {
            try {
                val (info, descriptorStatus) = get(Request.Builder().url(base.resolve("/.well-known/t3/environment")!!).build())
                    ?: return@withTimeoutOrNull MachineProbe.Unreachable
                if (descriptorStatus != 200 || info == null) return@withTimeoutOrNull MachineProbe.Unreachable
                if (info["orchestrationProtocolVersion"]?.jsonPrimitive?.intOrNull != 2 ||
                    info["environmentId"]?.jsonPrimitive?.contentOrNull != environment.id) return@withTimeoutOrNull MachineProbe.NeedsAttention
                val sessionUrl = base.resolve("/api/auth/session")!!
                // Like the client's connect: a due DPoP token renews first, a renewal that cannot reach the host keeps an
                // unexpired token, and a definite refusal is AuthFailed.
                var current = try { tokens.fresh(environment, base) }
                    catch (_: TokenRenewalUnavailable) { tokens.current(environment).takeUnless(tokens::isExpired) ?: return@withTimeoutOrNull MachineProbe.Unreachable }
                    catch (_: TokenRenewalRejected) { return@withTimeoutOrNull MachineProbe.AuthFailed }
                    catch (_: DpopKeyUnavailable) { return@withTimeoutOrNull MachineProbe.AuthFailed }
                suspend fun sessionRequest() = withContext(Dispatchers.IO) { authorizer.authorize(Request.Builder().url(sessionUrl), current, "GET", sessionUrl).build() }
                val first = try { get(sessionRequest()) } catch (_: DpopKeyUnavailable) { return@withTimeoutOrNull MachineProbe.AuthFailed }
                    ?: return@withTimeoutOrNull MachineProbe.Unreachable
                // A DPoP `replay` or clock-skew answer gets one fresh proof; any other DPoP 401 renews once.
                val retry = when {
                    first.third -> true
                    first.second == 401 && current.tokenType == AccessTokenType.DPoP -> {
                        current = try { tokens.fresh(current, base, rejectedToken = current.accessToken) }
                            catch (_: TokenRenewalUnavailable) { return@withTimeoutOrNull MachineProbe.Unreachable }
                            catch (_: TokenRenewalRejected) { return@withTimeoutOrNull MachineProbe.AuthFailed }
                            catch (_: DpopKeyUnavailable) { return@withTimeoutOrNull MachineProbe.AuthFailed }
                        true
                    }
                    else -> false
                }
                val (session, status) = (if (retry) try { get(sessionRequest()) } catch (_: DpopKeyUnavailable) { return@withTimeoutOrNull MachineProbe.AuthFailed } else first)
                    ?: return@withTimeoutOrNull MachineProbe.Unreachable
                when {
                    status == 401 || status == 403 -> MachineProbe.AuthFailed
                    status != 200 -> MachineProbe.Unreachable
                    session?.get("authenticated")?.jsonPrimitive?.booleanOrNull == true -> MachineProbe.Online
                    else -> MachineProbe.AuthFailed
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { MachineProbe.Unreachable }
        } ?: MachineProbe.Unreachable
    }

    /**
     * Parsed JSON object body (null if absent or not an object), status and whether a DPoP retry may help; null on network
     * failure. Headers can arrive before the body, so the body is read and closed on [Dispatchers.IO], never on the caller's
     * dispatcher (Main for the cold-start probe). Until it is consumed, a cancelled caller cancels the call, which also
     * aborts a body read in progress.
     */
    private suspend fun get(request: Request): Triple<JsonObject?, Int, Boolean>? = try {
        val call = http.newCall(request)
        withContext(Dispatchers.IO) {
            val consumed = AtomicBoolean(false)
            val cancelOnAbandon = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { if (!consumed.get()) call.cancel() }
            }
            try {
                call.await().use { response ->
                    val body = runCatching { EffectRpc.json.parseToJsonElement(response.peekBody(MAX_BODY).string()) as? JsonObject }.getOrNull()
                    Triple(body, response.code, authorizer.shouldRetry(response))
                }
            } finally { consumed.set(true); cancelOnAbandon.cancel() }
        }
    } catch (_: IOException) { null }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
        })
    }

    private companion object { const val MAX_BODY = 65_536L; const val LOCAL_TIMEOUT_MS = 3_000L }
}

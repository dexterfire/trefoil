package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.ssh.SshConnectionException
import com.tajchert.trefoil.core.ssh.SshHostKeyException
import kotlinx.coroutines.*

/** A short-lived SSH tunnel opened only to probe; [close] must be safe to call from IO. */
interface ProbeTunnel { val baseUrl: String; fun close() }

/**
 * Tries [routes] in order with [single] (a one-address probe): the first [MachineProbe.Online] wins, an
 * [MachineProbe.AuthFailed] is decisive (the token is the same on every route) and stops, and an identity mismatch or
 * blocked address ([MachineProbe.NeedsAttention]) moves on but is reported if no route ends up online. Each route gets
 * [preflightTimeoutMillis]-sized budget (Lan/Tailnet 3 s, others [publicTimeoutMillis]) so a dead LAN address cannot
 * starve the tunnel ([probeRouteTimeoutMillis]). Adapter over the single-route `EnvironmentProbe.probe`; the session's multi-route probe has the
 * same contract and may replace it.
 */
suspend fun probeRoutes(routes: List<EnvironmentRoute>, publicTimeoutMillis: Long = PROBE_PUBLIC_TIMEOUT_MS, single: suspend (EnvironmentRoute) -> MachineProbe): MachineProbe {
    var attention = false
    for (route in routes) {
        val timeout = probeRouteTimeoutMillis(route.kind, publicTimeoutMillis)
        val result = try { withTimeoutOrNull(timeout) { single(route) } ?: MachineProbe.Unreachable }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { MachineProbe.Unreachable }
        when (result) {
            MachineProbe.Online, MachineProbe.AuthFailed -> return result
            MachineProbe.NeedsAttention -> attention = true
            else -> {}
        }
    }
    return if (attention) MachineProbe.NeedsAttention else MachineProbe.Unreachable
}

internal const val PROBE_PUBLIC_TIMEOUT_MS = 8_000L

/** One route's probe budget in [probeRoutes]: Lan/Tailnet answer fast or not at all. */
internal fun probeRouteTimeoutMillis(kind: RouteKind, publicTimeoutMillis: Long = PROBE_PUBLIC_TIMEOUT_MS): Long =
    if (kind == RouteKind.Lan || kind == RouteKind.Tailnet) preflightTimeoutMillis(kind) else publicTimeoutMillis

/** Budget for one group of direct routes: every route's [probeRouteTimeoutMillis] plus slack, so the last route (the tunnel) is always reached. */
internal fun directGroupTimeoutMillis(routes: List<EnvironmentRoute>): Long = routes.sumOf { probeRouteTimeoutMillis(it.kind) } + 2_000

/**
 * Checks saved machines that are not the active connection. Read-only: direct machines get the descriptor + session
 * check on every direct route of their plan ([direct], in preference order); SSH machines try their learned direct
 * routes, then SSH (only with saved credentials, through a tunnel that verifies the pinned host key and never prompts
 * or trusts a new one), then the T3 Connect tunnel.
 */
class MachineProber(
    /** Multi-route probe with the [probeRoutes] contract. Only an SSH probe passes an Ssh route: its tunnel's ephemeral loopback address. */
    private val direct: suspend (SavedEnvironment, List<EnvironmentRoute>) -> MachineProbe,
    private val openSsh: suspend (SshConnectionInput, String?) -> ProbeTunnel,
    /** Budget for one group of direct routes. */
    private val directTimeoutMillis: (List<EnvironmentRoute>) -> Long = ::directGroupTimeoutMillis,
    /** SSH login plus runtime discovery needs a few round trips before the HTTP check. */
    private val sshTimeoutMillis: Long = 15_000,
) {
    suspend fun probe(environment: SavedEnvironment, hostKeys: List<SshHostKey>): MachineProbe = try {
        if (environment.ssh == null) probeDirect(environment, environment.candidateRoutes().filter { it.kind != RouteKind.Ssh }) ?: MachineProbe.NeedsAttention
        else probeSshMachine(environment, hostKeys)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { MachineProbe.Unreachable }

    /** Null when there is no route to try. */
    private suspend fun probeDirect(environment: SavedEnvironment, routes: List<EnvironmentRoute>): MachineProbe? {
        if (routes.isEmpty()) return null
        return try { withTimeoutOrNull(directTimeoutMillis(routes)) { direct(environment, routes) } ?: MachineProbe.Unreachable }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { MachineProbe.Unreachable }
    }

    private suspend fun probeSshMachine(environment: SavedEnvironment, hostKeys: List<SshHostKey>): MachineProbe {
        val plan = environment.candidateRoutes()
        val sshAt = plan.indexOfFirst { it.kind == RouteKind.Ssh }.takeIf { it >= 0 } ?: plan.size
        val results = mutableListOf<MachineProbe>()
        probeDirect(environment, plan.take(sshAt))?.let { result ->
            if (result == MachineProbe.Online || result == MachineProbe.AuthFailed) return result
            results += result
        }
        val (ssh, decisive) = probeSsh(environment, hostKeys)
        if (ssh == MachineProbe.Online || decisive) return ssh
        results += ssh
        probeDirect(environment, plan.drop(sshAt + 1))?.let { result ->
            if (result == MachineProbe.Online || result == MachineProbe.AuthFailed) return result
            results += result
        }
        // Nothing online: report what needs the user first (SSH login, then identity/trust, then missing credentials).
        return listOf(MachineProbe.AuthFailed, MachineProbe.NeedsAttention, MachineProbe.Unknown).firstOrNull { it in results } ?: MachineProbe.Unreachable
    }

    /** Second: true when the T3 server itself rejected the token (decisive for every route), not the SSH login. */
    private suspend fun probeSsh(environment: SavedEnvironment, hostKeys: List<SshHostKey>): Pair<MachineProbe, Boolean> {
        val input = environment.sshInput()
        val hasCredentials = when (input.authMethod) {
            SshAuthMethod.Password -> input.password.isNotEmpty()
            SshAuthMethod.PrivateKey -> input.privateKey.isNotEmpty()
        }
        if (!hasCredentials) return MachineProbe.Unknown to false
        if (sshInputValidationError(input) != null) return MachineProbe.NeedsAttention to false
        val profile = environment.ssh!!
        val pinned = hostKeys.firstOrNull { it.host.equals(input.host, ignoreCase = true) && it.port == input.port }?.fingerprint
            ?: profile.fingerprint.takeIf { it.isNotBlank() }
            // Without a pinned key the probe would have to ask for trust; that only happens on an explicit connect.
            ?: return MachineProbe.NeedsAttention to false
        return try {
            withTimeoutOrNull(sshTimeoutMillis) {
                val tunnel = try { openSsh(input, pinned) }
                catch (e: CancellationException) { throw e }
                catch (_: SshHostKeyException) { return@withTimeoutOrNull MachineProbe.NeedsAttention to false }
                catch (e: SshConnectionException) { return@withTimeoutOrNull sshFailure(e) to false }
                try { direct(environment, listOf(EnvironmentRoute(RouteKind.Ssh, tunnel.baseUrl, RouteSource.Paired))).let { it to (it == MachineProbe.AuthFailed) } }
                finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { tunnel.close() } } }
            } ?: (MachineProbe.Unreachable to false)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { MachineProbe.Unreachable to false }
    }

    private fun sshFailure(error: SshConnectionException): MachineProbe {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("SSH authentication failed") || message.startsWith("Could not unlock the private key") -> MachineProbe.AuthFailed
            message.contains("identity changed") || message.startsWith("Unsupported T3 runtime") || message.contains("compatible protocol") -> MachineProbe.NeedsAttention
            else -> MachineProbe.Unreachable
        }
    }
}

/** What to do about auto-connect on app open, given the probes so far. */
sealed interface AutoConnectDecision {
    data object Wait : AutoConnectDecision
    data object None : AutoConnectDecision
    data class Connect(val environmentId: String) : AutoConnectDecision
}

/**
 * Prefer the last-used machine when it is reachable; otherwise connect only when exactly one
 * machine is reachable. Waits while a deciding probe is still running.
 */
fun autoConnectDecision(lastUsedId: String?, environments: List<SavedEnvironment>, probes: Map<String, MachineProbe>): AutoConnectDecision {
    val lastUsed = environments.firstOrNull { it.id == lastUsedId }
    if (lastUsed != null) when (probes[lastUsed.id]) {
        MachineProbe.Online -> return AutoConnectDecision.Connect(lastUsed.id)
        MachineProbe.Checking -> return AutoConnectDecision.Wait
        else -> {}
    }
    if (environments.any { probes[it.id] == MachineProbe.Checking }) return AutoConnectDecision.Wait
    val online = environments.filter { probes[it.id] == MachineProbe.Online }
    return if (online.size == 1) AutoConnectDecision.Connect(online.single().id) else AutoConnectDecision.None
}

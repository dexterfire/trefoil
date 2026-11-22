package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal const val BETTER_ROUTE_CHECK_INTERVAL_MS = 60_000L
internal const val SILENT_ROUTE_BACKOFF_MAX_MS = 600_000L
internal const val BETTER_ROUTE_COOLDOWN_MS = 300_000L

/** What [RouteMonitor] watches: the live connection of [environmentId] on [active], and its connection [plan]. */
internal data class RouteWatch(val environmentId: String, val active: EnvironmentRoute, val plan: List<EnvironmentRoute>, val allowInsecureHttp: Boolean)

/** Ssh routes match by kind (their loopback port is ephemeral); others by normalized base. */
internal fun sameRoute(a: EnvironmentRoute, b: EnvironmentRoute): Boolean =
    if (a.kind == RouteKind.Ssh || b.kind == RouteKind.Ssh) a.kind == b.kind
    else a.baseUrl == b.baseUrl || normalizedRouteBase(a.baseUrl)?.let { it == normalizedRouteBase(b.baseUrl) } == true

/** Routes of [plan] ranked above [active], without Ssh (it needs a login, never a silent preflight). */
internal fun betterRoutes(plan: List<EnvironmentRoute>, active: EnvironmentRoute): List<EnvironmentRoute> {
    val index = plan.indexOfFirst { sameRoute(it, active) }
    val above = if (index >= 0) plan.take(index) else plan.filter { it.kind.ordinal < active.kind.ordinal }
    return above.filter { it.kind != RouteKind.Ssh }
}

/**
 * [plan] without the routes that cannot work on the default network when it is plain cellular ([cellularOnly]: no Wi-Fi,
 * Ethernet, Bluetooth, USB or VPN transport). There a literal RFC 1918 address (LAN) or 100.64/10 address (Tailnet) is
 * carrier space, never the user's machine, so trying it only spends its timeout before the next route. Host names
 * (a `.ts.net` name may be Funnel), loopback and the emulator's 10.0.2.2 host alias are kept. Null (network unknown)
 * keeps every route, and so does a plan that would end up empty: the attempt then fails as before instead of not trying.
 */
internal fun usableRoutes(plan: List<EnvironmentRoute>, cellularOnly: Boolean?): List<EnvironmentRoute> =
    if (cellularOnly != true) plan else plan.filterNot(::unreachableOnCellular).ifEmpty { plan }

private fun unreachableOnCellular(route: EnvironmentRoute): Boolean {
    if (route.kind != RouteKind.Lan && route.kind != RouteKind.Tailnet) return false
    val host = try { java.net.URI(route.baseUrl).host } catch (_: Exception) { null } ?: return false
    return if (route.kind == RouteKind.Lan) host != "10.0.2.2" && isPrivateLanAddress(host) else isCarrierGradeNatAddress(host)
}

/** A literal IPv4 address in 100.64.0.0/10 (Tailscale addresses, carrier-grade NAT elsewhere). */
private fun isCarrierGradeNatAddress(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } }) return false
    val octets = parts.map { it.toInt() }
    return octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
}

/** Direct routes answer fast or not at all; public and tunnel routes may cross the internet. */
internal fun preflightTimeoutMillis(kind: RouteKind): Long = if (kind == RouteKind.Lan || kind == RouteKind.Tailnet) 3_000 else 15_000

/** T3 supervisor `backOffSilentRoute`: `60 s * 2^failures`, here capped at 10 min. */
internal fun silentRouteBackoffMillis(failures: Int): Long =
    if (failures >= 10) SILENT_ROUTE_BACKOFF_MAX_MS else minOf(SILENT_ROUTE_BACKOFF_MAX_MS, BETTER_ROUTE_CHECK_INTERVAL_MS shl failures)

/**
 * While connected on a route that is not the best of its plan, checks every [BETTER_ROUTE_CHECK_INTERVAL_MS] (and on
 * [requestCheck]) whether a better route answers a credential-free preflight. A route that stays silent backs off
 * ([silentRouteBackoffMillis]); a route whose switch failed is not offered again for [BETTER_ROUTE_COOLDOWN_MS]. When a
 * better route answers and [canSwitch] (no mutation or draft send in flight), [onBetterRoute] is called once; otherwise
 * the next tick tries again. It never switches by itself: the caller reconnects and reports failure via [switchFailed].
 *
 * Confine calls to [scope]'s thread (the main dispatcher in the app). [now] is a monotonic clock in milliseconds.
 */
internal class RouteMonitor(
    private val scope: CoroutineScope,
    private val preflight: suspend (environmentId: String, route: EnvironmentRoute, allowInsecureHttp: Boolean, timeoutMillis: Long) -> Boolean,
    private val canSwitch: () -> Boolean,
    private val onBetterRoute: (RouteWatch, EnvironmentRoute) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Silent(val failures: Int, val until: Long)
    private var current: RouteWatch? = null
    private var job: Job? = null
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private val silent = mutableMapOf<String, Silent>()
    private val cooldowns = mutableMapOf<String, Long>()

    private fun key(environmentId: String, route: EnvironmentRoute) = environmentId + "|" + (normalizedRouteBase(route.baseUrl) ?: route.baseUrl)

    val watching: RouteWatch? get() = current

    /** Starts, updates or (with null, or when nothing ranks above the active route) stops watching. */
    fun watch(target: RouteWatch?) {
        val next = target?.takeIf { betterRoutes(it.plan, it.active).isNotEmpty() }
        val previous = current
        current = next
        if (next == null) { job?.cancel(); job = null; return }
        if (previous?.environmentId != next.environmentId) silent.clear()
        // A learned route or consent change only updates the plan; a new connection restarts the interval.
        if (job?.isActive == true && previous != null && previous.environmentId == next.environmentId && sameRoute(previous.active, next.active)) return
        job?.cancel()
        triggers.tryReceive()
        job = scope.launch {
            while (true) {
                withTimeoutOrNull(BETTER_ROUTE_CHECK_INTERVAL_MS) { triggers.receive() }
                check()
            }
        }
    }

    /** Checks soon. A network change ([networkChanged]) also forgets silent-route backoff: those routes may answer now. */
    fun requestCheck(networkChanged: Boolean) {
        if (networkChanged) silent.clear()
        if (job?.isActive == true) triggers.trySend(Unit)
    }

    /** The switch to [route] did not end on it; do not offer it again for [BETTER_ROUTE_COOLDOWN_MS]. */
    fun switchFailed(environmentId: String, route: EnvironmentRoute) {
        cooldowns[key(environmentId, route)] = now() + BETTER_ROUTE_COOLDOWN_MS
    }

    private suspend fun check() {
        val target = current ?: return
        val start = now()
        cooldowns.values.removeAll { it <= start }
        val due = betterRoutes(target.plan, target.active).filter { route ->
            val key = key(target.environmentId, route)
            (silent[key]?.until ?: 0) <= start && (cooldowns[key] ?: 0) <= start
        }
        if (due.isEmpty()) return
        val answered = coroutineScope {
            due.map { route ->
                async {
                    val timeout = preflightTimeoutMillis(route.kind)
                    try { withTimeoutOrNull(timeout + 1_000) { preflight(target.environmentId, route, target.allowInsecureHttp, timeout) } == true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                }
            }.awaitAll()
        }
        val finished = now()
        due.forEachIndexed { index, route ->
            val key = key(target.environmentId, route)
            if (answered[index]) silent.remove(key)
            else {
                val failures = (silent[key]?.failures ?: 0) + 1
                silent[key] = Silent(failures, finished + silentRouteBackoffMillis(failures))
            }
        }
        val best = due.indices.firstOrNull { answered[it] }?.let(due::get) ?: return
        // The connection may have changed during the preflight; a later tick re-evaluates.
        val live = current ?: return
        if (live.environmentId != target.environmentId || !sameRoute(live.active, target.active) || live.plan.none { sameRoute(it, best) }) return
        if (!canSwitch()) return
        onBetterRoute(live, best)
    }
}

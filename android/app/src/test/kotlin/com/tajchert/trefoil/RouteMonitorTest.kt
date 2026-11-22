package com.tajchert.trefoil

import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.model.RouteKind.*
import com.tajchert.trefoil.core.model.RouteSource.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteMonitorTest {
    private val lan = EnvironmentRoute(Lan, "https://192.168.1.5/", Learned)
    private val tailnet = EnvironmentRoute(Tailnet, "https://mac.tail1234.ts.net/", Learned)
    private val public = EnvironmentRoute(Public, "https://mac.example/", Paired)
    private val tunnel = EnvironmentRoute(Tunnel, "https://prod-abc.t3coderelay.com/", Learned)
    private val ssh = EnvironmentRoute(Ssh, "", Paired)

    private class Recorder {
        val asked = mutableListOf<Pair<Long, EnvironmentRoute>>()
        val timeouts = mutableMapOf<RouteKind, Long>()
        val offered = mutableListOf<Pair<Long, EnvironmentRoute>>()
    }

    private fun TestScope.monitor(recorder: Recorder, canSwitch: () -> Boolean = { true }, answers: suspend (EnvironmentRoute) -> Boolean) =
        RouteMonitor(backgroundScope,
            preflight = { id, route, _, timeout -> assertEquals("env", id); recorder.asked += currentTime to route; recorder.timeouts[route.kind] = timeout; answers(route) },
            canSwitch = canSwitch,
            onBetterRoute = { _, route -> recorder.offered += currentTime to route },
            now = { currentTime },
        )

    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test fun plainCellularLeavesOutPrivateAndCarrierAddressesOnly() {
        val lanTen = EnvironmentRoute(Lan, "https://10.1.2.3:3773/", Learned)
        val emulatorHost = EnvironmentRoute(Lan, "http://10.0.2.2:3773/", Paired)
        val loopback = EnvironmentRoute(Lan, "http://127.0.0.1:3773/", Paired)
        val tailnetIp = EnvironmentRoute(Tailnet, "https://100.101.102.103/", Learned)
        val plan = listOf(lan, lanTen, emulatorHost, loopback, tailnetIp, tailnet, public, ssh, tunnel)
        assertEquals(listOf(emulatorHost, loopback, tailnet, public, ssh, tunnel), usableRoutes(plan, cellularOnly = true))
        assertEquals("Wi-Fi, VPN or another local transport keeps every route", plan, usableRoutes(plan, cellularOnly = false))
        assertEquals("An unknown network keeps every route", plan, usableRoutes(plan, cellularOnly = null))
        assertEquals("Never an empty plan", listOf(lan, tailnetIp), usableRoutes(listOf(lan, tailnetIp), cellularOnly = true))
        // Only a literal address is left out: a host name stays, even as a LAN route.
        val namedLan = EnvironmentRoute(Lan, "https://mac.local/", Learned)
        assertEquals(listOf(namedLan, public), usableRoutes(listOf(lan, namedLan, public), cellularOnly = true))
        val outsideCgnat = EnvironmentRoute(Tailnet, "https://100.128.0.1/", Learned)
        assertEquals("Outside 100.64/10 stays", listOf(outsideCgnat, public), usableRoutes(listOf(outsideCgnat, public), cellularOnly = true))
    }

    @Test fun checksEveryMinuteAndOffersTheBestAnsweringRoute() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { it != lan }
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, tailnet, tunnel), false))
        advance(59_999)
        assertTrue(recorder.asked.isEmpty())
        advance(1)
        assertEquals(setOf(lan, tailnet), recorder.asked.map { it.second }.toSet())
        assertEquals(listOf(60_000L to tailnet), recorder.offered)
        assertEquals(3_000L, recorder.timeouts[Lan])
    }

    @Test fun nothingIsWatchedOnTheBestRouteOrAfterStop() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { true }
        monitor.watch(RouteWatch("env", lan, listOf(lan, tunnel), false))
        assertNull(monitor.watching)
        advance(120_000)
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, tunnel), false))
        monitor.watch(null)
        advance(600_000)
        assertTrue(recorder.asked.isEmpty())
    }

    @Test fun sshIsNeverPreflightedAndOnlyRoutesAboveTheActiveOneAre() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { false }
        monitor.watch(RouteWatch("env", tunnel.copy(baseUrl = "https://PROD-abc.t3coderelay.com"), listOf(lan, ssh, tunnel), false))
        advance(60_000)
        assertEquals(listOf(lan), recorder.asked.map { it.second })
        assertEquals(listOf(lan), betterRoutes(listOf(lan, ssh, tunnel), EnvironmentRoute(Ssh, "http://127.0.0.1:41000/", Paired)))
        // An active route missing from the plan is ranked by kind.
        assertEquals(listOf(lan, tailnet), betterRoutes(listOf(lan, tailnet, tunnel), EnvironmentRoute(Public, "https://elsewhere.example/", Paired)))
    }

    @Test fun idleGateSkipsTheSwitchAndTheNextTickRetries() = runTest {
        val recorder = Recorder()
        var idle = false
        val monitor = monitor(recorder, canSwitch = { idle }) { true }
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, tunnel), false))
        advance(60_000)
        assertEquals(1, recorder.asked.size)
        assertTrue(recorder.offered.isEmpty())
        idle = true
        advance(60_000)
        assertEquals(listOf(120_000L to lan), recorder.offered)
    }

    @Test fun silentRouteBacksOffExponentiallyUpToTenMinutes() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { false }
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, tunnel), false))
        advance(2_400_000)
        // 60 s first check, then 120 s, 240 s, 480 s, and the 10 min cap.
        assertEquals(listOf(60L, 180L, 420L, 900L, 1_500L, 2_100L), recorder.asked.map { it.first / 1_000 })
        assertEquals(120_000L, silentRouteBackoffMillis(1))
        assertEquals(480_000L, silentRouteBackoffMillis(3))
        assertEquals(SILENT_ROUTE_BACKOFF_MAX_MS, silentRouteBackoffMillis(4))
        assertEquals(SILENT_ROUTE_BACKOFF_MAX_MS, silentRouteBackoffMillis(40))
        assertTrue(recorder.offered.isEmpty())
    }

    @Test fun networkChangeChecksAtOnceAndForgetsSilentBackoff() = runTest {
        val recorder = Recorder()
        var answers = false
        val monitor = monitor(recorder) { answers }
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, tunnel), false))
        advance(60_000)
        advance(30_000)
        monitor.requestCheck(networkChanged = false)
        runCurrent()
        // Still backing off after a plain request.
        assertEquals(1, recorder.asked.size)
        answers = true
        monitor.requestCheck(networkChanged = true)
        runCurrent()
        assertEquals(listOf(60_000L, 90_000L), recorder.asked.map { it.first })
        assertEquals(listOf(90_000L to lan), recorder.offered)
    }

    @Test fun failedSwitchPutsTheRouteOnFiveMinuteCooldown() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { true }
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, tunnel), false))
        advance(60_000)
        assertEquals(listOf(60_000L to lan), recorder.offered)
        monitor.switchFailed("env", lan)
        advance(299_999)
        assertEquals(1, recorder.asked.size)
        advance(1)
        assertEquals(listOf(60_000L, 360_000L), recorder.asked.map { it.first })
        assertEquals(listOf(60_000L to lan, 360_000L to lan), recorder.offered)
    }

    @Test fun hangingPreflightCountsAsSilentAndDoesNotHideAnAnsweringRoute() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { route -> if (route == lan) awaitCancellation() else true }
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, public, tunnel), false))
        advance(60_000)
        assertTrue(recorder.offered.isEmpty())
        advance(4_000)
        assertEquals(listOf(64_000L to public), recorder.offered)
        assertEquals(15_000L, recorder.timeouts[Public])
        // The silent LAN route backs off; public is asked again on the next tick.
        advance(60_000)
        assertEquals(listOf(lan, public, public), recorder.asked.map { it.second })
    }

    @Test fun learnedRoutesJoinThePlanWithoutRestartingTheInterval() = runTest {
        val recorder = Recorder()
        val monitor = monitor(recorder) { false }
        monitor.watch(RouteWatch("env", tunnel, listOf(public, tunnel), false))
        advance(30_000)
        monitor.watch(RouteWatch("env", tunnel, listOf(lan, public, tunnel), false))
        advance(30_000)
        assertEquals(setOf(lan, public), recorder.asked.map { it.second }.toSet())
        assertTrue(recorder.asked.all { it.first == 60_000L })
    }
}

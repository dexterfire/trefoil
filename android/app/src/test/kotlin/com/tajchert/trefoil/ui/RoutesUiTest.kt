package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesUiTest {
    private fun learned(kind: RouteKind, url: String) = EnvironmentRoute(kind, url, RouteSource.Learned)
    private val tunnel = learned(RouteKind.Tunnel, "https://prod-0123abcd.t3coderelay.com/")
    private val lanHttp = learned(RouteKind.Lan, "http://192.168.1.20:3773/")
    private val base = SavedEnvironment("e1", "Box", "https://box.example.test", "fake-token")

    @Test fun hostsDropSchemePathAndFragment() {
        assertEquals("box.example.test", routeHost("https://box.example.test/pair#token=secret"))
        assertEquals("192.168.1.20:3773", routeHost("http://192.168.1.20:3773/"))
        assertEquals("", routeHost("not a url"))
    }

    @Test fun learnedHttpLanRouteWaitsForConsentThenJoinsThePlan() {
        val env = base.copy(routes = listOf(lanHttp))
        val waiting = routeRows(env, null).single { it.route.kind == RouteKind.Lan }
        assertTrue(waiting.needsConsent)
        val allowed = routeRows(env.copy(allowInsecureHttp = true), null).single { it.route.kind == RouteKind.Lan }
        assertFalse(allowed.needsConsent)
        assertTrue(hasLearnedHttpRoute(env))
        assertFalse(hasLearnedHttpRoute(base))
    }

    @Test fun rowsFollowPreferenceOrderAndMarkTheActiveRoute() {
        val env = base.copy(routes = listOf(tunnel, lanHttp), allowInsecureHttp = true)
        val rows = routeRows(env, EnvironmentRoute(RouteKind.Tunnel, "https://prod-0123abcd.t3coderelay.com", RouteSource.Learned))
        assertEquals(listOf(RouteKind.Lan, RouteKind.Public, RouteKind.Tunnel), rows.map { it.route.kind })
        assertEquals(listOf(false, false, true), rows.map { it.active })
        assertTrue(routeRows(env, null).none { it.active })
    }

    @Test fun sshRowShowsTheSshHostNotTheEphemeralTunnelPort() {
        val ssh = SavedEnvironment("e2", "Build", "", "fake-token", ssh = SshProfile(host = "build.example.test", username = "dev", fingerprint = "f", keyType = "ssh-ed25519"), routes = listOf(tunnel))
        val rows = routeRows(ssh, EnvironmentRoute(RouteKind.Ssh, "http://127.0.0.1:41234/", RouteSource.Paired))
        assertEquals("build.example.test:22", rows.single { it.route.kind == RouteKind.Ssh }.host)
        assertTrue(rows.single { it.route.kind == RouteKind.Ssh }.active)
        assertFalse(rows.any { "41234" in it.host })
    }

    @Test fun tunnelHintOnlyWithoutATunnelRoute() {
        assertFalse(hasTunnelRoute(base))
        assertTrue(hasTunnelRoute(base.copy(routes = listOf(tunnel))))
        assertTrue(hasTunnelRoute(base.copy(baseUrl = "https://prod-0123abcd.t3coderelay.com", routes = listOf(tunnel))))
        assertNull(routeRows(base, null).firstOrNull { it.route.kind == RouteKind.Tunnel })
    }
}

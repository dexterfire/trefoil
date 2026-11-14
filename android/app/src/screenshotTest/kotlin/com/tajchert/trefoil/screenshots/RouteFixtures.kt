package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.EnvironmentRoute
import com.tajchert.trefoil.core.model.RouteKind
import com.tajchert.trefoil.core.model.RouteSource
import com.tajchert.trefoil.core.model.SavedEnvironment

/** Fake machines and connections for route previews. Hosts are reserved or invented; no real addresses. */
internal object RouteFixtures {
    private fun learned(kind: RouteKind, url: String) = EnvironmentRoute(kind, url, RouteSource.Learned)
    private val tunnel = learned(RouteKind.Tunnel, "https://prod-0123abcd.t3coderelay.com/")
    private val tailnet = learned(RouteKind.Tailnet, "https://workstation.tail0000.ts.net/")

    /** Paired on the local network; Tailscale and the tunnel were learned from the host. */
    val lanWithTunnel = SavedEnvironment(
        id = "env-workstation", name = "Workstation", baseUrl = "http://192.168.1.20:3773", accessToken = "fake-token",
        allowInsecureHttp = true, routes = listOf(tailnet, tunnel),
    )

    /** Same machine seen from away: the local address does not answer, the tunnel carries the connection. */
    val awayOnTunnel = lanWithTunnel.copy(baseUrl = "https://prod-0123abcd.t3coderelay.com", routes = listOf(learned(RouteKind.Lan, "http://192.168.1.20:3773/"), tunnel))

    val sshWithTunnel = ShellFixtures.buildServer.copy(routes = listOf(tunnel))

    /** HTTPS machine whose learned LAN address is plain HTTP and not yet allowed. */
    val lanNeedsConsent = ScreenshotFixtures.workstation.copy(routes = listOf(learned(RouteKind.Lan, "http://192.168.1.20:3773/")))

    /** Public HTTPS only: the host has not reported a tunnel. */
    val noTunnel = ScreenshotFixtures.laptop

    fun connected(environment: SavedEnvironment, route: EnvironmentRoute): ClientState =
        ScreenshotFixtures.connectedState().copy(environment = environment, activeRoute = route)

    fun paired(kind: RouteKind, url: String) = EnvironmentRoute(kind, url, RouteSource.Paired)
}

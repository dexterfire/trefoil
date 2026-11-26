package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.RouteKind
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.ui.MachinesScreen
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

/** Machines list with one saved machine whose route list is expanded. */
@Composable
private fun RouteMachine(environment: SavedEnvironment, state: ClientState, dark: Boolean = false) =
    TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
        Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
            MachinesScreen(state, listOf(environment), onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = {}, expandedRoutes = setOf(environment.id))
        }
            }

private val lanActive = RouteFixtures.connected(RouteFixtures.lanWithTunnel, RouteFixtures.paired(RouteKind.Lan, "http://192.168.1.20:3773"))
private val tunnelActive = RouteFixtures.connected(RouteFixtures.awayOnTunnel, RouteFixtures.paired(RouteKind.Tunnel, "https://prod-0123abcd.t3coderelay.com"))

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Routes_LanActiveTunnelLearned_Light() = RouteMachine(RouteFixtures.lanWithTunnel, lanActive)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Routes_TunnelAway_Light() = RouteMachine(RouteFixtures.awayOnTunnel, tunnelActive)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = NIGHT)
@Composable fun Routes_TunnelAway_Dark() = RouteMachine(RouteFixtures.awayOnTunnel, tunnelActive, dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Routes_SshWithTunnel_Light() =
    RouteMachine(RouteFixtures.sshWithTunnel, RouteFixtures.connected(RouteFixtures.sshWithTunnel, RouteFixtures.paired(RouteKind.Ssh, "http://127.0.0.1:41234/")))

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Routes_HttpLanNeedsConsent_Light() =
    RouteMachine(RouteFixtures.lanNeedsConsent, RouteFixtures.connected(RouteFixtures.lanNeedsConsent, RouteFixtures.paired(RouteKind.Public, "https://workstation.example.test")))

@PreviewTest @Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable fun Routes_NoTunnelHint_Light() =
    RouteMachine(RouteFixtures.noTunnel, RouteFixtures.connected(RouteFixtures.noTunnel, RouteFixtures.paired(RouteKind.Public, "https://laptop.example.test")))

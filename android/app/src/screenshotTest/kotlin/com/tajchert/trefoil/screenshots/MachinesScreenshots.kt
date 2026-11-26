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
import com.tajchert.trefoil.core.model.MachineProbe
import com.tajchert.trefoil.core.model.RouteKind
import com.tajchert.trefoil.core.model.SavedEnvironment
import com.tajchert.trefoil.ui.MachinesScreen
import com.tajchert.trefoil.ui.PairDialog
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

/** Connected to the workstation (three routes, local HTTP allowed), three more machines saved. */
private val connectedSaved: List<SavedEnvironment> = listOf(RouteFixtures.lanWithTunnel) + ShellFixtures.saved.drop(1)
private val connectedState: ClientState = ScreenshotFixtures.connectedState().copy(
    environment = RouteFixtures.lanWithTunnel, activeRoute = RouteFixtures.paired(RouteKind.Lan, "http://192.168.1.20:3773"), providers = UsageFixtures.providers,
)
private val chooserProbes = ShellFixtures.probes + (RouteFixtures.lanWithTunnel.id to MachineProbe.Online)

/** The screen alone, as the shell gives it room beside the rail: no back arrow unless [back]. */
@Composable
private fun MachinesShot(saved: List<SavedEnvironment>, state: ClientState, dark: Boolean = false, back: Boolean = false, probes: Map<String, MachineProbe> = emptyMap()) =
    TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
        Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
            MachinesScreen(state, saved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = if (back) ({}) else null, probes = probes, onOpenUsage = {})
        }
            }

@Composable
private fun PairShot(dark: Boolean = false, privateHttp: Boolean = true) =
    TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
        Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
            MachinesScreen(connectedState, connectedSaved, onConnect = {}, onForget = {}, onPair = {}, onSsh = {}, onDisconnect = {}, onBack = null, probes = chooserProbes, onOpenUsage = {})
            // Fake link: the pairing link is masked in the field and never leaves the preview.
            PairDialog(busy = false, dismiss = {}, initialLink = if (privateHttp) "http://192.168.1.20:3773/pair#token=fake-token" else "", pair = { _, _, _ -> })
        }
            }

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Machines_Connected_TabletLandscape() = MachinesShot(connectedSaved, connectedState, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Machines_Chooser_TabletLandscape() = MachinesShot(ShellFixtures.saved, ShellFixtures.nothingConnected(), probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable fun Machines_FirstRun_TabletLandscape() = MachinesShot(emptyList(), ShellFixtures.nothingConnected())

@PreviewTest @Preview(showBackground = true, widthDp = 720, heightDp = 1280)
@Composable fun Machines_Connected_TabletPortrait() = MachinesShot(connectedSaved, connectedState, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 720, heightDp = 1280)
@Composable fun Machines_Chooser_TabletPortrait() = MachinesShot(ShellFixtures.saved, ShellFixtures.nothingConnected(), probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 720, heightDp = 1280)
@Composable fun Machines_FirstRun_TabletPortrait() = MachinesShot(emptyList(), ShellFixtures.nothingConnected())

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Machines_Connected_FoldPortrait() = MachinesShot(connectedSaved, connectedState, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Machines_Chooser_FoldPortrait() = MachinesShot(ShellFixtures.saved, ShellFixtures.nothingConnected(), probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Machines_FirstRun_FoldPortrait() = MachinesShot(emptyList(), ShellFixtures.nothingConnected())

/** One saved machine on a medium container: a single card, never a lone half-width cell. */
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Machines_Single_FoldPortrait() = MachinesShot(connectedSaved.take(1), connectedState, probes = chooserProbes)

/** Connected with a back arrow: arrow and title share one 64dp row. */
@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Machines_ConnectedBack_FoldPortrait() = MachinesShot(connectedSaved, connectedState, back = true, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1840, heightDp = 1080)
@Composable fun Machines_Connected_Desktop() = MachinesShot(connectedSaved, connectedState, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1840, heightDp = 1080)
@Composable fun Machines_Chooser_Desktop() = MachinesShot(ShellFixtures.saved, ShellFixtures.nothingConnected(), probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1840, heightDp = 1080)
@Composable fun Machines_FirstRun_Desktop() = MachinesShot(emptyList(), ShellFixtures.nothingConnected())

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800, uiMode = NIGHT)
@Composable fun Machines_Connected_TabletLandscape_Dark() = MachinesShot(connectedSaved, connectedState, dark = true, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800, uiMode = NIGHT)
@Composable fun Machines_Chooser_TabletLandscape_Dark() = MachinesShot(ShellFixtures.saved, ShellFixtures.nothingConnected(), dark = true, probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1200, heightDp = 800, uiMode = NIGHT)
@Composable fun Machines_FirstRun_TabletLandscape_Dark() = MachinesShot(emptyList(), ShellFixtures.nothingConnected(), dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Machines_Chooser_Full() = MachinesShot(ShellFixtures.saved, ShellFixtures.nothingConnected(), probes = chooserProbes)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Machines_FirstRun_Full() = MachinesShot(emptyList(), ShellFixtures.nothingConnected())

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Machines_PairDialog_TabletLandscape() = PairShot()

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = NIGHT)
@Composable fun Machines_PairDialog_TabletLandscape_Dark() = PairShot(dark = true)

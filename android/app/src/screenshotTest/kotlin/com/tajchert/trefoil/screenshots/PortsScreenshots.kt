package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.ports.PortsScreen
import com.tajchert.trefoil.ui.ports.PortsUiState
import com.tajchert.trefoil.ui.ports.ThreadPortsChip
import com.tajchert.trefoil.ui.ports.TunnelBrowserLayout

@Composable
private fun PortsShot(state: PortsUiState, dark: Boolean = false) {
    TrefoilTheme(darkTheme = dark) { PortsScreen(state, PortsFixtures.actions) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Ports_Mixed_Light() { PortsShot(PortsFixtures.mixed) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Ports_Mixed_Dark() { PortsShot(PortsFixtures.mixed, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Ports_Empty_Light() { PortsShot(PortsFixtures.empty) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Ports_Empty_Dark() { PortsShot(PortsFixtures.empty, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Ports_Unsupported_Light() { PortsShot(PortsFixtures.unsupported) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Ports_Disabled_Light() { PortsShot(PortsFixtures.disabled) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Ports_Disabled_Dark() { PortsShot(PortsFixtures.disabled, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Ports_FailedForward_Light() { PortsShot(PortsFixtures.failed) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Ports_FailedForward_Dark() { PortsShot(PortsFixtures.failed, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Ports_Scanning_Light() { PortsShot(PortsFixtures.scanning) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 400)
@Composable
fun Ports_ReadOnly_Light() { PortsShot(PortsFixtures.readOnly) }

@Composable
private fun ChipShot(content: @Composable () -> Unit) {
    TrefoilTheme { Column(Modifier.background(Tf.colors.bg).padding(16.dp)) { content() } }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 80)
@Composable
fun ThreadPortsChip_Single_Light() { ChipShot { ThreadPortsChip(listOf(PortsFixtures.vite), onClick = {}) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 80)
@Composable
fun ThreadPortsChip_Multiple_Light() { ChipShot { ThreadPortsChip(listOf(PortsFixtures.vite, PortsFixtures.api), onClick = {}) } }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 80, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadPortsChip_Single_Dark() {
    TrefoilTheme(darkTheme = true) { Column(Modifier.background(Tf.colors.bg).padding(16.dp)) { ThreadPortsChip(listOf(PortsFixtures.vite), onClick = {}) } }
}

/** The WebView itself does not render under Layoutlib, so a placeholder stands in for the page. */
@Composable
private fun BrowserShot(error: String? = null, loading: Boolean = false, dark: Boolean = false) {
    TrefoilTheme(darkTheme = dark) {
        TunnelBrowserLayout(
            title = "vite", address = "http://localhost:5173/", canGoBack = true, canGoForward = false,
            loading = loading, progress = 60, error = error,
            onClose = {}, onPageBack = {}, onPageForward = {}, onReload = {}, onStop = {}, onOpenExternal = {},
        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Page", color = Tf.colors.muted) } }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun TunnelBrowser_Loaded_Light() { BrowserShot() }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun TunnelBrowser_Error_Light() { BrowserShot(error = "net::ERR_CONNECTION_RESET") }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TunnelBrowser_Error_Dark() { BrowserShot(error = "net::ERR_CONNECTION_RESET", dark = true) }

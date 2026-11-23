package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ScreenAccess
import com.tajchert.trefoil.core.model.ScreenCatalog
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.LocalFold
import com.tajchert.trefoil.ui.screens.ScreenSessionsScreen
import com.tajchert.trefoil.ui.screens.ScreenViewerState
import com.tajchert.trefoil.ui.screens.StreamController
import com.tajchert.trefoil.ui.screens.StreamPhase
import com.tajchert.trefoil.ui.screens.StreamViewerStatus

@Composable
private fun SessionsShot(catalog: ScreenCatalog, dark: Boolean = false) {
    val state: ClientState = ScreenshotFixtures.connectedState().copy(selectedThreadId = "t-approval", screens = catalog)
    TrefoilTheme(darkTheme = dark) {
        ScreenSessionsScreen(
            state, ScreenViewerState(), fullscreen = false, onFullscreen = {}, onRefresh = {},
            onOpen = {}, onClose = {}, onLeave = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ScreenSessions_List_Light() { SessionsShot(UsageFixtures.screenCatalog) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ScreenSessions_List_Dark() { SessionsShot(UsageFixtures.screenCatalog, dark = true) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ScreenSessions_Empty() { SessionsShot(ScreenCatalog(loaded = true)) }

// Tablets and foldables. A fake stream stands in for the WebView and every value is fixture data.

private val fakeStream: @Composable (StreamController, (StreamViewerStatus) -> Unit) -> Unit = { _, report ->
    LaunchedEffect(Unit) { report(StreamViewerStatus(StreamPhase.Streaming, inputConnected = true, canOperate = true, controller = "you")) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B3A57), Color(0xFF0B1620)))), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxHeight(.8f).aspectRatio(9f / 19.5f).background(Color(0xFF263B4F), RoundedCornerShape(24.dp)))
    }
}

private val shotTarget = UsageFixtures.screenCatalog.devices.first()

@Composable
private fun AdaptiveSessionsShot(open: Boolean, dark: Boolean = false, fullscreen: Boolean = false, fold: Fold? = null, compactHeight: Boolean = false) {
    val state: ClientState = ScreenshotFixtures.connectedState().copy(selectedThreadId = "t-approval", screens = UsageFixtures.screenCatalog)
    val viewer = if (!open) ScreenViewerState() else ScreenViewerState(
        ScreenshotFixtures.workstation.id, shotTarget,
        ScreenAccess("fixture", 1, shotTarget, "https://fixture.invalid/api/device-hub", "wss://fixture.invalid/api/device-hub", mapOf("wsTicket" to "fixture-media", "hostId" to "host-1"), interactive = true),
    )
    TrefoilTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalFold provides fold, LocalCompactHeight provides compactHeight) {
            ScreenSessionsScreen(
                state, viewer, fullscreen = fullscreen, onFullscreen = {}, onRefresh = {}, onOpen = {}, onClose = {}, onLeave = null,
                isAccessCurrent = { true }, viewerContent = fakeStream,
            )
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable
fun Screens_ListDetailOpen_Tablet1200x800_Light() = AdaptiveSessionsShot(open = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 1200, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Screens_ListDetailOpen_Tablet1200x800_Dark() = AdaptiveSessionsShot(open = true, dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 1200, heightDp = 800)
@Composable
fun Screens_ListDetailEmpty_Tablet1200x800_Light() = AdaptiveSessionsShot(open = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable
fun Screens_SinglePane_Tablet800x1280_Light() = AdaptiveSessionsShot(open = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable
fun Screens_Tabletop_Fold841x673_Light() = AdaptiveSessionsShot(open = true, fold = Fold(FoldOrientation.Horizontal, 326f, 346f, separating = true, halfOpened = true))

@PreviewTest
@Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable
fun Screens_FullscreenFold_Fold673x841_Light() = AdaptiveSessionsShot(
    open = true, fullscreen = true, fold = Fold(FoldOrientation.Vertical, 326f, 346f, separating = true, halfOpened = true),
)

@PreviewTest
@Preview(showBackground = true, widthDp = 880, heightDp = 400)
@Composable
fun Screens_CompactHeight_Landscape880x400_Light() = AdaptiveSessionsShot(open = true, compactHeight = true)

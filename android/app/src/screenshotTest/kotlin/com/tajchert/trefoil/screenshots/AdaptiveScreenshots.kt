package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.ui.adaptive.TfTwoPane
import com.tajchert.trefoil.ui.adaptive.rememberTwoPaneState

private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun Pane(label: String, color: (com.tajchert.trefoil.ui.TrefoilColors) -> Color) =
    Box(Modifier.fillMaxSize().background(color(Tf.colors)).padding(16.dp)) { Text(label, style = TrefoilType.meta, color = Tf.colors.text) }

@Composable
private fun TwoPanes(width: Dp = 340.dp, dark: Boolean = false, locked: Boolean = false, hinge: Dp = 0.dp) = TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
    // Rail colour behind the panes so the empty hinge band is visible in the locked preview.
    Box(Modifier.fillMaxSize().background(Tf.colors.rail)) {
        TfTwoPane(rememberTwoPaneState(width), defaultStartWidth = 340.dp, start = { Pane("List") { it.bg } }, end = { Pane("Conversation") { it.s1 } },
            modifier = Modifier.fillMaxSize(), locked = locked, hingeWidth = hinge)
    }
        }

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun Adaptive_TwoPane_Default_FoldLandscape() = TwoPanes()

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Adaptive_TwoPane_DraggedWide_TabletLandscape() = TwoPanes(width = 480.dp)

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673)
@Composable fun Adaptive_TwoPane_LockedHinge_FoldLandscape() = TwoPanes(width = 410.dp, locked = true, hinge = 20.dp)

@PreviewTest @Preview(showBackground = true, widthDp = 841, heightDp = 673, uiMode = NIGHT)
@Composable fun Adaptive_TwoPane_Default_FoldLandscape_Dark() = TwoPanes(dark = true)

package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.shell.KeyboardShortcutsContent

/**
 * The keyboard-shortcuts cheat sheet on tablets and foldables. Dialog windows are not captured, so the body is drawn inside a
 * hand-built frame (scrim plus a 28dp-radius surface at the dialog's size). Names: `Dialog_Shortcuts_<FormFactor>`.
 */
private const val DARK = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun ShortcutsDialog(width: Dp, height: Dp, dark: Boolean) = TrefoilTheme(darkTheme = dark) {
    Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
        Box(Modifier.fillMaxSize().background(Tf.colors.scrim), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.width(width).height(height),
                shape = RoundedCornerShape(28.dp), color = Tf.colors.sheet, contentColor = Tf.colors.text, tonalElevation = 0.dp,
            ) { Column { KeyboardShortcutsContent(onClose = {}) } }
        }
    }
}

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Dialog_Shortcuts_TabletLandscape() = ShortcutsDialog(720.dp, 640.dp, dark = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = DARK)
@Composable fun Dialog_Shortcuts_TabletLandscape_Dark() = ShortcutsDialog(720.dp, 640.dp, dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Dialog_Shortcuts_FoldPortrait() = ShortcutsDialog(560.dp, 780.dp, dark = false)

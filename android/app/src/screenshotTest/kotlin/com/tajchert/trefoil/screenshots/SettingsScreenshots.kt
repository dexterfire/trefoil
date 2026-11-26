package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.AppPreferences
import com.tajchert.trefoil.ui.SettingsScreen
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.theme.ThemeFamily

/** Default settings: the list-order bands, the six theme families, brightness and the width slider. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880)
@Composable
fun Settings_Default_Light() {
    TrefoilTheme(darkTheme = false) { SettingsScreen(AppPreferences(), 400f, onChange = {}, onBack = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Settings_Default_Dark() {
    TrefoilTheme(darkTheme = true) { SettingsScreen(AppPreferences(), 400f, onChange = {}, onBack = {}) }
}

/** A non-default family (Teal) with a forced 1280 dp width: the slider shows the override. */
private val overridden = AppPreferences(themeFamily = ThemeFamily.Teal, forcedWidthDp = 1280f)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Settings_WidthOverride_Dark() {
    TrefoilTheme(darkTheme = true, family = ThemeFamily.Teal) { Box(Modifier.fillMaxSize()) { SettingsScreen(overridden, 1280f, onChange = {}, onBack = {}) } }
}

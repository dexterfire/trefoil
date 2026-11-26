package com.tajchert.trefoil.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.ui.ProviderMark
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme

// Every T3 Code provider driver with a bundled mark, plus an unknown driver for the fallback.
private val drivers = listOf("codex", "claudeAgent", "cursor", "grok", "opencode", "antigravity", "fx", "kiro", "muse", "pi", "acpRegistry", "unknown")

@Composable
private fun Marks(dark: Boolean) {
    TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
        Column(Modifier.background(Tf.colors.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            drivers.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { ProviderMark(it, it, Modifier.size(24.dp)) }
                }
            }
        }
            }
}

@PreviewTest @Preview(name = "provider marks light") @Composable fun ProviderMarksLight() = Marks(dark = false)

@PreviewTest @Preview(name = "provider marks dark") @Composable fun ProviderMarksDark() = Marks(dark = true)

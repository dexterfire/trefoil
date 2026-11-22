/* Provider marks and account stack adapted from T3 Code mobile (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.ProviderInstance

/** Single-color provider mark meant to be tinted, or null for unknown drivers. Widgets use this too. */
@DrawableRes
internal fun providerMonoIcon(driver: String): Int? = when (driver) {
    "codex" -> R.drawable.ic_provider_codex
    "claudeAgent" -> R.drawable.ic_provider_claude
    "cursor" -> R.drawable.ic_provider_cursor
    "grok" -> R.drawable.ic_provider_grok
    "opencode" -> R.drawable.ic_provider_opencode
    "antigravity" -> R.drawable.ic_provider_antigravity
    "fx" -> R.drawable.ic_provider_fx
    "kiro" -> R.drawable.ic_provider_kiro
    "muse" -> R.drawable.ic_provider_muse
    "pi" -> R.drawable.ic_provider_pi
    "acpRegistry" -> R.drawable.ic_provider_acp_registry
    else -> null
}

/** Marks drawn in their own brand colors (as T3 Code does), never tinted. */
@DrawableRes
private fun providerColorIcon(driver: String): Int? = when (driver) {
    "antigravity" -> R.drawable.ic_provider_antigravity
    "kiro" -> R.drawable.ic_provider_kiro_color
    "muse" -> R.drawable.ic_provider_muse_color
    else -> null
}

@Composable
internal fun ProviderMark(driver: String, description: String?, modifier: Modifier = Modifier) {
    val color = providerColorIcon(driver)
    val mono = providerMonoIcon(driver)
    when {
        color != null -> Image(painterResource(color), description, modifier)
        mono != null -> Icon(painterResource(mono), description, modifier,
            tint = if (driver == "claudeAgent") Color(0xFFD97757) else MaterialTheme.colorScheme.onSurface)
        else -> Icon(Icons.Default.SmartToy, description, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ThreadProviderStack(stack: List<ProviderInstance>, catalog: List<ProviderInstance>) {
    if (stack.isEmpty()) return
    val current = stack.last()
    val displayName = providerDisplayName(current)
    val accent = current.accentColor?.trim()?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.let { Color(android.graphics.Color.parseColor(it)) }
    val showBadge = accent != null || catalog.count { it.driver == current.driver && (!current.enabled || it.enabled) } > 1
    Row(Modifier.semantics { contentDescription = "Agent: $displayName" }, verticalAlignment = Alignment.CenterVertically) {
        stack.dropLast(1).forEach { provider -> ProviderMark(provider.driver, null, Modifier.size(14.dp).alpha(.3f)) }
        // Fixed slot so the current agent mark lands in the same spot on every row, badge or not.
        Box(Modifier.size(24.dp)) {
            ProviderMark(current.driver, null, Modifier.size(20.dp).alpha(.85f).align(Alignment.Center))
            if (showBadge) {
                val background = accent ?: MaterialTheme.colorScheme.surfaceContainerHigh
                val foreground = if (background.luminance() > .179f) Color.Black else Color.White
                Text(providerInitials(displayName), Modifier.align(Alignment.BottomEnd).background(background, CircleShape).padding(horizontal = 2.dp),
                    fontSize = 7.sp, lineHeight = 9.sp, color = foreground)
            }
        }
    }
}

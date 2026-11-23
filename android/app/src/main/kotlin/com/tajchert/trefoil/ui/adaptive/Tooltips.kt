package com.tajchert.trefoil.ui.adaptive

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 plain tooltip above exactly one control, shown on hover, keyboard focus and long-press. Keep the control's own
 * content description: the tooltip never replaces it. Skip it where long-press already drags, selects or opens a menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TfTooltip(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(positioning = TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(), modifier = modifier, content = content,
    )
}

/**
 * Icon-only button with [label] as content description and tooltip ([tooltip] overrides the tooltip text, e.g. to add a shortcut).
 * Renders exactly like `IconButton(onClick, modifier) { Icon(icon, label, Modifier.size(iconSize), tint = tint) }`, so swapping a
 * plain IconButton for it does not move pixels; pass the same `Modifier.size(…)` the old call used. [tint] unspecified keeps the
 * button's content colour (dimmed when disabled).
 */
@Composable
internal fun TfIconButton(
    icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Color.Unspecified,
    iconSize: Dp = 24.dp, tooltip: String = label, colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
) {
    TfTooltip(tooltip) {
        IconButton(onClick = onClick, modifier = modifier.handPointer(enabled), enabled = enabled, colors = colors) {
            Icon(icon, label, Modifier.size(iconSize), tint = if (tint.isSpecified) tint else LocalContentColor.current)
        }
    }
}

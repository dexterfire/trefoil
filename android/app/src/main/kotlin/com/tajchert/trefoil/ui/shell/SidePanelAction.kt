package com.tajchert.trefoil.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** The one action a tab shows in the docked side panel's tab bar, left of Close: what its own header used to carry. */
class PanelAction(val icon: ImageVector, val description: String, val enabled: Boolean, val onClick: () -> Unit)

/**
 * Where a screen hosted in the docked side panel hands its single header action to the panel's tab bar. The screen keeps the click
 * handler, the enabled rule and the accessible name; the panel only draws the button. Null while the tab shows no such action.
 */
@Stable
class SidePanelHost {
    var action by mutableStateOf<PanelAction?>(null)
}

/**
 * Offers [onClick] to the panel's tab bar while [host] is set and [active]. It leaves when this leaves composition, the tab changes
 * or [active] turns false; a newer offer from another screen is never removed by an older one going away.
 */
@Composable
fun PanelActionEffect(host: SidePanelHost?, active: Boolean, icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    if (host == null || !active) return
    val latest by rememberUpdatedState(onClick)
    DisposableEffect(host, icon, description, enabled) {
        val mine = PanelAction(icon, description, enabled) { latest() }
        host.action = mine
        onDispose { if (host.action === mine) host.action = null }
    }
}

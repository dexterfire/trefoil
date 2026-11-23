package com.tajchert.trefoil.ui.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.TfTwoPane
import com.tajchert.trefoil.ui.adaptive.TwoPaneState
import com.tajchert.trefoil.ui.shell.PanelActionEffect
import com.tajchert.trefoil.ui.shell.SidePanelHost

/**
 * The dev servers surface: the list of servers on the connected machine and, once the user opens one, the built-in browser.
 * Every width decision comes from the pane's own width. Below 840dp it is today's either/or (the list, or the browser in its
 * place); from 840dp the list sits on the left (360dp, locked divider) and the browser, or an empty state, on the right.
 *
 * [hosted] means the host supplies navigation (a rail or the side panel's tab row): no back arrow, the list does not take Back,
 * and in a narrow pane no second title. Back still ends an open page first. [onOpenExternal] opens the page's forward in the
 * device browser; [browserActions] is drawn in the browser toolbar just before "open externally".
 * [panel] is set while the surface is the docked side panel's Preview tab: the list has no title row of its own (the tab names it) and
 * its "Forward a port" action is drawn in the panel's tab bar; an open page gets one 48dp toolbar that starts with a back-to-list arrow.
 */
@Composable
fun DevServersPane(
    state: PortsUiState,
    actions: PortsActions,
    browser: PortBrowserState?,
    onCloseBrowser: () -> Unit,
    onOpenExternal: () -> Unit,
    hosted: Boolean = false,
    modifier: Modifier = Modifier,
    browserActions: (@Composable RowScope.() -> Unit)? = null,
    panel: SidePanelHost? = null,
) {
    DevServersPaneLayout(state, actions, browser, hosted, modifier, panel) { url, title, singleToolbar ->
        TunnelBrowserScreen(url, title, onCloseBrowser, onOpenExternal, Modifier.fillMaxSize(), singleToolbar, browserActions, panelToolbar = panel != null)
    }
}

/** What the browser slot is drawn with; replaced as a whole whenever the layout or the page changes. */
private class BrowserSlot(val url: String, val title: String, val singleToolbar: Boolean)

/**
 * [DevServersPane] with the page itself supplied by [browserContent] (url, title, whether the pane is wide enough for the single
 * toolbar). Previews pass a fake page because a WebView does not render under Layoutlib.
 */
@Composable
internal fun DevServersPaneLayout(
    state: PortsUiState,
    actions: PortsActions,
    browser: PortBrowserState?,
    hosted: Boolean,
    modifier: Modifier = Modifier,
    panel: SidePanelHost? = null,
    browserContent: @Composable (url: String, title: String, singleToolbar: Boolean) -> Unit,
) {
    val c = Tf.colors
    var manualDialog by rememberSaveable { mutableStateOf(false) }
    val content by rememberUpdatedState(browserContent)
    // One browser for both layouts: crossing 840dp moves it, so the page keeps loading state and history. Another forward is another page.
    val slot = remember { movableContentOf { s: BrowserSlot -> key(s.url) { content(s.url, s.title, s.singleToolbar) } } }
    BoxWithConstraints(modifier.fillMaxSize().background(c.bg)) {
        val wide = maxWidth >= AdaptiveSpec.ExpandedMinWidth.dp
        if (!wide) {
            if (browser != null) slot(BrowserSlot(browser.url, browser.title, singleToolbar = false))
            else {
                // In the docked panel the tab bar carries the action, with the same rule the list's own button had.
                PanelActionEffect(panel, state.supported, TfIcons.Plus, stringResource(R.string.ports_forward_port_action), state.canForward) { manualDialog = true }
                PortsList(state, actions, hosted = hosted, showTitle = !hosted || maxWidth >= AdaptiveSpec.MediumMinWidth.dp, embedded = panel != null, onManual = { manualDialog = true })
            }
        } else {
            val twoPane = remember { TwoPaneState(ListWidth) }
            TfTwoPane(
                state = twoPane, defaultStartWidth = ListWidth, minStartWidth = ListWidth, maxStartWidth = ListWidth, collapseBelow = null, locked = true,
                start = { PortsList(state, actions, hosted = hosted, selectedPort = browser?.remotePort, takesBack = browser == null, onManual = { manualDialog = true }) },
                end = {
                    if (browser != null) slot(BrowserSlot(browser.url, browser.title, singleToolbar = true))
                    else NothingOpen(state) { manualDialog = true }
                },
            )
        }
    }
    if (manualDialog) ForwardPortDialog(onDismiss = { manualDialog = false }, onConfirm = { port -> manualDialog = false; actions.onForwardManual(port) })
}

private val ListWidth: Dp = 360.dp

/** The right-hand pane while no page is open: one line and the existing "forward a port" action, with its existing enabled rule. */
@Composable
private fun NothingOpen(state: PortsUiState, onManual: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Tf.colors.bg), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 400.dp)) {
            PortsEmptyState(TfIcons.Globe, R.string.ports_detail_empty, body = null) {
                // An empty list already offers the action in its own pane.
                if (state.supported && portRows(state).isNotEmpty()) OutlinedButton(onClick = onManual, enabled = state.canForward) { Text(stringResource(R.string.ports_forward_port_action)) }
            }
        }
    }
}

package com.tajchert.trefoil.ui.ports

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.PortForward
import com.tajchert.trefoil.core.model.PortForwardError
import com.tajchert.trefoil.core.model.PortForwardStatus
import com.tajchert.trefoil.ui.SectionLabel
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilType

/**
 * Full-pane list of the dev servers T3 found on the connected machine, with forward state and the Open,
 * Open in browser and Stop forward actions. Stateless apart from the manual-port dialog.
 */
@Composable
fun PortsScreen(state: PortsUiState, actions: PortsActions, modifier: Modifier = Modifier) {
    val c = Tf.colors
    var manualDialog by rememberSaveable { mutableStateOf(false) }
    BackHandler(onBack = actions.onBack)
    Column(modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(TfIcons.Back, stringResource(R.string.ports_back), Modifier.size(22.dp), tint = c.text) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stringResource(R.string.ports_title), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(if (state.live || !state.supported) R.string.ports_subtitle_live else R.string.ports_subtitle_scanning),
                    style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.supported) IconButton(onClick = { manualDialog = true }, enabled = state.canForward) {
                Icon(TfIcons.Plus, stringResource(R.string.ports_forward_port_action), Modifier.size(22.dp), tint = if (state.canForward) c.text else c.muted)
            }
        }
        HorizontalDivider(color = c.line)
        Box(Modifier.weight(1f).fillMaxWidth()) { PortsBody(state, actions) { manualDialog = true } }
    }
    if (manualDialog) ForwardPortDialog(onDismiss = { manualDialog = false }, onConfirm = { port -> manualDialog = false; actions.onForwardManual(port) })
}

@Composable
private fun PortsBody(state: PortsUiState, actions: PortsActions, onManual: () -> Unit) {
    val rows = portRows(state)
    when {
        !state.supported -> PortsEmptyState(TfIcons.Alert, R.string.ports_unsupported_title, R.string.ports_unsupported_body)
        rows.isEmpty() && state.tunnelsDisabled -> PortsEmptyState(TfIcons.Lock, R.string.ports_disabled_title, R.string.ports_disabled_body)
        rows.isEmpty() -> PortsEmptyState(
            icon = TfIcons.Globe,
            title = if (state.live) R.string.ports_empty_title else R.string.ports_scanning_title,
            body = if (state.live) R.string.ports_empty_body else null,
            loading = !state.live,
        ) { if (state.canForward) OutlinedButton(onClick = onManual) { Text(stringResource(R.string.ports_forward_port_action)) } }
        else -> {
            val mine = rows.filter { it.inSelectedThread }
            val others = rows.filterNot { it.inSelectedThread }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.tunnelsDisabled) item(key = "notice:disabled") { Notice(R.string.ports_disabled_body) }
                else if (!state.canWrite) item(key = "notice:read-only") { Notice(R.string.ports_read_only) }
                if (mine.isNotEmpty()) {
                    item(key = "section:thread") { SectionLabel(stringResource(R.string.ports_section_this_thread), Modifier.padding(start = 4.dp, top = 4.dp)) }
                    items(mine, key = { "port:${it.port}" }) { PortRowCard(it, state, actions) }
                }
                if (others.isNotEmpty()) {
                    if (mine.isNotEmpty()) item(key = "section:other") { SectionLabel(stringResource(R.string.ports_section_other), Modifier.padding(start = 4.dp, top = 12.dp)) }
                    items(others, key = { "port:${it.port}" }) { PortRowCard(it, state, actions) }
                }
                if (state.canForward) item(key = "manual") {
                    OutlinedButton(onClick = onManual, Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Icon(TfIcons.Plus, null, Modifier.size(16.dp))
                        Text(stringResource(R.string.ports_forward_port_action), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(@StringRes message: Int) {
    val c = Tf.colors
    Surface(shape = RoundedCornerShape(12.dp), color = c.amberSoft, contentColor = c.amber) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(TfIcons.Alert, null, Modifier.size(18.dp))
            Text(stringResource(message), style = TrefoilType.meta, color = c.amber)
        }
    }
}

@Composable
private fun PortsEmptyState(
    icon: ImageVector,
    @StringRes title: Int,
    @StringRes body: Int?,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Tf.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp).background(c.s2, CircleShape), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = c.muted, strokeWidth = 2.dp)
            else Icon(icon, null, Modifier.size(26.dp), tint = c.muted)
        }
        Text(stringResource(title), Modifier.padding(top = 16.dp), style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
        if (body != null) Text(stringResource(body), Modifier.padding(top = 8.dp), style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 20.dp)) { action() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PortRowCard(row: PortRow, state: PortsUiState, actions: PortsActions) {
    val c = Tf.colors
    val forward = row.forward
    Surface(shape = RoundedCornerShape(16.dp), color = c.s1, border = BorderStroke(1.dp, c.line)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.ports_port, row.port), style = TrefoilType.rowTitle.copy(fontFamily = TrefoilType.mono.fontFamily), color = c.text, maxLines = 1)
                Text(
                    row.server?.processName?.takeIf { it.isNotBlank() }
                        ?: stringResource(if (row.server == null) R.string.ports_manual_process else R.string.ports_unknown_process),
                    Modifier.weight(1f), style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            ownerLabel(row, state)?.let { Text(it, style = TrefoilType.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            ForwardStateLine(forward)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilledTonalButton(onClick = { actions.onOpenInApp(row.port) }, enabled = state.canForward, contentPadding = BUTTON_PADDING) {
                    Text(stringResource(R.string.ports_action_open))
                }
                OutlinedButton(onClick = { actions.onOpenExternal(row.port) }, enabled = state.canForward, contentPadding = BUTTON_PADDING) {
                    Text(stringResource(R.string.ports_action_open_browser))
                }
                if (forward != null) TextButton(onClick = { actions.onStopForward(row.port) }, contentPadding = BUTTON_PADDING) {
                    Text(stringResource(R.string.ports_action_stop))
                }
            }
        }
    }
}

private val BUTTON_PADDING = PaddingValues(horizontal = 14.dp, vertical = 0.dp)

/** "Agent · title" or "Terminal · title"; the bare kind when the thread title is unknown; null for no owner. */
@Composable
private fun ownerLabel(row: PortRow, state: PortsUiState): String? {
    val title = row.ownerThreadId?.let { state.threadTitles[it] }?.takeIf { it.isNotBlank() }
    return when (row.ownerKind) {
        PortOwnerKind.Agent -> if (title != null) stringResource(R.string.ports_owner_agent, title) else stringResource(R.string.ports_owner_agent_untitled)
        PortOwnerKind.Terminal -> if (title != null) stringResource(R.string.ports_owner_terminal, title) else stringResource(R.string.ports_owner_terminal_untitled)
        PortOwnerKind.None -> null
    }
}

@Composable
private fun ForwardStateLine(forward: PortForward?) {
    val c = Tf.colors
    val error = forward?.error?.let { stringResource(it.messageRes()) }
    val local = forward?.localPort ?: forward?.remotePort
    val (color: Color, text: String) = when {
        forward == null -> c.muted to stringResource(R.string.ports_state_idle)
        forward.status == PortForwardStatus.Starting -> c.blue to stringResource(R.string.ports_state_starting)
        forward.status == PortForwardStatus.Failed -> c.red to stringResource(R.string.ports_state_failed, error ?: stringResource(PortForwardError.Unreachable.messageRes()))
        error != null -> c.amber to stringResource(R.string.ports_state_active_error, local!!, error)
        forward.activeConnections > 0 -> c.green to stringResource(R.string.ports_state_active_connections, local!!, forward.activeConnections)
        else -> c.green to stringResource(R.string.ports_state_active, local!!)
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).background(color, CircleShape))
        Text(text, style = TrefoilType.meta, color = if (forward == null) c.muted else color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@StringRes
internal fun PortForwardError.messageRes(): Int = when (this) {
    PortForwardError.TunnelDisabled -> R.string.ports_error_tunnel_disabled
    PortForwardError.PortRefused -> R.string.ports_error_port_refused
    PortForwardError.ServerOutdated -> R.string.ports_error_server_outdated
    PortForwardError.Unauthorized -> R.string.ports_error_unauthorized
    PortForwardError.Unreachable -> R.string.ports_error_unreachable
    PortForwardError.LocalBindFailed -> R.string.ports_error_local_bind
}

@Composable
private fun ForwardPortDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val port = parsePortInput(text)
    val invalid = text.isNotBlank() && port == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ports_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ports_manual_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.filter { ch -> ch in '0'..'9' }.take(5) },
                    label = { Text(stringResource(R.string.ports_manual_label)) }, singleLine = true, isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.ports_manual_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (port != null) onConfirm(port) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (port != null) onConfirm(port) }, enabled = port != null) { Text(stringResource(R.string.ports_manual_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ports_cancel)) } },
    )
}

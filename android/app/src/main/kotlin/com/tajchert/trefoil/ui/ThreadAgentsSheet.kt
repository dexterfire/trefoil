/* Agents sheet layout adapted from T3 Code mobile thread agents UI (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.TfAdaptiveSheet
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.TfModalSideSheet
import com.tajchert.trefoil.ui.adaptive.usesDialog
import com.tajchert.trefoil.ui.adaptive.windowSizeDp
import kotlinx.coroutines.delay
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/** One prebuilt row of the Agents sheet; the content composable never touches the domain model. */
internal data class AgentRowUi(
    val id: String, val title: String, val statusLabel: String, val tone: SubagentTone,
    val elapsed: String? = null, val modelLine: String? = null, val detail: String? = null, val runsOn: String? = null,
    val openThreadId: String? = null, val canStop: Boolean = false,
)

/** Maps the roster onto row models. [canWrite] gates Stop on top of the subagent's own rule. */
internal fun agentRows(roster: SubagentRoster, providers: List<ProviderInstance>, models: List<ModelOption>, now: Long, canWrite: Boolean): List<AgentRowUi> =
    roster.subagents.map { s ->
        AgentRowUi(
            id = s.id, title = subagentRowTitle(s), statusLabel = subagentStatusLabel(s.status), tone = subagentTone(s.status),
            elapsed = subagentElapsedMs(s, now)?.let(::formatSubagentDuration),
            modelLine = subagentModelLine(s, providers, models),
            detail = subagentWorkflowSummary(s) ?: subagentDetail(s),
            runsOn = s.remoteChild?.let { it.label.ifBlank { it.environmentId } },
            openThreadId = subagentOpenThreadId(s), canStop = s.canStop && canWrite,
        )
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadAgentsSheet(
    roster: SubagentRoster, providers: List<ProviderInstance>, models: List<ModelOption>, canWrite: Boolean,
    dismiss: () -> Unit, onOpen: (String) -> Unit, onStop: (String) -> Unit,
) {
    val c = Tf.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(roster.liveCount > 0) {
        now = System.currentTimeMillis()
        while (roster.liveCount > 0) { delay(1000); now = System.currentTimeMillis() }
    }
    // One stop per agent while the sheet is open: the row keeps its button until the projection settles it.
    var requested by remember { mutableStateOf(emptySet<String>()) }
    val rows = remember(roster, providers, models, now, canWrite, requested) {
        agentRows(roster, providers, models, now, canWrite).map { if (it.id in requested) it.copy(canStop = false) else it }
    }
    val onStopRow = { id: String -> requested = requested + id; onStop(id) }
    if (usesDialog(windowSizeDp().width.value)) {
        // From 600dp: a trailing modal side sheet; in a short window (phone landscape) a 480dp centred dialog instead.
        if (LocalCompactHeight.current) {
            // No drag handle in either, so the heading gets its own top gap.
            // The centred dialog has no side-sheet close button, so the header carries one (a short window may not show the scrim to tap).
            TfAdaptiveSheet(onDismiss = dismiss, dialogMaxWidth = AGENTS_COMPACT_DIALOG_WIDTH, insetContent = false) { ThreadAgentsSheetContent(rows, onOpen, onStopRow, Modifier.padding(top = 4.dp), onClose = dismiss) }
        } else {
            TfModalSideSheet(onDismiss = dismiss, title = "Agents") { ThreadAgentsSheetContent(rows, onOpen, onStopRow, Modifier.padding(top = 16.dp)) }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) { ThreadAgentsSheetContent(rows, onOpen, onStopRow, Modifier.navigationBarsPadding()) }
}

private val AGENTS_COMPACT_DIALOG_WIDTH = 480.dp

/**
 * Sheet body, separate from the [ModalBottomSheet] chrome so it renders and tests standalone. With [onClose] (the compact-height dialog) the
 * heading is a fixed header with a trailing 48dp Close button, and the list scrolls under it with a hairline while more rows lie below.
 */
@Composable
internal fun ThreadAgentsSheetContent(
    rows: List<AgentRowUi>, onOpen: (String) -> Unit, onStop: (String) -> Unit, modifier: Modifier = Modifier, showHeading: Boolean = true, onClose: (() -> Unit)? = null,
) {
    val c = Tf.colors
    var stoppingId by remember { mutableStateOf<String?>(null) }
    // Re-resolve the pending stop target from live rows so a settled agent cannot be stopped from a stale dialog.
    val stopping = stoppingId?.let { id -> rows.firstOrNull { it.id == id && it.canStop } }
    val scroll = rememberScrollState()
    @Composable fun ColumnScope.Rows() {
        if (rows.isEmpty()) Text("No agents in this turn.", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 12.dp), style = TrefoilType.preview, color = c.muted)
        rows.forEach { row ->
            AgentRow(row, onOpen, { stoppingId = row.id })
            if (row !== rows.last()) HorizontalDivider(color = c.line)
        }
    }
    if (onClose == null) {
        Column(modifier.fillMaxWidth().verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            if (showHeading) Text("Agents", Modifier.padding(start = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.headlineSmall, color = c.text)
            Rows()
        }
    } else {
        Column(modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Agents", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 1)
                TfIconButton(TfIcons.Close, "Close", onClose, Modifier.size(48.dp))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { Rows() }
            // A fixed 1dp slot, so the hairline appearing and disappearing never moves the list.
            Box(Modifier.fillMaxWidth().height(1.dp).testTag("agents-scroll-divider").background(if (scroll.canScrollForward) c.line else Color.Transparent))
        }
    }
    stopping?.let { target ->
        AlertDialog(
            onDismissRequest = { stoppingId = null }, title = { Text("Stop agent?") }, text = { Text("Stop ${target.title}?") },
            confirmButton = { TextButton(onClick = { stoppingId = null; onStop(target.id) }) { Text("Stop", color = c.red) } },
            dismissButton = { TextButton(onClick = { stoppingId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AgentRow(row: AgentRowUi, onOpen: (String) -> Unit, onStop: () -> Unit) {
    val c = Tf.colors
    val toneColor = when (row.tone) { SubagentTone.Working -> c.blue; SubagentTone.Completed -> c.accent; SubagentTone.Failed -> c.red; SubagentTone.Stopped -> c.muted }
    val openId = row.openThreadId
    Row(
        Modifier.fillMaxWidth().testTag("agent-row-${row.id}")
            .then(if (openId != null) Modifier.focusRing().handPointer().clickable(role = Role.Button, onClickLabel = "Open ${row.title}") { onOpen(openId) } else Modifier)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(toneColor, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, Modifier.weight(1f, fill = false), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("· ${row.statusLabel}", style = TrefoilType.meta, color = toneColor, maxLines = 1)
                if (row.elapsed != null) Text(row.elapsed, style = TrefoilType.meta, color = c.muted, maxLines = 1)
            }
            if (row.modelLine != null) Text(row.modelLine, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.detail != null) Text(row.detail, style = TrefoilType.preview, color = if (row.tone == SubagentTone.Failed) c.red else c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (row.runsOn != null) Text("on ${row.runsOn}", style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.canStop) TfIconButton(TfIcons.Stop, "Stop ${row.title}", onStop, Modifier.size(40.dp), iconSize = 20.dp, tint = c.text)
        if (openId != null) Icon(TfIcons.ChevronRight, null, Modifier.padding(top = 2.dp).size(20.dp), tint = c.muted)
    }
}

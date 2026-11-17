/* Live turn rows adapted from T3 Code web TimelineWorkGroupRows.tsx (WorkingTimelineRow, ThinkingTimelineRow)
 * and V2LifecycleRow.tsx (SubagentTimelineLink, SubagentAvatar) (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.model.ToolActivity

/**
 * "Working for 1m 11s" at the top of the active turn: the same quiet row and divider as the "Worked for" fold it becomes
 * once the turn settles, ticking each second. Without a start time it reads "Working…".
 */
@Composable
internal fun WorkingTurnHeader(startedAt: Long?) {
    val c = Tf.colors
    val now = rememberThreadWorkClock(startedAt != null)
    val label = if (startedAt != null) "Working for ${workingTimerLabel(now - startedAt)}" else "Working…"
    Column(Modifier.fillMaxWidth().testTag("working-header")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = WorkRowText.copy(fontFeatureSettings = "tnum"), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = c.line)
    }
}

/** Trailing live indicator: reasoning glyph and a softly pulsing muted label (static when animations are off). */
@Composable
internal fun ThinkingRow(label: String) {
    val c = Tf.colors
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val pulse = if (reducedMotion) null else rememberInfiniteTransition(label = "thinking")
        .animateFloat(1f, .45f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "thinking-alpha")
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).testTag("thinking-row").semantics(mergeDescendants = true) {}.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(TfIcons.Reasoning, null, Modifier.size(16.dp), tint = c.muted.copy(alpha = .7f))
        Text(label, Modifier.graphicsLayer { alpha = pulse?.value ?: 1f }, style = WorkRowText, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A run of `subagent` items: one inline agent row per agent. */
@Composable
internal fun SubagentActivityRows(activities: List<ToolActivity>, subagents: List<Subagent>, onOpenThread: ((String) -> Unit)?, onOpenAgents: (() -> Unit)?) {
    val agents = remember(activities, subagents) { subagentsForGroup(activities, subagents) }
    Column(Modifier.fillMaxWidth()) {
        agents.forEach { agent -> key(agent.id) { SubagentTimelineRow(agent, onOpenThread, onOpenAgents) } }
    }
}

/** Opens the agent's child thread when it has one, else the Agents sheet; not tappable when neither is available. */
@Composable
internal fun SubagentTimelineRow(subagent: Subagent, onOpenThread: ((String) -> Unit)?, onOpenAgents: (() -> Unit)?) {
    val now = rememberThreadWorkClock(subagent.isActive)
    val row = remember(subagent, now) { subagentTimelineRow(subagent, now) }
    val threadId = row.openThreadId
    val open = if (threadId != null && onOpenThread != null) ({ onOpenThread(threadId) }) else onOpenAgents
    SubagentTimelineRowContent(row, open)
}

@Composable
internal fun SubagentTimelineRowContent(row: SubagentTimelineRowUi, onClick: (() -> Unit)?) {
    val c = Tf.colors
    val failedColor = c.red.copy(alpha = .85f)
    val trailing = TrefoilType.meta.copy(fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("subagent-row-${row.id}")
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${row.title}", onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = row.accessibilityLabel }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SubagentAvatar(row.driver, row.tone)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, Modifier.weight(1f, fill = false), style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.runsOn != null) Text("on ${row.runsOn}", style = trailing, color = c.muted, maxLines = 1)
            }
            Text(row.detail ?: row.statusLabel, style = TrefoilType.meta.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
                color = if (row.failed) failedColor else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.showStatus) Text(row.statusLabel, style = trailing, color = if (row.failed) failedColor else c.muted, maxLines = 1)
        if (row.elapsed != null) Text(row.elapsed, style = trailing, color = c.muted, maxLines = 1)
        if (onClick != null) Icon(TfIcons.ChevronRight, null, Modifier.size(14.dp), tint = c.muted.copy(alpha = .7f))
    }
}

/** Provider mark in a small circle with a status dot at its corner. */
@Composable
private fun SubagentAvatar(driver: String, tone: SubagentTone) {
    val c = Tf.colors
    val dot = when (tone) { SubagentTone.Working -> c.blue; SubagentTone.Completed -> c.green; SubagentTone.Failed -> c.red; SubagentTone.Stopped -> c.muted.copy(alpha = .6f) }
    Box(Modifier.size(26.dp)) {
        Box(Modifier.size(24.dp).background(c.s2, CircleShape).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
            ProviderMark(driver, null, Modifier.size(14.dp))
        }
        Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(c.bg, CircleShape).padding(1.5.dp).background(dot, CircleShape))
    }
}

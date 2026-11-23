package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.TfTwoPane
import com.tajchert.trefoil.ui.adaptive.maxContentWidth
import com.tajchert.trefoil.ui.adaptive.rememberTwoPaneState
import com.tajchert.trefoil.ui.adaptive.usesDialog
import com.tajchert.trefoil.ui.adaptive.windowSizeDp
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.onSecondaryPress
import com.tajchert.trefoil.ui.adaptive.focusRing

/** Text-field styling shared by the connect flows: accent focus, line border, s1 container. */
@Composable
internal fun tfFieldColors(): TextFieldColors {
    val c = Tf.colors
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent, unfocusedBorderColor = c.line, disabledBorderColor = c.line.copy(alpha = .5f),
        errorBorderColor = c.red, focusedLabelColor = c.accent, unfocusedLabelColor = c.muted, cursorColor = c.accent,
        focusedContainerColor = c.s1, unfocusedContainerColor = c.s1, disabledContainerColor = c.s1,
    )
}

internal val TfFieldShape = RoundedCornerShape(14.dp)

/** How Machines lays itself out for the width of its container (never the window). */
internal enum class MachinesLayout {
    /** Phone column, edge to edge: today's screen. */
    Phone,
    /** First run on a wider container: one centred onboarding card. */
    FirstRunCard,
    /** Saved machines on a medium container: two columns of cards in a centred column. */
    Column,
    /** Saved machines on an expanded container: list beside an inspector of the selected machine. */
    ListDetail,
}

internal object MachinesLayoutSpec {
    const val ColumnMax = 760f
    const val FirstRunCardMax = 560f
    const val FirstRunNarrowCardMax = 520f
    const val FirstRunNarrowBelow = 720f
    const val ListWidth = 360f
    /** Widest a lone saved machine grows in the two-column list. */
    const val SingleCardMax = 480f
    /** Inspector content: start-aligned, at most this wide. */
    const val InspectorContentMax = 760f
    /** Add tile height in the list pane beside the inspector. */
    const val ListAddTileMinHeight = 76f
}

internal fun machinesLayout(containerWidthDp: Float, hasSaved: Boolean): MachinesLayout = when {
    containerWidthDp < AdaptiveSpec.MediumMinWidth -> MachinesLayout.Phone
    !hasSaved -> MachinesLayout.FirstRunCard
    containerWidthDp >= AdaptiveSpec.ExpandedMinWidth -> MachinesLayout.ListDetail
    else -> MachinesLayout.Column
}

internal fun firstRunCardMaxWidth(containerWidthDp: Float): Float =
    if (containerWidthDp < MachinesLayoutSpec.FirstRunNarrowBelow) MachinesLayoutSpec.FirstRunNarrowCardMax else MachinesLayoutSpec.FirstRunCardMax

/** The machine the inspector shows: the one the user picked, else the connected one, else the first saved; null only when nothing is saved. */
internal fun selectedMachine(saved: List<SavedEnvironment>, chosenId: String?, connectedId: String?): SavedEnvironment? =
    saved.firstOrNull { it.id == chosenId } ?: saved.firstOrNull { it.id == connectedId } ?: saved.firstOrNull()

/**
 * Machines: saved T3 environments, the current connection, and ways to add one.
 * [onBack] is null when there is nothing to go back to (no environment connected yet, or the shell offers its own navigation).
 * [onOpenUsage] makes the "Tightest limit" tile of the wide inspector open Limits & usage; without it the tile is not tappable.
 */
@Composable
internal fun MachinesScreen(
    state: ClientState, saved: List<SavedEnvironment>,
    onConnect: (SavedEnvironment) -> Unit, onForget: (SavedEnvironment) -> Unit,
    onPair: () -> Unit, onSsh: () -> Unit, onDisconnect: () -> Unit,
    onBack: (() -> Unit)?, modifier: Modifier = Modifier,
    probes: Map<String, MachineProbe> = emptyMap(),
    onAllowLocalHttp: (SavedEnvironment, Boolean) -> Unit = { _, _ -> },
    expandedRoutes: Set<String> = emptySet(),
    onOpenUsage: (() -> Unit)? = null,
) {
    val c = Tf.colors
    // Saved by id: a restored dialog needs the machine to still exist, else it stays closed.
    var forgettingId by rememberSaveable { mutableStateOf<String?>(null) }
    val forgetting = saved.firstOrNull { it.id == forgettingId }
    // The list-detail selection lives here, above the layout switch, so it survives the container crossing 840dp and back.
    // Machine ids are not secrets; a forgotten or unknown id falls back to the connected (else first) machine.
    var chosenId by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(modifier.background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val containerWidth = maxWidth.value
        when (machinesLayout(containerWidth, saved.isNotEmpty())) {
            MachinesLayout.FirstRunCard -> MachinesFirstRun(containerWidth, state, onBack, onPair, onSsh)
            MachinesLayout.ListDetail -> MachinesListDetail(
                state, saved, probes, chosenId, onChoose = { chosenId = it }, onConnect = onConnect, onForget = { forgettingId = it.id }, onPair = onPair, onSsh = onSsh,
                onDisconnect = onDisconnect, onBack = onBack, onAllowLocalHttp = onAllowLocalHttp, onOpenUsage = onOpenUsage)
            MachinesLayout.Column -> MachinesWideColumn(
                state = state, saved = saved, probes = probes, onConnect = onConnect,
                onForget = { forgettingId = it.id }, onPair = onPair, onSsh = onSsh, onDisconnect = onDisconnect, onBack = onBack,
                onAllowLocalHttp = onAllowLocalHttp, expandedRoutes = expandedRoutes)
            else -> MachinesColumn(
                state = state, saved = saved, probes = probes, onConnect = onConnect,
                onForget = { forgettingId = it.id }, onPair = onPair, onSsh = onSsh, onDisconnect = onDisconnect, onBack = onBack,
                onAllowLocalHttp = onAllowLocalHttp, expandedRoutes = expandedRoutes)
        }
    }
    forgetting?.let { environment ->
        AlertDialog(onDismissRequest = { forgettingId = null }, containerColor = c.sheet, shape = RoundedCornerShape(28.dp),
            title = { Text("Forget ${environment.name}?") },
            text = { Text("Saved credentials for this machine are removed from this device. You can pair it again later.", color = c.muted) },
            confirmButton = { TextButton(onClick = { forgettingId = null; onForget(environment) }) { Text("Forget", color = c.red) } },
            dismissButton = { TextButton(onClick = { forgettingId = null }) { Text("Cancel") } })
    }
}

/** Today's Machines list on a phone-width container. */
@Composable
private fun MachinesColumn(
    state: ClientState, saved: List<SavedEnvironment>, probes: Map<String, MachineProbe>,
    onConnect: (SavedEnvironment) -> Unit, onForget: (SavedEnvironment) -> Unit, onPair: () -> Unit, onSsh: () -> Unit, onDisconnect: () -> Unit,
    onBack: (() -> Unit)?, onAllowLocalHttp: (SavedEnvironment, Boolean) -> Unit, expandedRoutes: Set<String>,
) {
    val c = Tf.colors
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TfIconButton(TfIcons.Back, "Back", onBack, Modifier.size(48.dp), tint = c.text)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)) {
            item {
                TrefoilMark(Modifier.size(44.dp).offset(x = (-6).dp), color = Tf.colors.accent)
                Spacer(Modifier.height(12.dp))
                Text("Machines", style = TrefoilType.display, color = c.text)
                if (state.environment == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Follow your T3 Code conversations and keep remote work moving from anywhere.", style = TrefoilType.preview, color = c.muted)
                }
            }
            if (saved.isNotEmpty()) {
                item { Text("SAVED", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp)) }
                items(saved, key = { it.id }) { environment ->
                    MachineCard(environment, state.environment?.id == environment.id, state, machineStatus(environment, state, probes), onConnect = { onConnect(environment) },
                        onForget = { onForget(environment) }, onDisconnect = onDisconnect, modifier = Modifier.padding(bottom = 10.dp),
                        onAllowLocalHttp = { onAllowLocalHttp(environment, it) }, routesExpanded = environment.id in expandedRoutes)
                }
            }
            item {
                Text("ADD", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = if (saved.isEmpty()) 28.dp else 18.dp, bottom = 10.dp))
                AddTiles(state, onPair, onSsh)
            }
        }
    }
}

/**
 * Machines on a medium container (600 to 839dp): saved machines as two columns of cards, read and tabbed row by row.
 * A lone machine is one card at most [MachinesLayoutSpec.SingleCardMax] wide.
 */
@Composable
private fun MachinesWideColumn(
    state: ClientState, saved: List<SavedEnvironment>, probes: Map<String, MachineProbe>,
    onConnect: (SavedEnvironment) -> Unit, onForget: (SavedEnvironment) -> Unit, onPair: () -> Unit, onSsh: () -> Unit, onDisconnect: () -> Unit,
    onBack: (() -> Unit)?, onAllowLocalHttp: (SavedEnvironment, Boolean) -> Unit, expandedRoutes: Set<String>,
) {
    val c = Tf.colors
    val brandTop = if (state.environment == null && onBack == null) 24.dp else 0.dp
    // Paired rows in a LazyColumn rather than a grid: a grid hands every cell its own height, so a card with a route badge or a status line
    // sat 8 to 16dp lower than its neighbour. Each row is as tall as its taller card and both cards fill it, footers on one line.
    LazyColumn(
        Modifier.fillMaxHeight().maxContentWidth(MachinesLayoutSpec.ColumnMax.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = brandTop, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.environment != null) item { MachinesPaneHeader(onBack) }
        else {
            if (onBack != null) item { MachinesBackRow(onBack) }
            item { MachinesBrandHeader("Follow your T3 Code conversations and keep remote work moving from anywhere.") }
        }
        if (saved.isNotEmpty()) {
            item { Text("SAVED", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) }
            @Composable fun Card(environment: SavedEnvironment, modifier: Modifier, fillHeight: Boolean) = MachineCard(
                environment, state.environment?.id == environment.id, state, machineStatus(environment, state, probes), onConnect = { onConnect(environment) },
                onForget = { onForget(environment) }, onDisconnect = onDisconnect, modifier = modifier.testTag("machine-card-${environment.id}"),
                onAllowLocalHttp = { onAllowLocalHttp(environment, it) }, routesExpanded = environment.id in expandedRoutes, roomy = true, fillHeight = fillHeight,
            )
            if (saved.size == 1) {
                items(saved, key = { it.id }) { environment -> Card(environment, Modifier.wrapContentWidth(Alignment.Start).widthIn(max = MachinesLayoutSpec.SingleCardMax.dp), fillHeight = false) }
            } else {
                items(saved.chunked(2), key = { row -> row.first().id }) { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { environment -> Card(environment, Modifier.weight(1f).fillMaxHeight(), fillHeight = true) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Text("ADD", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
        item { AddTiles(state, onPair, onSsh, roomy = true) }
    }
}

/** Pane title of a connected Machines list: one 64dp row, sharing it with the back arrow when there is one (the shell or rail already carries the brand mark). */
@Composable
private fun MachinesPaneHeader(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Row(modifier.fillMaxWidth().height(64.dp).testTag("machines-pane-header"), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Box(Modifier.offset(x = (-12).dp)) { TfIconButton(TfIcons.Back, "Back", onBack, Modifier.size(48.dp), tint = c.text) }
        Text("Machines", Modifier.then(if (onBack != null) Modifier.offset(x = (-12).dp) else Modifier).semantics { heading() },
            style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 1)
    }
}

/** The back row above the brand header of a Machines list that is not connected yet. */
@Composable
private fun MachinesBackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.offset(x = (-12).dp)) { TfIconButton(TfIcons.Back, "Back", onBack, Modifier.size(48.dp), tint = Tf.colors.text) }
    }
}

/** Brand mark, large title and a line of context: the header of a Machines list with no connection. */
@Composable
private fun MachinesBrandHeader(subtitle: String) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().testTag("machines-brand-header")) {
        TrefoilMark(Modifier.size(44.dp).offset(x = (-6).dp), color = c.accent)
        Spacer(Modifier.height(12.dp))
        Text("Machines", Modifier.semantics { heading() }, style = TrefoilType.display, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = TrefoilType.preview, color = c.muted)
    }
}

/** The two ways to add a machine, side by side at the width of their parent. [roomy] (from 600dp) gives the secondary tile its card fill. */
@Composable
private fun AddTiles(state: ClientState, onPair: () -> Unit, onSsh: () -> Unit, modifier: Modifier = Modifier, roomy: Boolean = false, minHeight: Dp = 88.dp) {
    val c = Tf.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (roomy) 12.dp else 10.dp)) {
        AddTile("Link", "Pair with link", TfIcons.Link, primary = true, enabled = !state.busy, onClick = onPair, modifier = Modifier.weight(1f), minHeight = minHeight)
        AddTile("SSH", "Connect over SSH", TfIcons.Terminal, primary = false, enabled = !state.busy, onClick = onSsh, modifier = Modifier.weight(1f),
            minHeight = minHeight, fill = if (roomy) c.s1 else Color.Transparent)
    }
}

/** No saved machine on a container of at least 600dp: one centred card instead of a phone column floating in space. */
@Composable
private fun MachinesFirstRun(containerWidth: Float, state: ClientState, onBack: (() -> Unit)?, onPair: () -> Unit, onSsh: () -> Unit) {
    val c = Tf.colors
    val cardShape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxSize()) {
        if (onBack != null) Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { TfIconButton(TfIcons.Back, "Back", onBack, Modifier.size(48.dp), tint = c.text) }
        Column(
            Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = firstRunCardMaxWidth(containerWidth).dp).fillMaxWidth()
                .clip(cardShape).background(c.s1).border(1.dp, c.line, cardShape).verticalScroll(rememberScrollState()).padding(32.dp),
        ) {
            TrefoilMark(Modifier.size(48.dp).offset(x = (-6).dp), color = c.accent)
            Spacer(Modifier.height(14.dp))
            Text("Machines", style = TrefoilType.display, color = c.text)
            Spacer(Modifier.height(8.dp))
            Text("Follow your T3 Code conversations and keep remote work moving from anywhere.", style = TrefoilType.preview, color = c.muted)
            Text("ADD", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 28.dp, bottom = 10.dp))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AddTile("Pair with link", "Pair with link", TfIcons.Link, primary = true, enabled = !state.busy, onClick = onPair,
                    modifier = Modifier.weight(1f).fillMaxHeight(), helper = "Paste a pairing link", minHeight = 112.dp)
                AddTile("Connect over SSH", "Connect over SSH", TfIcons.Terminal, primary = false, enabled = !state.busy, onClick = onSsh,
                    modifier = Modifier.weight(1f).fillMaxHeight(), helper = "Use an SSH login", minHeight = 112.dp, fill = c.bg)
            }
            Text("Trefoil verifies a machine before it connects and never installs anything on it.", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

/** Saved machines beside an inspector of the selected one. Selecting a machine only shows it; connecting stays an explicit action. */
@Composable
private fun MachinesListDetail(
    state: ClientState, saved: List<SavedEnvironment>, probes: Map<String, MachineProbe>, chosenId: String?, onChoose: (String) -> Unit,
    onConnect: (SavedEnvironment) -> Unit, onForget: (SavedEnvironment) -> Unit, onPair: () -> Unit, onSsh: () -> Unit, onDisconnect: () -> Unit,
    onBack: (() -> Unit)?, onAllowLocalHttp: (SavedEnvironment, Boolean) -> Unit, onOpenUsage: (() -> Unit)?,
) {
    val c = Tf.colors
    val selected = selectedMachine(saved, chosenId, state.environment?.id)
    val pane = rememberTwoPaneState(MachinesLayoutSpec.ListWidth.dp)
    TfTwoPane(
        pane, MachinesLayoutSpec.ListWidth.dp, modifier = Modifier.fillMaxSize(), collapseBelow = null, dividerLabel = "Resize machine list",
        start = {
            val brandTop = if (state.environment == null && onBack == null) 24.dp else 0.dp
            LazyColumn(Modifier.fillMaxSize().testTag("machines-list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = brandTop, bottom = 24.dp)) {
                // Connected, the rail already carries the brand mark: the list opens with its 64dp pane title (and the back arrow, if any).
                if (state.environment != null) item { MachinesPaneHeader(onBack) }
                else {
                    if (onBack != null) item { MachinesBackRow(onBack) }
                    item { MachinesBrandHeader("Not connected") }
                }
                item { Text("SAVED", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 10.dp)) }
                items(saved, key = { it.id }) { environment ->
                    MachineListCard(environment, selected?.id == environment.id, state, machineStatus(environment, state, probes),
                        onSelect = { onChoose(environment.id) }, modifier = Modifier.padding(bottom = 10.dp))
                }
                item {
                    Text("ADD", style = TrefoilType.section, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 10.dp))
                    AddTiles(state, onPair, onSsh, roomy = true, minHeight = MachinesLayoutSpec.ListAddTileMinHeight.dp)
                }
            }
        },
        end = {
            if (selected != null) key(selected.id) {
                MachineInspector(
                    selected, state, machineStatus(selected, state, probes), onConnect = { onConnect(selected) }, onDisconnect = onDisconnect,
                    onForget = { onForget(selected) }, onAllowLocalHttp = { onAllowLocalHttp(selected, it) }, onOpenUsage = onOpenUsage,
                    modifier = Modifier.fillMaxSize())
            }
        },
    )
}

/** A saved machine in the wide list: tap selects it for the inspector (no menu, no routes; the inspector owns both). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MachineListCard(environment: SavedEnvironment, selected: Boolean, state: ClientState, status: MachineStatus, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val shape = RoundedCornerShape(20.dp)
    val current = state.environment?.id == environment.id
    val live = current && state.connection == ConnectionStatus.Connected
    val activeRoute = if (live) state.activeRoute else null
    val working = if (live) machineWorkingCount(state.threads) else 0
    val needsYou = if (live) state.threads.count { !it.archived && threadIndicator(it).kind in setOf("attention", "input") } else 0
    Row(
        modifier.fillMaxWidth().clip(shape).background(c.s1).border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .testTag("machine-list-card-${environment.id}")
            .focusRing().handPointer().clickable(role = Role.Button, onClick = onSelect)
            .semantics { this.selected = selected; stateDescription = listOfNotNull(status.label, activeRoute?.let { "via ${routeKindLabel(it.kind)}" }).joinToString(", ") }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MachineAvatar(environment, status, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(environment.name, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machineSubtitle(environment), style = TrefoilType.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (!live && status != MachineStatus.Unknown && status != MachineStatus.Offline) {
                Text(if (status.inProgress) "${status.label}…" else status.label, style = TrefoilType.meta,
                    color = if (status.inProgress) c.muted else status.color(c), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
            // Route and counts share one line, wrapping only when the card is too narrow for both.
            if (activeRoute != null || working > 0 || needsYou > 0) FlowRow(
                Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (activeRoute != null) RouteBadge(activeRoute.kind)
                if (working > 0 || needsYou > 0) MachineCounts(working, needsYou)
            }
        }
        if (current) Icon(TfIcons.Check, "Current machine", tint = c.accent)
    }
}

@Composable
private fun MachineCounts(working: Int, needsYou: Int, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (working > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkingSpinner(size = 14.dp); Text("$working", style = TrefoilType.meta, color = c.blue)
        }
        if (needsYou > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(c.amber); Text("$needsYou", style = TrefoilType.meta, color = c.amber)
        }
    }
}

/** Device icon with the status dot (or spinner) cut into its bottom-end corner. */
@Composable
private fun MachineAvatar(environment: SavedEnvironment, status: MachineStatus, size: Dp, cutoutColor: Color = Tf.colors.s1) {
    val c = Tf.colors
    val big = size > 48.dp
    Box(Modifier.size(size)) {
        Box(Modifier.fillMaxSize().background(c.s2, RoundedCornerShape(if (big) 16.dp else 14.dp)), contentAlignment = Alignment.Center) {
            Icon(if (environment.ssh != null) TfIcons.Server else TfIcons.Desktop, null, Modifier.size(if (big) 28.dp else 24.dp), tint = c.text)
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(if (status.inProgress) 4.dp else 2.dp, if (status.inProgress) 4.dp else 2.dp)
            .size(if (status.inProgress) 18.dp else 12.dp).background(cutoutColor, CircleShape), contentAlignment = Alignment.Center) {
            MachineStatusIndicator(status, size = 8.dp)
        }
    }
}

/** The selected machine, with only what this client knows: live counts and limit for the connected one, never placeholders. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MachineInspector(
    environment: SavedEnvironment, state: ClientState, status: MachineStatus, onConnect: () -> Unit, onDisconnect: () -> Unit, onForget: () -> Unit,
    onAllowLocalHttp: (Boolean) -> Unit, onOpenUsage: (() -> Unit)?, modifier: Modifier = Modifier,
) {
    val c = Tf.colors
    val current = state.environment?.id == environment.id
    val live = current && state.connection == ConnectionStatus.Connected
    val activeRoute = if (live) state.activeRoute else null
    BoxWithConstraints(modifier) {
        // Start-aligned, so the inspector reads as the list's neighbour on any width; a narrow detail pane keeps 24dp.
        val side = if (maxWidth < 600.dp) 24.dp else 32.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            Modifier.widthIn(max = MachinesLayoutSpec.InspectorContentMax.dp).fillMaxWidth().padding(horizontal = side, vertical = 28.dp).testTag("machine-inspector"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MachineAvatar(environment, status, size = 56.dp, cutoutColor = c.bg)
                Column(Modifier.weight(1f)) {
                    Text(environment.name, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(machineSubtitle(environment), style = TrefoilType.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    if (status != MachineStatus.Unknown || activeRoute != null) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (status != MachineStatus.Unknown) Text(if (status.inProgress) "${status.label}…" else status.label, style = TrefoilType.meta,
                            color = if (status.inProgress) c.muted else status.color(c), maxLines = 1)
                        if (activeRoute != null) RouteBadge(activeRoute.kind)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (current) {
                    Button(onClick = onConnect, enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
                        Icon(TfIcons.Threads, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open threads")
                    }
                    OutlinedButton(onClick = onDisconnect, border = BorderStroke(1.dp, c.line), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text)) {
                        Icon(TfIcons.Disconnect, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Disconnect")
                    }
                } else {
                    Button(onClick = onConnect, enabled = !state.busy, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { Text("Connect") }
                }
                OutlinedButton(onClick = onForget, enabled = !state.busy, border = BorderStroke(1.dp, c.line), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.red)) {
                    Icon(TfIcons.Trash, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Forget")
                }
            }
            if (live) {
                val working = machineWorkingCount(state.threads)
                val needsYou = state.threads.count { !it.archived && threadIndicator(it).kind in setOf("attention", "input") }
                val tightest = tightestLimitPercent(state.providers)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile("Needs you", "$needsYou", if (needsYou > 0) c.amber else c.text, Modifier.weight(1f).fillMaxHeight())
                    SummaryTile("Working", "$working", if (working > 0) c.blue else c.text, Modifier.weight(1f).fillMaxHeight())
                    // The tightest limit reads in its severity colour and is the widest tile: its chevron must not squeeze "NN% left".
                    if (tightest != null) SummaryTile("Tightest limit", "$tightest% left", limitSeverity(tightest).color(c), Modifier.weight(1.3f).fillMaxHeight(), onClick = onOpenUsage)
                }
            }
            val card = RoundedCornerShape(16.dp)
            RoutesSection(environment, activeRoute, onAllowLocalHttp, Modifier.fillMaxWidth().clip(card).background(c.s1).border(1.dp, c.line, card).padding(horizontal = 20.dp, vertical = 18.dp), enabled = !state.busy)
        }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Tf.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(c.s1).border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.focusRing().handPointer().clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.headlineSmall, color = valueColor, maxLines = 1)
            if (onClick != null) Icon(TfIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.muted)
        }
        Text(label, style = TrefoilType.meta, color = c.muted, maxLines = 1)
    }
}

private fun machineSubtitle(environment: SavedEnvironment): String =
    environment.ssh?.let { "${it.username}@${it.host}:${it.port}" }
        ?: runCatching { java.net.URI(environment.baseUrl).let { uri -> uri.host?.let { host -> if (uri.port > 0) "$host:${uri.port}" else host } } }.getOrNull()
        ?: environment.baseUrl

/** Threads with a turn actively running now; unknown/idle/archived/awaiting-user statuses never count. */
internal fun machineWorkingCount(threads: List<ThreadSummary>): Int = threads.count { thread ->
    if (thread.archived || thread.archivedAt != null) return@count false
    if (thread.pendingRequestKind != null && thread.pendingRequestKind != "auth_refresh" || thread.pendingChildRequestKind != null) return@count false
    if (thread.completionHeldBackgroundWork && thread.status != "failed") return@count false
    (thread.activityRunStatus ?: thread.status) in setOf("preparing", "queued", "starting", "running", "waiting", "working", "streaming")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MachineCard(
    environment: SavedEnvironment, current: Boolean, state: ClientState, status: MachineStatus,
    onConnect: () -> Unit, onForget: () -> Unit, onDisconnect: () -> Unit, modifier: Modifier = Modifier,
    onAllowLocalHttp: (Boolean) -> Unit = {}, routesExpanded: Boolean = false,
    /** From 600dp: 48dp touch targets for the menu and the routes footer, a divider above the footer. */
    roomy: Boolean = false,
    /** The card sits in a row of equal-height cards: free space goes above the footer so the footers line up. */
    fillHeight: Boolean = false,
) {
    val c = Tf.colors
    var menu by remember { mutableStateOf(false) }
    var showRoutes by rememberSaveable(environment.id) { mutableStateOf(routesExpanded) }
    val icon = if (environment.ssh != null) TfIcons.Server else TfIcons.Desktop
    val live = current && state.connection == ConnectionStatus.Connected
    val shape = RoundedCornerShape(20.dp)
    val working = if (live) machineWorkingCount(state.threads) else 0
    val needsYou = if (live) state.threads.count { !it.archived && threadIndicator(it).kind in setOf("attention", "input") } else 0
    val activeRoute = if (live) state.activeRoute else null
    val routeCount = routeRows(environment, activeRoute).size
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column(modifier.fillMaxWidth().clip(shape).background(c.s1).hoverable(hover).background(if (hovered) c.s2.copy(alpha = .55f) else Color.Transparent)
        .onSecondaryPress(enabled = !state.busy) { menu = true }.border(if (current) 2.dp else 1.dp, if (current) c.accent else c.line, shape)) {
    Row(
        Modifier.fillMaxWidth()
            .focusRing().handPointer(!state.busy).combinedClickable(enabled = !state.busy, role = Role.Button, onClick = onConnect, onLongClick = { menu = true })
            .semantics { stateDescription = listOfNotNull(status.label, activeRoute?.let { "via ${routeKindLabel(it.kind)}" }).joinToString(", ") }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            Box(Modifier.fillMaxSize().background(c.s2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.text) }
            Box(Modifier.align(Alignment.BottomEnd).offset(if (status.inProgress) 4.dp else 2.dp, if (status.inProgress) 4.dp else 2.dp)
                .size(if (status.inProgress) 18.dp else 12.dp).background(c.s1, CircleShape), contentAlignment = Alignment.Center) {
                MachineStatusIndicator(status, size = 8.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(environment.name, style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(machineSubtitle(environment), style = TrefoilType.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (activeRoute != null) RouteBadge(activeRoute.kind, Modifier.padding(top = 6.dp))
            if (!live && status != MachineStatus.Unknown && status != MachineStatus.Offline) {
                Text(if (status.inProgress) "${status.label}…" else status.label, style = TrefoilType.meta,
                    color = if (status.inProgress) c.muted else status.color(c), maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            }
            if (working > 0 || needsYou > 0) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (working > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        WorkingSpinner(size = 14.dp); Text("$working", style = TrefoilType.meta, color = c.blue)
                    }
                    if (needsYou > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(c.amber); Text("$needsYou", style = TrefoilType.meta, color = c.amber)
                    }
                }
            }
        }
        if (current) Icon(TfIcons.Check, "Current machine", tint = c.accent)
        Box {
            TfIconButton(TfIcons.More, "Options for ${environment.name}", { menu = true }, Modifier.size(if (roomy) 48.dp else 40.dp), iconSize = 20.dp, tint = c.muted)
            DropdownMenu(menu, { menu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                MachineMenuItems(current, state, onDisconnect, onForget) { menu = false }
            }
        }
    }
    if (fillHeight) Spacer(Modifier.weight(1f))
    if (roomy) HorizontalDivider(color = c.line)
    Row(Modifier.fillMaxWidth().then(if (roomy) Modifier.heightIn(min = 48.dp) else Modifier).focusRing().handPointer().clickable(role = Role.Button) { showRoutes = !showRoutes }.semantics { stateDescription = if (showRoutes) "Expanded" else "Collapsed" }
        .padding(start = 16.dp, end = 12.dp, top = if (roomy) 0.dp else 2.dp, bottom = if (roomy) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (routeCount == 1) "1 route" else "$routeCount routes", style = TrefoilType.meta, color = c.muted, modifier = Modifier.weight(1f))
        Icon(if (showRoutes) TfIcons.ChevronDown else TfIcons.ChevronRight, if (showRoutes) "Hide routes" else "Show routes", Modifier.size(18.dp), tint = c.muted)
    }
    if (showRoutes) RoutesSection(environment, activeRoute, onAllowLocalHttp, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), enabled = !state.busy)
    }
}

/** Items of the machine card overflow menu; split out so previews can render them without a popup window. */
@Composable
internal fun ColumnScope.MachineMenuItems(
    current: Boolean, state: ClientState,
    onDisconnect: () -> Unit, onForget: () -> Unit, close: () -> Unit,
) {
    val c = Tf.colors
    if (current) DropdownMenuItem(
        text = { Text("Disconnect") }, leadingIcon = { Icon(TfIcons.Disconnect, null, Modifier.size(20.dp)) },
        onClick = { close(); onDisconnect() })
    DropdownMenuItem(
        text = { Text("Forget", color = c.red) }, enabled = !state.busy, leadingIcon = { Icon(TfIcons.Trash, null, Modifier.size(20.dp), tint = c.red) },
        onClick = { close(); onForget() })
}

@Composable
private fun AddTile(
    label: String, description: String, icon: ImageVector, primary: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    helper: String? = null, minHeight: Dp = 88.dp, fill: Color = Color.Transparent,
) {
    val c = Tf.colors
    val shape = RoundedCornerShape(20.dp)
    val fg = (if (primary) c.onAccent else c.text).let { if (enabled) it else it.copy(alpha = .5f) }
    Column(
        modifier.heightIn(min = minHeight).clip(shape).background(if (primary) c.accent else fill)
            .border(1.dp, if (primary) c.accent else c.line, shape)
            .focusRing().handPointer(enabled).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (helper == null) 14.dp else 10.dp),
    ) {
        Icon(icon, null, tint = fg)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = TrefoilType.rowTitle.copy(fontSize = 15.sp), color = fg)
            if (helper != null) Text(helper, style = TrefoilType.meta, color = fg.copy(alpha = fg.alpha * .8f))
        }
    }
}

@Composable
internal fun PairDialog(busy: Boolean, dismiss: () -> Unit, attempt: ConnectionAttempt = ConnectionAttempt(), initialLink: String = "", pair: (String, String, Boolean) -> Unit) {
    val c = Tf.colors
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var url by remember { mutableStateOf(initialLink) }
    var name by rememberSaveable { mutableStateOf("") }
    // The pairing link is a credential: plain remember, never saved state (so a rotation clears it; the display name survives).
    var insecure by remember(url) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val target = remember(url) { runCatching { parsePairingLink(url) }.getOrNull() }
    val uri = target?.let { runCatching { java.net.URI(it.baseUrl) }.getOrNull() }
    val privateHttp = uri?.scheme == "http" && isPrivateLanAddress(uri.host.orEmpty())
    // The clipboard holds a credential: read it only on the tap on "Paste link", never on open.
    val pasteLink = { clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { url = it; error = null }; Unit }
    val shownError = error ?: attempt.message.takeIf { attempt.phase == ConnectionPhase.Failed }
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(shownError) { if (shownError != null) reveal.bringIntoView() }
    // Window-level decision: from 600dp the dialog is a fixed 500dp card with "Paste link" inside the field; below, today's dialog.
    val wide = usesDialog(windowSizeDp().width.value)
    AlertDialog(onDismissRequest = dismiss, containerColor = c.sheet, shape = RoundedCornerShape(28.dp),
        modifier = if (wide) Modifier.width(AdaptiveSpec.DialogPairWidth.dp) else Modifier,
        properties = if (wide) DialogProperties(usePlatformDefaultWidth = false) else DialogProperties(),
        icon = { Icon(TfIcons.Link, null, tint = c.accent) }, title = { Text("Pair an environment") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text("Paste a pairing link from your T3 server. A link from any address works, including a T3 Connect tunnel address (https://…t3coderelay.com/pair#token=…). Trefoil verifies the environment before connecting.", color = c.muted)
            OutlinedTextField(name, { name = it; error = null }, enabled = !busy, label = { Text("Display name") }, singleLine = true, shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it; error = null }, enabled = !busy, label = { Text("Pairing link") }, visualTransformation = PasswordVisualTransformation(),
                textStyle = TrefoilType.mono.copy(fontSize = 14.sp, color = c.text), shape = TfFieldShape, colors = tfFieldColors(), modifier = Modifier.fillMaxWidth(),
                // One masked line in the wide field: a wrapped mask would orphan a few dots on a second line.
                singleLine = wide, maxLines = if (wide) 1 else 3,
                trailingIcon = if (wide) ({ PasteLinkButton(busy, Modifier.padding(end = 4.dp), paste = pasteLink) }) else null)
            if (!wide) PasteLinkButton(busy, Modifier.align(Alignment.End), paste = pasteLink)
            Text("Pairing links contain credentials. Keep them private.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            if (privateHttp && wide) Column(Modifier.fillMaxWidth().background(c.s2, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(insecure, { insecure = it; error = null }, enabled = !busy); Text("Allow unencrypted HTTP on this private network", Modifier.weight(1f)) }
                Text("HTTP exposes pairing credentials and conversations to others on the network. Use only a network you trust.", style = MaterialTheme.typography.bodySmall, color = c.amber,
                    // Flush with the checkbox label: 48dp checkbox slot.
                    modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 6.dp))
            } else if (privateHttp) {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(insecure, { insecure = it; error = null }, enabled = !busy); Text("Allow unencrypted HTTP on this private network", Modifier.weight(1f)) }
                Text("HTTP exposes pairing credentials and conversations to others on the network. Use only a network you trust.", style = MaterialTheme.typography.bodySmall, color = c.amber)
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(attempt.message.ifBlank { "Testing connection…" }) }
            shownError?.let { Text(it, color = c.red, modifier = Modifier.bringIntoViewRequester(reveal)) }
        }
    }, confirmButton = { Button(onClick = {
        error = when { name.isBlank() -> "Enter a display name."; target == null -> "Enter a valid pairing link."; privateHttp && !insecure -> "Choose whether to allow HTTP on this private network."; else -> null }
        if (error == null) pair(url.trim(), name.trim(), privateHttp && insecure)
    }, enabled = !busy) {
        if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.onAccent); Spacer(Modifier.width(8.dp)) }
        Text(if (busy) "Testing connection…" else "Pair")
    } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel", color = c.muted) } })
}

/** "Paste link": the caller's [paste] reads the clipboard, and only runs on this tap. */
@Composable
private fun PasteLinkButton(busy: Boolean, modifier: Modifier = Modifier, paste: () -> Unit) {
    TextButton(onClick = paste, enabled = !busy, modifier = modifier) { Text("Paste link") }
}

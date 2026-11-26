package com.tajchert.trefoil.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.shell.ShortcutId
import com.tajchert.trefoil.ui.shell.tooltipWithShortcut
import com.tajchert.trefoil.ui.adaptive.TfTooltip
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome
import com.tajchert.trefoil.ui.adaptive.focusRing

/* ---------- Header ---------- */

/**
 * List header. The default [chrome] is the phone header; the rail and navigation variants decide every width-dependent detail
 * from this header's own width (the list's width), never from the window. [compactHeight] narrows it to 48dp.
 */
@Composable
internal fun ThreadListHeader(
    state: ClientState, shell: ShellActions?, onSearch: () -> Unit,
    chrome: ThreadListChrome = ThreadListChrome(), compactHeight: Boolean = false,
) {
    val c = Tf.colors
    BoxWithConstraints {
        val minHeight = if (compactHeight) 48.dp else 64.dp
        // The machine name has priority: narrow headers show the limit gauge without its number, and so do large font scales.
        val listWidth = maxWidth
        val limitNumber = listWidth >= LimitNumberMinWidth
        val roomyText = LocalDensity.current.fontScale < 1.3f
        when {
            chrome.railPresent -> Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.environment?.name ?: "Треды", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall,
                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TfIconButton(TfIcons.Search, "Поиск", onSearch, iconSize = 22.dp, tint = c.text, tooltip = tooltipWithShortcut("Поиск тредов", ShortcutId.SearchThreads))
                chrome.onCollapse?.let { collapse -> TfIconButton(TfIcons.PanelLeft, "Скрыть список тредов", collapse, iconSize = 22.dp, tint = c.text, tooltip = tooltipWithShortcut("Скрыть список тредов", ShortcutId.ToggleThreadList)) }
            }
            chrome != ThreadListChrome() -> {
                val navigation = chrome.onOpenNavigation
                Row(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(start = if (navigation != null) 4.dp else 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (navigation != null) TfIconButton(TfIcons.Menu, "Открыть навигацию", navigation, iconSize = 22.dp, tint = c.text, tooltip = "Меню навигации")
                    Box(Modifier.weight(1f)) { MachinePill(state, onClick = { shell?.onMachines?.invoke() }, showRoute = listWidth >= RouteIconMinWidth) }
                    shell?.onUsage?.let { openUsage ->
                        val percent = remember(state.providers) { tightestLimitPercent(state.providers) }
                        TfTooltip(limitChipTooltip(percent)) { LimitChip(percent, limitNumber && roomyText, openUsage) }
                    }
                    TfIconButton(TfIcons.Search, "Поиск", onSearch, iconSize = 22.dp, tint = c.text, tooltip = tooltipWithShortcut("Поиск тредов", ShortcutId.SearchThreads))
                    val collapse = chrome.onCollapse
                    if (collapse != null && listWidth >= CollapseMinWidth) TfIconButton(TfIcons.PanelLeft, "Скрыть список тредов", collapse, iconSize = 22.dp, tint = c.text, tooltip = tooltipWithShortcut("Скрыть список тредов", ShortcutId.ToggleThreadList))
                    else if (navigation == null && shell != null) ShellMenuButton(state, shell, c.text, description = "Меню", tooltip = "Меню приложения")
                }
            }
            else -> Row(Modifier.fillMaxWidth().height(minHeight).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.weight(1f)) { MachinePill(state, onClick = { shell?.onMachines?.invoke() }) }
                shell?.onUsage?.let { openUsage -> LimitChip(remember(state.providers) { tightestLimitPercent(state.providers) }, limitNumber, openUsage) }
                TfIconButton(TfIcons.Search, "Поиск", onSearch, iconSize = 22.dp, tint = c.text, tooltip = tooltipWithShortcut("Поиск тредов", ShortcutId.SearchThreads))
                if (shell != null) ShellMenuButton(state, shell, c.text, description = "Меню", tooltip = "Меню приложения")
            }
        }
    }
}

/** List widths (dp) at which header details appear; shared with tests. */
internal val LimitNumberMinWidth = 340.dp
internal val RouteIconMinWidth = 340.dp
internal val CollapseMinWidth = 360.dp

@Composable
private fun limitChipTooltip(percent: Int?): String =
    limitChipDescription(percent, stringResource(R.string.menu_limits_usage), percent?.let { stringResource(R.string.usage_limit_left, it) }.orEmpty())

/** Current machine: status dot + environment name + chevron. Opens Machines. */
@Composable
internal fun MachinePill(state: ClientState, onClick: () -> Unit, showRoute: Boolean = true) {
    val c = Tf.colors
    val status = machineStatus(state)
    val route = state.activeRoute?.takeIf { state.connection == ConnectionStatus.Connected }
    val machineTitle = state.environment?.name ?: stringResource(R.string.current_machine)
    val machineName = state.environment?.name ?: stringResource(R.string.machine_none)
    val machineSwitch = stringResource(R.string.machine_switch)
    val statusLabel = status.resolvedLabel()
    val viaRoute = route?.let { stringResource(R.string.route_via, routeKindLabel(it.kind)) }
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = c.s2, contentColor = c.text,
        modifier = Modifier.focusRing(RoundedCornerShape(20.dp)).handPointer().semantics {
            contentDescription = machineSwitch
            stateDescription = listOfNotNull(machineName, statusLabel, viaRoute).joinToString(", ")
        }) {
        Row(Modifier.height(40.dp).padding(start = 14.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(status.color(c))
            Text(machineTitle, Modifier.weight(1f, fill = false), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (route != null && showRoute) Icon(routeKindIcon(route.kind), null, Modifier.size(14.dp), tint = c.muted)
            Icon(TfIcons.ChevronDown, null, Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ThreadSearchField(query: String, onQuery: (String) -> Unit, close: () -> Unit) {
    val c = Tf.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).height(48.dp).background(c.s2, RoundedCornerShape(24.dp)).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(query, onQuery, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                textStyle = TrefoilType.preview.copy(color = c.text, fontSize = 15.sp), cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Поиск тредов", style = TrefoilType.preview.copy(fontSize = 15.sp), color = c.muted)
                    inner()
                })
        }
        TfIconButton(TfIcons.Close, if (query.isEmpty()) "Закрыть поиск" else "Очистить поиск", close, iconSize = 18.dp, tint = c.muted)
    }
}

/* ---------- Chips ---------- */

@Composable
internal fun ThreadFilterChips(filter: ThreadFilter, counts: Map<ThreadFilter, Int>, projects: List<Project>, project: Project?, onFilter: (ThreadFilter) -> Unit, onProject: (String?) -> Unit,
                               /** Shell chrome only. Fades the strip where it scrolls, spaces chips 6dp, tightens the chips (10dp inner padding) and the start inset (12dp) so a 336-340dp list peeks the Project chip, and centres them in the 48dp strip. */
                               tabletChrome: Boolean = false) {
    val c = Tf.colors
    var projectsOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // The 48dp strip is unchanged (4 + 36 + 8): centring the chips lets their 48dp touch area fit inside it without adding header height.
    Row(Modifier.fillMaxWidth()
        .then(if (tabletChrome) Modifier.scrollEdgeFades(scroll, c.bg, ChipFadeWidth) else Modifier)
        .horizontalScroll(scroll)
        .padding(start = if (tabletChrome) 12.dp else 16.dp, end = 16.dp, top = if (tabletChrome) 6.dp else 4.dp, bottom = if (tabletChrome) 6.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tabletChrome) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TfChip("Все", { onFilter(ThreadFilter.All) }, selected = filter == ThreadFilter.All, count = counts[ThreadFilter.All], dense = tabletChrome)
        // Jump comes second: on a 360dp phone a fourth chip is off-screen, and this one is the new, least discoverable action.
        if (projects.isNotEmpty()) Box {
            TfChip("К проекту", { projectsOpen = true }, selected = false,
                trailing = { Icon(TfIcons.ChevronDown, null, Modifier.size(14.dp)) }, dense = tabletChrome)
            DropdownMenu(projectsOpen, { projectsOpen = false }) {
                DropdownMenuItem(text = { Text("Все проекты") }, onClick = { onProject(null); projectsOpen = false })
                projects.forEach { item ->
                    DropdownMenuItem(text = { Text(item.title) }, leadingIcon = { ProjectTile(item.title, size = 24.dp, key = item.id) },
                        onClick = { onProject(item.id); projectsOpen = false })
                }
            }
        }
        TfChip("Нужен ответ", { onFilter(if (filter == ThreadFilter.NeedsYou) ThreadFilter.All else ThreadFilter.NeedsYou) }, selected = filter == ThreadFilter.NeedsYou,
            count = counts[ThreadFilter.NeedsYou], leading = { StatusDot(c.amber) }, dense = tabletChrome)
        TfChip("Работает", { onFilter(if (filter == ThreadFilter.Working) ThreadFilter.All else ThreadFilter.Working) }, selected = filter == ThreadFilter.Working,
            count = counts[ThreadFilter.Working], leading = { StatusDot(c.blue) }, dense = tabletChrome)
    }
}

private val ChipFadeWidth = 16.dp

/* ---------- Project groups ---------- */

/**
 * A project's heading in the grouped list: its tile, name, the state counts it holds, and the disclosure chevron.
 *
 * A project whose threads need you gets a 2dp amber underline and an amber count; the underline is the only decoration
 * so a list of many projects does not turn into a traffic light. Counts are omitted when zero, which keeps long names
 * from truncating on a 360dp phone.
 */
@Composable
internal fun ProjectHeader(
    group: ProjectGroup,
    open: Boolean,
    icon: ProjectIcon?,
    color: ProjectColor?,
    pinned: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    onTogglePin: () -> Unit = {},
) {
    val c = Tf.colors
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "projectFold")
    val needsYou = group.needsYou > 0
    val interactive = remember { MutableInteractionSource() }
    var menu by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth()
            .semantics {
                contentDescription = "Проект ${group.title}"
                stateDescription = if (open) "Развёрнуто" else "Свёрнуто"
                heading()
            }
            .focusRing().handPointer()
            .combinedClickable(
                interactionSource = interactive,
                indication = LocalIndication.current,
                onClickLabel = if (open) "Свернуть проект" else "Развернуть проект",
                onLongClickLabel = "Вид проекта",
                onLongClick = onLongPress,
                onClick = onToggle,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProjectTile(group.title, size = 26.dp, key = group.key, icon = icon, color = color)
            Text(
                group.title, Modifier.weight(1f, fill = false),
                fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = if (group.project == null) c.muted else c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (group.needsYou > 0) ProjectCount(group.needsYou, c.amber)
                if (group.working > 0) ProjectCount(group.working, c.blue)
            }
            Spacer(Modifier.weight(1f))
            if (pinned) Icon(TfIcons.StarFilled, "Закреплённый проект", Modifier.size(13.dp), tint = c.muted.copy(alpha = .8f))
            Text(group.threads.size.toString(), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.muted.copy(alpha = .8f))
            Box {
                TfIconButton(TfIcons.More, "Меню проекта", { menu = true }, iconSize = 16.dp, tint = c.muted)
                ProjectMenu(open, pinned, menu, { menu = it }, onToggle, onTogglePin, onLongPress)
            }
        }
        if (needsYou) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp).fillMaxWidth().height(2.dp)
                .background(c.amber, RoundedCornerShape(1.dp)))
        }
    }
}

/** One state count beside a project name: a dot plus its number, coloured by the state it counts. */
@Composable
private fun ProjectCount(count: Int, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        StatusDot(tone)
        Text(count.toString(), fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = tone)
    }
}

/** Per-project actions. Split out so a preview can render the items without a popup window. */
@Composable
internal fun ProjectMenu(
    open: Boolean,
    pinned: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onAppearance: () -> Unit,
) {
    DropdownMenu(expanded, { onExpandedChange(false) }) {
        DropdownMenuItem(text = { Text(if (open) "Свернуть проект" else "Развернуть проект") },
            onClick = { onExpandedChange(false); onToggle() })
        DropdownMenuItem(text = { Text(if (pinned) "Открепить проект" else "Закрепить проект") },
            leadingIcon = { Icon(if (pinned) TfIcons.StarFilled else TfIcons.Star, null, Modifier.size(18.dp)) },
            onClick = { onExpandedChange(false); onTogglePin() })
        DropdownMenuItem(text = { Text("Иконка и цвет") },
            leadingIcon = { Icon(TfIcons.Edit, null, Modifier.size(18.dp)) },
            onClick = { onExpandedChange(false); onAppearance() })
    }
}

/* ---------- Sections ---------- */

/** Uppercase section label; tapping folds the section. Count + chevron only appear while folded. */
@Composable
internal fun ShelfLabel(title: String, count: Int, open: Boolean, dot: Color?, toggle: () -> Unit) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().focusRing().handPointer().clickable(onClick = toggle).semantics { contentDescription = "Раздел $title"; stateDescription = if (open) "Развёрнуто" else "Свёрнуто"; heading() }
        .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f), dot = dot)
        if (!open) {
            Text(count.toString(), style = TrefoilType.section, color = c.muted)
            Icon(TfIcons.ChevronRight, null, Modifier.padding(start = 4.dp).size(16.dp), tint = c.muted)
        }
    }
}

/** Settled / Archived: one quiet row with a count that expands in place. */
@Composable
internal fun FoldedShelfRow(title: String, count: Int, open: Boolean, toggle: () -> Unit) {
    val c = Tf.colors
    val angle by animateFloatAsState(if (open) 90f else 0f, label = "fold")
    Row(Modifier.fillMaxWidth().focusRing().handPointer().clickable(onClick = toggle).semantics { contentDescription = "Раздел $title"; stateDescription = if (open) "Развёрнуто" else "Свёрнуто" }
        .padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(TfIcons.Settled, null, Modifier.size(18.dp), tint = c.muted)
        Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.muted)
        Text(count.toString(), fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = c.muted.copy(alpha = .72f))
        Spacer(Modifier.weight(1f))
        Icon(TfIcons.ChevronRight, null, Modifier.size(18.dp).rotate(angle), tint = c.muted)
    }
}

/* ---------- Rows ---------- */

/**
 * What the right side of a row shows: spinner for work, a muted spinner while the agent waits on work it started
 * (subagents, awaited background tasks, another thread), dots for done/error, otherwise just time.
 */
internal enum class RowMark { None, Working, Waiting, Done, Error }

/**
 * Work the thread still waits on although its own turn may have finished: T3 parks such a thread at "Waiting"
 * (never Completed) while background work holds completion or the run is blocked on another thread.
 */
internal fun threadAwaitsWork(thread: ThreadSummary): Boolean =
    thread.completionHeldBackgroundWork && thread.status != "failed" ||
        thread.blockedOnThreadWait == true && (thread.activityRunStatus ?: thread.status).lowercase(java.util.Locale.ROOT) in
            setOf("preparing", "queued", "starting", "running", "waiting", "working", "streaming")

internal fun threadRowMark(thread: ThreadSummary): RowMark = when (threadIndicator(thread).kind) {
    "working" -> RowMark.Working
    "error" -> RowMark.Error
    "waiting" -> if (threadAwaitsWork(thread)) RowMark.Waiting else doneMark(thread)
    "ready" -> doneMark(thread)
    else -> RowMark.None
}
private fun doneMark(thread: ThreadSummary) =
    if (thread.latestRunCompletedAt != null || thread.status.equals("completed", ignoreCase = true)) RowMark.Done else RowMark.None

/** Right-aligned row time: work duration while working, otherwise time since the last update. */
internal fun threadRowTime(thread: ThreadSummary, now: Long): String {
    if (threadIndicator(thread).kind == "working") threadWorkStartedAt(thread)?.let(::validThreadInstant)?.let { return formatWorkDuration(now - it) }
    return relativeThreadTime(thread.updatedAt ?: thread.latestRunCompletedAt ?: thread.createdAt, now)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThreadRow(state: ClientState, thread: ThreadSummary, project: Project?, now: Long, selected: Boolean,
                       onAction: (ThreadTarget, ThreadAction, String?) -> Unit, replyDraft: ComposerDraft? = null, tabletChrome: Boolean = false,
                       showProject: Boolean = true, select: () -> Unit) {
    val c = Tf.colors
    val indicator = threadIndicator(thread)
    val mark = threadRowMark(thread)
    val title = thread.title.ifBlank { "Без названия" }
    val compact = LocalCompactHeight.current
    var menu by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(RowMenuOffset) }
    val hasMenu = state.environment != null
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val density = LocalDensity.current
    fun openMenu(offset: DpOffset = RowMenuOffset) { menuOffset = offset; menu = true }
    Box {
        // Selected and hovered rows are a 12dp container inset 8dp from the list edges; the content keeps its 20dp inset.
        Box(Modifier.fillMaxWidth()
            .semantics {
                this.selected = selected
                if (hasMenu) customActions = listOf(CustomAccessibilityAction("Действия для ${thread.title}") { openMenu(); true })
            }
            .hoverable(hover)
            .then(if (hasMenu) Modifier.pointerInput(density) { openMenuOnSecondaryPress(density) { offset -> openMenu(offset) } } else Modifier)
            .focusRing().handPointer().combinedClickable(interactionSource = hover, indication = LocalIndication.current, onClickLabel = "Открыть тред", onLongClickLabel = if (hasMenu) "Действия с тредом" else null,
                onLongClick = if (hasMenu) ({ openMenu() }) else null, onClick = select)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .background(when { selected -> c.s2; hovered -> c.s2.copy(alpha = .55f); else -> Color.Transparent }, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                ProjectTile(project?.title ?: "?", size = if (compact) 32.dp else 40.dp, key = project?.id ?: thread.projectId, capFontScale = tabletChrome)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, Modifier.weight(1f), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        RowTime(thread, indicator, mark, now)
                    }
                    val preview = thread.latestMessage.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    val needs = indicator.kind in setOf("attention", "input")
                    // An unsent reply replaces the latest message unless the thread is open (its composer shows the text).
                    val draftLine = replyDraft?.takeIf { !selected }?.let(::draftFirstLine)
                    when {
                        draftLine != null && needs -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(indicator.label, style = TrefoilType.preview, color = c.amber, maxLines = 1, softWrap = false)
                            Text(" · ", style = TrefoilType.preview, color = c.muted, maxLines = 1)
                            DraftPreview(draftLine, Modifier.weight(1f, fill = false))
                        }
                        draftLine != null -> DraftPreview(draftLine)
                        // A waiting thread always names what it wants; the label keeps its width and the preview gives way.
                        needs -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(indicator.label, style = TrefoilType.preview, color = c.amber, maxLines = 1, softWrap = false)
                            if (preview != null) Text(" · $preview", Modifier.weight(1f, fill = false), style = TrefoilType.preview, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        preview != null -> Text(preview, style = TrefoilType.preview, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        indicator.kind == "error" -> Text(indicator.label, style = TrefoilType.preview, color = c.red, maxLines = 1)
                    }
                    // Inside a project group the project name is already the heading, so the meta line keeps only the branch.
                    if (!compact) Row(Modifier.padding(top = 6.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (showProject) Text(project?.title ?: "Проект", style = TrefoilType.meta, color = c.muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                            thread.branch?.takeIf { it.isNotBlank() }?.let { branch ->
                                Icon(TfIcons.Branch, null, Modifier.size(14.dp), tint = c.muted)
                                Text(branch, Modifier.weight(1f, fill = false), style = TrefoilType.mono, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        ThreadProviderStack(threadProviders(thread, state.providers), state.providers)
                    }
                }
            }
            // Round-3a item 15: the bar sits beside the project tile (row padding 12dp + tile 40dp or 32dp), not at the row's vertical centre.
            if (selected) {
                val bar = tileBarTop(compact)
                Box(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = bar.top).size(width = 3.dp, height = bar.height)
                    .background(c.accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
            }
        }
        if (hasMenu) ThreadActionsDropdown(state, thread, onAction, menu, { menu = it }, offset = menuOffset)
    }
}

internal data class SelectedBar(val top: Dp, val height: Dp)

/** Selected-row accent bar: anchored to the project tile (tile top 12dp, or 8dp compact, plus centring on the tile). */
internal fun tileBarTop(compact: Boolean) = if (compact) SelectedBar(14.dp, 20.dp) else SelectedBar(20.dp, 24.dp)

/** Where the actions menu opens for a long-press or an accessibility action: below the row, near its text. */
private val RowMenuOffset = DpOffset(74.dp, 0.dp)

/**
 * Calls [open] with the pointer position when a secondary (right) mouse button is pressed, and consumes that press so the
 * row is not also opened. The offset is relative to the row's bottom-start corner, which is where the dropdown anchors.
 */
private suspend fun PointerInputScope.openMenuOnSecondaryPress(density: Density, open: (DpOffset) -> Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                val at = event.changes.first().position
                open(with(density) { DpOffset(at.x.toDp(), (at.y - size.height).toDp()) })
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/** First non-blank line of a draft's text, trimmed; null when there is none. */
internal fun draftFirstLine(draft: ComposerDraft): String? = draft.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

/** Pencil plus "Draft: <first line>" in the accent colour. */
@Composable
internal fun DraftPreview(firstLine: String, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Черновик: $firstLine" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(TfIcons.Edit, null, Modifier.size(13.dp), tint = c.accent)
        Text("Черновик: $firstLine", style = TrefoilType.preview, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Relative age of a draft edited at [updatedAtMillis], in the same units as the thread rows. */
internal fun relativeDraftTime(updatedAtMillis: Long, now: Long): String = relativeThreadTime(java.time.Instant.ofEpochMilli(updatedAtMillis).toString(), now)

/** One unsent new-thread prompt: pencil, project tile, first line, "Draft" label and age. Tap reopens the sheet; the trailing button discards. */
@Composable
internal fun DraftRow(draft: ComposerDraft, project: Project, now: Long, onOpen: () -> Unit, tabletChrome: Boolean = false, onDiscard: () -> Unit) {
    val c = Tf.colors
    val title = draftFirstLine(draft) ?: "Черновик без названия"
    Row(Modifier.fillMaxWidth().focusRing().handPointer().clickable(onClickLabel = "Открыть черновик", onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = "Черновик в ${project.title}: $title" }
        .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        ProjectTile(project.title, size = 40.dp, key = project.id, capFontScale = tabletChrome)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(TfIcons.Edit, null, Modifier.size(15.dp), tint = c.accent)
                Text(title, Modifier.weight(1f), style = TrefoilType.rowTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val time = relativeDraftTime(draft.updatedAtMillis, now)
                if (time.isNotEmpty()) Text(time, style = TrefoilType.meta, color = c.muted, maxLines = 1)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Черновик", style = TrefoilType.preview, color = c.accent, maxLines = 1, softWrap = false)
                Text("· ${project.title}", Modifier.weight(1f, fill = false), style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TfIconButton(TfIcons.Close, "Удалить черновик", onDiscard, Modifier.size(40.dp), iconSize = 18.dp, tint = c.muted)
    }
}

@Composable
private fun RowTime(thread: ThreadSummary, indicator: ThreadIndicator, mark: RowMark, now: Long) {
    val c = Tf.colors
    val time = threadRowTime(thread, now)
    val status = if (indicator.kind == "ready") "" else threadStatusLabel(thread, now)
    val description = when { status.isBlank() -> time; time.isBlank() || status.endsWith(time) -> status; else -> "$status, $time" }
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (mark) {
            RowMark.Working -> WorkingSpinner(size = 16.dp)
            RowMark.Waiting -> WorkingSpinner(size = 16.dp, color = c.muted)
            RowMark.Done -> StatusDot(c.accent)
            RowMark.Error -> StatusDot(c.red)
            RowMark.None -> {}
        }
        if (time.isNotEmpty()) Text(time, style = TrefoilType.meta, color = if (mark == RowMark.Working) c.blue else c.muted, maxLines = 1)
    }
}

/* ---------- New + empty ---------- */

@Composable
internal fun NewThreadFab(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val shape = CircleShape
    // The caller's modifier (align, snackbar offset) stays on a wrapper: TooltipBox does not pass parent data through.
    Box(modifier) {
        TfTooltip(tooltipWithShortcut("Новый тред", ShortcutId.NewThread)) {
            Surface(onClick = onClick, enabled = enabled, shape = shape, color = if (enabled) c.accent else c.s2, contentColor = if (enabled) c.onAccent else c.muted,
                modifier = Modifier.shadow(if (enabled) 8.dp else 0.dp, shape).focusRing(shape).handPointer(enabled).semantics { contentDescription = "Новый тред"; role = Role.Button }) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) { Icon(TfIcons.Plus, null, Modifier.size(24.dp)) }
            }
        }
    }
}

@Composable
internal fun ThreadListLoading(rows: Int = 6) {
    val c = Tf.colors
    val bar = RoundedCornerShape(4.dp)
    // Static placeholders shaped like ThreadRow; the list header's progress bar shows the activity.
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).semantics(mergeDescendants = true) { contentDescription = "Загрузка тредов" }) {
        repeat(rows) { index ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(40.dp).background(c.s2, RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.fillMaxWidth(if (index % 2 == 0) .62f else .48f).height(12.dp).background(c.s2, bar))
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.width(28.dp).height(10.dp).background(c.s2, bar))
                    }
                    Box(Modifier.fillMaxWidth(if (index % 3 == 1) .7f else .9f).height(10.dp).background(c.s2.copy(alpha = .6f), bar))
                }
            }
        }
    }
}

@Composable
internal fun ThreadListEmpty(searching: Boolean) {
    val c = Tf.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (searching) Icon(TfIcons.Search, null, Modifier.size(32.dp), tint = c.muted)
        else TrefoilMark(Modifier.size(40.dp), color = c.muted.copy(alpha = .6f))
        Text(if (searching) "Ничего не найдено" else "Здесь начнётся ваша следующая идея", Modifier.padding(top = 16.dp), style = TrefoilType.rowTitle, color = c.text, textAlign = TextAlign.Center)
        Text(if (searching) "Попробуйте другое название, проект или фильтр." else "Начните тред в одном из своих проектов.", Modifier.padding(top = 6.dp),
            style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
    }
}

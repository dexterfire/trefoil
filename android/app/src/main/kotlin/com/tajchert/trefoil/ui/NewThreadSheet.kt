package com.tajchert.trefoil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.*

/** Modes the model supports; an empty or missing list means every mode is allowed. */
internal fun allowedRuntimeModes(model: ModelOption?): List<RuntimeMode> =
    model?.supportedRuntimeModes?.takeIf { it.isNotEmpty() }?.let { supported -> RuntimeMode.entries.filter { it in supported } }?.takeIf { it.isNotEmpty() } ?: RuntimeMode.entries.toList()

/** Filled bars (1..4) for choice [index] of [count] selectable choices. */
/** Desktop-matching glyph for a runtime mode. */
internal val RuntimeMode.icon: ImageVector get() = when (this) {
    RuntimeMode.ApprovalRequired -> TfIcons.Lock
    RuntimeMode.AutoAcceptEdits -> TfIcons.PenLine
    RuntimeMode.Auto -> TfIcons.ShieldCheck
    RuntimeMode.FullAccess -> TfIcons.ShieldOff
}

internal fun effortBarCount(index: Int, count: Int): Int = if (count <= 1 || index < 0) 4 else 1 + Math.round(index * 3f / (count - 1))

/** Keeps [preferred] when allowed, otherwise falls back to the first allowed mode. */
internal fun resolveRuntimeMode(preferred: RuntimeMode?, allowed: List<RuntimeMode>): RuntimeMode =
    preferred?.takeIf { it in allowed } ?: allowed.first()

/**
 * New thread: pick project, model and mode, optionally type the first prompt.
 * [start] receives the draft and the prompt (blank when none); the shell starts the draft and
 * hands the prompt to the conversation, which sends it through its normal composer path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewThreadSheet(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit) {
    val c = Tf.colors
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) { NewThreadSheetContent(state, dismiss, start, Modifier.imePadding().navigationBarsPadding()) }
}

/** Sheet body, separate from the [ModalBottomSheet] chrome so it renders and tests standalone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewThreadSheetContent(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit, modifier: Modifier = Modifier, initiallyCollapsed: Boolean? = null) {
    val c = Tf.colors
    var projectId by rememberSaveable { mutableStateOf(state.projects.firstOrNull()?.id.orEmpty()) }
    var modelIndex by rememberSaveable { mutableIntStateOf(0) }
    var chosenMode by rememberSaveable { mutableStateOf((state.defaultRuntimeMode ?: RuntimeMode.ApprovalRequired).wire) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pickerCollapsed by rememberSaveable { mutableStateOf(initiallyCollapsed ?: (state.projects.size == 1)) }
    val selectedProject = state.projects.firstOrNull { it.id == projectId }
    val collapsed = pickerCollapsed && selectedProject != null
    var userExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.projects) {
        if (state.projects.none { it.id == projectId }) { projectId = state.projects.firstOrNull()?.id.orEmpty(); pickerCollapsed = false }
        if (state.projects.size == 1 && !userExpanded && initiallyCollapsed == null) pickerCollapsed = true
    }
    fun collapsePicker() { pickerCollapsed = true; searching = false; query = "" }
    var chosenEffort by rememberSaveable { mutableStateOf<String?>(null) }
    val model = state.models.getOrNull(modelIndex)
    val reasoning = model?.reasoning?.takeIf { it.selectableChoices.isNotEmpty() }
    val explicitOptions: ModelOptionValues = if (reasoning != null && chosenEffort != null) model.validOptions(mapOf(reasoning.id to chosenEffort!!)) else emptyMap()
    LaunchedEffect(model, reasoning) { if (chosenEffort != null && explicitOptions.isEmpty()) chosenEffort = null }
    val allowedModes = allowedRuntimeModes(model)
    val mode = resolveRuntimeMode(RuntimeMode.fromWire(chosenMode), allowedModes)
    val canStart = state.canLaunchThreads && !state.busy && selectedProject != null && model != null

    val filtered = state.projects.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
    val limit = 8
    val shown = when {
        expanded || searching || filtered.size <= limit -> filtered
        else -> {
            val head = filtered.take(limit)
            val selected = filtered.firstOrNull { it.id == projectId }
            if (selected == null || selected in head) head else filtered.take(limit - 1) + selected
        }
    }
    val hidden = filtered.size - shown.size

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New thread", style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = dismiss, Modifier.size(48.dp)) { Icon(TfIcons.Close, "Close", tint = c.text) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROJECT", style = TrefoilType.section, color = c.muted, modifier = Modifier.weight(1f))
            if (state.projects.size > 1 && !collapsed) IconButton(onClick = { searching = !searching; if (!searching) query = "" }, Modifier.size(40.dp)) {
                Icon(TfIcons.Search, "Search projects", Modifier.size(18.dp), tint = if (searching) c.accent else c.text)
            }
        }
        AnimatedContent(
            selectedProject.takeIf { collapsed }, label = "projectPicker",
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) },
        ) { target ->
            if (target != null) SelectedProjectPill(target) { pickerCollapsed = false; userExpanded = true }
            else Column {
                if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search projects") }, shape = TfFieldShape, colors = tfFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { filtered.singleOrNull()?.let { projectId = it.id; collapsePicker() } }), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp))
                if (state.projects.isEmpty()) Text("No projects reported by this environment.", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { project -> ProjectChip(project, project.id == projectId) { projectId = project.id; collapsePicker() } }
                    if (hidden > 0) Box(
                        Modifier.height(40.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button, onClick = { expanded = true }).padding(horizontal = 14.dp)
                            .semantics { contentDescription = "Show $hidden more projects" },
                        contentAlignment = Alignment.Center,
                    ) { Text("+$hidden", style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.muted) }
                }
            }
        }

        Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(24.dp))) {
            TextField(
                prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp).onFocusChanged { if (it.isFocused && selectedProject != null) collapsePicker() }, minLines = 5, maxLines = 10, enabled = !state.busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
                placeholder = { Text("What should the agent do?", fontSize = 17.sp, lineHeight = 24.sp) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                    cursorColor = c.accent, focusedPlaceholderColor = c.muted, unfocusedPlaceholderColor = c.muted,
                ),
            )
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                FilledIconButton(
                    onClick = { model?.let { start(ThreadDraft(projectId, it, mode, modelOptions = it.validOptions(explicitOptions)), prompt) } }, enabled = canStart, modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.accent.copy(alpha = .35f), disabledContentColor = c.onAccent.copy(alpha = .7f)),
                ) { Icon(TfIcons.Send, "Start") }
            }
        }

        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var modelMenu by remember { mutableStateOf(false) }
            var effortMenu by remember { mutableStateOf(false) }
            var modeMenu by remember { mutableStateOf(false) }
            Box {
                SheetPill(model?.label ?: "No models", TfIcons.Sparkle, "Model: ${model?.label ?: "not set"}", enabled = state.models.isNotEmpty()) { modelMenu = true }
                DropdownMenu(modelMenu, { modelMenu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                    state.models.forEachIndexed { index, option ->
                        DropdownMenuItem(
                            text = { Column { Text(option.label); Text(option.instanceId, style = TrefoilType.mono, color = c.muted) } },
                            trailingIcon = { if (index == modelIndex) Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent) },
                            onClick = { modelIndex = index; modelMenu = false })
                    }
                }
            }
            if (model != null && reasoning != null) {
                val choices = reasoning.selectableChoices
                val current = reasoningChoice(model, explicitOptions)
                val currentIndex = choices.indexOfFirst { it.id == current?.id }
                val currentLabel = current?.label ?: "Default"
                Box {
                    SheetPill(null, TfIcons.effort(effortBarCount(currentIndex, choices.size)), "${reasoning.label.ifBlank { "Reasoning" }}: $currentLabel") { effortMenu = true }
                    DropdownMenu(effortMenu, { effortMenu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                        choices.forEachIndexed { index, option ->
                            DropdownMenuItem(
                                text = { Column { Text(option.label); option.description?.let { Text(it, style = TrefoilType.meta, color = c.muted) } } },
                                leadingIcon = { Icon(TfIcons.effort(effortBarCount(index, choices.size)), null, Modifier.size(18.dp), tint = c.muted) },
                                trailingIcon = { if (option.id == current?.id) Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent) },
                                onClick = { chosenEffort = option.id; effortMenu = false })
                        }
                    }
                }
            }
            Box {
                SheetPill(null, mode.icon, "Access: ${mode.label}") { modeMenu = true }
                DropdownMenu(modeMenu, { modeMenu = false }, containerColor = c.sheet, shape = RoundedCornerShape(16.dp)) {
                    allowedModes.forEach { option ->
                        DropdownMenuItem(
                            text = { Column { Text(option.label); Text(option.description, style = TrefoilType.meta, color = c.muted) } },
                            leadingIcon = { Icon(option.icon, null, Modifier.size(18.dp), tint = c.muted) },
                            trailingIcon = { if (option == mode) Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent) },
                            onClick = { chosenMode = option.wire; modeMenu = false })
                    }
                }
            }
        }
        if (state.models.isEmpty()) Text("No models reported by this environment. Reconnect to refresh configuration.", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
        Text("Workspace uses the server default.", style = TrefoilType.meta, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 10.dp))
    }
}

@Composable
private fun SelectedProjectPill(project: Project, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.padding(top = 6.dp).height(40.dp).widthIn(max = 320.dp).clip(shape).border(1.dp, c.line, shape)
            .clickable(onClickLabel = "Change project", role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "Project: ${project.title}" }
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectTile(project.title, size = 24.dp, key = project.id)
        Text(project.title, style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(TfIcons.ChevronDown, null, Modifier.size(14.dp), tint = c.muted)
    }
}

@Composable
private fun ProjectChip(project: Project, selected: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.height(40.dp).widthIn(max = 280.dp).clip(shape).background(if (selected) c.accentSoft else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProjectTile(project.title, size = 24.dp, key = project.id)
        Text(project.title, style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SheetPill(label: String?, icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.height(36.dp).clip(shape).background(c.s2, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }.padding(horizontal = if (label == null) 10.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (label == null) 4.dp else 6.dp),
    ) {
        Icon(icon, null, Modifier.size(if (label == null) 18.dp else 14.dp), tint = if (enabled) c.text else c.muted)
        if (label != null) Text(label, style = TrefoilType.meta, color = if (enabled) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(TfIcons.ChevronDown, null, Modifier.size(14.dp), tint = c.muted)
    }
}

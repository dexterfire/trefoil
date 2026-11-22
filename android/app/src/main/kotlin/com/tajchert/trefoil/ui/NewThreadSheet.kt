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
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftDiscard
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.core.session.hasContent

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
 * The local, unsent composer drafts as the UI sees them: [drafts] holds the new-thread drafts (settings-only ones
 * too) and reply drafts reduced to their first line, empty until [loaded]; [get] returns a full stored draft.
 * Editors write nothing before [loaded]. The callbacks write through to the view model's store.
 */
class ComposerDraftUi(
    val drafts: Map<ComposerDraftKey, ComposerDraft> = emptyMap(),
    val get: (ComposerDraftKey) -> ComposerDraft? = { drafts[it] },
    val update: (key: ComposerDraftKey, text: String, modelInstanceId: String?, model: String?, runtimeMode: String?, effort: String?, fastMode: Boolean?) -> Unit = { _, _, _, _, _, _, _ -> },
    val clear: (ComposerDraftKey) -> Unit = {},
    val discard: (ComposerDraftKey) -> ComposerDraftDiscard? = { null },
    val restore: (ComposerDraftDiscard) -> Unit = {},
    /** False (nothing changed) when the target already holds a draft; see `ComposerDraftStore.move`. */
    val move: (from: ComposerDraftKey, to: ComposerDraftKey, keepSource: Boolean) -> Boolean = { _, _, _ -> false },
    val updateText: (ComposerDraftKey, String) -> Unit = { _, _ -> },
    val loaded: Boolean = true,
)

/** The most recently edited new-thread draft with text for [environmentId] whose project still exists. */
internal fun recentNewThreadDraft(drafts: Map<ComposerDraftKey, ComposerDraft>, environmentId: String?, projects: List<Project>): ComposerDraft? =
    drafts.values.filter { it.key is ComposerDraftKey.NewThread && it.key.environmentId == environmentId && it.hasContent() && projects.any { p -> p.id == (it.key as ComposerDraftKey.NewThread).projectId } }
        .maxByOrNull { it.updatedAtMillis }

private fun activityMillis(value: String?): Long? = value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

/** A thread's activity time, as T3's `updated_at` sidebar order reads it: latest user message, else updated or created. */
private fun ThreadSummary.activityMillis(): Long? = activityMillis(latestUserMessageAt) ?: activityMillis(updatedAt) ?: activityMillis(createdAt)

/** Each project's newest thread activity (epoch millis); projects without dated threads are absent. */
internal fun projectActivity(threads: List<ThreadSummary>): Map<String, Long> =
    threads.asSequence().filter { it.deletedAt == null }.mapNotNull { t -> t.activityMillis()?.let { t.projectId to it } }
        .groupingBy { it.first }.fold(Long.MIN_VALUE) { latest, (_, time) -> maxOf(latest, time) }

/** Most recently active first (T3 sidebar `updated_at`); projects without activity follow alphabetically. */
internal fun projectsByRecentActivity(projects: List<Project>, activity: Map<String, Long>): List<Project> =
    projects.sortedWith(compareByDescending<Project> { activity[it.id] ?: Long.MIN_VALUE }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })

/**
 * Projects matching every whitespace-separated term of [query] in their title or path, keeping [projects]' order within
 * three ranks: title starts with the query, title contains every term, then the rest (path matches).
 */
internal fun searchProjects(projects: List<Project>, query: String): List<Project> {
    val q = query.trim()
    if (q.isEmpty()) return projects
    val terms = q.split(Regex("\\s+"))
    return projects.filter { p -> terms.all { p.title.contains(it, ignoreCase = true) || p.path.contains(it, ignoreCase = true) } }
        .sortedBy { p -> when { p.title.startsWith(q, ignoreCase = true) -> 0; terms.all { p.title.contains(it, ignoreCase = true) } -> 1; else -> 2 } }
}

/** The last two segments of a workspace path (`priv/trefoil`), enough to tell same-named projects apart. */
internal fun shortProjectPath(path: String): String = path.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

/** Projects listed before "Show all"; search always lists every match. */
internal const val RECENT_PROJECT_LIMIT = 5

/**
 * New thread: pick project, model and mode, optionally type the first prompt.
 * [start] receives the draft and the prompt (blank when none); the shell starts the draft and
 * hands the prompt to the conversation, which sends it through its normal composer path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewThreadSheet(state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit, composerDrafts: ComposerDraftUi = ComposerDraftUi(), openProjectId: String? = null, onSetModelFavorite: (ModelKey, Boolean) -> Unit = { _, _ -> }) {
    val c = Tf.colors
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) { NewThreadSheetContent(state, dismiss, start, Modifier.imePadding().navigationBarsPadding(), composerDrafts = composerDrafts, openProjectId = openProjectId, onSetModelFavorite = onSetModelFavorite) }
}

/** Sheet body, separate from the [ModalBottomSheet] chrome so it renders and tests standalone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewThreadSheetContent(
    state: ClientState, dismiss: () -> Unit, start: (ThreadDraft, String) -> Unit, modifier: Modifier = Modifier, initiallyCollapsed: Boolean? = null,
    composerDrafts: ComposerDraftUi = ComposerDraftUi(), openProjectId: String? = null, onSetModelFavorite: (ModelKey, Boolean) -> Unit = { _, _ -> },
) {
    val c = Tf.colors
    val environmentId = state.environment?.id
    // A project from a draft row wins; from "+" the most recent draft's project is reopened. Drafts load asynchronously, so
    // the effect below can still adopt one that arrives later while nothing was touched.
    var projectId by rememberSaveable {
        mutableStateOf(openProjectId?.takeIf { id -> state.projects.any { it.id == id } }
            ?: recentNewThreadDraft(composerDrafts.drafts, environmentId, state.projects)?.let { (it.key as ComposerDraftKey.NewThread).projectId }
            ?: projectsByRecentActivity(state.projects, projectActivity(state.threads)).firstOrNull()?.id.orEmpty())
    }
    // Model and access stay unset (-1 / null) until the user or a stored draft picks one: the project's T3 defaults apply.
    var modelIndex by rememberSaveable { mutableIntStateOf(-1) }
    var chosenMode by rememberSaveable { mutableStateOf<String?>(null) }
    var prompt by rememberSaveable { mutableStateOf(composerDrafts.get(ComposerDraftKey.NewThread(environmentId.orEmpty(), projectId))?.text.orEmpty()) }
    // True once the user changed text, project or settings: a draft that loads later must not overwrite that.
    var touched by rememberSaveable { mutableStateOf(false) }
    // Projects whose draft this sheet session created: only those may be moved away from; older drafts stay put.
    var createdHere by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Nothing is editable before the stored drafts load, so no edit can replace a stored prompt or settings.
    val loaded = composerDrafts.loaded
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pickerCollapsed by rememberSaveable { mutableStateOf(initiallyCollapsed ?: (state.projects.size == 1)) }
    val selectedProject = state.projects.firstOrNull { it.id == projectId }
    val collapsed = pickerCollapsed && selectedProject != null
    var userExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.projects) {
        if (state.projects.none { it.id == projectId }) { projectId = projectsByRecentActivity(state.projects, projectActivity(state.threads)).firstOrNull()?.id.orEmpty(); pickerCollapsed = false }
        if (state.projects.size == 1 && !userExpanded && initiallyCollapsed == null) pickerCollapsed = true
    }
    fun collapsePicker() { pickerCollapsed = true; expanded = false; query = "" }
    var chosenEffort by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenFast by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val defaults = state.newThreadSettings(projectId.ifEmpty { null })
    val model = state.models.getOrNull(modelIndex) ?: defaults.model
    val reasoning = model?.reasoning?.takeIf { it.selectableChoices.isNotEmpty() }
    // The configured default's options (effort, Fast mode, ...) ride along while its model is used; an effort or Fast
    // mode the user picked replaces the default's.
    val baseOptions = if (model != null && model.sameModelAs(defaults.model)) defaults.options else emptyMap()
    val explicitOptions: ModelOptionValues = model?.validOptions(baseOptions + listOfNotNull(
        reasoning?.id?.let { id -> chosenEffort?.let { id to it } },
        chosenFast?.let { FAST_MODE_OPTION_ID to it },
    )).orEmpty()
    LaunchedEffect(model, reasoning) { if (chosenEffort != null && (reasoning == null || model.validOptions(mapOf(reasoning.id to chosenEffort!!)).isEmpty())) chosenEffort = null }
    LaunchedEffect(model) { if (chosenFast != null && (model == null || model.validOptions(mapOf(FAST_MODE_OPTION_ID to chosenFast!!)).isEmpty())) chosenFast = null }
    // Seed from the stored draft: it may arrive after the sheet opens (the store loads asynchronously) and is applied
    // only while the user has not touched anything.
    LaunchedEffect(composerDrafts.drafts, loaded, state.models, state.projects, environmentId) {
        if (touched || environmentId == null || !loaded) return@LaunchedEffect
        if (openProjectId == null) recentNewThreadDraft(composerDrafts.drafts, environmentId, state.projects)?.let { recent ->
            val recentProject = (recent.key as ComposerDraftKey.NewThread).projectId
            if (recentProject != projectId && prompt.isBlank() && composerDrafts.get(ComposerDraftKey.NewThread(environmentId, projectId))?.hasContent() != true) projectId = recentProject
        }
        val saved = composerDrafts.get(ComposerDraftKey.NewThread(environmentId, projectId)) ?: return@LaunchedEffect
        if (prompt.isBlank() && saved.text.isNotBlank()) prompt = saved.text
        state.models.indexOfFirst { it.instanceId == saved.modelInstanceId && it.model == saved.model }.takeIf { it >= 0 }?.let { modelIndex = it }
        saved.runtimeMode?.let { chosenMode = it }
        saved.effort?.let { chosenEffort = it }
        saved.fastMode?.let { chosenFast = it }
    }
    val allowedModes = allowedRuntimeModes(model)
    val mode = resolveRuntimeMode(RuntimeMode.fromWire(chosenMode) ?: defaults.runtimeMode, allowedModes)
    // Write-through: every user change goes to the store (it debounces); initial composition never writes, so it cannot
    // overwrite a draft that has not loaded yet.
    fun persist(text: String = prompt, index: Int = modelIndex, modeWire: String = chosenMode ?: mode.wire, effort: String? = chosenEffort, fast: Boolean? = chosenFast, id: String = projectId) {
        val env = environmentId ?: return
        if (id.isEmpty() || !loaded) return
        val chosen = state.models.getOrNull(index) ?: state.newThreadSettings(id).model
        if (composerDrafts.get(ComposerDraftKey.NewThread(env, id)) == null && id !in createdHere) createdHere = createdHere + id
        composerDrafts.update(ComposerDraftKey.NewThread(env, id), text, chosen?.instanceId, chosen?.model, modeWire.takeIf { chosen != null }, effort.takeIf { chosen != null }, fast.takeIf { chosen != null })
    }
    /**
     * Switching project keeps the typed prompt. A draft already stored for the target is never overwritten: one with
     * text is opened instead (this one stays where it is); a settings-only one takes the prompt and keeps its settings.
     * Otherwise the draft moves, or is copied when it existed before this sheet opened or has a pending launch.
     */
    fun pickProject(id: String) {
        if (id == projectId || !loaded) return
        touched = true
        val env = environmentId
        if (env == null) { projectId = id; return }
        val from = ComposerDraftKey.NewThread(env, projectId)
        val target = composerDrafts.get(ComposerDraftKey.NewThread(env, id))
        if (target != null) {
            projectId = id
            if (target.hasContent()) prompt = target.text
            state.models.indexOfFirst { it.instanceId == target.modelInstanceId && it.model == target.model }.takeIf { it >= 0 }?.let { modelIndex = it }
            target.runtimeMode?.let { chosenMode = it }
            chosenEffort = target.effort
            chosenFast = target.fastMode
            if (!target.hasContent() && prompt.isNotBlank()) {
                persist()
                val source = composerDrafts.get(from)
                if (from.projectId in createdHere && source?.pendingLaunch == null && source != null) composerDrafts.clear(from)
            }
        } else {
            if (prompt.isNotBlank() && composerDrafts.move(from, ComposerDraftKey.NewThread(env, id), from.projectId !in createdHere)) createdHere = createdHere + id
            projectId = id
        }
    }
    val canStart = state.canLaunchThreads && !state.busy && selectedProject != null && model != null

    val activity = remember(state.threads) { projectActivity(state.threads) }
    val ordered = remember(state.projects, activity) { projectsByRecentActivity(state.projects, activity) }
    val searchable = state.projects.size > RECENT_PROJECT_LIMIT
    val searching = query.isNotBlank()
    val filtered = remember(ordered, query) { searchProjects(ordered, query) }
    val shown = when {
        expanded || searching || filtered.size <= RECENT_PROJECT_LIMIT -> filtered
        else -> {
            val head = filtered.take(RECENT_PROJECT_LIMIT)
            val selected = filtered.firstOrNull { it.id == projectId }
            if (selected == null || selected in head) head else filtered.take(RECENT_PROJECT_LIMIT - 1) + selected
        }
    }
    val hidden = filtered.size - shown.size
    val now = remember { System.currentTimeMillis() }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        Row(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("New thread", style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f))
            IconButton(onClick = dismiss, Modifier.size(48.dp)) { Icon(TfIcons.Close, "Close", tint = c.text) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PROJECT", style = TrefoilType.section, color = c.muted, modifier = Modifier.weight(1f))
        }
        AnimatedContent(
            selectedProject.takeIf { collapsed }, label = "projectPicker",
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false) },
        ) { target ->
            if (target != null) SelectedProjectPill(target) { pickerCollapsed = false; userExpanded = true }
            else Column {
                if (searchable) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search projects") }, shape = TfFieldShape, colors = tfFieldColors(),
                    leadingIcon = { Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(TfIcons.Close, "Clear search", Modifier.size(18.dp), tint = c.muted) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { filtered.firstOrNull()?.takeIf { searching }?.let { pickProject(it.id); collapsePicker() } }),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).semantics { contentDescription = "Search projects" })
                if (state.projects.isEmpty()) Text("No projects reported by this environment.", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                else if (filtered.isEmpty()) Text("No projects match \"${query.trim()}\".", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    shown.forEach { project ->
                        ProjectRow(project, project.id == projectId, activity[project.id]?.let { relativeThreadTime(java.time.Instant.ofEpochMilli(it).toString(), now) }) { pickProject(project.id); collapsePicker() }
                    }
                    if (hidden > 0) Row(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Show all projects", onClick = { expanded = true })
                            .semantics(mergeDescendants = true) { contentDescription = "Show all ${filtered.size} projects" }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Show all ${filtered.size} projects", style = TrefoilType.meta.copy(fontSize = 14.sp), color = c.accent, modifier = Modifier.weight(1f))
                        Icon(TfIcons.ChevronDown, null, Modifier.size(16.dp), tint = c.accent)
                    }
                }
            }
        }

        Column(Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.s1).border(1.dp, c.line, RoundedCornerShape(24.dp))) {
            TextField(
                prompt, { prompt = it; touched = true; persist(text = it) }, Modifier.fillMaxWidth().heightIn(min = 140.dp).onFocusChanged { if (it.isFocused && selectedProject != null) collapsePicker() }, minLines = 5, maxLines = 10, enabled = !state.busy && loaded,
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
                SheetPill(model?.let { modelPillLabel(it, explicitOptions) } ?: "No models", TfIcons.Sparkle, "Model: ${model?.let { modelPillLabel(it, explicitOptions) } ?: "not set"}", enabled = state.models.isNotEmpty() || modelPickerLoading(state.models, state.connection)) { modelMenu = true }
                // An effort pick takes the chosenEffort path, the Fast switch the chosenFast one; both persist with the draft.
                if (modelMenu) ModelPickerSheet(
                    models = state.models, providers = state.providers, current = model, explicit = explicitOptions, enabled = loaded, dismiss = { modelMenu = false },
                    onSetModel = { option -> state.models.indexOfFirst { it.sameModelAs(option) }.takeIf { it >= 0 && loaded }?.let { index -> modelIndex = index; touched = true; persist(index = index) } },
                    onSetModelOption = { id, value ->
                        val effortId = model?.reasoning?.id
                        if (loaded && id == effortId && value is String) { chosenEffort = value; touched = true; persist(effort = value) }
                        else if (loaded && id == FAST_MODE_OPTION_ID && value is Boolean && model?.fastMode != null) { chosenFast = value; touched = true; persist(fast = value) }
                    },
                    preferences = state.modelPreferences, canWriteSettings = state.canWriteSettings, onToggleFavorite = onSetModelFavorite, showFast = true,
                    loading = modelPickerLoading(state.models, state.connection),
                )
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
                                onClick = { if (loaded) { chosenEffort = option.id; touched = true; persist(effort = option.id) }; effortMenu = false })
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
                            onClick = { if (loaded) { chosenMode = option.wire; touched = true; persist(modeWire = option.wire) }; modeMenu = false })
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

/** One project in the picker: tile, title, and when it was last active plus where it lives. */
@Composable
private fun ProjectRow(project: Project, selected: Boolean, lastActive: String?, onClick: () -> Unit) {
    val c = Tf.colors
    val shape = RoundedCornerShape(12.dp)
    val detail = listOfNotNull(lastActive?.takeIf { it.isNotEmpty() }, shortProjectPath(project.path).takeIf { it.isNotEmpty() && it != project.title }).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(if (selected) c.accentSoft else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick).semantics(mergeDescendants = true) { this.selected = selected }
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProjectTile(project.title, size = 32.dp, key = project.id)
        Column(Modifier.weight(1f)) {
            Text(project.title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(TfIcons.Check, null, Modifier.size(18.dp), tint = c.accent)
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

/* Presentation adapted from T3 Code mobile threadListV2 (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.shell.RegisterShortcut
import com.tajchert.trefoil.ui.shell.ShortcutId
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.core.session.hasContent
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

internal data class ThreadShelf(val title: String, val threads: List<ThreadSummary>)
internal data class ThreadIndicator(val label: String, val kind: String)

internal fun threadIndicator(thread: ThreadSummary): ThreadIndicator {
    val requests = listOfNotNull(thread.pendingRequestKind, thread.pendingChildRequestKind).map { it.lowercase(Locale.ROOT) }
    val status = (if (thread.completionHeldBackgroundWork && thread.status != "failed") "idle" else thread.activityRunStatus ?: thread.status).lowercase(Locale.ROOT)
    return when {
        requests.any { it.contains("approval") } || status.contains("approval") -> ThreadIndicator("Needs approval", "attention")
        requests.any { it.contains("input") } || status in setOf("waiting_for_input", "awaiting_input") -> ThreadIndicator("Needs input", "input")
        requests.isNotEmpty() -> ThreadIndicator("Needs attention", "attention")
        thread.blockedOnThreadWait == true -> ThreadIndicator("Waiting", "waiting")
        status in setOf("preparing", "queued", "starting", "running", "waiting", "working", "streaming") -> ThreadIndicator(if (status == "queued") "Queued" else "Working", "working")
        status in setOf("failed", "error", "interrupted") -> ThreadIndicator(if (status == "interrupted") "Interrupted" else "Failed", "error")
        status == "idle" -> ThreadIndicator("Waiting", "waiting")
        else -> ThreadIndicator("Ready", "ready")
    }
}
internal fun timestamp(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
internal fun relativeThreadTime(value: String?, now: Long = System.currentTimeMillis()): String {
    val time = timestamp(value)
    if (time == 0L) return ""
    val minutes = ((now - time).coerceAtLeast(0) / 60_000)
    return when { minutes < 1 -> "now"; minutes < 60 -> "${minutes}m"; minutes < 1440 -> "${minutes / 60}h"; minutes < 10080 -> "${minutes / 1440}d"; else -> "${minutes / 10080}w" }
}
/** T3 hides child conversations before searching, counting or partitioning its lists. */
internal fun threadListRows(threads: List<ThreadSummary>): List<ThreadSummary> {
    val active = threads.filter { !it.archived && it.archivedAt == null && it.deletedAt == null }
    val byId = active.associateBy { it.id }
    val requests = mutableMapOf<String, String>()
    fun flag(id: String, kind: String) { if (requests[id] != "approval") requests[id] = kind }
    active.forEach { child ->
        val kind = child.pendingRequestKind?.takeIf { it != "auth_refresh" } ?: return@forEach
        val parentId = child.parentThreadId ?: return@forEach
        val request = if (kind == "user_input") "user_input" else "approval"
        if (child.sideChat) { flag(parentId, request); return@forEach }
        if (child.relationshipToParent != "subagent" || child.creationSource == "provider" || request == "user_input") return@forEach
        val live = if (child.activityRunStatusPresent) child.activityRunStatus != null
            else child.status in setOf("preparing", "starting", "running", "waiting")
        if (!live) return@forEach
        var parent: String? = parentId
        val seen = mutableSetOf(child.id)
        while (parent != null && seen.add(parent)) {
            flag(parent, request)
            val row = byId[parent]
            parent = if (row?.relationshipToParent == "subagent" || row?.sideChat == true) row.parentThreadId else null
        }
    }
    return threads.filter { it.deletedAt == null && it.relationshipToParent != "subagent" && !it.sideChat }.map {
        val request = requests[it.id] ?: it.pendingChildRequestKind
        if (request == it.pendingChildRequestKind) it else it.copy(pendingChildRequestKind = request)
    }
}

private fun parsedTimestamp(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
private fun threadIsSnoozed(thread: ThreadSummary, now: Long): Boolean {
    if ((parsedTimestamp(thread.snoozedUntil) ?: return false) <= now) return false
    val request = thread.pendingRequestKind
    if (request != null && request != "user_input") return false
    if (request == "user_input") {
        val question = parsedTimestamp(thread.pendingRequestCreatedAt)
        val snoozed = parsedTimestamp(thread.snoozedAt)
        if (question == null || snoozed == null || question > snoozed) return false
    }
    if (thread.pendingChildRequestKind != null || thread.pendingGuestMessageCount > 0) return false
    val runtimeStatus = if (thread.completionHeldBackgroundWork && thread.status != "failed") "idle" else thread.activityRunStatus ?: thread.status
    if (runtimeStatus in setOf("failed", "error")) {
        val failed = if (thread.latestRunId != null && thread.status == "failed") parsedTimestamp(thread.latestRunCompletedAt) else parsedTimestamp(thread.updatedAt)
        val snoozed = parsedTimestamp(thread.snoozedAt)
        if (snoozed == null || failed != null && failed > snoozed) return false
    }
    return true
}

internal fun threadIsWorking(thread: ThreadSummary): Boolean {
    if (thread.pendingRequestKind != null && thread.pendingRequestKind != "auth_refresh" || thread.pendingChildRequestKind != null) return false
    val status = if (thread.completionHeldBackgroundWork && thread.status != "failed") "idle" else thread.activityRunStatus ?: thread.status
    val active = status in setOf("preparing", "queued", "starting", "running", "waiting", "working", "streaming")
    val idle = status == "idle" && (thread.latestRunId != null || thread.activeProviderThreadId != null || thread.completionHeldBackgroundWork)
    if (!active && !idle) return false
    val terminalRun = thread.latestRunId != null && thread.status !in setOf("preparing", "queued", "starting", "running", "waiting") && thread.activeRunId != thread.latestRunId
    return !(thread.interactionMode == "plan" && thread.hasActionableProposedPlan && terminalRun)
}

internal fun threadShelves(threads: List<ThreadSummary>, now: Long = System.currentTimeMillis()): List<ThreadShelf> {
    val groups = threadListRows(threads).groupBy {
        when {
            it.archived || it.archivedAt != null -> "Archived"
            threadIsSnoozed(it, now) -> "Snoozed"
            it.settledOverride == "settled" -> "Settled"
            it.pinnedAt != null -> "Pinned"
            threadIsWorking(it) -> "Working"
            else -> "Threads"
        }
    }
    return listOf("Pinned", "Threads", "Working", "Snoozed", "Settled", "Archived").mapNotNull { title ->
        val rows = groups[title] ?: return@mapNotNull null
        val sorted = if (title == "Pinned") rows.sortedWith(compareBy<ThreadSummary> { it.pinOrderKey == null }.thenBy { it.pinOrderKey.orEmpty() }.thenByDescending { timestamp(it.createdAt) }.thenBy { it.id })
        else rows.sortedWith(compareByDescending<ThreadSummary> {
            when (title) {
                "Working" -> maxOf(timestamp(if (it.latestUserAuthoredMessageAtPresent || it.latestUserAuthoredMessageAt != null) it.latestUserAuthoredMessageAt else it.latestRunRequestedAt), timestamp(it.createdAt))
                "Settled" -> parsedTimestamp(it.settledAt) ?: listOfNotNull(parsedTimestamp(it.latestUserMessageAt), parsedTimestamp(it.latestRunRequestedAt), parsedTimestamp(it.latestRunStartedAt), parsedTimestamp(it.latestRunCompletedAt)).maxOrNull() ?: timestamp(it.updatedAt)
                "Snoozed" -> -timestamp(it.snoozedUntil)
                "Threads" -> maxOf(timestamp(it.createdAt), timestamp(it.unsettledAt), timestamp(it.latestRunRequestedAt), timestamp(it.latestRunCompletedAt))
                else -> timestamp(it.updatedAt)
            }
        }.thenBy { it.id })
        ThreadShelf(title, sorted)
    }
}


/** Chip filters on the thread list. */
internal enum class ThreadFilter { All, NeedsYou, Working }

/** A presentation group of the list. [folded] groups render as a single expandable row (Settled, Archived). */
internal data class ThreadSection(val title: String, val threads: List<ThreadSummary>, val folded: Boolean = false)

internal fun threadNeedsYou(thread: ThreadSummary): Boolean = threadIndicator(thread).kind in setOf("attention", "input")

/**
 * Maps T3's shelves onto the design's grouping without changing membership rules: open threads that need a
 * reply are pulled up into "Needs you"; the inbox shelf ("Threads") is presented as "Recent"; Settled and
 * Archived fold into expandable rows. Settled and snoozed threads are never pulled up.
 */
internal fun threadSections(shelves: List<ThreadShelf>, filter: ThreadFilter = ThreadFilter.All): List<ThreadSection> {
    val shelf = shelves.associate { it.title to it.threads }
    val pinned = shelf["Pinned"].orEmpty()
    val working = shelf["Working"].orEmpty()
    val inbox = shelf["Threads"].orEmpty()
    val needs = (pinned + inbox + working).filter(::threadNeedsYou)
    val needIds = needs.mapTo(HashSet()) { it.id }
    fun open(rows: List<ThreadSummary>) = rows.filter { it.id !in needIds }
    val sections = when (filter) {
        ThreadFilter.NeedsYou -> listOf(ThreadSection("Needs you", needs))
        ThreadFilter.Working -> listOf(ThreadSection("Working", open(pinned).filter(::threadIsWorking) + open(working)))
        ThreadFilter.All -> listOf(
            ThreadSection("Needs you", needs), ThreadSection("Pinned", open(pinned)), ThreadSection("Working", open(working)),
            ThreadSection("Recent", open(inbox)), ThreadSection("Snoozed", shelf["Snoozed"].orEmpty()),
            ThreadSection("Settled", shelf["Settled"].orEmpty(), folded = true), ThreadSection("Archived", shelf["Archived"].orEmpty(), folded = true))
    }
    return sections.filter { it.threads.isNotEmpty() }
}

internal fun threadFilterCounts(shelves: List<ThreadShelf>): Map<ThreadFilter, Int> = mapOf(
    ThreadFilter.All to shelves.filter { it.title != "Archived" }.sumOf { it.threads.size },
    ThreadFilter.NeedsYou to threadSections(shelves, ThreadFilter.NeedsYou).sumOf { it.threads.size },
    ThreadFilter.Working to threadSections(shelves, ThreadFilter.Working).sumOf { it.threads.size },
)

private val defaultExpanded = listOf("Drafts", "Needs you", "Pinned", "Working", "Recent")

/**
 * Unsent new-thread prompts to list for [environmentId], newest first: only drafts with text whose project is in [projects]
 * (a draft of a missing project stays stored but hidden), narrowed by the project filter and the search query.
 */
internal fun threadDraftRows(drafts: Map<ComposerDraftKey, ComposerDraft>, environmentId: String?, projects: Map<String, Project>, projectId: String?, query: String = ""): List<Pair<ComposerDraft, Project>> =
    drafts.values.mapNotNull { draft ->
        val key = draft.key as? ComposerDraftKey.NewThread ?: return@mapNotNull null
        if (key.environmentId != environmentId || !draft.hasContent() || (projectId != null && key.projectId != projectId)) return@mapNotNull null
        val project = projects[key.projectId] ?: return@mapNotNull null
        if (query.isNotBlank() && !draft.text.contains(query.trim(), ignoreCase = true) && !project.title.contains(query.trim(), ignoreCase = true)) return@mapNotNull null
        draft to project
    }.sortedByDescending { it.first.updatedAtMillis }

/**
 * The machine has no shell snapshot yet ([ClientState.shellLoaded]) while a connection is being made or is live: the
 * list shows placeholder rows, never "no threads", until the first snapshot arrives.
 */
internal fun threadListLoading(state: ClientState): Boolean = !state.hasShellRows() &&
    state.connection in setOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Connected)

/**
 * Rows on screen are not live yet: kept from an earlier connection or restored from the on-device cache while the
 * connection is made, or resuming before its `synchronized` marker. The list shows them under the progress bar.
 */
internal fun threadListUpdating(state: ClientState): Boolean = state.shellLoaded && !state.shellLive && state.threads.isNotEmpty() &&
    state.connection in setOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Connected)

/** "No threads" is a fact only about a loaded shell; while it is loading or never loaded the list stays blank. */
internal fun threadListShowsEmpty(state: ClientState): Boolean = state.hasShellRows() && !state.busy

/** Rows on screen came from a snapshot, also when a client does not report [ClientState.shellLoaded]. */
private fun ClientState.hasShellRows() = shellLoaded || threads.isNotEmpty()

/** A pending "К проекту" jump: the group whose heading to scroll to, or null for the top of the list (`Все проекты`). */
private data class ProjectJump(val key: String?)

/**
 * Where each project's heading sits in the list, so `К проекту` can scroll to it. Mirrors the rows the LazyColumn
 * builds: [leadingRows] covers the loading/empty row and the drafts section, then every group surviving [filter]
 * contributes its heading and, while [isOpen], its threads.
 */
internal fun projectHeaderIndexes(
    groups: List<ProjectGroup>,
    filter: ThreadFilter,
    leadingRows: Int = 0,
    isOpen: (String) -> Boolean = { true },
): Map<String, Int> {
    var index = leadingRows
    val indexes = LinkedHashMap<String, Int>()
    groups.forEach { group ->
        val visible = group.visibleThreads(filter)
        if (visible.isEmpty()) return@forEach
        indexes[group.key] = index
        index += if (isOpen(group.key)) 1 + visible.size else 1
    }
    return indexes
}

/**
 * Threads list: header (machine pill or machine title on the rail layout), inline search, filter chips,
 * grouped rows and the New action. [selectedId] highlights a row only where the detail pane is visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadList(
    state: ClientState, select: (String) -> Unit, create: () -> Unit,
    onAction: (ThreadTarget, ThreadAction, String?) -> Unit = { _, _, _ -> },
    selectedId: String? = null,
    shell: ShellActions? = null,
    banner: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    composerDrafts: ComposerDraftUi = ComposerDraftUi(),
    onOpenDraft: (String) -> Unit = {},
    onDiscardDraft: (ComposerDraft) -> Unit = {},
    chrome: ThreadListChrome = ThreadListChrome(),
    preferences: ListPreferences = ListPreferences(),
    onPreferencesChange: ((ListPreferences) -> ListPreferences) -> Unit = {},
    onEditProject: (String) -> Unit = {},
) {
    var query by rememberSaveable(state.environment?.id) { mutableStateOf("") }
    var searching by rememberSaveable(state.environment?.id) { mutableStateOf(false) }
    // Ctrl+K / Ctrl+F: the Search button's state change, then the query field takes focus (an open field is just focused, not closed).
    val searchFocus = remember { FocusRequester() }
    RegisterShortcut(ShortcutId.SearchThreads) {
        if (searching || query.isNotEmpty()) runCatching { searchFocus.requestFocus() } else searching = true
    }
    var filter by rememberSaveable(state.environment?.id) { mutableStateOf(ThreadFilter.All) }
    val projects = remember(state.projects) { state.projects.associateBy { it.id } }
    val listed = remember(state.threads) { threadListRows(state.threads) }
    val filtered = remember(query, listed, projects) {
        listed.filter { thread -> query.isBlank() || listOf(thread.title, thread.latestMessage, projects[thread.projectId]?.title.orEmpty(), thread.branch.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) } }
    }
    // Expansion is remembered per project id in preferences, so it survives a restart the way the user left it. It is also
    // saveable here: leaving for a conversation and coming back must not re-open a project the user folded. The set lives
    // as a list because that is what the saved-state bundle can carry.
    var expandedProjects by rememberSaveable(state.environment?.id) { mutableStateOf(preferences.expandedProjects.toList()) }
    var expansionChosen by rememberSaveable(state.environment?.id) { mutableStateOf(preferences.expansionChosen) }
    // Searching or filtering overrides the saved fold: the matching rows must be on screen to be useful. Until the user
    // folds something, every project starts open.
    fun isOpen(key: String): Boolean = query.isNotBlank() || filter != ThreadFilter.All ||
        (!expansionChosen && key !in expandedProjects) || key in expandedProjects
    val listState = rememberLazyListState()
    var jump by remember { mutableStateOf<ProjectJump?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val groups = remember(filtered, state.projects, preferences.pinnedOrder, preferences.otherOrder, preferences.threadOrder, preferences.pinnedProjects, now) {
        projectGroups(
            threads = filtered, projects = state.projects,
            pinned = preferences.pinnedProjects,
            pinnedOrder = preferences.pinnedOrder, otherOrder = preferences.otherOrder, threadOrder = preferences.threadOrder,
            now = now,
        )
    }
    val counts = remember(groups) { projectGroupsFilterCounts(groups) }
    // Drafts belong to "All": the Needs you and Working chips are about threads.
    // Keyed on the new-thread drafts only (equal maps): typing a reply does not rebuild the shelf.
    val newThreadDrafts = remember(composerDrafts.drafts) { composerDrafts.drafts.filterKeys { it is ComposerDraftKey.NewThread } }
    val draftRows = remember(newThreadDrafts, state.environment?.id, projects, filter, query) {
        if (filter == ThreadFilter.All) threadDraftRows(newThreadDrafts, state.environment?.id, projects, null, query) else emptyList()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val liveTiming = remember(filtered) { filtered.any { thread ->
        (threadIndicator(thread).kind == "working" && threadWorkStartedAt(thread) != null) ||
            (threadIndicator(thread).kind == "waiting" && validThreadInstant(thread.latestRunCompletedAt) != null)
    } }
    LaunchedEffect(lifecycle, liveTiming, state.threads) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (coroutineContext.isActive) {
                val current = System.currentTimeMillis()
                now = current
                delay(nextThreadClockDelay(current, liveTiming, state.threads.mapNotNull { validThreadInstant(it.snoozedUntil) }))
            }
        }
    }
    // Creating a thread depends on the shell, not on the open thread's refresh state.
    val canCreate = state.canLaunchThreads && state.projects.isNotEmpty() && state.models.isNotEmpty()
    val c = Tf.colors
    // A rail beside the list already has the New action, so the list shows neither a FAB nor a button.
    val railBeside = chrome.railPresent
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ThreadListHeader(state, shell, onSearch = { if (searching || query.isNotEmpty()) { searching = false; query = "" } else searching = true },
            chrome = chrome, compactHeight = LocalCompactHeight.current)
        banner()
        if (searching || query.isNotEmpty()) Box(Modifier.focusRequester(searchFocus).focusGroup()) { ThreadSearchField(query, { query = it }) { query = ""; searching = false } }
        ThreadFilterChips(filter, counts, state.projects, null, { filter = it }, { selected ->
            // "Jump to project" scrolls rather than filters: the groups stay, and the chosen heading comes into view.
            if (selected == null) {
                // "All projects" opens every group and returns to the top; it hides nothing.
                val all = groups.mapTo(mutableSetOf()) { it.key }
                if (!expansionChosen || expandedProjects.toSet() != all) {
                    expandedProjects = all.toList()
                    expansionChosen = true
                    onPreferencesChange { it.copy(expandedProjects = all, expansionChosen = true) }
                }
                jump = ProjectJump(null)
            } else {
                // Jumping opens one project and leaves the rest as they were: the "everything open" default is
                // materialised first, so the first jump does not fold every other project.
                val current = if (expansionChosen) expandedProjects.toSet() else groups.mapTo(mutableSetOf()) { it.key }
                if (selected !in current) {
                    val next = current + selected
                    expandedProjects = next.toList()
                    expansionChosen = true
                    onPreferencesChange { it.copy(expandedProjects = next, expansionChosen = true) }
                }
                jump = ProjectJump(selected)
            }
        }, tabletChrome = chrome != ThreadListChrome())
        val loading = threadListLoading(state)
        // The heading's row index, computed from the same rows the LazyColumn below builds: jumping must land on the
        // project's heading, so the scroll runs after the opening of the target has been composed.
        val draftsOpen = query.isNotBlank() || "Drafts" in defaultExpanded
        val emptyRow = if (groups.isEmpty() && draftRows.isEmpty() && (loading || threadListShowsEmpty(state))) 1 else 0
        val draftRowsCount = if (draftRows.isEmpty()) 0 else 1 + if (draftsOpen) draftRows.size else 0
        val headerIndexes = remember(groups, filter, emptyRow, draftRowsCount, query, expandedProjects, expansionChosen) {
            projectHeaderIndexes(groups, filter, emptyRow + draftRowsCount, ::isOpen)
        }
        LaunchedEffect(jump, headerIndexes) {
            val request = jump ?: return@LaunchedEffect
            val index = if (request.key == null) 0 else headerIndexes[request.key]
            if (index != null) listState.animateScrollToItem(index)
            jump = null
        }
        if ((state.busy || loading) && state.threads.isEmpty() || threadListUpdating(state)) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(2.dp), color = c.accent, trackColor = c.s2)
        PullToRefreshBox(isRefreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomInset + if (railBeside) 24.dp else 96.dp)) {
                if (groups.isEmpty() && draftRows.isEmpty()) {
                    if (loading) item(key = "loading") { ThreadListLoading() }
                    else if (threadListShowsEmpty(state)) item(key = "empty") {
                        ThreadListEmpty(searching = query.isNotBlank() || filter != ThreadFilter.All)
                    }
                }
                if (draftRows.isNotEmpty()) {
                    item(key = "shelf:Drafts", contentType = "shelf") {
                        ShelfLabel("Drafts", draftRows.size, draftsOpen, dot = null) { /* drafts keep their own single fold */ }
                    }
                    if (draftsOpen) items(draftRows, key = { (draft, _) -> "draft:${(draft.key as ComposerDraftKey.NewThread).projectId}" }, contentType = { "draft" }) { (draft, project) ->
                        DraftRow(draft, project, now, { onOpenDraft(project.id) }, tabletChrome = chrome != ThreadListChrome()) { onDiscardDraft(draft) }
                    }
                }
                groups.forEach { group ->
                    val visible = group.visibleThreads(filter)
                    if (visible.isEmpty()) return@forEach
                    val open = isOpen(group.key)
                    val toggle = {
                        val current = if (expansionChosen) expandedProjects.toSet() else groups.mapTo(mutableSetOf()) { it.key }
                        val next = if (group.key in current) current - group.key else current + group.key
                        expandedProjects = next.toList()
                        expansionChosen = true
                        onPreferencesChange { it.copy(expandedProjects = next, expansionChosen = true) }
                    }
                    item(key = "project:${group.key}", contentType = "project") {
                        ProjectHeader(
                            group = group, open = open,
                            icon = ProjectIcon.byId(preferences.projectIcons[group.key]),
                            color = ProjectColor.byId(preferences.projectColors[group.key]?.let(::colorId)),
                            pinned = group.key in preferences.pinnedProjects,
                            onToggle = toggle,
                            onLongPress = { onEditProject(group.key) },
                            onTogglePin = {
                                if (group.key != NoProjectKey) onPreferencesChange { prefs ->
                                    val pins = if (group.key in prefs.pinnedProjects) prefs.pinnedProjects - group.key else prefs.pinnedProjects + group.key
                                    prefs.copy(pinnedProjects = pins)
                                }
                            },
                        )
                    }
                    if (open) items(visible, key = { "thread:${group.key}:${it.id}" }, contentType = { "thread" }) { thread ->
                        ThreadRow(state, thread, projects[thread.projectId], now, selected = thread.id == selectedId, onAction = onAction,
                            replyDraft = composerDrafts.drafts[ComposerDraftKey.Reply(state.environment?.id.orEmpty(), thread.id)]?.takeIf { it.hasContent() },
                            tabletChrome = chrome != ThreadListChrome(), showProject = false) { select(thread.id) }
                    }
                }
            }
            if (!railBeside) NewThreadFab(canCreate, create, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp).avoidSnackbar())
        }
    }
}

/** Stored colours are indices into [ProjectColor]; this maps one back for the tile. */
private fun colorId(value: Int): String = ProjectColor.entries.getOrNull(value)?.id.orEmpty()

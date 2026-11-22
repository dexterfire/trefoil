/* Presentation adapted from T3 Code mobile threadListV2 (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
private fun timestamp(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
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
    railLayout: Boolean = false,
    banner: @Composable () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    composerDrafts: ComposerDraftUi = ComposerDraftUi(),
    onOpenDraft: (String) -> Unit = {},
    onDiscardDraft: (ComposerDraft) -> Unit = {},
) {
    var query by rememberSaveable(state.environment?.id) { mutableStateOf("") }
    var searching by rememberSaveable(state.environment?.id) { mutableStateOf(false) }
    var projectId by rememberSaveable(state.environment?.id) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(state.environment?.id) { mutableStateOf(ThreadFilter.All) }
    val projects = remember(state.projects) { state.projects.associateBy { it.id } }
    LaunchedEffect(projects, projectId) { if (projectId != null && projectId !in projects) projectId = null }
    val listed = remember(state.threads) { threadListRows(state.threads) }
    val inProject = remember(listed, projectId) { listed.filter { projectId == null || it.projectId == projectId } }
    val filtered = remember(query, inProject, projects) {
        inProject.filter { thread -> query.isBlank() || listOf(thread.title, thread.latestMessage, projects[thread.projectId]?.title.orEmpty(), thread.branch.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) } }
    }
    var expanded by rememberSaveable(state.environment?.id) { mutableStateOf(defaultExpanded) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val shelves = remember(filtered, now) { threadShelves(filtered, now) }
    val sections = remember(shelves, filter) { threadSections(shelves, filter) }
    // Drafts belong to "All": the Needs you and Working chips are about threads.
    // Keyed on the new-thread drafts only (equal maps): typing a reply does not rebuild the shelf.
    val newThreadDrafts = remember(composerDrafts.drafts) { composerDrafts.drafts.filterKeys { it is ComposerDraftKey.NewThread } }
    val draftRows = remember(newThreadDrafts, state.environment?.id, projects, projectId, query, filter) {
        if (filter == ThreadFilter.All) threadDraftRows(newThreadDrafts, state.environment?.id, projects, projectId, query) else emptyList()
    }
    val counts = remember(shelves) { threadFilterCounts(shelves) }
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
    Column(Modifier.fillMaxSize().background(c.bg)) {
        ThreadListHeader(state, shell, railLayout, canCreate, create, onSearch = { if (searching || query.isNotEmpty()) { searching = false; query = "" } else searching = true })
        banner()
        if (searching || query.isNotEmpty()) ThreadSearchField(query, { query = it }) { query = ""; searching = false }
        ThreadFilterChips(filter, counts, state.projects, projects[projectId], { filter = it }, { projectId = it })
        val loading = threadListLoading(state)
        if ((state.busy || loading) && state.threads.isEmpty() || threadListUpdating(state)) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).height(2.dp), color = c.accent, trackColor = c.s2)
        PullToRefreshBox(isRefreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomInset + if (railLayout) 24.dp else 96.dp)) {
                if (sections.isEmpty() && draftRows.isEmpty()) {
                    if (loading) item(key = "loading") { ThreadListLoading() }
                    else if (threadListShowsEmpty(state)) item(key = "empty") {
                        ThreadListEmpty(searching = query.isNotBlank() || filter != ThreadFilter.All || projectId != null)
                    }
                }
                if (draftRows.isNotEmpty()) {
                    val draftsOpen = query.isNotBlank() || "Drafts" in expanded
                    item(key = "shelf:Drafts", contentType = "shelf") {
                        ShelfLabel("Drafts", draftRows.size, draftsOpen, dot = null) { expanded = if ("Drafts" in expanded) expanded - "Drafts" else expanded + "Drafts" }
                    }
                    if (draftsOpen) items(draftRows, key = { (draft, _) -> "draft:${(draft.key as ComposerDraftKey.NewThread).projectId}" }, contentType = { "draft" }) { (draft, project) ->
                        DraftRow(draft, project, now, { onOpenDraft(project.id) }) { onDiscardDraft(draft) }
                    }
                }
                sections.forEach { section ->
                    val open = query.isNotBlank() || filter != ThreadFilter.All || section.title in expanded
                    val toggle = { expanded = if (section.title in expanded) expanded - section.title else expanded + section.title }
                    item(key = "shelf:${section.title}", contentType = "shelf") {
                        if (section.folded) FoldedShelfRow(section.title, section.threads.size, open, toggle)
                        else ShelfLabel(section.title, section.threads.size, open, dot = if (section.title == "Needs you") c.amber else null, toggle = toggle)
                    }
                    if (open) items(section.threads, key = { "thread:${section.title}:${it.id}" }, contentType = { "thread" }) { thread ->
                        ThreadRow(state, thread, projects[thread.projectId], now, selected = thread.id == selectedId, onAction = onAction,
                            replyDraft = composerDrafts.drafts[ComposerDraftKey.Reply(state.environment?.id.orEmpty(), thread.id)]?.takeIf { it.hasContent() }) { select(thread.id) }
                    }
                }
            }
            if (!railLayout) NewThreadFab(canCreate, create, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = 16.dp))
        }
    }
}

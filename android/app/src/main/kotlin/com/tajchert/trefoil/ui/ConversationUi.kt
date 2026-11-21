/* Conversation layout adapted from T3 Code's ThreadFeed (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.tajchert.trefoil.ui.htmlrender.HtmlRenderCard
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraftKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

@Composable
internal fun Conversation(
    rawState: ClientState, send: (String) -> Unit, interrupt: () -> Unit, respond: (String, String, Map<String, List<String>>) -> Unit,
    onLoadActivity: (String) -> Unit = {}, fileDownloadUi: FileDownloadUi = FileDownloadUi(),
    draft: ThreadDraft? = null, onSetRuntimeMode: (RuntimeMode) -> Unit = {}, onSetModel: (ModelOption) -> Unit = {}, onSetModelOption: (String, Any) -> Unit = { _, _ -> },
    sendFailed: Flow<Unit> = emptyFlow(),
    /** Back affordance for the conversation header; null on wide layouts where the list stays visible. */
    onBack: (() -> Unit)? = null,
    /** Trailing header actions supplied by the shell (thread actions menu, etc.). */
    actions: @Composable RowScope.() -> Unit = {},
    /** First prompt from the new-thread sheet: put in the composer and submit once, then call [onSeedConsumed]. */
    seedPrompt: String? = null,
    onSeedConsumed: () -> Unit = {},
    /** Opens the parent of a subagent child; null when the parent is not loaded. */
    onOpenParent: (() -> Unit)? = null,
    /** "Subagent of <parent title>" header subtitle for subagent children. */
    subtitle: String? = null,
    onOpenAgents: (() -> Unit)? = null,
    /** Opens a subagent's local child thread from its inline timeline row. */
    onOpenThread: ((String) -> Unit)? = null,
    /** Other threads waiting for the user; shown as a badge on the back arrow. */
    otherNeedsYou: Int = 0,
    /** Local unsent reply text; the composer starts from it and writes every change back. */
    composerDrafts: ComposerDraftUi = ComposerDraftUi(),
    /**
     * Sends an existing-thread message: [mode] null for an idle thread, otherwise the follow-up delivery. The outcome comes
     * back keyed by the submission's id in [sendOutcomes]; the default is a plain [send] confirmed by the timeline alone.
     */
    submitMessage: (ComposerSubmission, FollowUpMode?) -> Unit = { submission, _ -> send(submission.text) },
    /** Outcomes of [submitMessage] calls, by submission id; the composer settles only its own and then calls [onConsumeSendOutcome]. */
    sendOutcomes: Map<String, SendOutcome> = emptyMap(),
    /** Submissions this process still has in flight; a restored submission id the view model does not know is settled as failed. */
    sendsInFlight: Set<String> = emptySet(),
    onConsumeSendOutcome: (String) -> Unit = {},
    /** Threads (see [stopKey]) with a Stop in flight: the view model holds each until the thread has nothing left to stop, or 10 s. */
    stopPendingKeys: Set<String> = emptySet(),
    /** Threads (see [stopKey]) with a queue command in flight: the queue strip's controls wait for it. */
    queueOperationKeys: Set<String> = emptySet(),
    onCancelQueued: (String) -> Unit = {}, onSteerQueued: (String) -> Unit = {}, onResumeQueue: () -> Unit = {},
) {
    val c = Tf.colors
    // A draft hides whatever thread was previously selected; its first message creates the real thread.
    val base = if (draft == null) rawState else rawState.copy(messages = emptyList(), activities = emptyList(), requests = emptyList(), activeRunId = null, runs = emptyList(), attempts = emptyList())
    // A draft launches a new thread, which needs only the shell; an open thread rides out its refresh gaps.
    val steadyWrite = rememberSteadyCanWrite(base)
    val writable = if (draft != null) base.canLaunchThreads else steadyWrite
    val state = if (writable == base.canWrite) base else base.copy(canWrite = writable)
    if (state.selectedThreadId == null && draft == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TrefoilMark(Modifier.size(48.dp))
                Text("Pick a thread", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 20.dp))
                Text("Choose a thread from the list, or start a new one.", style = TrefoilType.preview, color = c.muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        return
    }
    // A new-thread conversation edits its project's NewThread draft; a thread edits its Reply draft.
    val composerKey = if (draft != null) "draft:${draft.projectId}" else state.selectedThreadId
    val editorKey = state.environment?.id?.let { environment ->
        if (draft != null) ComposerDraftKey.NewThread(environment, draft.projectId) else state.selectedThreadId?.let { ComposerDraftKey.Reply(environment, it) }
    }
    var text by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf(editorKey?.let { composerDrafts.get(it)?.text }.orEmpty()) }
    // The store loads asynchronously: a stored draft that arrives later fills an untouched, empty composer, never one the user edited.
    var edited by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf(false) }
    LaunchedEffect(editorKey, composerDrafts.loaded) {
        if (composerDrafts.loaded && !edited && text.isBlank()) editorKey?.let { composerDrafts.get(it)?.text }?.takeIf { it.isNotBlank() }?.let { text = it }
    }
    var editRevision by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf(0) }
    fun editText(value: String) { text = value; edited = true; editRevision++; if (composerDrafts.loaded) editorKey?.let { composerDrafts.updateText(it, value) } }
    var submitted by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf<String?>(null) }
    // Every message and queued message present at submission: a confirmation must be something new (an older identical one moving into the timeline is not).
    var idsAtSend by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf(arrayListOf<String>()) }
    // Set for sends on the correlated path: only their own outcome settles them. Restored with the composer, so a send that
    // settled while the conversation was closed is reconciled from the view model's outcomes when it is shown again.
    var submissionId by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf<String?>(null) }
    var revisionAtSend by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf(0) }
    // The text of the submission in flight: restored into an emptied, unedited composer if the send turns out to have failed.
    var sentText by rememberSaveable(state.environment?.id, composerKey) { mutableStateOf("") }
    val stopPending = stopKey(state.environment?.id, state.selectedThreadId) in stopPendingKeys
    val canSend = state.canWrite && !state.busy
    /** The send settled: the composer clears unless the text was edited since the submission. */
    fun settle(clear: Boolean) {
        if (clear && editRevision == revisionAtSend) text = ""
        submitted = null
        submissionId?.let(onConsumeSendOutcome)
        submissionId = null
    }
    /** The send failed (or its outcome was lost): the composer frees up, and its text comes back unless it was edited since. */
    fun failSubmission() {
        val restore = editRevision == revisionAtSend && text.isEmpty() && sentText.isNotEmpty()
        settle(clear = false)
        if (restore) editText(sentText)
    }
    /**
     * The one send path: the Send button and the seeded first prompt both go through here. [requested] is the delivery the
     * user chose (the button's label or the menu entry); it is revalidated against the live state, never downgraded to a plain send.
     */
    fun submit(value: String, requested: FollowUpMode? = null): Boolean {
        if (value.isBlank() || submitted != null || !canSend) return false
        val route = routeSend(state, draft != null, requested)
        if (route == SendRoute.Refused) return false
        edited = true // the accepted send clears the stored draft; a late-loading one must not refill the composer
        idsAtSend = ArrayList(state.messages.map { it.id } + state.queuedMessages.map { it.messageId })
        revisionAtSend = editRevision
        submitted = value
        sentText = value
        if (draft != null) send(value)
        else {
            submissionId?.let(onConsumeSendOutcome) // an earlier send the timeline already showed is superseded
            val submission = newSubmission(state, value)
            submissionId = submission.id
            submitMessage(submission, (route as? SendRoute.FollowUp)?.mode)
        }
        return true
    }
    // Survives recomposition and configuration change so the seed is never submitted twice.
    var seedHandled by rememberSaveable(seedPrompt) { mutableStateOf(false) }
    LaunchedEffect(seedPrompt, draft != null, seedHandled) {
        if (seedPrompt != null && draft != null && !seedHandled) {
            seedHandled = true
            text = seedPrompt
            submit(seedPrompt) // if this cannot send (read-only), the text stays in the composer
            onSeedConsumed()
        }
    }
    // A follow-up behind a running turn shows up in the queue (or steers straight into the timeline), not as a new message.
    LaunchedEffect(state.messages, state.queuedMessages) {
        val pending = submitted
        if (pending != null && sendAccepted(pending, state, idsAtSend)) {
            // A matching message may be another client's. A correlated send frees the composer but keeps its submission, so its own
            // outcome still decides: a later failure brings the text back (unless it was edited meanwhile).
            if (submissionId == null) settle(clear = true)
            else { if (editRevision == revisionAtSend) text = ""; submitted = null }
        }
    }
    // The view model's answer for this submission: accepted (done, whether or not the message shows up yet) or failed (text stays).
    LaunchedEffect(submissionId, sendOutcomes) {
        when (submissionId?.let { sendOutcomes[it] }) {
            SendOutcome.Accepted -> settle(clear = true)
            SendOutcome.Failed -> failSubmission()
            null -> {}
        }
    }
    // A submission id restored from saved state that this process knows nothing about (process death) will never be answered:
    // settle it as failed, keeping the text so it can be sent again. Checked once when the composer is (re)created.
    LaunchedEffect(state.environment?.id, composerKey) {
        val id = submissionId
        if (id != null && id !in sendOutcomes && id !in sendsInFlight) failSubmission()
    }
    // Uncorrelated signals only free a send that has no submission of its own (a new-thread launch).
    LaunchedEffect(state.error) { if (state.error != null && submissionId == null) submitted = null }
    // Identical consecutive errors do not retrigger the effect above, so every failed send also emits here.
    LaunchedEffect(sendFailed) { sendFailed.collect { if (submissionId == null) submitted = null } }
    val entries = remember(state.messages, state.activities, state.activeRunId, state.runs) {
        foldConversationTimeline(conversationTimeline(state.messages, state.activities), state.activeRunId, state.runs)
    }
    val roster = remember(state.runs, state.subagents, draft) { if (draft == null) subagentRoster(state.runs, state.subagents) else null }
    val streaming = state.messages.any { it.streaming }
    val turnEnded = state.runs.firstOrNull { it.id == state.activeRunId }?.turnEnded == true
    val liveTurn = remember(entries, state.activeRunId, roster, state.subagents, streaming, state.requests.isNotEmpty(), turnEnded) {
        liveTurnLayout(entries, state.activeRunId, roster, state.subagents, streaming, state.requests.isNotEmpty(), turnEnded)
    }
    val backgroundWork = remember(state.backgroundWork, liveTurn.thinkingLabel, state.requests.isNotEmpty(), draft) {
        if (draft == null) backgroundWorkStatus(state.backgroundWork, liveTurn.thinkingLabel, state.requests.isNotEmpty()) else null
    }
    // Client-side window over the fully loaded timeline. Deliberately not saveable (like T3, expanded history is not
    // persisted): the shell's per-thread SaveableStateProvider would otherwise reopen a thread with old pages showing, so
    // entering or re-entering a thread starts from the latest turns.
    var turnWindow by remember(state.environment?.id, composerKey) { mutableStateOf(TurnWindowState()) }
    val turnCount = remember(entries) { entries.count { it.startsUserTurn } }
    val liveIndex = liveTimelineIndex(entries, state.activeRunId, liveTurn.headerIndex)
    val window = remember(entries, turnWindow, turnCount, liveIndex) { windowConversationTimeline(entries, turnWindow.visibleTurns(turnCount), liveIndex) }
    // Record what is on screen: the first load, rewinds, and live work that widened the window (so it stays put after).
    SideEffect { turnWindow.settled(turnCount, window).let { if (it != turnWindow) turnWindow = it } }
    val shown = window.visible
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val nearBottom by remember { derivedStateOf { !list.canScrollForward || (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 2 } }
    var following by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to nearBottom }.collect { (scrolling, bottom) -> if (scrolling) following = bottom }
    }
    // Keyed on the full timeline, not the window: revealing earlier turns must not yank the list to the bottom.
    LaunchedEffect(entries, state.requests, state.activeRunId, liveTurn, backgroundWork) {
        if (following) {
            // Let LazyColumn measure new rows before scrolling to the trailing anchor.
            withFrameNanos { }
            if (list.layoutInfo.totalItemsCount > 0) list.scrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    // The keyboard opening (or the composer growing) shrinks the viewport, and LazyColumn keeps its top
    // anchored, which would push the latest messages under the keyboard. Stay pinned to the bottom only if
    // the list was at its end before the resize; a position the reader scrolled to is left untouched.
    LaunchedEffect(list) {
        var lastHeight = -1
        var wasAtEnd = true
        snapshotFlow { list.layoutInfo.viewportSize.height to !list.canScrollForward }.collect { (height, atEnd) ->
            if (height != lastHeight && lastHeight > 0 && wasAtEnd) {
                val count = list.layoutInfo.totalItemsCount
                if (!atEnd && count > 0) list.scrollToItem(count - 1)
            } else wasAtEnd = atEnd
            lastHeight = height
        }
    }
    val thread = if (draft != null) null else state.threads.find { it.id == state.selectedThreadId }
    val project = state.projects.find { it.id == (draft?.projectId ?: thread?.projectId) }
    val canLoadActivity = state.connection == ConnectionStatus.Connected && !state.busy
    val workStartedAt = thread?.let(::threadWorkStartedAt)?.let(::validThreadInstant)
    val clock = rememberThreadWorkClock(state.activeRunId != null && workStartedAt != null)
    val workLabel = "Working" + if (workStartedAt != null) " ${formatWorkDuration(clock - workStartedAt)}" else ""
    val activeRun = state.runs.firstOrNull { it.id == state.activeRunId }
    val turnStartedAt = workStartedAt ?: validThreadInstant(activeRun?.workStartedAt ?: activeRun?.startedAt)
    /** Reveals [TURN_WINDOW_PAGE] more turns and keeps the entry the reader was looking at in place (prepend anchoring). */
    fun loadEarlier() {
        val anchor = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != LOAD_EARLIER_KEY }
        val expanded = turnWindow.expanded(turnCount, window)
        val next = windowConversationTimeline(entries, expanded.visibleTurns(turnCount), liveIndex)
        turnWindow = expanded
        if (anchor == null) return
        // Applied on the next measure together with the revealed rows, instead of LazyColumn's key tracking, which would
        // keep the (still first) "Load earlier turns" row in place and push the reader's entry down. `following` is left
        // alone: requestScrollToItem runs no scroll pass of its own (it only cancels one already in progress), so the
        // isScrollInProgress observer above sees nothing and only real user scrolls change it.
        list.requestScrollToItem(prependedAnchorIndex(anchor.index, window, next), -anchor.offset)
    }
    // One stable callback, so the row does not recompose on every streaming delta.
    val latestLoadEarlier by rememberUpdatedState(::loadEarlier)
    val onLoadEarlier = remember { { latestLoadEarlier() } }
    CompositionLocalProvider(LocalFileDownloadUi provides fileDownloadUi, LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize()) {
            ConversationHeader(
                title = if (draft != null) "New thread" else thread?.title?.ifBlank { "Untitled conversation" } ?: "Conversation",
                projectTitle = project?.title, branch = thread?.branch?.takeIf { it.isNotBlank() },
                workLabel = workLabel.takeIf { state.activeRunId != null }, onBack = onBack, actions = actions, subtitle = subtitle, otherNeedsYou = otherNeedsYou)
            HorizontalDivider(color = c.line)
            Box(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.busy && entries.isEmpty()) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.s2) }
                    if (!state.busy && entries.isEmpty() && state.activeRunId == null) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            TrefoilMark(Modifier.size(40.dp))
                            Text(if (draft != null) "New thread in ${project?.title ?: "this project"}" else "Nothing here yet", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium)
                            Text(if (draft != null) "Your first message starts the thread." else "Send a message to get started.", Modifier.padding(top = 6.dp), style = TrefoilType.preview, color = c.muted)
                        }
                    }
                    val entryRow: @Composable (ConversationEntry) -> Unit = { entry ->
                        when (entry) {
                            is ConversationEntry.Message -> MessageRow(entry.message, entry.stacked,
                                showMetadata = if (entry.message.role == "user") {
                                    (entries.getOrNull(entries.indexOf(entry) + 1) as? ConversationEntry.Message)?.message?.role != "user"
                                } else !entry.message.streaming && (state.activeRunId == null || (entry.message.runId != null && entry.message.runId != state.activeRunId)) &&
                                    entry.message.id == state.messages.lastOrNull { it.role == "assistant" && it.runId == entry.message.runId }?.id)
                            is ConversationEntry.Activities -> if (entry.isSubagentGroup) SubagentActivityRows(entry.activities, state.subagents, onOpenThread, onOpenAgents)
                                else WorkGroup(entry.activities, live = state.activeRunId != null && entry.activities.first().runId == state.activeRunId, canLoadActivity = canLoadActivity, onLoadActivity = onLoadActivity)
                            is ConversationEntry.HtmlRender -> HtmlRenderRow(entry.activity)
                            is ConversationEntry.Work -> CompletedWork(entry, canLoadActivity, onLoadActivity, subagents = state.subagents, onOpenThread = onOpenThread, onOpenAgents = onOpenAgents)
                        }
                    }
                    if (window.hasEarlier) item(key = LOAD_EARLIER_KEY) { LoadEarlierTurnsRow(onLoadEarlier) }
                    // The active turn opens with its "Working for" header, right after the prompt that started it.
                    // The window always keeps the header's turn, so its index maps into the visible tail.
                    val headerAt = liveTurn.headerIndex?.minus(window.hiddenCount)?.coerceIn(0, shown.size)
                    items(if (headerAt == null) shown else shown.subList(0, headerAt), key = { it.key }) { entryRow(it) }
                    if (headerAt != null) {
                        val headerKey = state.activeRunId?.let(::workingHeaderKey)?.takeIf { key -> entries.none { it.key == key } } ?: "working-header"
                        item(key = headerKey) { WorkingTurnHeader(turnStartedAt) }
                        items(shown.subList(headerAt, shown.size), key = { it.key }) { entryRow(it) }
                    }
                    items(liveTurn.orphanSubagents, key = { "subagent:${it.id}" }) { SubagentTimelineRow(it, onOpenThread, onOpenAgents) }
                    liveTurn.thinkingLabel?.let { label -> item(key = "thinking") { ThinkingRow(label) } }
                    backgroundWork?.let { work -> item(key = "background-work") { BackgroundWorkRow(work, onOpenThread, onOpenAgents, live = state.canRead && state.connection == ConnectionStatus.Connected) } }
                    items(state.requests, key = { "request:${it.id}" }) { RequestCard(it, canSend, respond) }
                    item(key = "bottom") { Spacer(Modifier.height(1.dp)) }
                }
                if (!nearBottom) Surface(onClick = { following = true; scope.launch { list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text, border = BorderStroke(1.dp, c.line), shadowElevation = 2.dp) {
                    Row(Modifier.height(32.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(TfIcons.ChevronDown, null, Modifier.size(16.dp)); Text("Latest", style = TrefoilType.meta.copy(color = c.text))
                    }
                }
            }
            // The composer owns its bottom insets: it rides above the keyboard, or above the navigation bar when closed.
            Column(Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                QuickReplies(state.requests, canSend, respond)
                if (thread != null && thread.isProviderNativeSubagentThread()) ReadOnlySubagentBar(thread, clock, onOpenParent)
                else ConversationComposer(state, draft, text, ::editText, submitted != null, stopPending, onSetRuntimeMode, onSetModel, onSetModelOption,
                    stop = interrupt, onSend = { mode -> submit(text, mode) }, canWriteNow = rawState.canWrite, queueBusy = stopKey(state.environment?.id, state.selectedThreadId) in queueOperationKeys, onCancelQueued = onCancelQueued, onSteerQueued = onSteerQueued, onResumeQueue = onResumeQueue)
            }
        }
    }
}

/** Standalone timeline row for an agent HTML render; the wrapper owns the row's test tag. */
@Composable
internal fun HtmlRenderRow(activity: ToolActivity) {
    val reference = activity.htmlRender ?: return
    Box(Modifier.fillMaxWidth().testTag("html-render:${activity.id}")) { HtmlRenderCard(reference, Modifier.fillMaxWidth()) }
}

@Composable
private fun ConversationHeader(title: String, projectTitle: String?, branch: String?, workLabel: String?, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, subtitle: String? = null, otherNeedsYou: Int = 0) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            BadgedBox(badge = {
                if (otherNeedsYou > 0) Badge(Modifier.clearAndSetSemantics {}, containerColor = c.amber, contentColor = c.onAccent) { Text(if (otherNeedsYou > 9) "9+" else otherNeedsYou.toString()) }
            }) { Icon(TfIcons.Back, backDescription(otherNeedsYou), Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f).padding(start = if (onBack != null) 2.dp else 0.dp)) {
            Text(title, style = TrefoilType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (projectTitle != null || workLabel != null) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (projectTitle != null) {
                    ProjectTile(projectTitle, size = 20.dp)
                    Text(projectTitle, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (branch != null) BranchLabel(branch, Modifier.weight(1f, fill = false))
                }
                if (workLabel != null) {
                    WorkingSpinner(size = 14.dp)
                    Text(workLabel, style = TrefoilType.meta, color = c.blue, maxLines = 1)
                }
            }
        }
        actions()
    }
}

@Composable
internal fun ConversationComposer(
    state: ClientState, draft: ThreadDraft?, text: String, onText: (String) -> Unit, waiting: Boolean, stopPending: Boolean,
    onSetRuntimeMode: (RuntimeMode) -> Unit, onSetModel: (ModelOption) -> Unit, onSetModelOption: (String, Any) -> Unit,
    stop: () -> Unit, onSend: (FollowUpMode?) -> Unit, canWriteNow: Boolean = state.canWrite, queueBusy: Boolean = false,
    onCancelQueued: (String) -> Unit, onSteerQueued: (String) -> Unit, onResumeQueue: () -> Unit,
) {
    val c = Tf.colors
    val noticeStyle = TrefoilType.meta.copy(fontSize = 12.sp, color = c.muted)
    val button = resolveComposerButton(state, draft != null, text, waiting, stopPending)
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp, top = 4.dp)) {
        if (!state.canWrite) Text("Read only · reconnect to continue", Modifier.padding(start = 6.dp, bottom = 6.dp), style = noticeStyle)
        if (waiting) Text("Waiting for server confirmation…", Modifier.padding(start = 6.dp, bottom = 6.dp), style = noticeStyle)
        if (draft == null && (state.queuedMessages.isNotEmpty() || state.queueHeld)) QueuedMessagesStrip(state, canWriteNow && !queueBusy, onCancelQueued, onSteerQueued, onResumeQueue)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
            Column {
                BasicTextField(
                    value = text, onValueChange = onText, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) Text(composerPlaceholder(state, draft != null), style = MaterialTheme.typography.bodyLarge, color = c.muted)
                            inner()
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ComposerPickers(state, draft, onSetRuntimeMode, onSetModel, onSetModelOption, Modifier.weight(1f))
                    PrimaryComposerButton(button, state.canSteer, stop, onSend)
                }
            }
        }
    }
}

/** Stop, Send, Steer or Queue. While a follow-up can be sent, a long press offers both delivery modes. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrimaryComposerButton(button: ComposerButton, canSteer: Boolean, stop: () -> Unit, onSend: (FollowUpMode?) -> Unit) {
    val c = Tf.colors
    var menu by remember { mutableStateOf(false) }
    val (icon, container, content) = when (button.action) {
        ComposerPrimary.Stop -> Triple(TfIcons.Stop, c.text, c.bg)
        ComposerPrimary.Steer -> Triple(TfIcons.Steer, c.accent, c.onAccent)
        ComposerPrimary.Queue -> Triple(TfIcons.Queue, c.accent, c.onAccent)
        ComposerPrimary.Send -> Triple(TfIcons.Send, c.accent, c.onAccent)
    }
    // The menu only makes sense while a follow-up can be sent: close it when the run ends or writes stop.
    LaunchedEffect(button.offersAlternate, button.enabled) { if (!button.offersAlternate || !button.enabled) menu = false }
    Box {
        Surface(shape = CircleShape, color = container, contentColor = content,
            modifier = Modifier.size(44.dp).alpha(if (button.enabled) 1f else .4f)
                .semantics { contentDescription = button.action.description; role = Role.Button }
                .combinedClickable(
                    enabled = button.enabled,
                    onLongClickLabel = if (button.offersAlternate) "Choose steer or queue" else null,
                    onLongClick = if (button.offersAlternate) ({ menu = true }) else null,
                    onClick = { if (button.action == ComposerPrimary.Stop) stop() else onSend(button.action.followUp) },
                )) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
        }
        DropdownMenu(menu && button.offersAlternate && button.enabled, { menu = false }) {
            DropdownMenuItem(text = { Column { Text("Steer message"); Text(if (canSteer) "Redirect the running turn now" else "Not supported by this agent right now", style = MaterialTheme.typography.labelSmall, color = c.muted) } },
                leadingIcon = { Icon(TfIcons.Steer, null, Modifier.size(18.dp), tint = c.muted) }, enabled = canSteer,
                onClick = { menu = false; onSend(FollowUpMode.Steer) })
            DropdownMenuItem(text = { Column { Text("Queue message"); Text("Send after the current turn finishes", style = MaterialTheme.typography.labelSmall, color = c.muted) } },
                leadingIcon = { Icon(TfIcons.Queue, null, Modifier.size(18.dp), tint = c.muted) },
                onClick = { menu = false; onSend(FollowUpMode.Queue) })
        }
    }
}

/** Most queued rows the strip shows at once; more scroll inside it, so the editor and its actions stay on screen. */
internal val QUEUE_STRIP_MAX_HEIGHT = 144.dp

/**
 * User messages waiting behind the running turn: send one now, remove it, or resume a held queue. A held queue shows its
 * "Queue paused" header with Resume even when no row is listed (held automatic wakes count as held but are not rows).
 * [canWrite] already folds in a queue command in flight.
 */
@Composable
internal fun QueuedMessagesStrip(state: ClientState, canWrite: Boolean, onCancel: (String) -> Unit, onSteer: (String) -> Unit, onResume: () -> Unit) {
    val c = Tf.colors
    Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("queued-strip"), shape = RoundedCornerShape(16.dp), color = c.s2, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            if (state.queueHeld) Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Queue paused", Modifier.weight(1f), style = TrefoilType.meta, color = c.amber)
                TextButton(onClick = onResume, enabled = canWrite) { Text("Resume") }
            }
            Column(Modifier.heightIn(max = QUEUE_STRIP_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                state.queuedMessages.forEach { queued ->
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(TfIcons.Queue, null, Modifier.size(16.dp), tint = c.muted)
                        Text(queued.text, Modifier.weight(1f).padding(horizontal = 8.dp), style = TrefoilType.preview, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onSteer(queued.runId) }, enabled = canWrite && queued.canSendNow) { Text("Send now") }
                        IconButton(onClick = { onCancel(queued.runId) }, enabled = canWrite, modifier = Modifier.size(40.dp)) {
                            Icon(TfIcons.Close, "Remove queued message", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Provider-native children cannot receive messages: model, status and a way back to the parent replace the composer. */
@Composable
internal fun ReadOnlySubagentBar(thread: ThreadSummary, now: Long, onOpenParent: (() -> Unit)?) {
    val c = Tf.colors
    Surface(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp, top = 4.dp).fillMaxWidth().testTag("readonly-subagent-bar"),
        shape = RoundedCornerShape(24.dp), color = c.s1, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Runs on its own", style = MaterialTheme.typography.bodyMedium, color = c.text)
                val info = listOfNotNull(thread.model?.takeIf { it.isNotBlank() }, threadStatusLabel(thread, now)).joinToString(" · ")
                Text(info, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOpenParent != null) OutlinedButton(onClick = onOpenParent) { Text("Open parent") }
        }
    }
}

@Composable
private fun ComposerPickers(state: ClientState, draft: ThreadDraft?, onSetRuntimeMode: (RuntimeMode) -> Unit, onSetModel: (ModelOption) -> Unit, onSetModelOption: (String, Any) -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val mode = if (draft != null) draft.runtimeMode else state.selectedRuntimeMode
    val model = if (draft != null) draft.model else state.selectedModel
    val enabled = state.canWrite && !state.busy
    var modeOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f, fill = false)) {
            val shown = catalogModel(model, state.models)
            val explicit = if (draft != null) draft.modelOptions else state.selectedModelOptions
            val pillLabel = modelPillLabel(shown, explicit)
            TfPill(pillLabel, { modelOpen = true }, icon = TfIcons.Sparkle, enabled = enabled && state.models.isNotEmpty(), contentDescription = "Model: ${if (shown == null) "not set" else pillLabel}")
            if (modelOpen) ModelPickerSheet(
                models = pickerModels(state.models, model, state.selectedProviderLocked, draft != null), providers = state.providers,
                current = shown, explicit = explicit, enabled = enabled, dismiss = { modelOpen = false },
                onSetModel = onSetModel, onSetModelOption = onSetModelOption, threadSelection = draft == null,
            )
        }
        val effortShown = catalogModel(model, state.models)
        val effort = reasoningChoice(effortShown, if (draft != null) draft.modelOptions else state.selectedModelOptions, threadSelection = draft == null)
        val reasoning = effortShown?.reasoning
        if (reasoning != null && effort != null) {
            val choices = reasoning.selectableChoices
            TfIconPill(TfIcons.effort(effortBarCount(choices.indexOfFirst { it.id == effort.id }, choices.size)), { modelOpen = true },
                enabled = enabled && state.models.isNotEmpty(), contentDescription = "${reasoning.label.ifBlank { "Reasoning" }}: ${effort.label}")
        }
        Box {
            TfIconPill(mode?.icon ?: TfIcons.Shield, { modeOpen = true }, enabled = enabled, contentDescription = "Runtime mode: ${mode?.label ?: "not set"}")
            DropdownMenu(modeOpen, { modeOpen = false }) {
                allowedRuntimeModes(model).forEach { option ->
                    DropdownMenuItem(text = { Column { Text(option.label); Text(option.description, style = MaterialTheme.typography.labelSmall, color = c.muted) } },
                        leadingIcon = { Icon(option.icon, null, Modifier.size(18.dp), tint = c.muted) },
                        onClick = { modeOpen = false; onSetRuntimeMode(option) },
                        trailingIcon = { if (option == mode) Icon(TfIcons.Check, "Selected", Modifier.size(18.dp)) })
                }
            }
        }
    }
}

/**
 * While an agent works, every server event briefly marks the thread stale until its refetch lands, so the raw
 * `canWrite` blinks. Keep thread controls enabled through gaps shorter than [STALE_GRACE_MS] while the
 * connection itself is healthy; the client waits for freshness if one is tapped mid-gap. Any real outage
 * (disconnected, shell not ready) disables them immediately.
 */
@Composable
internal fun rememberSteadyCanWrite(state: ClientState): Boolean {
    val live = state.canWrite
    val healthy = state.canLaunchThreads
    var held by remember(state.environment?.id, state.selectedThreadId) { mutableStateOf(live) }
    LaunchedEffect(live, healthy) {
        if (live) held = true
        else if (!healthy) held = false
        else { delay(STALE_GRACE_MS); held = false }
    }
    return live || (held && healthy)
}

private const val STALE_GRACE_MS = 1_500L


private const val LOAD_EARLIER_KEY = "load-earlier"

/** T3's history control: a full-width secondary button at the top of the list, explicit tap only. */
@Composable
internal fun LoadEarlierTurnsRow(onClick: () -> Unit) {
    val c = Tf.colors
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(LOAD_EARLIER_KEY),
        border = BorderStroke(1.dp, c.line), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.muted)) {
        Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.load_earlier_turns), style = TrefoilType.meta.copy(color = c.muted))
    }
}

/** Launching a thread needs the shell (projects, models), not a fresh projection of whichever thread is open. `canWrite` implies it. */
internal val ClientState.canLaunchThreads: Boolean get() = canWrite || (canWriteThreads && connection == ConnectionStatus.Connected)

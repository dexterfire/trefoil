package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ThreadSummary
import java.util.Locale

/**
 * In-app signals for threads that started waiting for the user while another screen is open.
 * Everything here is pure; thread titles are conversation content and are never logged.
 */

/** Open (not archived, settled or snoozed) threads that need approval or input, in thread-list order. */
internal fun needsYouThreads(threads: List<ThreadSummary>, now: Long = System.currentTimeMillis()): List<ThreadSummary> =
    threadSections(threadShelves(threads, now), ThreadFilter.NeedsYou).flatMap { it.threads }

/** Threads in [needsYou] that were not needing the user in [previousIds] and are not the open conversation. */
internal fun threadsToAnnounce(previousIds: Set<String>, needsYou: List<ThreadSummary>, openThreadId: String?): List<ThreadSummary> =
    needsYou.filter { it.id !in previousIds && it.id != openThreadId }

/**
 * Edge-trigger memory. [needsYouIds] is null until a live snapshot has been adopted, so threads that were
 * already waiting at first load, connect, reconnect or environment switch never fire.
 */
internal data class AttentionTrack(val environmentId: String? = null, val needsYouIds: Set<String>? = null)

internal data class AttentionStep(val track: AttentionTrack, val announce: List<ThreadSummary>)

/**
 * One observation of the client state. [connected] is a live connection; [fresh] means the thread snapshot
 * is current (not the cache kept across a reconnect). Leaving the connection or switching environment
 * forgets the baseline; the next fresh snapshot is adopted silently.
 */
internal fun attentionStep(
    track: AttentionTrack, environmentId: String?, connected: Boolean, fresh: Boolean,
    threads: List<ThreadSummary>, openThreadId: String?, now: Long = System.currentTimeMillis(),
): AttentionStep {
    val base = if (environmentId != track.environmentId || !connected) AttentionTrack(environmentId, null) else track
    if (!connected) return AttentionStep(base, emptyList())
    val previous = base.needsYouIds
    if (previous == null && !fresh) return AttentionStep(base, emptyList())
    val needsYou = needsYouThreads(threads, now)
    val ids = needsYou.mapTo(HashSet()) { it.id }
    val announce = if (previous == null) emptyList() else threadsToAnnounce(previous, needsYou, openThreadId)
    return AttentionStep(AttentionTrack(environmentId, ids), announce)
}

/** What to show for a batch of newly waiting threads; [threadId] is the thread to open, null for the list. */
internal data class AttentionMessage(val text: String, val threadId: String?, val threadIds: List<String> = listOfNotNull(threadId))

/** A queued message is still worth showing while one of its threads still needs the user and is not on screen. */
internal fun attentionStillRelevant(message: AttentionMessage, needsYou: List<ThreadSummary>, openThreadId: String?): Boolean =
    message.threadIds.any { id -> id != openThreadId && needsYou.any { it.id == id } }

internal fun attentionMessage(announce: List<ThreadSummary>): AttentionMessage? = when (announce.size) {
    0 -> null
    1 -> announce[0].let { thread ->
        val title = thread.title.ifBlank { "Untitled conversation" }.let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
        val kind = threadIndicator(thread).label.replaceFirstChar { it.lowercase(Locale.ROOT) }
        AttentionMessage("$title $kind", thread.id)
    }
    else -> AttentionMessage("${announce.size} threads need you", null, announce.map { it.id })
}

/** Content description for the conversation back arrow, naming how many other threads wait. */
internal fun backDescription(otherNeedsYou: Int): String = when {
    otherNeedsYou <= 0 -> "Back to threads"
    otherNeedsYou == 1 -> "Back to threads, 1 thread needs you"
    else -> "Back to threads, $otherNeedsYou threads need you"
}

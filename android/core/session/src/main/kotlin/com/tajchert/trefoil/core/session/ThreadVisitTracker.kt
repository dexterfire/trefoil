package com.tajchert.trefoil.core.session

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Decides when to record a `thread.visit` for the thread the user is looking at, following T3's
 * client runtime: one open (`opened: true`) once the thread's projection is loaded, then
 * watermark-only visits (`opened` omitted) when new activity arrives, at most one per [throttleMillis].
 * It only decides; the caller sends fire-and-forget and never queues or replays.
 */
internal class ThreadVisitTracker(private val throttleMillis: Long = VISIT_THROTTLE_MS) {
    sealed interface Action {
        data object None : Action
        data class Send(val opened: Boolean) : Action
        /** Activity is waiting out the throttle; evaluate again after [millis]. */
        data class Wait(val millis: Long) : Action
    }

    private var threadId: String? = null
    private var openPending = false
    private var lastRevision = -1L
    private var lastSentAt = 0L

    /** A new subscription for [threadId] (new selection or new connection): the next ready state is an open. */
    fun reset(threadId: String?) {
        this.threadId = threadId
        openPending = threadId != null
        lastRevision = -1L
        lastSentAt = 0L
    }

    /**
     * [eligible] means connected, writable, fresh and the thread is on screen in the foreground;
     * [revision] changes whenever the thread's projection does.
     */
    fun evaluate(threadId: String?, eligible: Boolean, revision: Long, nowMillis: Long): Action {
        if (threadId == null || threadId != this.threadId || !eligible) return Action.None
        if (openPending) return send(true, revision, nowMillis)
        if (revision == lastRevision) return Action.None
        val elapsed = nowMillis - lastSentAt
        return if (elapsed >= throttleMillis) send(false, revision, nowMillis) else Action.Wait(throttleMillis - elapsed)
    }

    private fun send(opened: Boolean, revision: Long, nowMillis: Long): Action {
        openPending = false
        lastRevision = revision
        lastSentAt = nowMillis
        return Action.Send(opened)
    }

    companion object {
        const val VISIT_THROTTLE_MS = 10_000L

        /** `visitedAt` is the thread's own `updatedAt` (as T3 clients do), so device clock skew cannot mark unseen work read; null when it is missing or unparseable, and no visit is sent. */
        fun command(threadId: String, projection: JsonObject, opened: Boolean): JsonObject? {
            val updatedAt = ((projection["thread"] as? JsonObject)?.get("updatedAt") as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { runCatching { Instant.parse(it) }.isSuccess }
            if (updatedAt == null) return null
            return buildJsonObject {
                put("type", "thread.visit"); put("commandId", UUID.randomUUID().toString()); put("threadId", threadId)
                put("visitedAt", updatedAt)
                if (opened) put("opened", true)
            }
        }
    }
}

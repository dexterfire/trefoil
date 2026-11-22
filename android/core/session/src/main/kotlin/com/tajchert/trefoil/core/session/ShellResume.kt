package com.tajchert.trefoil.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** T3 client-runtime `CACHE_RESUME_MAX_AGE_MILLIS`: rows older than this reload instead of resuming. */
internal const val SHELL_RESUME_MAX_AGE_MS = 12L * 60 * 60 * 1000

/**
 * The server state shell sequences belong to (T3 client-runtime `shellIdentity`). Sequences only mean the
 * same rows on the same environment, build and database migration: migrations rewrite projections without
 * events, so a migrated or updated server answers with a fresh snapshot.
 */
internal data class ShellOrigin(val environmentId: String, val serverVersion: String, val databaseMigration: Long?)

/** `server.getConfig` `environment` descriptor; null when it does not name its build or names another environment. */
internal fun shellOrigin(environmentId: String, config: JsonObject): ShellOrigin? {
    val environment = config["environment"] as? JsonObject ?: return null
    fun text(key: String) = (environment[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
    val version = text("serverVersion").ifEmpty { return null }
    val reported = text("environmentId")
    if (reported.isNotEmpty() && reported != environmentId) return null
    val migration = environment["databaseMigration"]
    val databaseMigration = when {
        migration == null -> null
        migration is JsonPrimitive && !migration.isString -> migration.longOrNull ?: return null
        else -> return null
    }
    return ShellOrigin(environmentId, version, databaseMigration)
}

/**
 * The row lists a cursor describes, compared by identity: only the exact lists the tracked items produced
 * can resume, never rows that reached the state another way (an empty start, a re-paired environment).
 */
internal class ShellRows(val projects: List<*>, val threads: List<*>) {
    fun sameAs(other: ShellRows) = projects === other.projects && threads === other.threads
}

/**
 * The shell rows' cursor: the highest applied `sequence` since the authoritative snapshot taken at [snapshotAtMillis].
 * [origin] is null only while the subscription that recorded it waits for `server.getConfig`; such a cursor never resumes.
 */
internal data class ShellCursor(val origin: ShellOrigin?, val sequence: Long, val snapshotAtMillis: Long, val rows: ShellRows)

/** `OrchestrationV2SubscribeShellInput`. Without `httpSnapshotFallback`, a gap the server cannot replay answers a socket snapshot. */
internal fun shellSubscribeInput(afterSequence: Long?): JsonObject = buildJsonObject {
    afterSequence?.let { put("afterSequence", it) }
    put("requestCompletionMarker", true)
    // Native grouping needs complete lineage and all settled rows for exact counts.
    put("omitIdleSubagents", false); put("omitOlderSettled", false)
}

private val shellDeltaKinds = setOf("project.updated", "project.removed", "thread.updated", "thread.removed")

private fun JsonObject.sequence(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

/**
 * Memory-only shell cursors per environment, so a reconnect to an environment whose rows are retained
 * subscribes with `afterSequence` and the server replays the missed deltas instead of a full snapshot.
 *
 * A cursor exists only while it exactly describes the retained rows: an authoritative snapshot sets it,
 * each delta raises it to its `sequence`, and anything it cannot account for (a snapshot or delta without a
 * sequence, `snapshot-required`, an unknown server build) drops it, so the next subscription loads a snapshot.
 * Project enrichment snapshots never move it: their `snapshotSequence` can be ahead of the deltas delivered.
 * Not thread-safe; the client calls it under its lifecycle lock.
 */
internal class ShellResume(private val clockMillis: () -> Long = System::currentTimeMillis) {
    private val cursors = mutableMapOf<String, ShellCursor>()
    private var active: ShellOrigin? = null
    private var activeEnvironmentId: String? = null
    /** The active subscription started before its origin was known; see [beginBeforeOrigin]. */
    private var originPending = false

    fun cursor(environmentId: String): ShellCursor? = cursors[environmentId]

    /**
     * Whether [environmentId] has a cursor that could resume [rows] if the server origin still matches. False means
     * no origin can make [begin] resume, so the subscription may start before `server.getConfig` answers.
     */
    fun mayResume(environmentId: String, rows: ShellRows): Boolean {
        val cursor = cursors[environmentId] ?: return false
        val age = clockMillis() - cursor.snapshotAtMillis
        return cursor.origin != null && cursor.rows.sameAs(rows) && age in 0 until SHELL_RESUME_MAX_AGE_MS
    }

    /**
     * Starts a snapshot subscription for [environmentId] before the server origin is known (only when [mayResume] is
     * false). The snapshot it records waits for [originKnown]: a known origin completes the cursor, none drops it.
     */
    fun beginBeforeOrigin(environmentId: String, retainedEnvironments: Set<String>) {
        cursors.keys.retainAll(retainedEnvironments + environmentId)
        activeEnvironmentId = environmentId
        active = null
        originPending = true
        cursors.remove(environmentId)
    }

    /** The origin of a subscription started with [beginBeforeOrigin]; a no-op otherwise. */
    fun originKnown(origin: ShellOrigin?) {
        if (!originPending) return
        originPending = false
        active = origin
        val environmentId = activeEnvironmentId ?: return
        val cursor = cursors[environmentId] ?: return
        if (origin == null) cursors.remove(environmentId) else cursors[environmentId] = cursor.copy(origin = origin)
    }

    /**
     * Starts a shell subscription for [environmentId] showing [rows]. Returns the `afterSequence` to resume
     * from, or null to load a snapshot: only the very rows the cursor was recorded with resume. Cursors of
     * environments not in [retainedEnvironments] are dropped with their rows.
     */
    fun begin(environmentId: String, origin: ShellOrigin?, rows: ShellRows, retainedEnvironments: Set<String>): Long? {
        cursors.keys.retainAll(retainedEnvironments + environmentId)
        activeEnvironmentId = environmentId
        active = origin
        originPending = false
        val cursor = cursors[environmentId]
        val age = cursor?.let { clockMillis() - it.snapshotAtMillis }
        val resumable = cursor != null && cursor.rows.sameAs(rows) && origin != null && cursor.origin == origin &&
            age != null && age in 0 until SHELL_RESUME_MAX_AGE_MS
        if (!resumable) { cursors.remove(environmentId); return null }
        return cursor!!.sequence
    }

    /**
     * Installs a cursor read back from the local cache for rows restored as [cursor]'s [ShellCursor.rows]. It resumes
     * only under the same rules as any other ([begin]: same origin, same row lists, younger than
     * [SHELL_RESUME_MAX_AGE_MS]); a cursor without an origin or with a negative sequence is ignored.
     */
    fun restore(environmentId: String, cursor: ShellCursor) {
        if (cursor.origin == null || cursor.origin.environmentId != environmentId || cursor.sequence < 0) return
        cursors[environmentId] = cursor
    }

    /** Drops the active environment's cursor; the next subscription loads a snapshot. */
    fun invalidate() { activeEnvironmentId?.let(cursors::remove) }

    /** Drops the cursor of [environmentId], or every cursor when null. */
    fun forget(environmentId: String?) { if (environmentId == null) cursors.clear() else cursors.remove(environmentId) }

    /** Records [item] after the client applied it to the active environment's rows, which are now [rows]. */
    fun applied(item: JsonObject, rows: ShellRows) {
        val environmentId = activeEnvironmentId ?: return
        when ((item["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "snapshot" -> if (item["resolvedRepositoryIdentityRoots"] !is JsonArray) {
                val origin = active
                val sequence = (item["snapshot"] as? JsonObject)?.sequence("snapshotSequence")
                if ((origin == null && !originPending) || sequence == null) cursors.remove(environmentId)
                else cursors[environmentId] = ShellCursor(origin, sequence, clockMillis(), rows)
                return
            }
            "snapshot-required" -> { cursors.remove(environmentId); return }
            in shellDeltaKinds -> {
                val cursor = cursors[environmentId] ?: return
                val sequence = item.sequence("sequence")
                if (sequence == null || cursor.origin != active) { cursors.remove(environmentId); return }
                cursors[environmentId] = cursor.copy(sequence = maxOf(sequence, cursor.sequence), rows = rows)
                return
            }
        }
        // Enrichment and markers keep the sequence; the rows they leave are still the cursor's.
        cursors[environmentId]?.let { cursors[environmentId] = it.copy(rows = rows) }
    }
}

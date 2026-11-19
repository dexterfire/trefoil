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

/** The shell rows' cursor: the highest applied `sequence` since the authoritative snapshot taken at [snapshotAtMillis]. */
internal data class ShellCursor(val origin: ShellOrigin, val sequence: Long, val snapshotAtMillis: Long)

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

    fun cursor(environmentId: String): ShellCursor? = cursors[environmentId]

    /**
     * Starts a shell subscription for [environmentId] whose rows are retained when [rowsRetained].
     * Returns the `afterSequence` to resume from, or null to load a snapshot. Cursors of environments
     * not in [retainedEnvironments] are dropped with their rows.
     */
    fun begin(environmentId: String, origin: ShellOrigin?, rowsRetained: Boolean, retainedEnvironments: Set<String>): Long? {
        cursors.keys.retainAll(retainedEnvironments + environmentId)
        activeEnvironmentId = environmentId
        active = origin
        val cursor = cursors[environmentId]
        val age = cursor?.let { clockMillis() - it.snapshotAtMillis }
        val resumable = cursor != null && rowsRetained && origin != null && cursor.origin == origin &&
            age != null && age in 0 until SHELL_RESUME_MAX_AGE_MS
        if (!resumable) { cursors.remove(environmentId); return null }
        return cursor!!.sequence
    }

    /** Drops the active environment's cursor; the next subscription loads a snapshot. */
    fun invalidate() { activeEnvironmentId?.let(cursors::remove) }

    /** Records [item] after the client applied it to the active environment's rows. */
    fun applied(item: JsonObject) {
        val environmentId = activeEnvironmentId ?: return
        when ((item["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "snapshot" -> {
                if (item["resolvedRepositoryIdentityRoots"] is JsonArray) return
                val origin = active
                val sequence = (item["snapshot"] as? JsonObject)?.sequence("snapshotSequence")
                if (origin == null || sequence == null) cursors.remove(environmentId)
                else cursors[environmentId] = ShellCursor(origin, sequence, clockMillis())
            }
            "snapshot-required" -> cursors.remove(environmentId)
            in shellDeltaKinds -> {
                val cursor = cursors[environmentId] ?: return
                val sequence = item.sequence("sequence")
                if (sequence == null || cursor.origin != active) { cursors.remove(environmentId); return }
                if (sequence > cursor.sequence) cursors[environmentId] = cursor.copy(sequence = sequence)
            }
        }
    }
}

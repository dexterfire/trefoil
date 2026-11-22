package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ThreadSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The resume cursor of a cached shell: the server build ([serverVersion], [databaseMigration]) its [sequence] belongs
 * to and when its authoritative snapshot was taken. It resumes only under [ShellResume]'s rules.
 */
@Serializable
data class CachedShellCursor(val serverVersion: String, val databaseMigration: Long? = null, val sequence: Long, val snapshotAtMillis: Long)

/**
 * One environment's shell rows as last applied, for showing them at the next app start before the connection is up.
 * [cursor] is null when the rows cannot resume (the next connect loads a full snapshot). Holds thread titles and latest
 * message text: implementations store it encrypted and never log it.
 */
data class CachedShell(
    val environmentId: String,
    val projects: List<Project>,
    val threads: List<ThreadSummary>,
    val cursor: CachedShellCursor?,
    val savedAtMillis: Long,
) {
    override fun toString() = "CachedShell(projects=${projects.size}, threads=${threads.size}, cursor=${cursor != null})"
}

/**
 * Local storage of [CachedShell]s, one entry per environment (implemented encrypted in the app). Content never reaches
 * logs, exception messages or file names.
 */
interface ShellCacheStore {
    /** The stored entry, or null when there is none or it is unusable (corrupt, another format version, another environment). */
    suspend fun read(environmentId: String): CachedShell?
    /** Replaces the entry; one larger than [ShellCacheCodec.MAX_ENCODED_BYTES] is not written and removes the older one. */
    suspend fun write(shell: CachedShell)
    suspend fun delete(environmentId: String)
    /** Deletes every entry except those of [environmentIds] (machines forgotten, or a vault replaced). */
    suspend fun retainOnly(environmentIds: Set<String>)
}

/**
 * The versioned file format of a [CachedShell]: JSON `{version, environmentId, savedAtMillis, cursor?, projects, threads}`.
 * Unknown fields are ignored and missing ones take their defaults, so additive model changes keep old files readable;
 * a file of another [VERSION], for another environment, or that does not parse decodes to null.
 */
object ShellCacheCodec {
    const val VERSION = 1
    /** Larger snapshots are not cached (about 4 MB of JSON is thousands of threads). */
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024

    @Serializable
    private class ShellCacheFile(
        val version: Int,
        val environmentId: String,
        val savedAtMillis: Long = 0,
        val cursor: CachedShellCursor? = null,
        val projects: List<Project> = emptyList(),
        val threads: List<ThreadSummary> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /** The encoded entry, or null when it exceeds [maxBytes]. */
    fun encode(shell: CachedShell, maxBytes: Int = MAX_ENCODED_BYTES): ByteArray? {
        val bytes = json.encodeToString(ShellCacheFile.serializer(),
            ShellCacheFile(VERSION, shell.environmentId, shell.savedAtMillis, shell.cursor, shell.projects, shell.threads)).encodeToByteArray()
        return bytes.takeIf { it.size <= maxBytes }
    }

    /** Never throws: anything unreadable is null, and no part of the content goes into an exception or log. */
    fun decode(bytes: ByteArray, environmentId: String): CachedShell? = try {
        val element = json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        val version = (element?.get("version") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        if (element == null || version != VERSION) null else {
            val file = json.decodeFromJsonElement(ShellCacheFile.serializer(), element)
            if (file.environmentId != environmentId) null
            else CachedShell(environmentId, file.projects, file.threads,
                file.cursor?.takeIf { it.sequence >= 0 && it.snapshotAtMillis > 0 && it.serverVersion.isNotBlank() }, file.savedAtMillis)
        }
    } catch (_: Exception) { null }
}

/**
 * Writes shells to [store] off the caller's thread: [changed] schedules a write [delayMillis] later (later changes ride
 * on it, so a busy shell is written at most that often), [flush] writes at once, and [wipe] / [retainOnly] remove
 * entries. Operations run one at a time in order on [scope], and an entry whose content did not change since it was
 * written or restored ([markWritten]) is not written again. [snapshot] returns what to store for an environment, or null
 * when it must not be stored (no rows, or the machine is not saved); a scheduled write takes it when it runs, a flush when
 * it is called. Write failures are dropped: the cache is an optimization and never fails the app.
 */
class ShellCacheWriter(
    private val store: ShellCacheStore,
    private val scope: CoroutineScope,
    private val snapshot: (String) -> CachedShell?,
    private val delayMillis: Long = 3_000,
) {
    /** What was last written or restored per environment, compared by row-list identity. */
    private class Written(val projects: List<Project>, val threads: List<ThreadSummary>, val cursor: CachedShellCursor?)

    private val lock = Any()
    private val scheduled = mutableMapOf<String, Job>()
    private val written = mutableMapOf<String, Written>()
    /** Wipes per environment, so a flush captured before a wipe never writes after it. */
    private val wipes = mutableMapOf<String, Int>()
    /** The environments of the last [retainOnly]; others are never written afterwards. Null until the first one. */
    private var retained: Set<String>? = null
    private val mutex = Mutex()

    /** [environmentId]'s rows changed; written within [delayMillis]. */
    fun changed(environmentId: String) = synchronized(lock) {
        if (scheduled[environmentId]?.isActive == true) return@synchronized
        scheduled[environmentId] = scope.launch {
            delay(delayMillis)
            synchronized(lock) { scheduled.remove(environmentId) }
            mutex.withLock {
                val wiped = synchronized(lock) { wipesOf(environmentId) }
                snapshot(environmentId)?.let { persist(it, wiped) }
            }
        }
    }

    /** Writes [environmentId]'s current rows now (background, disconnect); returns the write's job. */
    fun flush(environmentId: String): Job {
        val shell = snapshot(environmentId)
        val wiped = synchronized(lock) { scheduled.remove(environmentId)?.cancel(); wipesOf(environmentId) }
        return scope.launch { if (shell != null) mutex.withLock { persist(shell, wiped) } }
    }

    /** [shell] is what the store already holds (just read and restored), so it is not written back unchanged. */
    fun markWritten(shell: CachedShell) = synchronized(lock) {
        written[shell.environmentId] = Written(shell.projects, shell.threads, shell.cursor)
    }

    /** Removes [environmentId]'s entry after any write already running; a scheduled one is cancelled. */
    suspend fun wipe(environmentId: String) {
        synchronized(lock) { scheduled.remove(environmentId)?.cancel(); written.remove(environmentId); wipes[environmentId] = wipesOf(environmentId) + 1 }
        mutex.withLock { attempt { store.delete(environmentId) } }
    }

    /** Removes every entry except those of [environmentIds]. */
    suspend fun retainOnly(environmentIds: Set<String>) {
        synchronized(lock) {
            scheduled.keys.filter { it !in environmentIds }.forEach { scheduled.remove(it)?.cancel() }
            written.keys.retainAll(environmentIds)
            retained = environmentIds.toSet()
        }
        mutex.withLock { attempt { store.retainOnly(environmentIds) } }
    }

    private fun wipesOf(environmentId: String) = wipes[environmentId] ?: 0

    /** Caller holds [mutex]; [wiped] is the environment's wipe count when [shell] was taken. */
    private suspend fun persist(shell: CachedShell, wiped: Int) {
        val id = shell.environmentId
        val last = synchronized(lock) {
            if (wipesOf(id) != wiped || retained?.contains(id) == false) return
            written[id]
        }
        if (last != null && last.projects === shell.projects && last.threads === shell.threads && last.cursor == shell.cursor) return
        if (attempt { store.write(shell) }) synchronized(lock) { written[id] = Written(shell.projects, shell.threads, shell.cursor) }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try { block(); true }
    catch (error: CancellationException) { throw error } catch (_: Exception) { false }
}

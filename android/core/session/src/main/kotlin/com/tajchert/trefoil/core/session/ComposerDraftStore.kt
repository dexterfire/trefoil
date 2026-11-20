package com.tajchert.trefoil.core.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Identifies one local, unsent composer text. Not the launch-reconciling `ThreadDraft`: a composer draft
 * never reaches the server and is never sent on its own.
 */
sealed interface ComposerDraftKey {
    val environmentId: String
    /** Partially typed reply in an existing thread. */
    data class Reply(override val environmentId: String, val threadId: String) : ComposerDraftKey
    /** Partially typed first prompt in the new-thread sheet; one per (environment, project), as in T3 Code. */
    data class NewThread(override val environmentId: String, val projectId: String) : ComposerDraftKey
}

/**
 * Identity of a `launchThread` started from a NewThread draft whose outcome is not known yet. A relaunch
 * reuses [threadId] and [commandId], so the server resumes instead of creating a duplicate. [text] is the
 * draft text at launch: the draft is cleared once the thread exists only if it was not edited since.
 */
data class PendingLaunch(val threadId: String, val commandId: String, val text: String)

/**
 * Unsent composer text. The settings apply to [ComposerDraftKey.NewThread] only and use the sheet's
 * identifiers: [modelInstanceId] and [model] of the `ModelOption`, [runtimeMode] as `RuntimeMode.wire`,
 * [effort] as the reasoning choice id.
 */
data class ComposerDraft(
    val key: ComposerDraftKey,
    val text: String,
    val modelInstanceId: String? = null,
    val model: String? = null,
    val runtimeMode: String? = null,
    val effort: String? = null,
    val pendingLaunch: PendingLaunch? = null,
    val updatedAtMillis: Long,
)

/** Shown as a draft (row, preview) only when there is text to resume. */
fun ComposerDraft.hasContent(): Boolean = text.isNotBlank()

private fun ComposerDraft.hasSettings(): Boolean = modelInstanceId != null || model != null || runtimeMode != null || effort != null

/**
 * Opaque storage for the serialized drafts; null clears it. Implementations encrypt and must not log the content.
 * [load] returns null when nothing is stored, throws [CorruptComposerDraftsException] for a payload that can
 * never be read (it is then replaced), and throws anything else for a failure that may be transient (the
 * stored file is then never replaced in this session).
 */
interface ComposerDraftPersistence {
    suspend fun load(): String?
    suspend fun save(json: String?)
}

/** Undo token of [ComposerDraftStore.discard]: restores [draft] only while its key is untouched since. */
data class ComposerDraftDiscard(val draft: ComposerDraft, val revision: Long)

class CorruptComposerDraftsException(cause: Throwable? = null) : Exception("Stored composer drafts are unreadable", cause)

/**
 * Local composer drafts of every environment, persisted through [persistence] [debounceMillis] after the
 * last change (or right away on [flush]). Thread-safe; one instance per process shares the map and the write
 * queue, and [scope] should outlive any screen so the load and the final write complete.
 *
 * [drafts] holds only drafts with text; a NewThread draft with settings but no text is kept for the sheet
 * ([get]) but not listed. A corrupt blob starts empty; an unreadable one (I/O, Keystore) is retried once
 * before the first write, and if it still fails nothing is written this session. Draft text is never logged.
 */
class ComposerDraftStore(
    private val persistence: ComposerDraftPersistence,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMillis: Long = 300,
) {
    private enum class LoadState { Loading, Loaded, Unreadable, Blocked }

    private val lock = Any()
    private var all: Map<ComposerDraftKey, ComposerDraft> = emptyMap()
        set(value) { field = value; _records.value = value; _drafts.value = value.filterValues { it.hasContent() } }
    private val _drafts = MutableStateFlow<Map<ComposerDraftKey, ComposerDraft>>(emptyMap())
    /** Drafts with content, by key. Empty until the persisted drafts are loaded. */
    val drafts: StateFlow<Map<ComposerDraftKey, ComposerDraft>> = _drafts.asStateFlow()
    private val _records = MutableStateFlow<Map<ComposerDraftKey, ComposerDraft>>(emptyMap())
    /** Every draft, including settings-only NewThread drafts. */
    val records: StateFlow<Map<ComposerDraftKey, ComposerDraft>> = _records.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    /** True once the first load attempt finished; editors must not write before (a write could replace a stored draft). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val writeMutex = Mutex()
    private var dirty = false
    private var pendingWrite: Job? = null
    private var loadState = LoadState.Loading
    /** Per-key edit counter ([revision]); in memory only, as is everything waiting on it. */
    private val revisions = mutableMapOf<ComposerDraftKey, Long>()
    private var nextRevision = 0L
    /** Changes made before the load finished win over the loaded copy. */
    private val touchedBeforeLoad = mutableSetOf<ComposerDraftKey>()
    private val environmentsClearedBeforeLoad = mutableSetOf<String>()

    private val loading: Job = scope.launch { try { load() } finally { _loaded.value = true } }

    /** Suspends until the first load attempt finished. */
    suspend fun awaitLoaded() = loading.join()

    /** The draft for [key], including a settings-only NewThread draft. */
    fun get(key: ComposerDraftKey): ComposerDraft? = synchronized(lock) { all[key] }

    /** Changes whenever the draft at [key] is edited, cleared, moved or restored; pass it to [clearIfUnchanged]. */
    fun revision(key: ComposerDraftKey): Long = synchronized(lock) { revisions[key] ?: 0L }

    /**
     * Writes the composer state for [key]. Blank text without settings removes the draft. Text is capped at
     * [MAX_TEXT]. A pending launch identity is kept.
     */
    fun update(key: ComposerDraftKey, text: String, modelInstanceId: String? = null, model: String? = null, runtimeMode: String? = null, effort: String? = null): Unit = synchronized(lock) {
        val current = all[key]
        val next = if (key is ComposerDraftKey.Reply) ComposerDraft(key, text.take(MAX_TEXT), updatedAtMillis = clock())
        else ComposerDraft(key, text.take(MAX_TEXT), modelInstanceId, model, runtimeMode, effort, current?.pendingLaunch, clock())
        if (current != null && current.copy(updatedAtMillis = next.updatedAtMillis) == next) return
        if (!next.hasContent() && !next.hasSettings()) return clear(key)
        mutate(key) { it + (key to next) }
    }

    /** Writes [text] keeping the draft's settings and pending launch (the conversation composer edits text only). */
    fun updateText(key: ComposerDraftKey, text: String): Unit = synchronized(lock) {
        val current = all[key]
        if (key is ComposerDraftKey.NewThread && current != null) update(key, text, current.modelInstanceId, current.model, current.runtimeMode, current.effort)
        else update(key, text)
    }

    /**
     * The revision to clear [key] with once a send of [text] is accepted, or null when the stored text is not what
     * is being sent (another editor changed it): then the accepted send must not clear it.
     */
    fun revisionIfText(key: ComposerDraftKey, text: String): Long? = synchronized(lock) {
        if (all[key]?.text?.let { sameSubmission(it, text) } != true) null else revision(key)
    }

    fun clear(key: ComposerDraftKey): Unit = synchronized(lock) {
        if (loadState != LoadState.Loaded) touchedBeforeLoad += key
        if (key !in all) return
        mutate(key) { it - key }
    }

    /** Clears [key] only if it was not edited since [revision] was read (a send accepted after more typing keeps the new text). */
    fun clearIfUnchanged(key: ComposerDraftKey, revision: Long): Boolean = synchronized(lock) {
        if (revision(key) != revision) return false
        clear(key); true
    }

    /** Removes every draft of [environmentId] (the environment was forgotten). */
    fun clearEnvironment(environmentId: String): Unit = synchronized(lock) {
        if (loadState != LoadState.Loaded) environmentsClearedBeforeLoad += environmentId
        val keys = all.keys.filter { it.environmentId == environmentId }
        if (keys.isEmpty()) return
        keys.forEach { revisions[it] = ++nextRevision }
        all = all - keys.toSet()
        scheduleWrite()
    }

    /**
     * Moves the draft at [from] to [to] (the sheet changed project) and returns true, or returns false and changes
     * nothing when [to] already holds a draft, [from] holds none, or the stored drafts are not loaded yet. [from]
     * stays when [keepSource] or when it carries a pending launch (that launch belongs to its project); the moved
     * copy never carries the launch identity.
     */
    fun move(from: ComposerDraftKey, to: ComposerDraftKey, keepSource: Boolean = false): Boolean = synchronized(lock) {
        if (from == to || loadState != LoadState.Loaded || to in all) return false
        val source = all[from] ?: return false
        val keep = keepSource || source.pendingLaunch != null
        revisions[to] = ++nextRevision
        if (!keep) revisions[from] = ++nextRevision
        all = (if (keep) all else all - from) + (to to source.copy(key = to, pendingLaunch = null, updatedAtMillis = clock()))
        scheduleWrite()
        true
    }

    /** Removes the draft at [key] and returns the token to undo that with [restore], or null when there was none. */
    fun discard(key: ComposerDraftKey): ComposerDraftDiscard? = synchronized(lock) {
        val draft = all[key] ?: return null
        clear(key)
        ComposerDraftDiscard(draft, revision(key))
    }

    /** Puts a discarded draft back, only if nothing wrote its key since (a replacement draft is never overwritten). */
    fun restore(discard: ComposerDraftDiscard): Boolean = synchronized(lock) {
        val key = discard.draft.key
        if (revision(key) != discard.revision || key in all) return false
        mutate(key) { it + (key to discard.draft) }
        true
    }

    /**
     * Records, before the launch is dispatched, that [key] is being launched as [threadId]/[commandId] with [text],
     * and writes it right away (no debounce). A record for the same [commandId] stays as first written: the server
     * replays that original payload. Not an edit: the revision stays. Returns false when it could not be persisted
     * (the launch may still go out; only a retry after a restart loses its identity).
     */
    suspend fun recordLaunch(key: ComposerDraftKey.NewThread, threadId: String, commandId: String, text: String): Boolean {
        loading.join()
        synchronized(lock) {
            val current = all[key]
            if (current?.pendingLaunch?.commandId != commandId) {
                val base = current ?: ComposerDraft(key, text.take(MAX_TEXT), updatedAtMillis = clock())
                all = all + (key to base.copy(pendingLaunch = PendingLaunch(threadId, commandId, text.take(MAX_TEXT))))
                dirty = true
            }
            pendingWrite?.cancel(); pendingWrite = null
        }
        return write()
    }

    /** Forgets the launch identity [commandId] of [key] (that launch definitely failed); the text stays. */
    fun dropLaunch(key: ComposerDraftKey.NewThread, commandId: String): Unit = synchronized(lock) {
        val current = all[key] ?: return
        if (current.pendingLaunch?.commandId != commandId) return
        all = all + (key to current.copy(pendingLaunch = null))
        scheduleWrite()
    }

    /**
     * Thread [threadId] exists: every NewThread draft of [environmentId] launched as it is cleared when its text is
     * unchanged since the launch, otherwise it keeps the newer text as a fresh draft without the launch identity.
     */
    fun settleLaunched(environmentId: String, threadId: String): Unit = settleLaunched(environmentId) { it == threadId }

    /** [settleLaunched] for every launch whose thread is in [existingThreadIds] (a connected snapshot of [environmentId]). */
    fun reconcileLaunches(environmentId: String, existingThreadIds: Set<String>): Unit = settleLaunched(environmentId) { it in existingThreadIds }

    private fun settleLaunched(environmentId: String, exists: (String) -> Boolean): Unit = synchronized(lock) {
        val settled = all.values.filter { it.key.environmentId == environmentId && it.pendingLaunch?.threadId?.let(exists) == true }
        if (settled.isEmpty()) return
        settled.forEach { draft ->
            if (draft.pendingLaunch?.text?.let { sameSubmission(draft.text, it) } == true) clear(draft.key)
            else all = all + (draft.key to draft.copy(pendingLaunch = null))
        }
        scheduleWrite()
    }

    /** Writes pending changes now (after the initial load); call when the app stops or a screen is cleared. */
    suspend fun flush() {
        synchronized(lock) { pendingWrite?.cancel(); pendingWrite = null }
        write()
    }

    /** [flush] on the store's own scope, for callers that are being torn down. */
    fun flushInBackground(): Job = scope.launch { flush() }

    private inline fun mutate(key: ComposerDraftKey, transform: (Map<ComposerDraftKey, ComposerDraft>) -> Map<ComposerDraftKey, ComposerDraft>) {
        if (loadState != LoadState.Loaded) touchedBeforeLoad += key
        revisions[key] = ++nextRevision
        all = transform(all)
        scheduleWrite()
    }

    /** Call holding [lock]. */
    private fun scheduleWrite() {
        dirty = true
        pendingWrite?.cancel()
        pendingWrite = scope.launch { delay(debounceMillis); write() }
    }

    /** Reads and merges the stored drafts; false when the storage could not be read (not corrupt: unreadable). */
    private suspend fun load(): Boolean {
        val stored = try { persistence.load()?.let(::decodeOrEmpty).orEmpty() }
        catch (e: CancellationException) { throw e }
        catch (_: CorruptComposerDraftsException) { emptyMap() }
        catch (_: Exception) { synchronized(lock) { loadState = LoadState.Unreadable }; return false }
        synchronized(lock) {
            val kept = stored.filter { (key, _) -> key !in touchedBeforeLoad && key.environmentId !in environmentsClearedBeforeLoad }
            all = kept + all
            loadState = LoadState.Loaded; touchedBeforeLoad.clear(); environmentsClearedBeforeLoad.clear()
            // Entries cleared before the load are still in the file.
            if (kept.size != stored.size) dirty = true
            if (dirty) scheduleWrite()
        }
        return true
    }

    /** True when everything is persisted afterwards. */
    private suspend fun write(): Boolean {
        // Writing before the stored drafts are merged would replace them. A cancelled load leaves nothing safe to write.
        loading.join()
        return writeMutex.withLock {
            // One retry of an unreadable load before the first write; after that the file is left alone.
            if (synchronized(lock) { loadState } == LoadState.Unreadable && !load()) synchronized(lock) { loadState = LoadState.Blocked }
            val snapshot = synchronized(lock) {
                if (loadState != LoadState.Loaded) return@withLock false
                if (!dirty) return@withLock true
                dirty = false
                all
            }
            try { persistence.save(if (snapshot.isEmpty()) null else encode(snapshot)); true }
            catch (e: CancellationException) { synchronized(lock) { dirty = true }; throw e }
            catch (_: Exception) { synchronized(lock) { dirty = true }; false }
        }
    }

    companion object {
        const val MAX_TEXT = 100_000

        /** Whether stored draft text [a] is what was submitted as [b] (a seeded prompt is sent trimmed). */
        fun sameSubmission(a: String, b: String): Boolean = a.take(MAX_TEXT).trim() == b.take(MAX_TEXT).trim()
        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        internal fun encode(drafts: Map<ComposerDraftKey, ComposerDraft>): String =
            json.encodeToString(StoredDrafts.serializer(), StoredDrafts(VERSION, drafts.values.map { it.toStored() }))

        /** Unknown versions, kinds and malformed entries are dropped; malformed JSON throws. */
        internal fun decode(blob: String): Map<ComposerDraftKey, ComposerDraft> {
            val stored = json.decodeFromString(StoredDrafts.serializer(), blob)
            if (stored.v != VERSION) return emptyMap()
            return stored.drafts.mapNotNull { it.toDraft() }.associateBy { it.key }
        }

        /** A blob that decrypted but does not parse can never be read: it is corrupt, not unreadable. */
        private fun decodeOrEmpty(blob: String): Map<ComposerDraftKey, ComposerDraft> =
            try { decode(blob) } catch (_: Exception) { emptyMap() }
    }
}

@Serializable
private data class StoredDrafts(val v: Int, val drafts: List<StoredDraft> = emptyList())

@Serializable
private data class StoredDraft(
    val kind: String, val environmentId: String, val threadId: String? = null, val projectId: String? = null,
    val text: String, val modelInstanceId: String? = null, val model: String? = null, val runtimeMode: String? = null,
    val effort: String? = null, val launch: StoredLaunch? = null, val updatedAtMillis: Long,
)

@Serializable
private data class StoredLaunch(val threadId: String, val commandId: String, val text: String)

private fun ComposerDraft.toStored(): StoredDraft = when (val k = key) {
    is ComposerDraftKey.Reply -> StoredDraft(REPLY, k.environmentId, threadId = k.threadId, text = text, updatedAtMillis = updatedAtMillis)
    is ComposerDraftKey.NewThread -> StoredDraft(NEW_THREAD, k.environmentId, projectId = k.projectId, text = text,
        modelInstanceId = modelInstanceId, model = model, runtimeMode = runtimeMode, effort = effort,
        launch = pendingLaunch?.let { StoredLaunch(it.threadId, it.commandId, it.text) }, updatedAtMillis = updatedAtMillis)
}

private fun StoredDraft.toDraft(): ComposerDraft? {
    val key = when (kind) {
        REPLY -> ComposerDraftKey.Reply(environmentId, threadId ?: return null)
        NEW_THREAD -> ComposerDraftKey.NewThread(environmentId, projectId ?: return null)
        else -> return null
    }
    val draft = if (key is ComposerDraftKey.Reply) ComposerDraft(key, text.take(ComposerDraftStore.MAX_TEXT), updatedAtMillis = updatedAtMillis)
    else ComposerDraft(key, text.take(ComposerDraftStore.MAX_TEXT), modelInstanceId, model, runtimeMode, effort,
        launch?.let { PendingLaunch(it.threadId, it.commandId, it.text) }, updatedAtMillis)
    return draft.takeIf { it.hasContent() || it.hasSettings() }
}

private const val REPLY = "reply"
private const val NEW_THREAD = "newThread"

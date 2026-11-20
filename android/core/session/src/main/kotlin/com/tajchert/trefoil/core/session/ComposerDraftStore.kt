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
    val updatedAtMillis: Long,
)

/** Shown as a draft (row, preview) only when there is text to resume. */
fun ComposerDraft.hasContent(): Boolean = text.isNotBlank()

private fun ComposerDraft.hasSettings(): Boolean = modelInstanceId != null || model != null || runtimeMode != null || effort != null

/** Opaque storage for the serialized drafts; null clears it. Implementations encrypt and must not log the content. */
interface ComposerDraftPersistence {
    suspend fun load(): String?
    suspend fun save(json: String?)
}

/**
 * Local composer drafts of every environment, persisted through [persistence] [debounceMillis] after the
 * last change (or right away on [flush]). Confine calls to one thread (the main dispatcher in the app).
 *
 * [drafts] holds only drafts with text; a NewThread draft with settings but no text is kept for the sheet
 * ([get]) but not listed. A corrupt or unreadable blob starts empty. Draft text is never logged.
 */
class ComposerDraftStore(
    private val persistence: ComposerDraftPersistence,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMillis: Long = 300,
) {
    private var all: Map<ComposerDraftKey, ComposerDraft> = emptyMap()
        set(value) { field = value; _drafts.value = value.filterValues { it.hasContent() } }
    private val _drafts = MutableStateFlow<Map<ComposerDraftKey, ComposerDraft>>(emptyMap())
    /** Drafts with content, by key. Empty until the persisted drafts are loaded. */
    val drafts: StateFlow<Map<ComposerDraftKey, ComposerDraft>> = _drafts.asStateFlow()
    private val writeMutex = Mutex()
    private var dirty = false
    private var pendingWrite: Job? = null
    private var loaded = false
    /** Changes made before the load finished win over the loaded copy. */
    private val touchedBeforeLoad = mutableSetOf<ComposerDraftKey>()
    private val environmentsClearedBeforeLoad = mutableSetOf<String>()

    private val loading: Job = scope.launch {
        val stored = try { persistence.load()?.let(::decode).orEmpty() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
        val kept = stored.filter { (key, _) -> key !in touchedBeforeLoad && key.environmentId !in environmentsClearedBeforeLoad }
        all = kept + all
        loaded = true; touchedBeforeLoad.clear(); environmentsClearedBeforeLoad.clear()
        if (dirty) scheduleWrite()
    }

    /** The draft for [key], including a settings-only NewThread draft. */
    fun get(key: ComposerDraftKey): ComposerDraft? = all[key]

    /** Writes the composer state for [key]. Blank text without settings removes the draft. Text is capped at [MAX_TEXT]. */
    fun update(key: ComposerDraftKey, text: String, modelInstanceId: String? = null, model: String? = null, runtimeMode: String? = null, effort: String? = null) {
        val next = if (key is ComposerDraftKey.Reply) ComposerDraft(key, text.take(MAX_TEXT), updatedAtMillis = clock())
        else ComposerDraft(key, text.take(MAX_TEXT), modelInstanceId, model, runtimeMode, effort, clock())
        val current = all[key]
        if (current != null && current.copy(updatedAtMillis = next.updatedAtMillis) == next) return
        if (!next.hasContent() && !next.hasSettings()) return clear(key)
        mutate(key) { it + (key to next) }
    }

    fun clear(key: ComposerDraftKey) {
        if (!loaded) touchedBeforeLoad += key
        if (key !in all) return
        mutate(key) { it - key }
    }

    /** Removes every draft of [environmentId] (the environment was forgotten). */
    fun clearEnvironment(environmentId: String) {
        if (!loaded) environmentsClearedBeforeLoad += environmentId
        if (all.keys.none { it.environmentId == environmentId }) return
        all = all.filterKeys { it.environmentId != environmentId }
        scheduleWrite()
    }

    /** Moves the draft at [from] to [to] (the sheet changed project), replacing any draft at [to]; [from] stays when [keepSource]. */
    fun move(from: ComposerDraftKey, to: ComposerDraftKey, keepSource: Boolean = false) {
        if (from == to) return
        val source = all[from] ?: return
        val moved = source.copy(key = to, updatedAtMillis = clock())
        if (!loaded) { touchedBeforeLoad += to; if (!keepSource) touchedBeforeLoad += from }
        all = (if (keepSource) all else all - from) + (to to moved)
        scheduleWrite()
    }

    /** Puts back a discarded [draft] unchanged (snackbar undo). */
    fun restore(draft: ComposerDraft) {
        if (!draft.hasContent() && !draft.hasSettings()) return
        mutate(draft.key) { it + (draft.key to draft.copy(text = draft.text.take(MAX_TEXT))) }
    }

    /** Writes pending changes now (after the initial load); call when the app stops or the owner is cleared. */
    suspend fun flush() {
        pendingWrite?.cancel(); pendingWrite = null
        write()
    }

    private inline fun mutate(key: ComposerDraftKey, transform: (Map<ComposerDraftKey, ComposerDraft>) -> Map<ComposerDraftKey, ComposerDraft>) {
        if (!loaded) touchedBeforeLoad += key
        all = transform(all)
        scheduleWrite()
    }

    private fun scheduleWrite() {
        dirty = true
        pendingWrite?.cancel()
        pendingWrite = scope.launch { delay(debounceMillis); write() }
    }

    private suspend fun write() {
        // Writing before the stored drafts are merged would replace them. A cancelled load leaves nothing safe to write.
        loading.join()
        writeMutex.withLock { writeLocked() }
    }

    private suspend fun writeLocked() {
        if (!dirty || !loaded) return
        dirty = false
        val snapshot = all
        try { persistence.save(if (snapshot.isEmpty()) null else encode(snapshot)) }
        catch (e: CancellationException) { dirty = true; throw e }
        catch (_: Exception) { dirty = true }
    }

    companion object {
        const val MAX_TEXT = 100_000
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
    }
}

@Serializable
private data class StoredDrafts(val v: Int, val drafts: List<StoredDraft> = emptyList())

@Serializable
private data class StoredDraft(
    val kind: String, val environmentId: String, val threadId: String? = null, val projectId: String? = null,
    val text: String, val modelInstanceId: String? = null, val model: String? = null, val runtimeMode: String? = null,
    val effort: String? = null, val updatedAtMillis: Long,
)

private fun ComposerDraft.toStored(): StoredDraft = when (val k = key) {
    is ComposerDraftKey.Reply -> StoredDraft(REPLY, k.environmentId, threadId = k.threadId, text = text, updatedAtMillis = updatedAtMillis)
    is ComposerDraftKey.NewThread -> StoredDraft(NEW_THREAD, k.environmentId, projectId = k.projectId, text = text,
        modelInstanceId = modelInstanceId, model = model, runtimeMode = runtimeMode, effort = effort, updatedAtMillis = updatedAtMillis)
}

private fun StoredDraft.toDraft(): ComposerDraft? {
    val key = when (kind) {
        REPLY -> ComposerDraftKey.Reply(environmentId, threadId ?: return null)
        NEW_THREAD -> ComposerDraftKey.NewThread(environmentId, projectId ?: return null)
        else -> return null
    }
    val draft = if (key is ComposerDraftKey.Reply) ComposerDraft(key, text.take(ComposerDraftStore.MAX_TEXT), updatedAtMillis = updatedAtMillis)
    else ComposerDraft(key, text.take(ComposerDraftStore.MAX_TEXT), modelInstanceId, model, runtimeMode, effort, updatedAtMillis)
    return draft.takeIf { it.hasContent() || it.hasSettings() }
}

private const val REPLY = "reply"
private const val NEW_THREAD = "newThread"

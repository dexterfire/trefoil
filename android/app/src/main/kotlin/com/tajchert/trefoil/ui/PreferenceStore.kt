package com.tajchert.trefoil.ui

import android.content.SharedPreferences
import com.tajchert.trefoil.ui.theme.ThemeFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** How the app picks between a theme family's light and dark half. */
enum class BrightnessMode { System, Dark, Light }

/**
 * What the grouped thread list remembers between launches: how projects and their threads are ordered, which projects
 * are pinned, which are open, and the icon and colour each project was given.
 */
data class ListPreferences(
    val pinnedOrder: ProjectOrder = ProjectOrder.Alphabetical,
    val otherOrder: ProjectOrder = ProjectOrder.Activity,
    val threadOrder: ThreadOrder = ThreadOrder.Activity,
    val pinnedProjects: Set<String> = emptySet(),
    val expandedProjects: Set<String> = emptySet(),
    /** False until the user folds something: a fresh install shows every project open rather than an empty screen. */
    val expansionChosen: Boolean = false,
    val projectIcons: Map<String, String> = emptyMap(),
    val projectColors: Map<String, Int> = emptyMap(),
)

/** Everything the settings screen owns. Read once at startup, written through as each control changes. */
data class AppPreferences(
    val brightness: BrightnessMode = BrightnessMode.System,
    val themeFamily: ThemeFamily = ThemeFamily.Trefoil,
    val forceRail: Boolean = false,
    val forcedWidthDp: Float? = null,
    val list: ListPreferences = ListPreferences(),
)

/**
 * The list preferences narrowed to the projects the environment still reports, so a deleted project leaves no pin,
 * icon, colour or fold behind. [NoProjectKey] is not a project and is kept: the orphan bucket holds threads that lost
 * their project, and dropping its fold would re-close the group on the next snapshot.
 */
internal fun ListPreferences.prunedTo(liveProjectIds: Set<String>): ListPreferences = copy(
    pinnedProjects = pinnedProjects intersect liveProjectIds,
    expandedProjects = expandedProjects.filterTo(mutableSetOf()) { it in liveProjectIds || it == NoProjectKey },
    projectIcons = projectIcons.filterKeys { it in liveProjectIds },
    projectColors = projectColors.filterKeys { it in liveProjectIds },
)

/**
 * Persists [AppPreferences] in one `SharedPreferences` file. Reads are synchronous and cheap; writes go through
 * [apply] and are visible to the flows immediately, so the UI never waits on disk.
 *
 * Unknown ids are dropped on read rather than kept forever: a project deleted on the server must not leave a stale
 * pin, icon or expansion entry behind.
 */
class PreferenceStore(private val prefs: SharedPreferences) {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppPreferences> = _state.asStateFlow()

    private fun read(): AppPreferences {
        val list = ListPreferences(
            pinnedOrder = prefs.enum(KEY_PINNED_ORDER, ProjectOrder.Alphabetical),
            otherOrder = prefs.enum(KEY_OTHER_ORDER, ProjectOrder.Activity),
            threadOrder = prefs.enum(KEY_THREAD_ORDER, ThreadOrder.Activity),
            pinnedProjects = prefs.getStringSet(KEY_PINNED_PROJECTS, emptySet()).orEmpty(),
            expandedProjects = prefs.getStringSet(KEY_EXPANDED_PROJECTS, emptySet()).orEmpty(),
            expansionChosen = prefs.getBoolean(KEY_EXPANSION_CHOSEN, false),
            projectIcons = decodeMap(prefs.getString(KEY_PROJECT_ICONS, null)),
            projectColors = decodeIntMap(prefs.getString(KEY_PROJECT_COLORS, null)),
        )
        val width = prefs.getFloat(KEY_FORCED_WIDTH, 0f)
        return AppPreferences(
            brightness = prefs.enum(KEY_BRIGHTNESS, BrightnessMode.System),
            themeFamily = prefs.enum(KEY_THEME_FAMILY, ThemeFamily.Trefoil),
            forceRail = prefs.getBoolean(KEY_FORCE_RAIL, false),
            forcedWidthDp = width.takeIf { it > 0f },
            list = list,
        )
    }

    fun update(transform: (AppPreferences) -> AppPreferences) {
        val next = transform(_state.value)
        _state.value = next
        val list = next.list
        prefs.edit()
            .putString(KEY_BRIGHTNESS, next.brightness.name)
            .putString(KEY_THEME_FAMILY, next.themeFamily.name)
            .putBoolean(KEY_FORCE_RAIL, next.forceRail)
            .putFloat(KEY_FORCED_WIDTH, next.forcedWidthDp ?: 0f)
            .putString(KEY_PINNED_ORDER, list.pinnedOrder.name)
            .putString(KEY_OTHER_ORDER, list.otherOrder.name)
            .putString(KEY_THREAD_ORDER, list.threadOrder.name)
            .putStringSet(KEY_PINNED_PROJECTS, list.pinnedProjects)
            .putStringSet(KEY_EXPANDED_PROJECTS, list.expandedProjects)
            .putBoolean(KEY_EXPANSION_CHOSEN, list.expansionChosen)
            .putString(KEY_PROJECT_ICONS, encodeMap(list.projectIcons))
            .putString(KEY_PROJECT_COLORS, encodeIntMap(list.projectColors))
            .apply()
    }

    fun togglePinnedProject(id: String) = updateList { list ->
        list.copy(pinnedProjects = if (id in list.pinnedProjects) list.pinnedProjects - id else list.pinnedProjects + id)
    }

    fun setExpandedProjects(ids: Set<String>) = updateList { it.copy(expandedProjects = ids, expansionChosen = true) }

    fun setProjectIcon(projectId: String, iconId: String?) = updateList { list ->
        list.copy(projectIcons = if (iconId == null) list.projectIcons - projectId else list.projectIcons + (projectId to iconId))
    }

    fun setProjectColor(projectId: String, color: Int?) = updateList { list ->
        list.copy(projectColors = if (color == null) list.projectColors - projectId else list.projectColors + (projectId to color))
    }

    /**
     * Drops stored ids for projects the environment no longer reports. Called with the live project ids after every
     * shell snapshot; returns true when something was removed, so callers can skip a redundant write.
     */
    fun pruneProjects(liveProjectIds: Set<String>): Boolean {
        val list = _state.value.list
        val pruned = list.prunedTo(liveProjectIds)
        if (pruned == list) return false
        updateList { pruned }
        return true
    }

    private fun updateList(transform: (ListPreferences) -> ListPreferences) =
        update { it.copy(list = transform(it.list)) }

    private inline fun <reified T : Enum<T>> SharedPreferences.enum(key: String, fallback: T): T =
        getString(key, null)?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: fallback

    private companion object {
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_THEME_FAMILY = "theme.family"
        const val KEY_FORCE_RAIL = "layout.forceRail"
        const val KEY_FORCED_WIDTH = "layout.forcedWidthDp"
        const val KEY_PINNED_ORDER = "projects.pinnedOrder"
        const val KEY_OTHER_ORDER = "projects.otherOrder"
        const val KEY_THREAD_ORDER = "projects.threadOrder"
        const val KEY_PINNED_PROJECTS = "projects.pinned"
        const val KEY_EXPANDED_PROJECTS = "projects.expanded"
        const val KEY_EXPANSION_CHOSEN = "projects.expansionChosen"
        const val KEY_PROJECT_ICONS = "projects.icons"
        const val KEY_PROJECT_COLORS = "projects.colors"

        fun encodeMap(map: Map<String, String>): String = Json.encodeToString(mapOfStringSerializer, map)

        fun decodeMap(raw: String?): Map<String, String> =
            raw?.let { runCatching { Json.decodeFromString(mapOfStringSerializer, it) }.getOrNull() }.orEmpty()

        fun encodeIntMap(map: Map<String, Int>): String = Json.encodeToString(mapOfIntSerializer, map)

        fun decodeIntMap(raw: String?): Map<String, Int> =
            raw?.let { runCatching { Json.decodeFromString(mapOfIntSerializer, it) }.getOrNull() }.orEmpty()

        val mapOfStringSerializer: KSerializer<Map<String, String>> = MapSerializer(String.serializer(), String.serializer())
        val mapOfIntSerializer: KSerializer<Map<String, Int>> = MapSerializer(String.serializer(), Int.serializer())
    }
}

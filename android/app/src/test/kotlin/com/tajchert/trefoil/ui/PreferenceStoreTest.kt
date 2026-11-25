package com.tajchert.trefoil.ui

import android.content.Context
import com.tajchert.trefoil.ui.theme.ThemeFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the store keeps between launches: every field a restart reads back, unknown stored names degrading to their
 * default instead of crashing, and the project ids pruning drops once the environment stops reporting them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferenceStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("trefoil.ui.test", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun everyFieldSurvivesASecondStoreOnTheSameFile() {
        PreferenceStore(prefs).update { current ->
            current.copy(
                brightness = BrightnessMode.Light,
                themeFamily = ThemeFamily.Violet,
                forceRail = true,
                forcedWidthDp = 411f,
                list = current.list.copy(
                    pinnedOrder = ProjectOrder.Server,
                    otherOrder = ProjectOrder.Alphabetical,
                    threadOrder = ThreadOrder.Created,
                    pinnedProjects = setOf("alpha", "beta"),
                    expandedProjects = setOf("beta", NoProjectKey),
                    expansionChosen = true,
                    projectIcons = mapOf("alpha" to "flask", "beta" to "bolt"),
                    projectColors = mapOf("alpha" to 3),
                ),
            )
        }
        // The next launch builds a store over the same file: nothing may come back as its default.
        val restored = PreferenceStore(prefs).state.value
        assertEquals(BrightnessMode.Light, restored.brightness)
        assertEquals(ThemeFamily.Violet, restored.themeFamily)
        assertTrue(restored.forceRail)
        assertEquals(411f, restored.forcedWidthDp!!, 0f)
        assertEquals(ProjectOrder.Server, restored.list.pinnedOrder)
        assertEquals(ProjectOrder.Alphabetical, restored.list.otherOrder)
        assertEquals(ThreadOrder.Created, restored.list.threadOrder)
        assertEquals(setOf("alpha", "beta"), restored.list.pinnedProjects)
        assertEquals(setOf("beta", NoProjectKey), restored.list.expandedProjects)
        assertTrue(restored.list.expansionChosen)
        assertEquals(mapOf("alpha" to "flask", "beta" to "bolt"), restored.list.projectIcons)
        assertEquals(mapOf("alpha" to 3), restored.list.projectColors)
    }

    @Test fun unknownStoredNamesFallBackToTheDefaults() {
        // Stored by name, so a value written by another version must degrade rather than crash on read.
        prefs.edit()
            .putString("brightness", "nonsense")
            .putString("theme.family", "nonsense")
            .putString("projects.pinnedOrder", "nonsense")
            .putString("projects.otherOrder", "nonsense")
            .putString("projects.threadOrder", "nonsense")
            .commit()
        val state = PreferenceStore(prefs).state.value
        val defaults = AppPreferences()
        assertEquals(defaults.brightness, state.brightness)
        assertEquals(defaults.themeFamily, state.themeFamily)
        assertEquals(defaults.list.pinnedOrder, state.list.pinnedOrder)
        assertEquals(defaults.list.otherOrder, state.list.otherOrder)
        assertEquals(defaults.list.threadOrder, state.list.threadOrder)
    }

    @Test fun togglingAPinAddsAndThenRemovesTheProject() {
        val store = PreferenceStore(prefs)
        store.togglePinnedProject("alpha")
        assertEquals(setOf("alpha"), store.state.value.list.pinnedProjects)
        store.togglePinnedProject("beta")
        assertEquals(setOf("alpha", "beta"), store.state.value.list.pinnedProjects)
        store.togglePinnedProject("alpha")
        assertEquals(setOf("beta"), store.state.value.list.pinnedProjects)
        // Written through, so the pin the user removed stays removed after a restart.
        assertEquals(setOf("beta"), PreferenceStore(prefs).state.value.list.pinnedProjects)
    }

    @Test fun projectIconAndColourAreSetAndClearedWithNull() {
        val store = PreferenceStore(prefs)
        store.setProjectIcon("alpha", "flask")
        store.setProjectColor("alpha", 4)
        assertEquals(mapOf("alpha" to "flask"), store.state.value.list.projectIcons)
        assertEquals(mapOf("alpha" to 4), store.state.value.list.projectColors)
        store.setProjectIcon("alpha", null)
        store.setProjectColor("alpha", null)
        assertTrue(store.state.value.list.projectIcons.isEmpty())
        assertTrue(store.state.value.list.projectColors.isEmpty())
        // Clearing leaves no entry behind in the file either: the project falls back to its default look.
        assertTrue(PreferenceStore(prefs).state.value.list.projectIcons.isEmpty())
        assertTrue(PreferenceStore(prefs).state.value.list.projectColors.isEmpty())
    }

    @Test fun foldingRecordsThatTheUserHasChosenAnExpansion() {
        val store = PreferenceStore(prefs)
        assertFalse("a fresh install shows every project open", store.state.value.list.expansionChosen)
        store.setExpandedProjects(setOf("alpha"))
        assertEquals(setOf("alpha"), store.state.value.list.expandedProjects)
        assertTrue(store.state.value.list.expansionChosen)
    }

    @Test fun forcedWidthRoundTripsAndIsClearedToNull() {
        val store = PreferenceStore(prefs)
        store.update { it.copy(forcedWidthDp = 480f) }
        assertEquals(480f, PreferenceStore(prefs).state.value.forcedWidthDp!!, 0f)
        store.update { it.copy(forcedWidthDp = null) }
        assertNull(PreferenceStore(prefs).state.value.forcedWidthDp)
        // Absence is written as 0f, so a stored zero must read back as "no override" rather than a zero-width layout.
        prefs.edit().putFloat("layout.forcedWidthDp", 0f).commit()
        assertNull(PreferenceStore(prefs).state.value.forcedWidthDp)
    }

    @Test fun pruningKeepsTheOrphanBucketFoldAndDropsDeletedProjects() {
        val store = PreferenceStore(prefs)
        store.setExpandedProjects(setOf("alpha", "deleted", NoProjectKey))
        assertTrue(store.pruneProjects(setOf("alpha")))
        // The orphan bucket is not a project the environment reports, so it is never pruned away: a thread that lost its
        // project must not re-close its group on the next snapshot.
        assertEquals(setOf("alpha", NoProjectKey), store.state.value.list.expandedProjects)
        // Written through, so a restart reads the same set of open groups back.
        assertEquals(setOf("alpha", NoProjectKey), PreferenceStore(prefs).state.value.list.expandedProjects)
    }

    @Test fun pruningDropsPinsIconsAndColoursOfDeletedProjects() {
        val store = PreferenceStore(prefs)
        store.togglePinnedProject("alpha")
        store.togglePinnedProject("deleted")
        store.setProjectIcon("alpha", "flask")
        store.setProjectIcon("deleted", "bolt")
        store.setProjectColor("alpha", 2)
        store.setProjectColor("deleted", 5)
        store.setExpandedProjects(setOf("alpha", "deleted"))
        assertTrue(store.pruneProjects(setOf("alpha")))
        val kept = PreferenceStore(prefs).state.value.list
        assertEquals(setOf("alpha"), kept.pinnedProjects)
        assertEquals(setOf("alpha"), kept.expandedProjects)
        assertEquals(mapOf("alpha" to "flask"), kept.projectIcons)
        assertEquals(mapOf("alpha" to 2), kept.projectColors)
        // Pruning is not a fold: the user's choice to have folds at all stays recorded.
        assertTrue(kept.expansionChosen)
    }

    @Test fun pruningAnAlreadyCleanStoreWritesNothing() {
        val store = PreferenceStore(prefs)
        store.setExpandedProjects(setOf("alpha", NoProjectKey))
        assertFalse(store.pruneProjects(setOf("alpha")))
        assertEquals(setOf("alpha", NoProjectKey), store.state.value.list.expandedProjects)
    }
}

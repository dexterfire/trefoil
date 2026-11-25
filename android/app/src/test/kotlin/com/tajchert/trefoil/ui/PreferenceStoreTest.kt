package com.tajchert.trefoil.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Pruning stored project ids: a deleted project loses its entries, while the orphan bucket keeps its fold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferenceStoreTest {
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("trefoil.ui.test", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

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

    @Test fun pruningAnAlreadyCleanStoreWritesNothing() {
        val store = PreferenceStore(prefs)
        store.setExpandedProjects(setOf("alpha", NoProjectKey))
        assertFalse(store.pruneProjects(setOf("alpha")))
        assertEquals(setOf("alpha", NoProjectKey), store.state.value.list.expandedProjects)
    }
}

package com.tajchert.trefoil.ui.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.nativeKeyCode
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.ui.ComposerButton
import com.tajchert.trefoil.ui.ComposerPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.tajchert.trefoil.core.model.FollowUpMode

/** The shortcut table is the single source of the key handler, the cheat sheet, the system Helper and the tooltip hints. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutTableTest {
    /** Robolectric context so label/group resources resolve; labels keep testing approval-word rules. */
    private val context get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun everyEntryHasALabelAGroupAndAChord() {
        ShortcutTable.forEach { entry ->
            assertTrue("${entry.id} label", entry.labelRes != 0)
            assertTrue("${entry.id} group ${entry.groupRes}", entry.groupRes in ShortcutGroups)
            assertTrue("${entry.id} chords", entry.chords.isNotEmpty())
        }
    }

    @Test fun everyIdAppearsExactlyOnce() {
        assertEquals(ShortcutId.entries.toSet(), ShortcutTable.map { it.id }.toSet())
        assertEquals(ShortcutId.entries.size, ShortcutTable.size)
    }

    @Test fun noChordIsBoundTwiceWithinAScope() {
        ShortcutScope.entries.forEach { scope ->
            val chords = ShortcutTable.filter { it.scope == scope }.flatMap { entry -> entry.chords.map { it.label to entry.id } }
            val duplicates = chords.groupBy { it.first }.filterValues { it.size > 1 }
            assertTrue("duplicate chords in $scope: $duplicates", duplicates.isEmpty())
        }
    }

    @Test fun globalChordsNeverTakeTypingKeys() {
        ShortcutTable.filter { it.scope == ShortcutScope.Global }.forEach { entry ->
            entry.chords.forEach { assertTrue("${entry.id} ${it.label} would steal typing", it.safeForGlobal) }
        }
        // Shift alone is typing, and AltGr layouts type letters with Alt: no Alt chord on a letter or digit.
        ShortcutTable.flatMap { it.chords }.filter { it.alt && !it.ctrl }.forEach { assertTrue("${it.label} is Alt+letter", it.key !in letters) }
    }

    @Test fun noGlobalShortcutAnswersAnApproval() {
        val approvalWords = listOf("allow", "deny", "approve", "reject", "accept", "decline")
        ShortcutTable.forEach { entry ->
            val label = context.getString(entry.labelRes)
            approvalWords.forEach { assertTrue("${entry.id} '$label'", !label.lowercase().contains(it)) }
        }
    }

    @Test fun theSystemHelperListsExactlyTheTable() {
        val groups = keyboardShortcutGroups(context)
        val listed = groups.flatMap { group -> group.items.map { Triple(group.label.toString(), it.label.toString(), it.keycode to it.modifiers) } }
        val expected = ShortcutTable.flatMap { entry -> entry.chords.map { Triple(context.getString(entry.groupRes), context.getString(entry.labelRes), it.key.nativeKeyCode to it.modifiers) } }
        assertEquals(expected.sortedBy { it.toString() }, listed.sortedBy { it.toString() })
        assertEquals(ShortcutGroups.map { context.getString(it) }.filter { g -> ShortcutTable.any { context.getString(it.groupRes) == g } }, groups.map { it.label.toString() })
    }

    @Test fun theCheatSheetRowsComeFromTheTable() {
        // The sheet draws ShortcutTable per group in ShortcutGroups order, so its rows are these.
        val rows = ShortcutGroups.flatMap { g -> ShortcutTable.filter { it.groupRes == g }.map { context.getString(it.labelRes) to it.chords.map { c -> c.label } } }
        assertEquals(ShortcutTable.size, rows.size)
        assertEquals(ShortcutTable.map { it.id }.toSet(), ShortcutTable.filter { it.groupRes in ShortcutGroups }.map { it.id }.toSet())
    }

    @Test fun hintsAndLabelsComeFromTheFirstChord() {
        assertEquals("Ctrl+N", shortcutHint(ShortcutId.NewThread))
        assertEquals("New thread (Ctrl+N)", labelWithShortcut("New thread", ShortcutId.NewThread))
        assertEquals("Ctrl+/", shortcutHint(ShortcutId.ShowShortcuts))
        assertEquals("Ctrl+Shift+U", shortcutHint(ShortcutId.NextNeedsYou))
        ShortcutId.entries.forEach { assertNotNull("$it", shortcutHint(it)) }
    }

    @Test fun theScreenOwnedShortcutsAreInTheSameTableAsTheRest() {
        // Their actions are registered by the screens that own the state, but the table (so the cheat sheet and the system Helper) lists them like any other.
        fun chords(id: ShortcutId) = ShortcutTable.single { it.id == id }.chords.map { it.label }
        assertEquals(listOf("Ctrl+K", "Ctrl+F"), chords(ShortcutId.SearchThreads))
        assertEquals(listOf("Ctrl+Shift+L"), chords(ShortcutId.ModelPicker))
        assertEquals(listOf("Ctrl+Shift+M"), chords(ShortcutId.MachineSwitcher))
        listOf(ShortcutId.SearchThreads, ShortcutId.ModelPicker, ShortcutId.MachineSwitcher).forEach { assertEquals(ShortcutScope.Global, ShortcutTable.single { e -> e.id == it }.scope) }
    }

    @Test fun chordLabelsListModifiersInOrder() {
        assertEquals("Ctrl+Alt+Shift+Meta+X", Chord(Key.X, "X", ctrl = true, shift = true, alt = true, meta = true).label)
        assertEquals(listOf("Ctrl", "Shift", "A"), ctrlShiftA.parts)
    }

    private val ctrlShiftA = Chord(Key.A, "A", ctrl = true, shift = true)
    private val letters = setOf(Key.A, Key.N, Key.U, Key.R)

    // ---- Thread stepping ----

    @Test fun adjacentThreadStopsAtTheEnds() {
        val order = listOf("a", "b", "c")
        assertEquals("b", adjacentThreadId(order, "a", 1))
        assertNull(adjacentThreadId(order, "c", 1))
        assertEquals("a", adjacentThreadId(order, "b", -1))
        assertNull(adjacentThreadId(order, "a", -1))
        assertEquals("a", adjacentThreadId(order, null, 1))
        assertEquals("c", adjacentThreadId(order, null, -1))
        assertEquals("a", adjacentThreadId(order, "gone", 1))
        assertNull(adjacentThreadId(emptyList(), "a", 1))
    }

    @Test fun nextNeedsYouWrapsAndSkipsTheOpenThread() {
        assertNull(nextNeedsYouId(emptyList(), null))
        assertNull(nextNeedsYouId(listOf("a"), "a"))
        assertEquals("a", nextNeedsYouId(listOf("a", "b"), null))
        assertEquals("b", nextNeedsYouId(listOf("a", "b", "c"), "a"))
        assertEquals("a", nextNeedsYouId(listOf("a", "b", "c"), "c"))
        assertEquals("a", nextNeedsYouId(listOf("a", "b"), "other"))
    }

    @Test fun listedThreadsFollowTheListOrderAndSkipFoldedGroups() {
        val threads = listOf(
            ThreadSummary("recent", "p", "Recent"),
            ThreadSummary("pinned", "p", "Pinned", pinnedAt = "2026-01-01T00:00:00Z"),
            ThreadSummary("settled", "p", "Settled", settledOverride = "settled"),
            ThreadSummary("archived", "p", "Archived", archived = true),
        )
        assertEquals(listOf("pinned", "recent"), listedThreadIds(threads))
    }

    // ---- Composer ----

    @Test fun theOtherFollowUpModeIsOfferedOnlyWhileATurnRuns() {
        assertEquals(FollowUpMode.Queue, alternateFollowUp(ComposerButton(ComposerPrimary.Steer, true), canSteer = true))
        assertEquals(FollowUpMode.Steer, alternateFollowUp(ComposerButton(ComposerPrimary.Queue, true), canSteer = true))
        assertNull(alternateFollowUp(ComposerButton(ComposerPrimary.Queue, true), canSteer = false))
        assertNull(alternateFollowUp(ComposerButton(ComposerPrimary.Send, true), canSteer = true))
        assertNull(alternateFollowUp(ComposerButton(ComposerPrimary.Stop, true), canSteer = true))
        assertNull(alternateFollowUp(ComposerButton(ComposerPrimary.Steer, false), canSteer = true))
    }
}

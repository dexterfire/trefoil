package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerDraftUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val alpha = Project("p1", "alpha", "/a")
    private val beta = Project("p2", "beta", "/b")
    private fun newThread(env: String, project: String, text: String, at: Long) = ComposerDraft(ComposerDraftKey.NewThread(env, project), text, updatedAtMillis = at)
    private fun byKey(vararg drafts: ComposerDraft) = drafts.associateBy { it.key }

    @Test fun draftRowsShowOnlyThisEnvironmentWithContentAndKnownProjectsNewestFirst() {
        val drafts = byKey(
            newThread("e", "p1", "first", 1), newThread("e", "p2", "second", 2), newThread("e", "gone", "hidden", 3),
            newThread("other", "p1", "elsewhere", 4), newThread("e", "p1x", " ", 5), ComposerDraft(ComposerDraftKey.Reply("e", "t1"), "reply", updatedAtMillis = 6),
        )
        val projects = listOf(alpha, beta).associateBy { it.id }
        assertEquals(listOf("second", "first"), threadDraftRows(drafts, "e", projects, null).map { it.first.text })
        assertEquals(listOf("first"), threadDraftRows(drafts, "e", projects, "p1").map { it.first.text })
        assertEquals(listOf("second"), threadDraftRows(drafts, "e", projects, null, "BET").map { it.first.text })
    }

    @Test fun recentDraftIsTheNewestOneWithAKnownProject() {
        val drafts = byKey(newThread("e", "p1", "old", 1), newThread("e", "p2", "new", 2), newThread("e", "gone", "newest", 3))
        assertEquals("new", recentNewThreadDraft(drafts, "e", listOf(alpha, beta))?.text)
        assertNull(recentNewThreadDraft(drafts, "other", listOf(alpha, beta)))
    }

    @Test fun firstLineSkipsBlankLines() {
        assertEquals("Fix the bug", draftFirstLine(newThread("e", "p1", "\n  Fix the bug  \nmore", 1)))
    }

    @Test fun sheetOpenedFromAProjectPrefillsItsDraftAndWritesThrough() {
        val state = ClientState(environment = SavedEnvironment("e", "E", "https://e.test", "t"), projects = listOf(alpha, beta), models = listOf(ModelOption("codex", "gpt-5")))
        val written = mutableListOf<Pair<ComposerDraftKey, String>>()
        val drafts = byKey(newThread("e", "p2", "resume me", 1))
        val ui = ComposerDraftUi(drafts, update = { key, text, _, _, _, _ -> written += key to text })
        compose.setContent { TrefoilTheme { NewThreadSheetContent(state, {}, { _, _ -> }, composerDrafts = ui, openProjectId = "p2") } }
        compose.onNodeWithText("resume me").assertExists()
        assertTrue(written.isEmpty())
        compose.onNodeWithText("resume me").performTextInput("!")
        assertEquals(ComposerDraftKey.NewThread("e", "p2"), written.last().first)
        assertTrue(written.last().second.contains("resume me") && written.last().second.contains("!"))
    }
}

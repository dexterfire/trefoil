package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reveal keeps the entry the reader was looking at in place (negative `requestScrollToItem` offset). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ConversationLoadEarlierTest {
    @get:Rule val compose = createComposeRule()

    private val state = ClientState(
        connection = ConnectionStatus.Connected, canWrite = true, projects = listOf(Project("p", "app")),
        threads = listOf(ThreadSummary("t", "p", "Long thread")), selectedThreadId = "t",
        messages = (0 until 25).flatMap { i ->
            listOf(ConversationMessage("u$i", "user", "Prompt $i", ordinal = i * 2), ConversationMessage("a$i", "assistant", "Answer $i", ordinal = i * 2 + 1))
        },
    )

    @Test fun revealKeepsTheFirstVisibleEntryInPlace() {
        compose.setContent { TrefoilTheme { Box(Modifier.size(390.dp, 844.dp)) { Conversation(state, send = {}, interrupt = {}, respond = { _, _, _ -> }) } } }
        compose.waitForIdle()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Prompt 14").fetchSemanticsNodes().isEmpty())
        val before = compose.onNodeWithText("Prompt 15").getUnclippedBoundsInRoot().top
        compose.onNodeWithTag("load-earlier").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Prompt 15").assertIsDisplayed()
        assertEquals(before.value, compose.onNodeWithText("Prompt 15").getUnclippedBoundsInRoot().top.value, 1f)
        compose.onNodeWithText("Prompt 5").assertIsNotDisplayed() // revealed above, off screen
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithTag("load-earlier").assertExists() // 5 turns still hidden
    }
}

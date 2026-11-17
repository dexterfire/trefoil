package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationUiTest {
    @get:Rule val compose = createComposeRule()

    private fun clickLabeled(label: String) = SemanticsMatcher("onClickLabel == $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test fun failedCommandStaysCollapsedUntilTheEntryIsOpened() {
        val activity = mutableStateOf(ToolActivity("command", "Run", kind = "command_execution", status = "running", input = "check"))
        compose.setContent { MaterialTheme { WorkGroup(listOf(activity.value), live = true) } }
        compose.runOnIdle { activity.value = activity.value.copy(status = "completed", output = "failure output", exitCode = 2) }
        compose.onNodeWithText("failure output").assertDoesNotExist()
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.onNodeWithText("failure output").assertIsDisplayed()
        compose.onNodeWithText("exit 2").assertIsDisplayed()
    }

    @Test fun liveRunningEntryShowsTheLiveLabelAndHidesOnceTheRunIsNotLive() {
        val running = ToolActivity("command", "Run", kind = "command_execution", status = "running", input = "check")
        val live = mutableStateOf(true)
        compose.setContent { MaterialTheme { WorkGroup(listOf(running), live = live.value) } }
        compose.onNodeWithText(liveWorkLabel(running)).assertExists()
        compose.runOnIdle { live.value = false }
        compose.onNodeWithText(liveWorkLabel(running)).assertDoesNotExist()
    }

    @Test fun failedGroupHeaderHasNoAutoExpansionAndDescribesTheFailure() {
        val first = ToolActivity("one", "Run", kind = "command_execution", status = "completed", input = "first", output = "first output", exitCode = 1)
        val second = ToolActivity("two", "Run", kind = "command_execution", status = "completed", input = "second", output = "second output", exitCode = 1)
        compose.setContent { MaterialTheme { WorkGroup(listOf(first, second)) } }
        compose.onNode(hasContentDescription("tool call failed", substring = true)).assertExists()
        compose.onNodeWithText("first output").assertDoesNotExist()
        compose.onNode(clickLabeled("Expand work details")).performClick()
        compose.onNodeWithText("first output").assertDoesNotExist()
        compose.onAllNodes(clickLabeled("Expand details")).assertCountEquals(2)
    }

    @Test fun staleRunningGroupSignalsUnknownOutcomeOnTheCollapsedHeader() {
        val done = ToolActivity("one", "Run", kind = "command_execution", status = "completed", input = "first", exitCode = 0)
        val stale = ToolActivity("two", "Run", kind = "command_execution", status = "running", input = "second")
        compose.setContent { MaterialTheme { WorkGroup(listOf(done, stale)) } }
        compose.onNodeWithText("Ran 2 commands · outcome unknown").assertExists()
        compose.onNode(hasContentDescription("Ran 2 commands, outcome unknown")).assertExists()
    }

    @Test fun liveRunningGroupHasNoUnknownOutcomeSignal() {
        val done = ToolActivity("one", "Run", kind = "command_execution", status = "completed", input = "first", exitCode = 0)
        val running = ToolActivity("two", "Run", kind = "command_execution", status = "running", input = "second")
        compose.setContent { MaterialTheme { WorkGroup(listOf(done, running), live = true) } }
        compose.onNode(hasContentDescription("outcome unknown", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("outcome unknown", substring = true).assertDoesNotExist()
    }

    @Test fun staleRunningSingleEntryStillSignalsUnknownOutcome() {
        val stale = ToolActivity("two", "Run", kind = "command_execution", status = "running", input = "second")
        compose.setContent { MaterialTheme { WorkGroup(listOf(stale)) } }
        compose.onNodeWithText("· outcome unknown").assertExists()
    }

    @Test fun expandableRowsAnnounceStateAndKeepDiffStatsInTheirDescription() {
        val edit = ToolActivity("edit", "File change", kind = "file_change", status = "completed", fileName = "a.kt", diffStr = "--- a\n+++ b\n+one\n+two\n-three")
        compose.setContent { MaterialTheme { WorkGroup(listOf(edit)) } }
        val row = hasContentDescription("Changed 1 file, +2 −1")
        compose.onNode(row).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).assertExists()
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertExists()
        compose.onNode(row).assertExists()
    }

    @Test fun groupHeaderAnnouncesExpandedState() {
        val first = ToolActivity("one", "Run", kind = "command_execution", status = "completed", input = "first", exitCode = 0)
        val second = ToolActivity("two", "Run", kind = "command_execution", status = "completed", input = "second", exitCode = 0)
        compose.setContent { MaterialTheme { WorkGroup(listOf(first, second)) } }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).assertExists()
        compose.onNode(clickLabeled("Expand work details")).performClick()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).assertCountEquals(2)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertExists()
    }

    @Test fun rowsWithoutDetailAnnounceNoState() {
        val plain = ToolActivity("plain", "Run", kind = "command_execution", status = "completed", exitCode = 0)
        compose.setContent { MaterialTheme { WorkGroup(listOf(plain)) } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription)).assertCountEquals(0)
    }

    @Test fun entryDescriptionCombinesFailureStaleAndStats() {
        assertNull(entryDescription("Ran a", false, false, null))
        assertEquals("Ran a, tool call failed", entryDescription("Ran a", true, false, null))
        assertEquals("Ran a, outcome unknown", entryDescription("Ran a", false, true, null))
        assertEquals("a.kt, +3 −1", entryDescription("a.kt", false, false, 3 to 1))
        assertEquals("a.kt, tool call failed, outcome unknown, +3 −1", entryDescription("a.kt", true, true, 3 to 1))
    }

    @Test fun runningEntryThatCompletesReplacesItsCachedLabel() {
        val activity = mutableStateOf(ToolActivity("p", "mcp__t3-code__html_preview", kind = "dynamic_tool", status = "running"))
        compose.setContent { MaterialTheme { WorkGroup(listOf(activity.value), live = true) } }
        compose.onNodeWithText("Previewing an HTML page").assertExists()
        compose.runOnIdle { activity.value = activity.value.copy(status = "completed") }
        compose.onNodeWithText("Previewing an HTML page").assertDoesNotExist()
        compose.onNodeWithText("Previewed an HTML page").assertExists()
        compose.runOnIdle { activity.value = activity.value.copy(output = """{"isError":true}""") }
        compose.onNodeWithText("Previewed an HTML page").assertDoesNotExist()
        compose.onNodeWithText("Failed to preview an HTML page").assertExists()
        compose.onNode(hasContentDescription("Failed to preview an HTML page, tool call failed")).assertExists()
    }

    @Test fun subagentFooterTracksLiveAgentsAndOpensSheet() {
        val agent = Subagent("a", "fixture", status = "running", driver = "codex")
        val state = mutableStateOf(ClientState(selectedThreadId = "fixture", subagents = listOf(agent)))
        var opened = false
        compose.setContent { MaterialTheme { Conversation(state.value, {}, {}, { _, _, _ -> }, onOpenAgents = { opened = true }) } }
        compose.onNodeWithContentDescription("1 subagent, 1 working, 0 done").performClick()
        compose.runOnIdle { assertTrue(opened); state.value = state.value.copy(subagents = listOf(agent.copy(status = "completed"))) }
        compose.onNodeWithContentDescription("1 subagent, 1 working, 0 done").assertDoesNotExist()
    }

    @Test fun draftWaitsForNewServerMessageAndNeverResendsOnReconnection() {
        val state = mutableStateOf(ClientState(selectedThreadId = "fixture", canWrite = true, connection = ConnectionStatus.Connected,
            messages = listOf(ConversationMessage("old", "user", "Hello"))))
        val sends = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state.value, { sends += it }, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("Reply…").performTextInput("Hello")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Disconnected, canWrite = false) }
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Connected, canWrite = true) }
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("Hello"), sends)
            state.value = state.value.copy(messages = state.value.messages + ConversationMessage("new", "user", "Hello"))
        }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun backArrowBadgesOtherWaitingThreadsAndNamesThemForAccessibility() {
        val count = mutableStateOf(2)
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture"), {}, {}, { _, _, _ -> }, onBack = {}, otherNeedsYou = count.value) } }
        compose.onNodeWithContentDescription("Back to threads, 2 threads need you").assertExists()
        count.value = 0
        compose.onNodeWithContentDescription("Back to threads").assertExists()
        compose.onNodeWithContentDescription("Back to threads, 2 threads need you").assertDoesNotExist()
    }

    @Test fun noBackArrowMeansNoBadgeOnWideLayouts() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture"), {}, {}, { _, _, _ -> }, onBack = null, otherNeedsYou = 2) } }
        compose.onNodeWithContentDescription("Back to threads, 2 threads need you").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to threads").assertDoesNotExist()
    }

    @Test fun disconnectedConversationDisablesApprovalAndSendButRetainsDraftEditing() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", requests = listOf(RuntimeRequest("approval", "Run fixture command"))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("Reply…").performTextInput("Draft")
        compose.onNodeWithContentDescription("Send message").assertIsNotEnabled()
        compose.onNodeWithText("Allow").assertIsNotEnabled()
        compose.onNodeWithText("Deny").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).assertTextEquals("Draft")
    }

    @Test fun completedResponseDisclosureKeepsFinalAnswerAndRevealsIntermediateWork() {
        val state = ClientState(selectedThreadId = "fixture", messages = listOf(
            ConversationMessage("c", "assistant", "Checking the fixture", runId = "r", ordinal = 0),
            ConversationMessage("a", "assistant", "The result is ready", runId = "r", ordinal = 2)),
            activities = listOf(ToolActivity("t", "Read file", "fixture.txt", status = "completed", runId = "r", ordinal = 1)))
        compose.setContent { MaterialTheme { Conversation(state, {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("The result is ready").assertExists()
        compose.onNodeWithText("Checking the fixture").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand completed work").performClick()
        compose.onNodeWithText("Checking the fixture").assertExists()
        compose.onNodeWithText(singleWorkLabel(ToolActivity("t", "Read file", "fixture.txt", status = "completed", runId = "r", ordinal = 1))).assertExists()
        compose.onNodeWithContentDescription("Collapse completed work").performClick()
        compose.onNodeWithText("Checking the fixture").assertDoesNotExist()
        compose.onNodeWithText("The result is ready").assertExists()
    }

    @Test fun failedCommandShowsItsInputOutputAndExitCodeOnceOpened() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(
            ToolActivity("command", "Run checks", kind = "command_execution", status = "completed", input = "./fixture-check", output = "Fixture failed", exitCode = 2))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("$ ./fixture-check").assertDoesNotExist()
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.onNodeWithText("$ ./fixture-check").assertExists()
        compose.onNodeWithText("Fixture failed").assertExists()
        compose.onNodeWithText("exit 2").assertExists()
        compose.onNodeWithContentDescription("Copy activity").assertExists()
    }

    @Test fun omittedOutputLoadsOnlyOnExplicitConnectedRequest() {
        val state = mutableStateOf(ClientState(selectedThreadId = "fixture", canWrite = false, connection = ConnectionStatus.Connected,
            activities = listOf(ToolActivity("command", "Run checks", kind = "command_execution", status = "completed", input = "./fixture-check", outputOmitted = true))))
        val loads = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state.value, {}, {}, { _, _, _ -> }, onLoadActivity = { loads += it }) } }
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.runOnIdle { assertTrue(loads.isEmpty()) }
        compose.onNodeWithText("Load output").performClick()
        compose.runOnIdle {
            assertEquals(listOf("command"), loads)
            state.value = state.value.copy(activities = state.value.activities.map { it.copy(outputOmitted = false, output = "Hydrated fixture") })
        }
        compose.onNodeWithText("Hydrated fixture").assertExists()
        compose.onNodeWithText("Load output").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(connection = ConnectionStatus.Disconnected, canWrite = false, activities = state.value.activities.map { it.copy(outputOmitted = true, output = null) }) }
        compose.onNodeWithText("Load output").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("command"), loads) }
    }

    @Test fun structuredCommandDetailsDoNotRepeatTheLegacyAggregate() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(
            ToolActivity("command", "Run checks", detail = "./fixture-check\n\nfixture-output", kind = "command_execution", status = "completed", input = "./fixture-check", output = "fixture-output", exitCode = 0))), {}, {}, { _, _, _ -> }) } }
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.onAllNodesWithText("fixture-output", substring = true).assertCountEquals(1)
        compose.onNodeWithText("$ ./fixture-check").assertExists()
        compose.onNodeWithText("exit 0").assertDoesNotExist()
    }

    @Test fun requestsRemainVisibleBesideCompletedWork() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", canWrite = true,
            messages = listOf(ConversationMessage("a", "assistant", "Ready", runId = "r", ordinal = 1)),
            activities = listOf(ToolActivity("t", "Read file", status = "completed", runId = "r", ordinal = 0)),
            requests = listOf(RuntimeRequest("approval", "Review the next command"))), {}, {}, { _, _, _ -> }) } }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithContentDescription("Expand completed work").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Review the next command"))
        compose.onNodeWithText("Review the next command").assertExists()
        compose.onNodeWithText("Allow").assertIsEnabled()
    }

    @Test fun navigatingAwayRestoresDraftAndExpandedWorkWithinTheThreadStateProvider() {
        val selected = mutableStateOf("a")
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            MaterialTheme {
                holder.SaveableStateProvider(selected.value) {
                    Conversation(ClientState(selectedThreadId = selected.value,
                        activities = listOf(ToolActivity("tool", "Read file", status = "completed", output = "fixture output"))), {}, {}, { _, _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Reply…").performTextInput("Keep my draft")
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.runOnIdle { selected.value = "b" }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("fixture output").assertDoesNotExist()
        compose.runOnIdle { selected.value = "a" }
        compose.onNode(hasSetTextAction()).assertTextEquals("Keep my draft")
        compose.onNodeWithText("fixture output").assertExists()
    }

    @Test fun activityDetailsAreCollapsedUntilExpanded() {
        compose.setContent { MaterialTheme { Conversation(ClientState(selectedThreadId = "fixture", activities = listOf(ToolActivity("read", "Read file", status = "completed", output = "fixture output"))), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithText("fixture output").assertDoesNotExist()
        compose.onNode(clickLabeled("Expand details")).performClick()
        compose.onNodeWithText("fixture output").assertExists()
    }

    @Test fun activityWithoutDetailHasNoExpandControl() {
        val empty = ToolActivity("empty", "Read file", status = "completed")
        compose.setContent { MaterialTheme { WorkGroup(listOf(empty)) } }
        compose.onNodeWithText(singleWorkLabel(empty)).assertExists()
        compose.onNode(clickLabeled("Expand details")).assertDoesNotExist()
    }

    @Test fun completedCommandsStartCollapsedAndExpandOnClick() {
        val first = ToolActivity("one", "Run", kind = "command_execution", status = "completed", input = "first", output = "first output")
        val second = ToolActivity("two", "Run", kind = "command_execution", status = "completed", input = "second", output = "second output")
        compose.setContent { MaterialTheme { WorkGroup(listOf(first, second)) } }
        compose.onNodeWithText(workEntryLabel(first)).assertDoesNotExist()
        compose.onNode(clickLabeled("Expand work details")).performClick()
        compose.onNodeWithText(workEntryLabel(first)).assertExists()
        compose.onNodeWithText(workEntryLabel(second)).assertExists()
        compose.onNodeWithText("first output").assertDoesNotExist()
        compose.onAllNodes(clickLabeled("Expand details"))[0].performClick()
        compose.onNodeWithText("first output").assertExists()
    }

    private val longThread = ClientState(selectedThreadId = "fixture", canWrite = true, connection = ConnectionStatus.Connected,
        messages = (1..30).map { ConversationMessage("m$it", if (it % 2 == 0) "assistant" else "user", "Fixture message $it") })

    /** Shrinking the host stands in for the keyboard opening: both reduce the list viewport (Robolectric windows are 470dp tall). */
    private fun shrinkableConversation(): androidx.compose.runtime.MutableState<Int> {
        val height = mutableStateOf(460)
        compose.setContent { TrefoilTheme { Box(Modifier.width(390.dp).height(height.value.dp)) { Conversation(longThread, {}, {}, { _, _, _ -> }) } } }
        return height
    }

    private fun scrollRange() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
    private fun atEnd() = scrollRange().let { it.value() >= it.maxValue() }

    @Test fun keyboardOpeningKeepsLatestMessageVisibleWhenFollowingTheBottom() {
        val height = shrinkableConversation()
        compose.waitForIdle()
        assertTrue(atEnd())
        compose.onNodeWithText("Reply…").performClick()
        compose.runOnIdle { height.value = 300 }
        compose.waitForIdle()
        assertTrue("list should stay pinned to the latest message", atEnd())
        compose.onNodeWithText("Fixture message 30").assertIsDisplayed()
        compose.onNodeWithText("Latest").assertDoesNotExist()
    }

    @Test fun keyboardOpeningKeepsScrolledPositionWhenReadingHistory() {
        val height = shrinkableConversation()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top + 50f, endY = bottom - 50f) }
        compose.waitForIdle()
        assertFalse(atEnd())
        val before = scrollRange().value()
        compose.onNodeWithText("Reply…").performClick()
        compose.runOnIdle { height.value = 300 }
        compose.waitForIdle()
        assertEquals(before, scrollRange().value())
        compose.onNodeWithText("Latest").assertIsDisplayed()
    }

    @Test fun stopStaysEnabledThroughBriefRefreshGapsButNotThroughOutages() {
        val live = ClientState(selectedThreadId = "fixture", canWrite = true, canRead = true, canWriteThreads = true, connection = ConnectionStatus.Connected,
            activeRunId = "run", messages = listOf(ConversationMessage("m", "assistant", "Working")))
        val state = mutableStateOf(live)
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { Conversation(state.value, {}, {}, { _, _, _ -> }) } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
        // An agent event: the projection refetches for a moment while the connection stays healthy.
        state.value = live.copy(canWrite = false, canRead = false); compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
        compose.onNodeWithText("Read only · reconnect to continue").assertDoesNotExist()
        state.value = live; compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
        // A refresh that never lands eventually reads as stale.
        state.value = live.copy(canWrite = false, canRead = false); compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithContentDescription("Stop").assertIsNotEnabled()
        // A real outage disables immediately.
        state.value = live; compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Stop").assertIsEnabled()
        state.value = live.copy(canWrite = false, canRead = false, canWriteThreads = false, connection = ConnectionStatus.Reconnecting); compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Stop").assertIsNotEnabled()
    }

    @Test fun htmlRenderEntryReachesCardAsOwnRow() {
        val render = HtmlRenderReference("attachment-1", "Chart", 300)
        val state = ClientState(
            connection = ConnectionStatus.Connected, canWrite = true,
            projects = listOf(Project("p", "backend")), threads = listOf(ThreadSummary("t", "p", "Thread")), selectedThreadId = "t",
            messages = listOf(ConversationMessage("u", "user", "Draw", runId = "r", ordinal = 0), ConversationMessage("a", "assistant", "Done", runId = "r", ordinal = 3)),
            activities = listOf(
                ToolActivity("t1", "Read", kind = "file_read", status = "completed", runId = "r", ordinal = 1),
                ToolActivity("h", "mcp__t3-code__html_render", kind = "dynamic_tool", status = "completed", runId = "r", ordinal = 2, htmlRender = render)),
            runs = listOf(ConversationRun("r", "completed", completedAt = "2026-11-06T10:00:00Z")))
        compose.setContent { TrefoilTheme(darkTheme = true) { Box(Modifier.width(390.dp).height(844.dp)) { Conversation(state, {}, {}, { _, _, _ -> }, onBack = {}) } } }
        compose.onNodeWithTag("html-render:h").assertExists()
        compose.onAllNodesWithTag("html-render:h").assertCountEquals(1)
    }
}

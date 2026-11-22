package com.tajchert.trefoil.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.text.input.ImeAction
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
class NewThreadFlowTest {
    @get:Rule val compose = createComposeRule()
    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"), ModelOption("claude", "sonnet", "Model B"))
    private val state = ClientState(projects = listOf(Project("p1", "Alpha"), Project("p2", "Beta")), models = models, canWrite = true)

    private fun sheet(s: ClientState, onStart: (ThreadDraft, String) -> Unit = { _, _ -> }) =
        compose.setContent { TrefoilTheme { NewThreadSheetContent(s, {}, onStart) } }

    @Test fun noTitleFieldAndStartEnabledWithoutOne() {
        sheet(state)
        compose.onNodeWithText("Conversation title").assertDoesNotExist()
        compose.onNodeWithText("What should the agent do?").assertExists()
        compose.onNodeWithContentDescription("Start").assertIsEnabled()
    }

    @Test fun tappingChipsAndMenusSelectsProjectModelAndModeAndStartEmitsDraftWithPrompt() {
        var started: ThreadDraft? = null
        var prompt: String? = null
        sheet(state) { draft, text -> started = draft; prompt = text }
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithContentDescription("Access: Supervised").performClick()
        compose.onNodeWithText("Auto-accept edits").performClick()
        compose.onNodeWithText("What should the agent do?").performTextInput("Fix the build")
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle {
            assertEquals(Triple("p2", models[1], RuntimeMode.AutoAcceptEdits), started?.let { Triple(it.projectId, it.model, it.runtimeMode) })
            assertEquals("Fix the build", prompt)
        }
    }

    private val many = state.copy(projects = (1..12).map { Project("p$it", "Project %02d".format(it), "/work/team/project-$it") })

    @Test fun manyProjectsShowRecentFiveThenAllAndSearchFiltersTitleAndPath() {
        sheet(many)
        compose.onNodeWithText("Project 05").assertExists()
        compose.onNodeWithText("Project 06").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show all 12 projects").performScrollTo().performClick()
        compose.onNodeWithText("Project 12").assertExists()
        compose.onNodeWithText("Search projects").performTextInput("project-11")
        compose.onNodeWithText("Project 12").assertDoesNotExist()
        compose.onNode(hasText("Project 11") and hasClickAction() and !isEditable()).assertExists()
    }

    @Test fun fewProjectsHaveNoSearchField() {
        sheet(state)
        compose.onNodeWithContentDescription("Search projects").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show all", substring = true).assertDoesNotExist()
    }

    @Test fun projectsSortByRecentThreadActivityAndTheMostRecentIsPreselected() {
        var started: ThreadDraft? = null
        val threads = listOf(
            ThreadSummary("t1", "p3", "old", latestUserMessageAt = "2026-01-01T00:00:00Z"),
            ThreadSummary("t2", "p9", "newest", updatedAt = "2026-03-01T00:00:00Z"),
            ThreadSummary("t3", "p3", "newer", latestUserMessageAt = "2026-02-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"),
            ThreadSummary("t4", "p1", "gone", latestUserMessageAt = "2026-09-01T00:00:00Z", deletedAt = "2026-09-02T00:00:00Z"),
        )
        sheet(many.copy(threads = threads)) { draft, _ -> started = draft }
        compose.onNode(hasText("Project 09") and hasClickAction()).assertIsSelected()
        // The two active projects lead, so Project 03 is listed even though alphabetical order would hide it behind 01, 02, 04...
        compose.onNodeWithText("Project 03").assertExists()
        compose.onNodeWithText("Project 05").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("p9", started?.projectId) }
    }

    @Test fun recencyOrderAndSearchRanking() {
        val projects = listOf(Project("a", "Zeta"), Project("b", "alpha"), Project("c", "Beta", "/src/alpha-tools"), Project("d", "Gamma"))
        val ordered = projectsByRecentActivity(projects, mapOf("d" to 20L, "a" to 10L))
        assertEquals(listOf("Gamma", "Zeta", "alpha", "Beta"), ordered.map { it.title })
        assertEquals(listOf("alpha", "Beta"), searchProjects(ordered, "alp").map { it.title })
        assertEquals(listOf("Beta"), searchProjects(ordered, "be tools").map { it.title })
        assertEquals("work/trefoil", shortProjectPath("/Users/me/work/trefoil/"))
    }

    @Test fun tappingProjectCollapsesPickerToSelectedPill() {
        sheet(state)
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Project: Beta").assertExists()
        compose.onNodeWithText("Alpha").assertDoesNotExist()
    }

    @Test fun focusingPromptCollapsesPickerAndPillReExpandsKeepingSelection() {
        var started: ThreadDraft? = null
        sheet(state) { draft, _ -> started = draft }
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Project: Beta").performClick()
        compose.onNodeWithText("Alpha").assertExists()
        compose.onNode(hasText("Beta") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithContentDescription("Project: Alpha").performClick()
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("What should the agent do?").performClick()
        compose.onNodeWithContentDescription("Project: Alpha").assertExists()
        compose.onNodeWithText("Beta").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals("p1", started?.projectId) }
    }

    @Test fun focusCollapseWorksFromExpandedDefaultSelection() {
        sheet(state)
        compose.onNodeWithText("Beta").assertExists()
        compose.onNodeWithText("What should the agent do?").performClick()
        compose.onNodeWithContentDescription("Project: Alpha").assertExists()
        compose.onNodeWithText("Beta").assertDoesNotExist()
    }

    @Test fun singleProjectStartsCollapsed() {
        sheet(state.copy(projects = listOf(Project("p1", "Alpha"))))
        compose.onNodeWithContentDescription("Project: Alpha").assertExists()
        compose.onNode(hasText("Alpha") and !hasContentDescription("Project: Alpha")).assertDoesNotExist()
        compose.onNodeWithContentDescription("Search projects").assertDoesNotExist()
    }

    @Test fun searchImeActionSelectsTopResultAndCollapses() {
        val projects = state.copy(projects = listOf("Alpha", "Beta", "Alps", "Delta", "Echo", "Foxtrot").mapIndexed { i, t -> Project("p$i", t) })
        sheet(projects)
        compose.onNode(hasImeAction(ImeAction.Search)).performImeAction()
        compose.onNodeWithContentDescription("Project: Alpha").assertDoesNotExist()
        compose.onNode(hasImeAction(ImeAction.Search)).performTextInput("Al")
        compose.onNode(hasImeAction(ImeAction.Search)).performImeAction()
        compose.onNodeWithContentDescription("Project: Alpha").assertExists()
        compose.onNodeWithContentDescription("Search projects").assertDoesNotExist()
    }

    @Test fun collapsingClearsSearchQuery() {
        sheet(many)
        compose.onNodeWithText("Search projects").performTextInput("Project 07")
        compose.onNode(hasText("Project 07") and hasClickAction() and !isEditable()).performClick()
        compose.onNodeWithContentDescription("Project: Project 07").performClick()
        compose.onNodeWithText("Project 01").assertExists()
        compose.onNode(hasText("Project 07") and isEditable()).assertDoesNotExist()
    }

    @Test fun lateArrivingProjectSelectsAndCollapses() {
        val current = mutableStateOf(state.copy(projects = emptyList()))
        var started: ThreadDraft? = null
        compose.setContent { TrefoilTheme { NewThreadSheetContent(current.value, {}, { d, _ -> started = d }) } }
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
        compose.runOnIdle { current.value = state.copy(projects = listOf(Project("p9", "Late"))) }
        compose.onNodeWithContentDescription("Project: Late").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals("p9", started?.projectId) }
    }

    @Test fun defaultModeIsApprovalRequired() {
        var started: ThreadDraft? = null
        sheet(state) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.ApprovalRequired, started?.runtimeMode); assertEquals("p1", started?.projectId) }
    }

    @Test fun startDisabledWhenReadOnly() {
        sheet(state.copy(canWrite = false))
        compose.onNodeWithContentDescription("Start").assertIsNotEnabled()
    }

    @Test fun draftConversationShowsHintAndPickersCallBack() {
        val draft = ThreadDraft("p1", models[0])
        var mode: RuntimeMode? = null
        var model: ModelOption? = null
        val sent = mutableListOf<String>()
        compose.setContent { MaterialTheme { Conversation(state, { sent += it }, {}, { _, _, _ -> }, draft = draft, onSetRuntimeMode = { mode = it }, onSetModel = { model = it }) } }
        compose.onNodeWithText("New thread in Alpha").assertIsDisplayed()
        compose.onNodeWithText("Your first message starts the thread.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Runtime mode: Supervised").performClick()
        compose.onNodeWithText("Full access").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithText("What should the agent do?").performTextInput("hello")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.FullAccess, mode); assertEquals(models[1], model); assertEquals(listOf("hello"), sent) }
    }

    @Test fun pickersDisabledWhenReadOnlyAndModelPickerWhenNoModels() {
        compose.setContent { MaterialTheme { Conversation(state.copy(selectedThreadId = "t", canWrite = false), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Runtime mode: not set").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Model: not set").assertIsNotEnabled()
    }

    @Test fun modelPickerDisabledWithoutModels() {
        compose.setContent { MaterialTheme { Conversation(state.copy(selectedThreadId = "t", models = emptyList()), {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Runtime mode: not set").assertIsEnabled()
        compose.onNodeWithContentDescription("Model: not set").assertIsNotEnabled()
    }

    private val restricted = listOf(
        ModelOption("codex", "gpt-a", "Model A", listOf(RuntimeMode.ApprovalRequired, RuntimeMode.FullAccess)),
        ModelOption("claude", "sonnet", "Model B", listOf(RuntimeMode.AutoAcceptEdits)),
    )

    @Test fun dialogDefaultsToServerDefaultMode() {
        var started: ThreadDraft? = null
        sheet(state.copy(defaultRuntimeMode = RuntimeMode.FullAccess)) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.FullAccess, started?.runtimeMode) }
    }

    @Test fun dialogShowsOnlySupportedModesAndFallsBackAfterModelChange() {
        var started: ThreadDraft? = null
        sheet(state.copy(models = restricted)) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Access: Supervised").performClick()
        compose.onNodeWithText("Auto-accept edits").assertDoesNotExist()
        compose.onNodeWithText("Auto", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Full access").assertExists()
        compose.onNodeWithText("Full access").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithContentDescription("Access: Auto-accept edits").assertExists()
        compose.onNodeWithContentDescription("Access: Auto-accept edits").performClick()
        compose.onNodeWithText("Full access").assertDoesNotExist()
        compose.onNodeWithText("Supervised").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(RuntimeMode.AutoAcceptEdits, started?.runtimeMode) }
    }

    @Test fun composerModeMenuFiltersByModel() {
        val draft = ThreadDraft("p1", restricted[0])
        compose.setContent { MaterialTheme { Conversation(state.copy(models = restricted), {}, {}, { _, _, _ -> }, draft = draft) } }
        compose.onNodeWithContentDescription("Runtime mode: Supervised").performClick()
        compose.onNodeWithText("Full access").assertExists()
        compose.onNodeWithText("Auto-accept edits").assertDoesNotExist()
    }

    @Test fun lockedProviderListsOnlySameInstanceModels() {
        val more = models + ModelOption("codex", "gpt-b", "Model C")
        val locked = state.copy(models = more, selectedThreadId = "t", selectedModel = more[0], selectedRuntimeMode = RuntimeMode.Auto, selectedProviderLocked = true)
        compose.setContent { MaterialTheme { Conversation(locked, {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithText("Model C").assertExists()
        compose.onNodeWithText("Model B").assertDoesNotExist()
    }

    @Test fun unlockedProviderListsAllModels() {
        val unlocked = state.copy(selectedThreadId = "t", selectedModel = models[0], selectedRuntimeMode = RuntimeMode.Auto)
        compose.setContent { MaterialTheme { Conversation(unlocked, {}, {}, { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Model B").assertExists()
    }

    @Test fun sendFailedClearsPendingAndKeepsTextEvenWithIdenticalError() {
        val outcomes = androidx.compose.runtime.mutableStateOf<Map<String, SendOutcome>>(emptyMap())
        val ids = mutableListOf<String>()
        val errored = state.copy(selectedThreadId = "t", error = "boom")
        compose.setContent {
            MaterialTheme {
                Conversation(errored, {}, {}, { _, _, _ -> }, submitMessage = { s, _ -> ids += s.id },
                    sendOutcomes = outcomes.value, onConsumeSendOutcome = { id -> outcomes.value = outcomes.value - id })
            }
        }
        repeat(2) {
            if (it == 0) compose.onNodeWithText("Reply…").performTextInput("hello")
            compose.onNodeWithContentDescription("Send message").performClick()
            compose.onNodeWithText("Waiting for server confirmation…").assertIsDisplayed()
            compose.runOnIdle { outcomes.value = mapOf(ids.last() to SendOutcome.Failed) }
            compose.waitForIdle()
            compose.onNodeWithText("Waiting for server confirmation…").assertDoesNotExist()
            compose.onNodeWithText("hello").assertExists()
        }
        compose.runOnIdle { assertEquals(2, ids.distinct().size) }
    }

    private val effort = ModelOptionDescriptor.Select(
        "effort", "Reasoning", choices = listOf(
            ModelOptionChoice("low", "Low"), ModelOptionChoice("high", "High", isDefault = true, description = "Deep thinking"),
        ), isReasoning = true,
    )
    private val reasoningModels = listOf(ModelOption("claude", "opus", "Opus", descriptors = listOf(effort)), ModelOption("codex", "gpt-a", "Plain"))
    private val reasoningState = state.copy(models = reasoningModels)

    @Test fun effortPillHiddenWithoutReasoning() {
        sheet(state)
        compose.onNodeWithContentDescription("Reasoning: High").assertDoesNotExist()
        compose.onAllNodes(hasContentDescription("Reasoning", substring = true)).assertCountEquals(0)
    }

    @Test fun effortPillShowsDefaultAndStartSendsNoOptionUntilPicked() {
        var started: ThreadDraft? = null
        sheet(reasoningState) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Reasoning: High").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertTrue(started!!.modelOptions.isEmpty()) }
    }

    @Test fun pickingEffortUpdatesLabelAndStartEmitsOption() {
        var started: ThreadDraft? = null
        sheet(reasoningState) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Reasoning: High").performClick()
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithContentDescription("Reasoning: Low").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(mapOf("effort" to "low"), started!!.modelOptions) }
    }

    @Test fun t3DefaultModelAndEffortSeedTheSheetAndProjectOverridesWin() {
        var started: ThreadDraft? = null
        val configured = reasoningState.copy(
            defaultModelSelection = ModelDefault("claude", "opus", mapOf("effort" to "low", "gone" to "x")),
            projectDefaults = mapOf("p2" to NewThreadDefaults(ModelDefault("codex", "gpt-a"), RuntimeMode.FullAccess)),
        )
        sheet(configured) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Model: Opus").assertExists()
        compose.onNodeWithContentDescription("Reasoning: Low").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals("opus" to mapOf<String, Any>("effort" to "low"), started!!.model.model to started!!.modelOptions) }
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Model: Plain").assertExists()
        compose.onNodeWithContentDescription("Access: Full access").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(Triple("gpt-a", RuntimeMode.FullAccess, emptyMap<String, Any>()), Triple(started!!.model.model, started!!.runtimeMode, started!!.modelOptions)) }
    }

    @Test fun anExplicitModelPickSurvivesProjectSwitch() {
        val configured = reasoningState.copy(projectDefaults = mapOf("p2" to NewThreadDefaults(ModelDefault("claude", "opus"))))
        sheet(configured.copy(defaultModelSelection = ModelDefault("codex", "gpt-a")))
        compose.onNodeWithContentDescription("Model: Plain").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Opus").performClick()
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Project: Beta").performClick()
        compose.onNodeWithText("Alpha").performClick()
        compose.onNodeWithContentDescription("Model: Opus").assertExists()
    }

    @Test fun switchingToModelWithoutEffortDropsOption() {
        var started: ThreadDraft? = null
        sheet(reasoningState) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Reasoning: High").performClick()
        compose.onNodeWithText("Low").performClick()
        compose.onNodeWithContentDescription("Model: Opus").performClick()
        compose.onNodeWithContentDescription("codex").performClick()
        compose.onNodeWithText("Plain").performClick()
        compose.onAllNodes(hasContentDescription("Reasoning", substring = true)).assertCountEquals(0)
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertTrue(started!!.modelOptions.isEmpty()) }
    }
    private val fast = ModelOptionDescriptor.Toggle("fastMode", "Fast mode")
    private val fastModels = listOf(ModelOption("claude", "opus", "Opus", descriptors = listOf(effort, fast)), ModelOption("codex", "gpt-a", "Plain"))
    private val fastState = state.copy(models = fastModels)

    @Test fun pickingFastInThePickerLabelsThePillAndStartEmitsIt() {
        var started: ThreadDraft? = null
        val written = mutableListOf<Boolean?>()
        val drafts = ComposerDraftUi(update = { _, _, _, _, _, _, fastMode -> written += fastMode })
        compose.setContent { TrefoilTheme { NewThreadSheetContent(fastState.copy(environment = SavedEnvironment("env", "Env", "https://fixture.invalid", "env")), {}, { draft, _ -> started = draft }, composerDrafts = drafts) } }
        compose.onNodeWithContentDescription("Model: Opus").performClick()
        compose.onNodeWithContentDescription("Fast mode").performClick()
        compose.onNodeWithContentDescription("Model: Opus · Fast").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle {
            assertEquals(mapOf<String, Any>("fastMode" to true), started!!.modelOptions)
            assertEquals("the pick is persisted with the draft", true, written.last())
        }
    }

    @Test fun switchingToModelWithoutFastDropsIt() {
        var started: ThreadDraft? = null
        sheet(fastState) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Model: Opus").performClick()
        compose.onNodeWithContentDescription("Fast mode").performClick()
        compose.onNodeWithContentDescription("codex").performClick()
        compose.onNodeWithText("Plain").performClick()
        compose.onNodeWithContentDescription("Model: Plain").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertTrue(started!!.modelOptions.isEmpty()) }
    }

    @Test fun switchingToModelWithoutFastStoresNoFast() {
        val written = mutableListOf<Boolean?>()
        val drafts = ComposerDraftUi(update = { _, _, _, _, _, _, fastMode -> written += fastMode })
        compose.setContent { TrefoilTheme { NewThreadSheetContent(fastState.copy(environment = SavedEnvironment("env", "Env", "https://fixture.invalid", "env")), {}, { _, _ -> }, composerDrafts = drafts) } }
        compose.onNodeWithContentDescription("Model: Opus").performClick()
        compose.onNodeWithContentDescription("Fast mode").performClick()
        compose.onNodeWithContentDescription("codex").performClick()
        compose.onNodeWithText("Plain").performClick()
        compose.runOnIdle { assertEquals(listOf(true, null), written) }
    }

    @Test fun aStoredFastChoiceTheDraftsModelLacksIsNotRestored() {
        var started: ThreadDraft? = null
        val key = com.tajchert.trefoil.core.session.ComposerDraftKey.NewThread("env", "p1")
        val stored = com.tajchert.trefoil.core.session.ComposerDraft(key, "", "codex", "gpt-a", updatedAtMillis = 1, fastMode = true)
        val drafts = ComposerDraftUi(drafts = mapOf(key to stored))
        compose.setContent { TrefoilTheme { NewThreadSheetContent(fastState.copy(environment = SavedEnvironment("env", "Env", "https://fixture.invalid", "env"), defaultModelSelection = ModelDefault("codex", "gpt-a")), {}, { draft, _ -> started = draft }, composerDrafts = drafts, openProjectId = "p1") } }
        compose.onNodeWithContentDescription("Model: Plain").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Opus").performClick()
        compose.onNodeWithContentDescription("Model: Opus").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertTrue(started!!.modelOptions.isEmpty()) }
    }

    @Test fun theDefaultsFastModeCanBeTurnedOff() {
        var started: ThreadDraft? = null
        sheet(fastState.copy(defaultModelSelection = ModelDefault("claude", "opus", mapOf("fastMode" to true)))) { draft, _ -> started = draft }
        compose.onNodeWithContentDescription("Model: Opus · Fast").performClick()
        compose.onNodeWithContentDescription("Fast mode").performClick()
        compose.onNodeWithContentDescription("Model: Opus").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(mapOf<String, Any>("fastMode" to false), started!!.modelOptions) }
    }

    @Test fun aStoredDraftSeedsFastMode() {
        var started: ThreadDraft? = null
        val key = com.tajchert.trefoil.core.session.ComposerDraftKey.NewThread("env", "p1")
        val stored = com.tajchert.trefoil.core.session.ComposerDraft(key, "", "claude", "opus", updatedAtMillis = 1, fastMode = true)
        val drafts = ComposerDraftUi(drafts = mapOf(key to stored))
        compose.setContent { TrefoilTheme { NewThreadSheetContent(fastState.copy(environment = SavedEnvironment("env", "Env", "https://fixture.invalid", "env")), {}, { draft, _ -> started = draft }, composerDrafts = drafts, openProjectId = "p1") } }
        compose.onNodeWithContentDescription("Model: Opus · Fast").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(mapOf<String, Any>("fastMode" to true), started!!.modelOptions) }
    }
}

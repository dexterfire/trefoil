package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Folding, rotating and resizing recreate the Activity, which restores `rememberSaveable` state. Each test drives a screen, calls
 * [StateRestorationTester.emulateSavedInstanceStateRestore], and checks what the user would still see. Content inside a dialog or sheet
 * window keeps its state in the real saved-state registry, which that tester does not reach, so those tests recreate the Activity for
 * real ([recreate]). Secrets (SSH credentials, pairing links) must never come back from saved state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConfigurationChangeStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    /** Recreates the Activity (a fold, rotation or resize) and composes [content] again in the new instance, as the app's onCreate does. */
    private fun recreate(content: @Composable () -> Unit) {
        compose.activityRule.scenario.recreate()
        compose.setContent(content)
    }

    private val environment = SavedEnvironment("env", "Fixture", "https://fixture.invalid", "fixture-token")
    private val composerState = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true)

    // ---- Composer ----

    @OptIn(ExperimentalTestApi::class)
    @Test fun composerCaretSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TrefoilTheme {
                ConversationComposer(composerState, null, "one two three", {}, waiting = false, stopPending = false, onSetRuntimeMode = {}, onSetModel = {},
                    onSetModelOption = { _, _ -> }, stop = {}, onSend = {}, onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {})
            }
        }
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(4, 7))
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(4, 7)))
    }

    // ---- Thread list ----

    private val threads = (0 until 60).map { ThreadSummary("t$it", if (it % 2 == 0) "alpha" else "beta", "Row %02d".format(it), "completed") } +
        ThreadSummary("ask", "alpha", "Needs an answer", "running", pendingRequestKind = "approval")
    private val listState = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWrite = true,
        projects = listOf(Project("alpha", "Alpha"), Project("beta", "Beta")), threads = threads)

    @Test fun threadListSearchAndFilterSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrefoilTheme { ThreadList(listState, {}, {}) } }
        compose.onNodeWithText("Needs you", substring = true).performClick()
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search threads").performTextInput("answer")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("answer")
        compose.onNode(hasText("Needs you", substring = true) and isSelected()).assertExists()
        compose.onNodeWithText("Needs an answer").assertExists()
        compose.onNodeWithText("Row 01").assertDoesNotExist()
    }

    @Test fun threadListScrollPositionSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrefoilTheme { ThreadList(listState, {}, {}) } }
        compose.onNodeWithText("Row 00").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        compose.waitForIdle()
        compose.onNodeWithText("Row 00").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Row 00").assertDoesNotExist()
    }

    // ---- New-thread sheet ----

    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"), ModelOption("claude", "sonnet", "Model B"))
    private val sheetState = ClientState(projects = listOf(Project("p1", "Alpha"), Project("p2", "Beta")), models = models, canWrite = true)

    @Test fun newThreadSheetChoicesAndPromptSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        var started: Triple<String?, ModelOption?, RuntimeMode?>? = null
        restoration.setContent { TrefoilTheme { NewThreadSheetContent(sheetState, {}, { draft, _ -> started = Triple(draft.projectId, draft.model, draft.runtimeMode) }) } }
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Model B").performClick()
        compose.onNodeWithContentDescription("Access: Supervised").performClick()
        compose.onNodeWithText("Auto-accept edits").performClick()
        compose.onNodeWithText("What should the agent do?").performTextInput("Fix the build")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Fix the build").assertExists()
        compose.onNodeWithContentDescription("Model: Model B").assertExists()
        compose.onNodeWithContentDescription("Access: Auto-accept edits").assertExists()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals(Triple("p2", models[1], RuntimeMode.AutoAcceptEdits), started) }
    }

    @Test fun newThreadModelPickerStaysOpenAcrossRecreation() {
        val content: @Composable () -> Unit = { TrefoilTheme { NewThreadSheetContent(sheetState, {}, { _, _ -> }) } }
        compose.setContent(content)
        compose.onNodeWithContentDescription("Model: Model A").performClick()
        compose.onNodeWithContentDescription("claude").performClick()
        compose.onNodeWithText("Model B").assertExists()
        recreate(content)
        compose.onNodeWithText("Model B").assertExists()
    }

    // ---- SSH dialog ----

    @Test fun sshDialogKeepsConnectionFieldsButNeverCredentials() {
        val content: @Composable () -> Unit = { MaterialTheme { SshConnectionDialog(false, null, {}, {}) {} } }
        compose.setContent(content)
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput("host.local")
        compose.onNodeWithText("SSH username").performTextInput("alice")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("hunter2")
        recreate(content)
        compose.onNodeWithText("My server").assertExists()
        compose.onNodeWithText("host.local").assertExists()
        compose.onNodeWithText("alice").assertExists()
        compose.onNodeWithText("22").assertExists()
        compose.onNodeWithText("hunter2").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("SSH password")).assertTextEquals("SSH password", "")
    }

    @Test fun sshDialogRestoresPrivateKeyMethodWithoutTheKey() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nsecret-material\n-----END OPENSSH PRIVATE KEY-----"
        val content: @Composable () -> Unit = { MaterialTheme { SshConnectionDialog(false, null, {}, {}) {} } }
        compose.setContent(content)
        compose.onNode(isSelectable() and isNotSelected()).performScrollTo().performClick()
        compose.onNodeWithText("Paste private key").performScrollTo().performTextInput(pem)
        compose.onNodeWithText("Advanced settings").performScrollTo().performClick()
        recreate(content)
        compose.onNodeWithText("Import private key file").assertExists()
        compose.onNodeWithText("Hide advanced settings").assertExists()
        compose.onNodeWithText("secret-material", substring = true).assertDoesNotExist()
        compose.onNodeWithText("SSH password").assertDoesNotExist()
    }

    // ---- Pair dialog ----

    @Test fun pairDialogKeepsTheDisplayNameButNeverThePairingLink() {
        val link = "very-secret"
        val content: @Composable () -> Unit = { MaterialTheme { PairDialog(false, {}) { _, _, _ -> } } }
        compose.setContent(content)
        compose.onNodeWithText("Display name").performTextInput("Studio Mac")
        compose.onNodeWithText("Pairing link").performTextInput(link)
        recreate(content)
        compose.onNodeWithText("Studio Mac").assertExists()
        compose.onNodeWithText(link).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("Pairing link")).assertTextEquals("Pairing link", "")
    }

    // ---- Machines ----

    @Test fun forgetConfirmationSurvivesRecreationWhileTheMachineStillExists() {
        val restoration = StateRestorationTester(compose)
        val saved = listOf(SavedEnvironment("m", "MacBook Pro", "https://laptop.invalid", "t"))
        restoration.setContent { TrefoilTheme { MachinesScreen(ClientState(), saved, {}, {}, {}, {}, {}, null) } }
        compose.onNodeWithContentDescription("Options for MacBook Pro").performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.onNodeWithText("Forget MacBook Pro?").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Forget MacBook Pro?").assertExists()
    }

    // ---- Thread actions ----

    @Test fun renameDialogAndItsEditedTitleSurviveRecreation() {
        val state = ClientState(environment = environment, connection = ConnectionStatus.Connected, canWriteThreads = true, threadActionsConfigured = true)
        val content: @Composable () -> Unit = { MaterialTheme { ThreadActionsMenu(state, ThreadSummary("row", "p", "Row")) { _, _, _ -> } } }
        compose.setContent(content)
        compose.onNodeWithContentDescription("Thread actions for Row").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Renamed by hand")
        recreate(content)
        compose.onNodeWithText("Rename thread").assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains("Renamed by hand")
    }

    // ---- Usage ----

    private val report = UsageReport(
        "m", UsageRange.Week, "2026-01-01", "2026-01-07", "UTC", "2026-01-07T12:00:00Z",
        totalCostUsd = 12.34, tokens = UsageTokens(uncachedInput = 1000, cachedInput = 200, output = 300), cacheSavingsUsd = 2.0,
        providers = listOf(UsageBreakdown("codex", costUsd = 10.0)), models = listOf(UsageBreakdown("codex", "model-fixture", costUsd = 10.0)),
        days = listOf(UsageDayTotal("2026-01-01", 2.34, 500), UsageDayTotal("2026-01-07", 10.0, 1000)), pricingStatus = "fresh",
    )

    @Test fun usageTabSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        val state = ClientState(environment = SavedEnvironment("m", "Mac mini", "https://fixture.example", "fixture"), connection = ConnectionStatus.Connected, canReadUsage = true)
        restoration.setContent { TrefoilTheme { UsageScreen(state, UsageUiState(reports = mapOf(UsageRange.Week to report)), {}, {}, {}, {}) } }
        compose.onNodeWithText("Cost").performClick()
        compose.onNodeWithText("Tokens").performClick()
        compose.onNodeWithText("1.5K").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("1.5K").assertExists()
        compose.onNodeWithText("$12.34").assertDoesNotExist()
    }
}

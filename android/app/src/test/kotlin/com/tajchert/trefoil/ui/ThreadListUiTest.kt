package com.tajchert.trefoil.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadListUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val fixture = ClientState(connection = ConnectionStatus.Connected, canWrite = true,
        providers = listOf(ProviderInstance("team", "codex", "Work Codex", "#326B41"), ProviderInstance("claudeAgent", "claudeAgent", "Claude")),
        projects = listOf(Project("trefoil", "Trefoil"), Project("t3", "T3 Code")),
        threads = listOf(
            ThreadSummary("pin", "trefoil", "Give every conversation a little more clarity", "completed", pinnedAt = "2026-11-06T08:00:00Z", branch = "feat/conversation-ui", providerInstanceId = "team", providerInstanceHistory = listOf("claudeAgent")),
            ThreadSummary("approval", "trefoil", "Review the connection flow", "running", pendingRequestKind = "approval"),
            ThreadSummary("active", "trefoil", "Polish the Android thread list", "completed"),
            ThreadSummary("work", "t3", "Keep activity in the right order", "running", providerInstanceId = "claudeAgent", activityRunStartedAt = Instant.now().minusSeconds(150).toString(), activityRunStartedAtPresent = true),
            ThreadSummary("done", "trefoil", "Secure environment storage", "completed", settledOverride = "settled", settledAt = "2026-11-06T08:00:00Z")
        ))
    /** Selected list rows; filter chips are tabs and carry their own selection. */
    private val selectedRow = isSelected() and SemanticsMatcher("is not a tab") { it.config.getOrNull(SemanticsProperties.Role) != Role.Tab }

    private fun systemBack() { compose.waitForIdle(); compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

    @Test fun searchFindsProjectAndCollapseHidesEvenTheSelectedThread() {
        compose.setContent { TrefoilTheme { ThreadList(fixture.copy(selectedThreadId = "work"), {}, {}) } }
        compose.onNodeWithContentDescription("T3 Code project").assertExists().performClick()
        compose.onNodeWithText("Keep activity in the right order").assertDoesNotExist()
        compose.onNodeWithContentDescription("T3 Code project").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнуто")).performClick()
        compose.onNodeWithText("Keep activity in the right order").assertExists()
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Поиск тредов").performTextInput("T3 Code")
        compose.onNodeWithText("Keep activity in the right order").assertExists()
        compose.onNodeWithText("Review the connection flow").assertDoesNotExist()
    }
    @Test fun newThreadFabSlidesAboveASnackbarAndBackDown() {
        val bounds = mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        compose.setContent { TrefoilTheme { CompositionLocalProvider(LocalSnackbarBounds provides bounds) { ThreadList(fixture, {}, {}) } } }
        val fab = compose.onNodeWithContentDescription("Новый тред")
        val rest = fab.getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        compose.runOnIdle { bounds.value = with(compose.density) { androidx.compose.ui.geometry.Rect(0f, (root.bottom - 72.dp).toPx(), root.right.toPx(), root.bottom.toPx()) } }
        compose.waitForIdle()
        // The snackbar's top edge sits 72 dp above the bottom; the FAB clears it by 8 dp.
        assertEquals((root.bottom - 80.dp).value, fab.getBoundsInRoot().bottom.value, 1f)
        compose.runOnIdle { bounds.value = null }
        compose.waitForIdle()
        assertEquals(rest.top.value, fab.getBoundsInRoot().top.value, 0.5f)
    }
    @Test fun pullingDownTheListRequestsARefresh() {
        var refreshes = 0
        compose.setContent { TrefoilTheme { ThreadList(fixture, {}, {}, onRefresh = { refreshes++ }) } }
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.4f, endY = height * 0.9f) }
        compose.waitForIdle()
        assertTrue("refresh requested", refreshes >= 1)
    }
    @Test fun pullingAnEmptyListRequestsARefresh() {
        var refreshes = 0
        compose.setContent { TrefoilTheme { ThreadList(fixture.copy(threads = emptyList()), {}, {}, onRefresh = { refreshes++ }) } }
        compose.onRoot().performTouchInput { swipeDown(startY = height * 0.4f, endY = height * 0.9f) }
        compose.waitForIdle()
        assertTrue("refresh requested", refreshes >= 1)
    }
    @Test fun needsYouIsPulledToTopAndSettledFoldsIntoAnExpandableRow() {
        compose.setContent { TrefoilTheme { ThreadList(fixture, {}, {}) } }
        // Projects are the top level now: the amber underline on a project heading is what marks needs-you.
        compose.onNodeWithContentDescription("Trefoil project").assertExists()
        compose.onNodeWithText("Review the connection flow").assertExists()
        // Settled threads live under their project, not in a separate fold.
        compose.onNodeWithText("Secure environment storage").assertExists()
        compose.onNode(hasText("Нужен ответ") and hasText("1")).performClick()
        compose.onNodeWithText("Polish the Android thread list").assertDoesNotExist()
        compose.onNodeWithText("Review the connection flow").assertExists()
        compose.onNode(hasText("Работает") and hasText("1")).performClick()
        compose.onNodeWithText("Keep activity in the right order").assertExists()
        compose.onNodeWithText("Review the connection flow").assertDoesNotExist()
    }
    @Test fun waitingThreadShowsItsKindBeforeThePreviewAndOtherRowsStayUnchanged() {
        val threads = listOf(
            ThreadSummary("ask", "trefoil", "Needs a yes", "running", pendingRequestKind = "approval", latestMessage = "\nRun ./gradlew test?"),
            ThreadSummary("bare", "trefoil", "Needs input bare", "running", pendingRequestKind = "user_input"),
            ThreadSummary("done", "trefoil", "Plain thread", "completed", latestMessage = "All green."),
            ThreadSummary("bad", "trefoil", "Broken thread", "failed"))
        compose.setContent { TrefoilTheme { ThreadList(fixture.copy(threads = threads), {}, {}) } }
        compose.onNodeWithText("Needs approval").assertExists()
        compose.onNodeWithText(" · Run ./gradlew test?").assertExists()
        compose.onNodeWithText("Нужен ответ").assertExists()
        compose.onNodeWithText("All green.").assertExists()
        compose.onNodeWithText("Failed").assertExists()
    }
    @Test fun snackbarAnnouncesOnlyThreadsThatEnterNeedsYouAndOpensThem() {
        val env = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")
        val quiet = ThreadSummary("late", "trefoil", "Late bloomer", "completed")
        val already = ThreadSummary("early", "trefoil", "Already waiting", "running", pendingRequestKind = "approval")
        // The live shell counts, not canRead: an open conversation still loading its detail must not hold announcements back.
        val state = androidx.compose.runtime.mutableStateOf(fixture.copy(environment = env, canRead = false, shellLive = true, threads = listOf(quiet, already)))
        compose.setContent { TrefoilApp(state.value, emptyList(), { _, _, _ -> }, {},
            { id -> state.value = state.value.copy(selectedThreadId = id) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        compose.onNodeWithText("Open").assertDoesNotExist()
        // First snapshot was adopted silently; a thread that now enters Needs you is announced once.
        state.value = state.value.copy(threads = listOf(quiet.copy(pendingRequestKind = "user_input"), already))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Late bloomer needs input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open").performClick()
        compose.waitForIdle()
        assertEquals("late", state.value.selectedThreadId)
    }

    @Test fun snackbarIsDroppedWhenTheConnectionIsLost() {
        val env = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")
        val quiet = ThreadSummary("late", "trefoil", "Late bloomer", "completed")
        val state = androidx.compose.runtime.mutableStateOf(fixture.copy(environment = env, canRead = true, shellLive = true, threads = listOf(quiet)))
        compose.setContent { TrefoilApp(state.value, emptyList(), { _, _, _ -> }, {},
            { id -> state.value = state.value.copy(selectedThreadId = id) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        state.value = state.value.copy(threads = listOf(quiet.copy(pendingRequestKind = "approval")))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Late bloomer needs approval").fetchSemanticsNodes().isNotEmpty() }
        state.value = state.value.copy(connection = ConnectionStatus.Reconnecting)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Late bloomer needs approval").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun returningFromConversationRetainsCollapsedProjectsAndSearch() {
        val state = androidx.compose.runtime.mutableStateOf(fixture.copy(environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token")))
        compose.setContent { TrefoilApp(state.value, emptyList(), { _, _, _ -> }, {},
            { id -> state.value = state.value.copy(selectedThreadId = id) }, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        // Collapse Trefoil, open a thread from the still-expanded T3 Code group, then come back.
        compose.onNodeWithContentDescription("Trefoil project").performClick()
        compose.onNodeWithText("Polish the Android thread list").assertDoesNotExist()
        compose.onNodeWithText("Secure environment storage").assertDoesNotExist()
        systemBack()
        compose.onAllNodes(selectedRow).assertCountEquals(0)
        compose.onNodeWithContentDescription("Trefoil project").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнуто"))
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Поиск тредов").performTextInput("Polish")
        compose.onNodeWithText("Polish the Android thread list").performClick()
        systemBack()
        compose.onNode(hasSetTextAction()).assertTextContains("Polish")
        compose.onNodeWithText("Review the connection flow").assertDoesNotExist()
    }
    @Test fun headerOpensMachinesAndAppMenu() {
        val env = SavedEnvironment("fixture", "Mac mini", "https://fixture.invalid", "fixture-token")
        compose.setContent { TrefoilApp(fixture.copy(environment = env), listOf(env), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithContentDescription("More").performClick()
        listOf("Screen sessions", "Limits & usage", "Machines", "Connect machine", "Disconnect machine").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Screen sessions").performClick()
        compose.onNodeWithContentDescription("Back to conversations").performClick()
        compose.onNodeWithContentDescription("Сменить машину").assertExists()
    }
    @Test @Config(sdk = [35], qualifiers = "w840dp-h900dp") fun twoPaneHighlightsSelectionWithoutBackButton() {
        compose.setContent { TrefoilApp(fixture.copy(environment = SavedEnvironment("fixture", "Fixture", "https://fixture.invalid", "fixture-token"), selectedThreadId = "active"),
            emptyList(), { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        compose.onAllNodes(selectedRow).assertCountEquals(1)
        compose.onNodeWithContentDescription("Back to threads").assertDoesNotExist()
    }
    @Test fun providerMarksAndWorkingDurationUseServerMetadata() {
        compose.setContent { TrefoilTheme { ThreadList(fixture, {}, {}) } }
        compose.onNodeWithContentDescription("Agent: Work Codex").assertExists()
        compose.onNodeWithContentDescription("Agent: Claude").assertExists()
        compose.onNodeWithContentDescription("Working 2m").assertExists()
    }
    @Test fun retainedClientSelectionDoesNotHighlightAThreadWhenListIsShownAgain() {
        compose.setContent { TrefoilTheme { ThreadList(fixture.copy(selectedThreadId = "active"), {}, {}) } }
        compose.onNodeWithText("Polish the Android thread list").assertExists()
        compose.onAllNodes(selectedRow).assertCountEquals(0)
    }
    @Test fun hiddenChildDoesNotInflateConversationCountOrAppearInSearch() {
        val state = fixture.copy(threads = listOf(ThreadSummary("parent", "trefoil", "Parent conversation"),
            ThreadSummary("child", "trefoil", "Hidden fixture child", relationshipToParent = "subagent", parentThreadId = "parent")))
        compose.setContent { TrefoilTheme { ThreadList(state, {}, {}) } }
        compose.onNode(hasText("Все") and hasText("1")).assertExists()
        compose.onNodeWithText("Hidden fixture child").assertDoesNotExist()
        compose.onNodeWithContentDescription("Поиск").performClick()
        compose.onNodeWithText("Поиск тредов").performTextInput("Hidden fixture child")
        compose.onNodeWithText("Ничего не найдено").assertExists()
    }
    @Test fun aMachineWithoutAShellSnapshotShowsPlaceholdersInsteadOfTheEmptyState() {
        var state by mutableStateOf(fixture.copy(threads = emptyList(), projects = emptyList(), shellLoaded = false))
        compose.setContent { TrefoilTheme { ThreadList(state, {}, {}) } }
        compose.onNodeWithContentDescription("Загрузка тредов").assertExists()
        compose.onNodeWithText("Здесь начнётся ваша следующая идея").assertDoesNotExist()
        // The first snapshot is empty: now "no threads" is true.
        state = state.copy(shellLoaded = true); compose.waitForIdle()
        compose.onNodeWithContentDescription("Загрузка тредов").assertDoesNotExist()
        compose.onNodeWithText("Здесь начнётся ваша следующая идея").assertExists()
    }
    @Test fun loadingAndEmptyRules() {
        val blank = ClientState(threads = emptyList())
        for (status in listOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Connected)) {
            assertTrue("$status", threadListLoading(blank.copy(connection = status)))
            assertFalse("$status", threadListShowsEmpty(blank.copy(connection = status)))
            assertFalse("a loaded shell is not loading: $status", threadListLoading(blank.copy(connection = status, shellLoaded = true)))
        }
        // Offline without a snapshot: neither placeholders nor "no threads" (the banner says what is wrong).
        for (status in listOf(ConnectionStatus.Disconnected, ConnectionStatus.Error)) {
            assertFalse("$status", threadListLoading(blank.copy(connection = status)))
            assertFalse("$status", threadListShowsEmpty(blank.copy(connection = status)))
        }
        assertTrue(threadListShowsEmpty(blank.copy(connection = ConnectionStatus.Error, shellLoaded = true)))
        assertFalse("busy keeps the progress bar instead", threadListShowsEmpty(blank.copy(shellLoaded = true, busy = true)))
        // Rows from a client that does not report shellLoaded still count as loaded.
        assertFalse(threadListLoading(fixture))
        assertTrue(threadListShowsEmpty(fixture))
    }
    @Test fun cachedRowsShowUnderTheProgressBarUntilTheShellIsLive() {
        val cached = ClientState(threads = fixture.threads, shellLoaded = true)
        for (status in listOf(ConnectionStatus.Connecting, ConnectionStatus.Reconnecting, ConnectionStatus.Connected)) {
            assertTrue("$status", threadListUpdating(cached.copy(connection = status)))
            assertFalse("live rows: $status", threadListUpdating(cached.copy(connection = status, shellLive = true)))
            assertFalse("rows are shown, not placeholders: $status", threadListLoading(cached.copy(connection = status)))
        }
        // Offline rows say so through the banner, not a progress bar that never ends.
        for (status in listOf(ConnectionStatus.Disconnected, ConnectionStatus.Error)) assertFalse("$status", threadListUpdating(cached.copy(connection = status)))
        assertFalse("No rows: the loading placeholders instead", threadListUpdating(cached.copy(threads = emptyList(), connection = ConnectionStatus.Connecting)))
    }

    /* ---------- Design renders (build/reports/ui/design-*.png) ---------- */

    private val now = Instant.now()
    private fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()
    private val macMini = SavedEnvironment("mini", "Mac mini", "https://mini.invalid", "fixture-token")
    private val saved = listOf(macMini, SavedEnvironment("mbp", "MBP", "https://mbp.invalid", "fixture-token"),
        SavedEnvironment("server", "Server", "https://server.invalid", "fixture-token", ssh = SshProfile("server.local", username = "dev", fingerprint = "SHA256:fixture", keyType = "ssh-ed25519")))
    private val design = ClientState(environment = macMini, connection = ConnectionStatus.Connected, canRead = true, canWrite = true, canWriteThreads = true,
        projects = listOf(Project("backend", "backend"), Project("t3code", "t3code"), Project("pth", "public-transport-hub"), Project("kanarek", "kanarek-android")),
        models = listOf(ModelOption("codex", "fable", "Fable 5.2")),
        providers = listOf(ProviderInstance("codex", "codex", "Codex"), ProviderInstance("claudeAgent", "claudeAgent", "Claude")),
        threads = listOf(
            ThreadSummary("districts", "backend", "Investigate Missing City Districts", "running", updatedAt = ago(2), pendingRequestKind = "user_input",
                latestMessage = "Plan ready. Start with steps 1–2?", branch = "chore/cams-ads-key", providerInstanceId = "claudeAgent"),
            ThreadSummary("adb", "t3code", "Set Up Custom T3 Fork and Remote ADB", "running", updatedAt = ago(8), pendingRequestKind = "approval",
                latestMessage = "Approve adb connect 192.168.10.42", branch = "t3/custom-t3-remote-adb", providerInstanceId = "codex"),
            ThreadSummary("explore", "pth", "Explore Mobile API Data Features", "running", updatedAt = ago(1), activityRunStartedAt = ago(3), activityRunStartedAtPresent = true,
                latestMessage = "Writing the page…", branch = "feat/mobile-api-data-features-exploration", createdAt = ago(30), providerInstanceId = "claudeAgent", providerInstanceHistory = listOf("codex")),
            ThreadSummary("commits", "kanarek", "Review Recent Commits and Prioritize Fixes", "running", updatedAt = ago(1), activityRunStartedAt = ago(6), activityRunStartedAtPresent = true,
                latestMessage = "Reading 24 commits on main", branch = "main", createdAt = ago(20), providerInstanceId = "codex"),
            ThreadSummary("air", "kanarek", "Add District Level Air Quality Overview", "completed", updatedAt = ago(13), latestRunCompletedAt = ago(13),
                latestMessage = "Overview card added, 3 files changed", branch = "main"),
        ) + (0 until 85).map { ThreadSummary("settled-$it", "backend", "Settled $it", "completed", settledOverride = "settled", settledAt = ago(600L + it)) })
    private val explore = design.copy(selectedThreadId = "explore", activeRunId = "run", messages = listOf(
        ConversationMessage("u1", "user", "Save as artifact with a longer description for each proposal and hard data: compute, frequency, storage and RAM per item.", runId = "run", ordinal = 0),
        ConversationMessage("u2", "user", "estimate requirements", runId = "run", ordinal = 1),
        ConversationMessage("a1", "assistant", "Measurements are in. I timed each candidate query on production, read-only and capped; now writing the page.", runId = "run", ordinal = 2)),
        runs = listOf(ConversationRun("run", "running", startedAt = ago(3))))

    private fun render(name: String, state: ClientState) {
        compose.setContent { TrefoilApp(state, saved, { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {}) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        val output = File("build/reports/ui/$name.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
    @Test @Config(sdk = [35], qualifiers = "w390dp-h844dp-night") fun renderDesignThreadsDark() = render("design-threads-dark", design)
    @Test @Config(sdk = [35], qualifiers = "w390dp-h844dp-notnight") fun renderDesignThreadsLight() = render("design-threads-light", design)
    @Test @Config(sdk = [35], qualifiers = "w840dp-h900dp-night") fun renderDesignFoldableDark() = render("design-foldable-dark", explore)
    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-night") fun renderDesignTabletDark() = render("design-tablet-dark", design.copy(selectedThreadId = "districts"))
    @Test @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-notnight") fun renderDesignTabletLight() = render("design-tablet-light", design.copy(selectedThreadId = "districts"))
    @Test @Config(sdk = [35], qualifiers = "w390dp-h844dp-night") fun renderDesignReconnectingDark() =
        render("design-threads-reconnecting-dark", design.copy(connection = ConnectionStatus.Reconnecting))

    @Test fun renderCommandWorkLog() {
        val state = fixture.copy(selectedThreadId = "work", activeRunId = "run", messages = listOf(
            ConversationMessage("prompt", "user", "Run the Android checks and investigate any failures.", runId = "run", ordinal = 0)),
            activities = listOf(
                ToolActivity("search", "file_search", kind = "file_search", status = "completed", input = "ConnectionPolicy", runId = "run", ordinal = 1),
                ToolActivity("command", "command_execution", kind = "command_execution", status = "failed", input = "./gradlew :app:testDebugUnitTest", output = "> Task :app:testDebugUnitTest\n\nConnectionPolicyTest > validates consent FAILED\nExpected explicit private LAN consent.\n\n1 test failed", exitCode = 1, runId = "run", ordinal = 2)))
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { Conversation(state, {}, {}, { _, _, _ -> }) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/command-work-log.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
    @Test fun renderOfflineConversation() {
        val state = fixture.copy(selectedThreadId = "pin", messages = listOf(
            ConversationMessage("user", "user", "Make the conversation calmer and easier to follow.", ordinal = 0, runId = "run", createdAt = "2026-11-06T10:00:00Z"),
            ConversationMessage("commentary", "assistant", "I’ll compare the message hierarchy and run the Android checks.", ordinal = 1, runId = "run"),
            ConversationMessage("answer", "assistant", "## A clearer conversation\n\nYour **ideas stay in focus**, with room for the work to breathe.\n\n- Related activity stays together.\n- Code is easier to read.\n- New replies follow naturally.\n\n```kotlin\nConversation(state = state)\n```", ordinal = 4, runId = "run", createdAt = "2026-11-06T10:01:30Z")
        ), activities = listOf(ToolActivity("read", "Read ThreadFeed.tsx", "Inspect message groups", kind = "file_read", status = "completed", runId = "run", ordinal = 2), ToolActivity("test", "Run Android checks", kind = "command_execution", status = "completed", input = "./gradlew test", output = "BUILD SUCCESSFUL", exitCode = 0, runId = "run", ordinal = 3)),
            runs = listOf(ConversationRun("run", "completed", startedAt = "2026-11-06T10:00:00Z", completedAt = "2026-11-06T10:01:30Z")))
        compose.setContent { TrefoilTheme { Surface { Box(Modifier.size(411.dp, 810.dp)) { Conversation(state, {}, {}, { _, _, _ -> }) } } } }
        compose.waitForIdle()
        val output = File("build/reports/ui/conversation.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
    }
}

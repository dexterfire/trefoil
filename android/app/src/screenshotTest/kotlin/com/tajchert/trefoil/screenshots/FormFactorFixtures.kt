package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.LocalServerCatalog
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.RuntimeMode
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import com.tajchert.trefoil.core.model.ScreenCatalog
import com.tajchert.trefoil.core.model.ScreenTarget
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.ui.UsageUiState
import com.tajchert.trefoil.widget.UsageWidgetSamples
import java.time.Instant

/**
 * Fake data for the form-factor shell previews: one connected workstation with a populated thread list
 * (needs you, pinned, working, recent across two projects) and the open conversations shown beside it.
 * Fake data only; thread ids match [PortsFixtures].
 */
internal object FormFactorFixtures {
    private val fx = ScreenshotFixtures
    private val cf = ConversationFixtures

    private val opus = ModelOption(instanceId = "claudeAgent", model = "claude-opus", label = "Opus")

    private fun working(thread: ThreadSummary, minutes: Long) = thread.copy(
        status = "running", activeRunId = "run-${thread.id}", latestRunId = "run-${thread.id}",
        activityRunStartedAt = fx.ago(minutes), activityRunStartedAtPresent = true,
    )

    val threads: List<ThreadSummary> = listOf(
        fx.thread("t-approval", "Add SSH host key check", pendingRequestKind = "approval", updatedMinutesAgo = 3,
            latestMessage = "Run ./gradlew :core:ssh:test?", branch = "feat/host-keys"),
        fx.thread("t-input", "Pick the 0.23 codename", project = fx.website, pendingRequestKind = "user_input", updatedMinutesAgo = 9,
            latestMessage = "Which codename should the release use?"),
        fx.thread("t-pinned", "Release checklist", pinned = true, updatedMinutesAgo = 240, latestMessage = "All checks green."),
        fx.thread("t-pinned-site", "Design review notes", project = fx.website, pinned = true, updatedMinutesAgo = 60 * 30, latestMessage = "Collected the open questions."),
        working(fx.thread("t-widget", "Multi-provider usage widget", updatedMinutesAgo = 1, branch = "feat/usage-widget", latestMessage = "Fanning out to three providers."), 4),
        working(fx.thread("t-landing", "Landing page dark mode", project = fx.website, updatedMinutesAgo = 1, latestMessage = "Checking contrast on every section."), 2),
        cf.thread.copy(latestMessage = "I added the previews and wired them into the theme."),
        fx.thread("t-recent", "Fix dark mode contrast", project = fx.website, updatedMinutesAgo = 45, latestMessage = "Updated the palette tokens."),
        fx.thread("t-startup", "Profile cold start", updatedMinutesAgo = 70, latestMessage = "Cold start is 18% faster now."),
        fx.thread("t-changelog", "Draft the changelog", project = fx.website, updatedMinutesAgo = 60 * 5, latestMessage = "Changelog is ready for review."),
        fx.thread("t-older", "Refactor usage widget", updatedMinutesAgo = 60 * 26, latestMessage = "Done, see the diff."),
        fx.thread("t-flaky", "Fix flaky reconnect test", updatedMinutesAgo = 60 * 27, latestMessage = "Reproduced it 3 times in 50 runs.", branch = "fix/reconnect"),
        fx.thread("t-sitemap", "Generate the sitemap", project = fx.website, updatedMinutesAgo = 60 * 50, latestMessage = "Sitemap now includes the docs."),
        fx.thread("t-cache", "Warm the build cache in CI", updatedMinutesAgo = 60 * 72, latestMessage = "Cache hit rate is up to 91%."),
    )

    /** Connected shell state with the thread list and no thread selected. */
    fun listState(): ClientState = fx.connectedState(threads).copy(
        projects = fx.projects, models = listOf(opus), selectedModel = opus, selectedRuntimeMode = RuntimeMode.ApprovalRequired,
    )

    private fun msg(id: String, role: String, text: String, ordinal: Int) = ConversationMessage(id = id, role = role, text = text, ordinal = ordinal)

    private val reply = """
        ## What changed

        I added the previews and wired them into the theme:

        - **Light** and **dark** variants for every screen
        - Fixtures use fake data only
        - Reference images are committed next to the tests

        ```kotlin
        @PreviewTest
        @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
        @Composable
        fun ThreadList_Populated_TabletLandscape() { TrefoilTheme(darkTheme = false) {
        WithRuLocale { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, shell = ShellFixtures.noActions, railLayout = true)         } }
        ```

        Run `./gradlew :app:testDebugScreenshotTestDefaultTestSuite` to verify.
    """.trimIndent()

    /** The Add screenshot previews thread: a user turn, a completed work group, then a markdown reply. */
    fun conversationState(): ClientState = listState().copy(
        selectedThreadId = cf.thread.id,
        messages = listOf(
            msg("m1", "user", "Can you add a dark theme preview for the thread list and make the preview names easier to read?", 1),
            msg("m2", "assistant", reply, 6),
        ),
        activities = listOf(cf.commandActivity.copy(ordinal = 2), cf.readActivity.copy(ordinal = 3), cf.editActivity.copy(ordinal = 4), cf.searchActivity.copy(ordinal = 5)),
    )

    /** The Add SSH host key check thread, waiting on an approval. */
    fun approvalState(): ClientState = listState().copy(
        selectedThreadId = "t-approval",
        messages = listOf(
            msg("m1", "user", "Pin the SSH host key on first use and fail closed when it changes.", 1),
            msg("m2", "assistant", "I added the pinned fingerprint store and the mismatch check. I need to run the SSH tests to confirm it fails closed.", 4),
        ),
        activities = listOf(cf.editActivity.copy(ordinal = 2, fileName = "core/ssh/HostKeys.kt"), cf.readActivity.copy(ordinal = 3)),
        requests = listOf(cf.approval),
    )

    // Usage and screen data are built here, at call time, rather than read from UsageFixtures: that object captures the
    // wall clock when first touched, and touching it this early would shift the reset labels of Usage_Limits_* later in the run.
    private fun at(minutes: Long) = Instant.ofEpochMilli(System.currentTimeMillis() + minutes * 60_000 + 30_000).toString()
    private fun window(id: String, kind: String, label: String, used: Double, resetMin: Long) = UsageLimitWindow(id, kind, label, used, at(resetMin), null)

    private fun usageProviders() = listOf(
        ProviderInstance("claude", "claudeAgent", "Claude", usageLimits = ProviderUsageLimits(at(-3), listOf(
            window("session", "session", "5h", 62.0, 125), window("weekly", "weekly", "Weekly", 41.0, 3 * 1440 + 200)))),
        ProviderInstance("codex", "codex", "Codex", usageLimits = ProviderUsageLimits(at(-3), listOf(window("weekly", "weekly", "Weekly", 20.0, 5 * 1440 + 30)))),
        ProviderInstance("cursor", "cursor", "Cursor", usageLimits = ProviderUsageLimits(at(-3), unavailableReason = "unsupported")),
    )

    private val reportNow = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
    fun usage() = UsageUiState(range = UsageRange.Week, reports = UsageRange.entries.associateWith { UsageWidgetSamples.report(it, reportNow) })

    /** Usage screens need a provider list and a connection that can read usage. */
    fun usageState(): ClientState = listState().copy(providers = usageProviders(), canReadUsage = true)

    /** Dev servers need a server that answers discovery, so the Dev servers screen is available. */
    fun portsState(): ClientState = listState().copy(
        selectedThreadId = "t-approval",
        localServers = LocalServerCatalog(servers = PortsFixtures.mixed.servers, live = true, supported = true),
        portForwards = PortsFixtures.mixed.forwards,
    )

    private val screenCatalog = ScreenCatalog(
        browsers = listOf(
            ScreenTarget.Browser("t-approval", "tab-1", "Docs preview", "http://localhost:5173/docs", interactive = true),
            ScreenTarget.Browser("t-approval", "tab-2", "Storybook", "http://localhost:6006/"),
        ),
        devices = listOf(
            ScreenTarget.Device("t-approval", "host-1", "emulator-5554", "android", "Pixel 9 emulator"),
            ScreenTarget.Device("t-approval", "host-1", "sim-1", "ios", "iPhone 17 simulator"),
        ),
        loaded = true,
    )

    fun screensState(): ClientState = listState().copy(selectedThreadId = "t-approval", screens = screenCatalog)
}

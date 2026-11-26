package com.tajchert.trefoil.screenshots

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.Project
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import com.tajchert.trefoil.core.model.RuntimeMode
import com.tajchert.trefoil.core.model.RuntimeRequest
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.model.SubagentRemoteChild
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.ToolActivity
import com.tajchert.trefoil.core.model.UsageLimitWindow
import java.time.Instant

/**
 * Fake data for the README screenshots (`docs/screenshots/`): one workstation running several providers at once.
 * Never put real machines, tokens, threads or conversation content here.
 */
object ReadmeFixtures {
    private val fx = ScreenshotFixtures

    // ---- Provider catalog ----
    val claude = ProviderInstance("claude", "claudeAgent", "Claude")
    val codex = ProviderInstance("codex", "codex", "Codex")
    val grok = ProviderInstance("grok", "grok", "Grok")
    val antigravity = ProviderInstance("antigravity", "antigravity", "Antigravity")
    val cursor = ProviderInstance("cursor", "cursor", "Cursor")
    val opencode = ProviderInstance("opencode", "opencode", "OpenCode")
    val providers = listOf(claude, codex, grok, antigravity, cursor, opencode)

    private val effort = ModelOptionDescriptor.Select(
        "effort", "Reasoning", choices = listOf(
            ModelOptionChoice("low", "Low", "Faster, lighter reasoning"),
            ModelOptionChoice("medium", "Medium"),
            ModelOptionChoice("high", "High", "Deeper reasoning for hard problems", isDefault = true),
            ModelOptionChoice("max", "Max"),
        ), isReasoning = true,
    )
    private val fast = ModelOptionDescriptor.Toggle("fastMode", "Fast mode", "Faster output at a higher usage rate")
    val fable = ModelOption("claude", "claude-fable-5-2", "Fable 5.2", descriptors = listOf(effort, fast))
    val opus = ModelOption("claude", "claude-opus-5-5", "Opus 5.5", descriptors = listOf(effort))
    val sonnet = ModelOption("claude", "claude-sonnet-5-5", "Sonnet 5.5", descriptors = listOf(effort))
    val gpt = ModelOption("codex", "gpt-5.5", "GPT-5.5", descriptors = listOf(effort))
    val grok4 = ModelOption("grok", "grok-4", "Grok 4")
    val gemini = ModelOption("antigravity", "gemini-3-pro", "Gemini 3 Pro")
    val composer = ModelOption("cursor", "composer-2", "Composer 2")
    val models = listOf(fable, opus, sonnet, gpt, grok4, gemini, composer)

    // ---- Thread list ----
    private val api = Project(id = "p-api", title = "api", path = "/home/dev/api")
    private val projects = fx.projects + api

    private fun row(
        id: String, title: String, provider: ProviderInstance, project: Project = fx.trefoil, minutesAgo: Long = 30,
        latest: String = "", branch: String? = null, request: String? = null, workingMinutes: Long? = null, history: List<String> = emptyList(),
    ): ThreadSummary {
        val base = fx.thread(id, title, project = project, updatedMinutesAgo = minutesAgo, latestMessage = latest, branch = branch, pendingRequestKind = request)
            .copy(providerInstanceId = provider.instanceId, providerInstanceHistory = history)
        if (workingMinutes == null) return base
        return base.copy(status = "running", activeRunId = "run-$id", latestRunId = "run-$id",
            activityRunStartedAt = fx.ago(workingMinutes), activityRunStartedAtPresent = true)
    }

    val threads = listOf(
        row("t-tokens", "Rotate pairing tokens", grok, minutesAgo = 2, request = "approval",
            latest = "Run ./gradlew :core:ssh:test?", branch = "feat/token-rotation"),
        row("t-codename", "Pick the 0.23 codename", antigravity, project = fx.website, minutesAgo = 9, request = "user_input",
            latest = "Which codename should the release use?"),
        row("t-widget", "Multi-provider usage widget", claude, minutesAgo = 1, workingMinutes = 4, branch = "feat/usage-widget",
            latest = "Fanning out to Codex, Grok and Antigravity.", history = listOf("codex", "grok")),
        row("t-flaky", "Fix flaky SSH reconnect test", codex, minutesAgo = 3, workingMinutes = 12, branch = "fix/ssh-reconnect",
            latest = "Reproduced it 3 times in 50 runs."),
        row("t-landing", "Landing page dark mode", antigravity, project = fx.website, minutesAgo = 1, workingMinutes = 2,
            latest = "Checking contrast on every section."),
        row("t-startup", "Profile cold start", cursor, minutesAgo = 70, latest = "Cold start is 18% faster now."),
        row("t-changelog", "Draft API changelog", opencode, project = api, minutesAgo = 60 * 5, latest = "Changelog is ready for review."),
    )

    fun threadListState() = ClientState(
        environment = fx.workstation, connection = ConnectionStatus.Connected, projects = projects, threads = threads,
        providers = providers, models = models, canRead = true, canWrite = true, canWriteThreads = true, shellLoaded = true, shellLive = true,
    )

    // ---- Orchestration conversation: Fable fans one task out to four providers ----
    /** Frozen now for live timers: 4m 12s into the run. Derived from the shared fixture base so one frozen clock serves
     * both the conversation and the thread list, and every run of the suite renders the same string. */
    val now: Long get() = ScreenshotFixtures.listClock()
    private const val RUN = "run-w"
    private fun at(seconds: Int): String {
        val started = ScreenshotFixtures.listClock() - 4 * 60_000 - 12_000
        return java.time.Instant.ofEpochMilli(started + seconds * 1000L).toString()
    }
    private val RUN_STARTED: String get() = at(0)

    private val widgetThread = fx.thread("t-widget", "Multi-provider usage widget", updatedMinutesAgo = 1, branch = "feat/usage-widget")
        .copy(status = "running", activeRunId = RUN, latestRunId = RUN, activityRunStartedAt = RUN_STARTED, activityRunStartedAtPresent = true,
            providerInstanceId = "claude")

    private fun msg(id: String, role: String, text: String, ordinal: Int, runId: String? = null) =
        ConversationMessage(id = id, role = role, text = text, ordinal = ordinal, runId = runId)

    private fun agent(
        id: String, provider: ProviderInstance, model: ModelOption, title: String, status: String, started: Int,
        completed: Int? = null, progress: String? = null, result: String? = null, effort: String? = null, remote: String? = null,
    ) = Subagent(
        id = id, threadId = widgetThread.id, runId = RUN, origin = if (provider == claude) "provider_native" else "app_owned",
        driver = provider.driver, providerInstanceId = provider.instanceId,
        childThreadId = if (remote == null) "child-$id" else null,
        remoteChild = remote?.let { SubagentRemoteChild("env-laptop", "remote-$id", it) },
        prompt = title, title = title, model = model.model, selectionInstanceId = provider.instanceId, selectionModel = model.model,
        selectionOptions = effort?.let { mapOf("effort" to it) }.orEmpty(),
        status = status, progress = progress, result = result, startedAt = at(started), completedAt = completed?.let(::at),
    )

    val subagents = listOf(
        agent("s-layout", codex, gpt, "Widget layout", "running", started = 31, effort = "high",
            progress = "Editing widget/UsageWidget.kt"),
        agent("s-review", grok, grok4, "Review data layer", "completed", started = 33, completed = 91,
            result = "No blockers. Left two naming nits as comments."),
        agent("s-shots", antigravity, gemini, "Screenshots", "running", started = 36, remote = "Laptop",
            progress = "Rendering 6 widget sizes"),
        agent("s-tests", claude, sonnet, "Write repo tests", "completed", started = 38, completed = 164, effort = "medium",
            result = "Added 14 tests. All pass."),
    )

    private fun agentItem(id: String, agentId: String, status: String, ordinal: Int) =
        ToolActivity(id = id, title = "Subagent", kind = "subagent", status = status, ordinal = ordinal, runId = RUN, subagentId = agentId)

    fun orchestrationState(): ClientState = ClientState(
        environment = fx.workstation, connection = ConnectionStatus.Connected, projects = projects,
        threads = listOf(widgetThread), selectedThreadId = widgetThread.id, providers = providers, models = models,
        selectedModel = fable, selectedModelOptions = mapOf("effort" to "high"), selectedRuntimeMode = RuntimeMode.AutoAcceptEdits,
        canRead = true, canWrite = true, canWriteThreads = true, shellLoaded = true, shellLive = true,
        activeRunId = RUN, canStop = true, canSteer = true,
        runs = listOf(ConversationRun(RUN, "running", startedAt = RUN_STARTED)),
        messages = listOf(
            msg("m1", "user", "Ship the usage widget. Split the work across providers and review it before merging.", 1),
            msg("m2", "assistant", "I'll fan this out: Codex builds the layout, Grok reviews the data layer, " +
                "Antigravity records screenshots on your laptop, and a Sonnet subagent writes the tests.", 3, RUN),
        ),
        activities = listOf(
            ToolActivity(id = "a-plan", title = "Reasoning", kind = "reasoning", status = "completed", ordinal = 2, runId = RUN,
                output = "**Planning the split** across four agents."),
            agentItem("a-layout", "s-layout", "running", 4),
            agentItem("a-review", "s-review", "completed", 5),
            agentItem("a-shots", "s-shots", "running", 6),
            agentItem("a-tests", "s-tests", "completed", 7),
        ),
        subagents = subagents,
    )

    // ---- Approval: Grok asks before running a command ----
    private val tokensThread = fx.thread("t-tokens", "Rotate pairing tokens", updatedMinutesAgo = 2, branch = "feat/token-rotation")
        .copy(providerInstanceId = "grok")

    private val tokensReply = """
        I rotated the tokens and revoked the old ones:

        - **New tokens** are encrypted at rest
        - **Old tokens** fail with `401` right away
        - Pairing codes expire after 10 minutes

        ```kotlin
        fun rotate(env: Environment): Token {
            val next = Token.generate()
            store.saveEncrypted(env.id, next)
            revoker.revokeAllExcept(env.id, next)
            return next
        }
        ```

        Next I'll run the SSH tests.
    """.trimIndent()

    fun approvalState(): ClientState = ClientState(
        environment = fx.workstation, connection = ConnectionStatus.Connected, projects = projects,
        threads = listOf(tokensThread), selectedThreadId = tokensThread.id, providers = providers, models = models,
        selectedModel = grok4, selectedRuntimeMode = RuntimeMode.ApprovalRequired,
        canRead = true, canWrite = true, canWriteThreads = true, shellLoaded = true, shellLive = true,
        messages = listOf(
            msg("m1", "user", "Rotate the pairing tokens and make sure the old ones stop working.", 1),
            msg("m2", "assistant", tokensReply, 4),
        ),
        activities = listOf(
            ToolActivity(id = "a-edit", title = "Edited file", kind = "file_change", status = "completed", ordinal = 2,
                fileName = "core/session/TokenStore.kt", diffStr = "--- a/TokenStore.kt\n+++ b/TokenStore.kt\n@@ -1 +1,2 @@\n-old\n+new\n+revoke"),
        ),
        requests = listOf(RuntimeRequest(id = "req-1", title = "Run command", detail = "./gradlew :core:ssh:test")),
    )

    // ---- Limits: four providers, Antigravity last ----
    private val usageNow = ScreenshotFixtures.listClock()
    // +30s keeps "in 2h 5m" labels stable while rendering.
    private fun resetIn(minutes: Long) = Instant.ofEpochMilli(usageNow + minutes * 60_000 + 30_000).toString()
    private val checked = Instant.ofEpochMilli(usageNow - 2 * 60_000).toString()
    // No window duration: the pace tick and "ahead of pace" label would drift with render time.
    private fun window(id: String, label: String, used: Double, resetMin: Long) = UsageLimitWindow(id, "session", label, used, resetIn(resetMin), null)

    val usageProviders = listOf(
        claude.copy(usageLimits = ProviderUsageLimits(checked, listOf(
            window("session", "5h", 62.0, 125), window("weekly", "Weekly", 41.0, 3 * 1440 + 200)))),
        codex.copy(usageLimits = ProviderUsageLimits(checked, listOf(window("weekly", "Weekly", 20.0, 5 * 1440 + 30)))),
        cursor.copy(usageLimits = ProviderUsageLimits(checked, unavailableReason = "unsupported")),
        antigravity.copy(usageLimits = ProviderUsageLimits(checked, listOf(
            window("gemini-pro", "Gemini 3 Pro", 28.0, 190),
            window("gemini-flash", "Gemini 3 Flash", 9.0, 190),
            window("claude", "Claude models", 76.0, 190),
        ))),
    )

    fun usageState() = ClientState(
        environment = fx.workstation, connection = ConnectionStatus.Connected,
        providers = usageProviders, canRead = true, canWrite = true, canReadUsage = true,
    )
}

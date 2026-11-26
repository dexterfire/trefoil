package com.tajchert.trefoil.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ConversationMessage
import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.PendingBackgroundTask
import com.tajchert.trefoil.core.model.Question
import com.tajchert.trefoil.core.model.QueuedMessage
import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.RuntimeMode
import com.tajchert.trefoil.core.model.RuntimeRequest
import com.tajchert.trefoil.core.model.Subagent
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.core.model.ToolActivity
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.downloads.CompletedDownload
import com.tajchert.trefoil.downloads.DownloadKey
import com.tajchert.trefoil.downloads.DownloadStatus
import com.tajchert.trefoil.downloads.FileDownloadState
import com.tajchert.trefoil.ui.ComposerDraftUi
import com.tajchert.trefoil.ui.FileDownloadUi
import com.tajchert.trefoil.ui.SubagentRoster

/** Fake conversation data for screenshot previews. Message times are omitted so clock labels never vary. */
object ConversationFixtures {
    private val fx = ScreenshotFixtures
    val model = ModelOption(instanceId = "codex", model = "gpt-5", label = "GPT-5")

    val markdownReply = """
        I added the preview and wired it into the theme:

        - **Light** and **dark** variants
        - Fixtures use fake data only
        - Reference images are committed

        ```kotlin
        @PreviewTest
        @Preview(showBackground = true)
        @Composable
        fun ThreadList_Populated_Light() {
            TrefoilTheme(darkTheme = false) { ThreadList(state) }
        }
        ```

        Run `./gradlew :app:testDebugScreenshotTestDefaultTestSuite` to verify.
    """.trimIndent()

    val thread = fx.thread("t-chat", "Add screenshot previews", updatedMinutesAgo = 5, branch = "feat/previews")
    /** Frozen "now" for live timers: 1m 11s after [RUN_STARTED]. */
    val now: Long = java.time.Instant.parse("2026-01-01T10:01:11Z").toEpochMilli()
    private const val RUN_STARTED = "2026-01-01T10:00:00Z"
    fun workingThread() = thread.copy(status = "running", activeRunId = "run-2", latestRunId = "run-2",
        activityRunStartedAt = RUN_STARTED, activityRunStartedAtPresent = true)

    private fun msg(id: String, role: String, text: String, ordinal: Int, streaming: Boolean = false, attachments: List<RemoteFileReference> = emptyList(), runId: String? = null) =
        ConversationMessage(id = id, role = role, text = text, streaming = streaming, ordinal = ordinal, attachments = attachments, runId = runId)

    private fun act(id: String, title: String, kind: String, status: String, ordinal: Int, input: String? = null, output: String? = null,
                    fileName: String? = null, diff: String? = null, exit: Int? = null) =
        ToolActivity(id = id, title = title, kind = kind, status = status, ordinal = ordinal, input = input, output = output,
            fileName = fileName, diffStr = diff, exitCode = exit)

    val commandActivity = act("a-cmd", "Ran command", "command_execution", "completed", 2, input = "./gradlew :app:lintDebug", output = "BUILD SUCCESSFUL in 12s", exit = 0)
    val editActivity = act("a-edit", "Edited file", "file_change", "completed", 3, fileName = "ui/ThreadList.kt",
        diff = "--- a/ui/ThreadList.kt\n+++ b/ui/ThreadList.kt\n@@ -1,2 +1,3 @@\n-old line\n+new line\n+another line\n context")
    val searchActivity = act("a-search", "Searched files", "web_search", "completed", 4, input = "PreviewTest annotation")
    val runningActivity = act("a-run", "Running tests", "command_execution", "running", 5, input = "./gradlew :app:testDebugUnitTest")
    val mixedActivities = listOf(commandActivity, editActivity, searchActivity)
    val readActivity = act("a-read-1", "Read file", "file_read", "completed", 6, input = "ui/ThreadList.kt")
    val readActivity2 = act("a-read-2", "Read file", "file_read", "completed", 7, input = "ui/DesignSystem.kt")
    /** One command plus two file reads: the header reads "Ran 1 command and read 2 files" once the labels land. */
    val commandReadActivities = listOf(commandActivity, readActivity, readActivity2)
    val failedCommand = act("a-fail", "Ran command", "command_execution", "completed", 8, input = "./gradlew :app:testDebugUnitTest", output = "3 tests completed, 1 failed", exit = 1)
    val reasoningActivity = act("a-think", "Reasoning", "reasoning", "completed", 9,
        output = "**Checking the preview setup**\n\nThe screenshot tests need one `@PreviewTest` per composable, so I will add a fixture for each state.")

    fun chatState(): ClientState = fx.connectedState(listOf(thread)).copy(
        selectedThreadId = thread.id, selectedModel = model, selectedRuntimeMode = RuntimeMode.ApprovalRequired, models = listOf(model),
        messages = listOf(
            msg("m1", "user", "Can you add a dark theme preview for the thread list?", 1),
            msg("m2", "assistant", markdownReply, 6),
        ),
        activities = listOf(commandActivity, editActivity),
    )

    /** Long enough to wrap at prose width, with a heading, list, wide code line, table and quote: what the reading column has to hold. */
    val mixedReply = """
        ## What changed

        The screenshot previews now cover every thread list state in light and dark, and each preview renders from fake data only, so the reference images never depend on a real server, a real project or the time of day the tests run.

        - **Fixtures** live next to the previews and never touch a network
        - Each state has one light and one dark reference image
        - The reference images are committed with the change

        ```kotlin
        @PreviewTest
        @Preview(showBackground = true, widthDp = 400, heightDp = 800)
        @Composable
        fun ThreadList_Populated_Light() = ThreadListFrame(dark = false) { ThreadList(populatedState(), select = {}, create = {}) }
        ```

        | Suite | Command | Result |
        | --- | --- | --- |
        | Unit tests | `:app:testDebugUnitTest` | passing |
        | Screenshots | `:app:testDebugScreenshotTestDefaultTestSuite` | passing |
        | Lint | `:app:lintDebug` | 0 errors |

        > Re-record only the previews you changed, then check that git shows new files and nothing else.

        Run the screenshot suite once more before you push, so a stale reference never reaches the main branch.
    """.trimIndent()

    /** A conversation that shows the whole reading column: short and long user bubbles, a work log, and a long reply with code and a table. */
    fun mixedState(): ClientState = chatState().copy(
        messages = listOf(
            msg("m1", "user", "Can you add dark theme previews for the thread list and then tell me what you changed?", 1),
            msg("m2", "assistant", mixedReply, 6),
            msg("m3", "user", "Looks good. Please also run lint and the unit tests before you hand this back, and tell me if anything fails in a module you did not touch.", 7),
            msg("m4", "assistant", "Both pass: lint reports 0 errors and the unit tests finish in 41 seconds.", 8),
        ),
    )

    /** A saved reply in the composer: the conversation starts with [text] already typed. */
    fun withDraft(text: String) = ComposerDraftUi(listOf(ComposerDraft(ComposerDraftKey.Reply(fx.workstation.id, thread.id), text, updatedAtMillis = 0L)).associateBy { it.key })

    private val liveThought = act("a-live-think", "Reasoning", "reasoning", "completed", 2,
        output = "**Checking the theme tokens** before running the unit tests.").copy(runId = "run-2")
    private val livePlan = msg("m2", "assistant", "I'll have a subagent review the dark theme tokens while I run the unit tests.", 3, runId = "run-2")
    private fun agentItem(id: String, agentId: String, status: String, ordinal: Int) =
        ToolActivity(id = id, title = "Subagent", kind = "subagent", status = status, ordinal = ordinal, runId = "run-2", subagentId = agentId)
    private val reviewAgent = Subagent(id = "s-review", threadId = "t-chat", runId = "run-2", origin = "provider_native", driver = "claudeAgent",
        childThreadId = "child-review", title = "Review dark theme tokens", prompt = "Review dark theme tokens", status = "completed",
        result = "Dark theme tokens match the spec; no changes needed.", startedAt = "2026-01-01T10:00:05Z", completedAt = "2026-01-01T10:00:52Z")
    private val lintAgent = Subagent(id = "s-lint", threadId = "t-chat", runId = "run-2", origin = "provider_native", driver = "claudeAgent",
        childThreadId = "child-lint", title = "Investigate lint crash", prompt = "Investigate lint crash", status = "running",
        progress = "Running cd /home/dev/trefoil && ./gradlew :app:lintDebug --stacktrace", startedAt = "2026-01-01T10:00:28Z")
    private val docsAgent = Subagent(id = "s-docs", threadId = "t-chat", runId = "run-2", origin = "app_owned", driver = "codex",
        childThreadId = "child-docs", title = "Update screenshot README", prompt = "Update screenshot README", status = "running", startedAt = "2026-01-01T10:00:50Z")

    /** Active turn: "Working for" header, a settled subagent and a finished command, then the trailing Thinking row. */
    fun workingState(): ClientState = chatState().copy(
        threads = listOf(workingThread()), activeRunId = "run-2", canStop = true,
        runs = listOf(ConversationRun("run-2", "running", startedAt = RUN_STARTED)),
        messages = listOf(msg("m1", "user", "Now run the unit tests too.", 1), livePlan),
        activities = listOf(liveThought, agentItem("a-agent", "s-review", "completed", 4),
            commandActivity.copy(id = "a-unit", ordinal = 5, runId = "run-2", input = "./gradlew :app:testDebugUnitTest", output = "BUILD SUCCESSFUL in 41s")),
        subagents = listOf(reviewAgent),
    )

    /** Active turn waiting on a running subagent, plus a roster-only agent rendered at the end of the turn. */
    fun liveSubagentState(): ClientState = chatState().copy(
        threads = listOf(workingThread()), activeRunId = "run-2", canStop = true,
        runs = listOf(ConversationRun("run-2", "running", startedAt = RUN_STARTED)),
        messages = listOf(msg("m1", "user", "Lint crashes with a FIR lazy resolve error. Can you find out why?", 1),
            livePlan.copy(text = "I'll have a subagent reproduce the crash and find the cause. It will fix it only if the fix is clear and low-risk.")),
        activities = listOf(liveThought.copy(output = "**Looking into the lint crash** from the FIR lazy resolve error."), agentItem("a-agent", "s-lint", "running", 4)),
        subagents = listOf(lintAgent, docsAgent),
    )

    /** The parent's turn ended while two delegated subagents keep running. */
    fun backgroundWorkState(): ClientState = chatState().copy(
        canStop = true,
        backgroundWork = listOf(
            PendingBackgroundTask("turn-item:delegated-task:a", "subagent", "Stream A: boundary ingestion (OSM)", childThreadId = "thread:delegated-task:a"),
            PendingBackgroundTask("turn-item:delegated-task:b", "subagent", "Stream B: tile cache warmup", childThreadId = "thread:delegated-task:b"),
        ),
    )

    /** Working thread whose turn accepts steering: the composer sends a steer by default. */
    fun steerableState(): ClientState = workingState().copy(canSteer = true)

    /** Two follow-ups waiting behind the running turn. */
    fun queuedState(): ClientState = steerableState().copy(queuedMessages = listOf(
        QueuedMessage("queued-1", "qm-1", "Also bump the version and update the changelog before you finish.", canSendNow = true),
        QueuedMessage("queued-2", "qm-2", "Then open the PR.", canSendNow = true)))

    /** A Stop held the queue: it waits for Resume. */
    fun heldQueueState(): ClientState = chatState().copy(queueHeld = true, queuedMessages = listOf(
        QueuedMessage("queued-1", "qm-1", "Also bump the version and update the changelog before you finish.", canSendNow = true)))

    /** Six follow-ups: the strip caps at about three rows and scrolls, so the editor and Stop stay visible. */
    fun longQueueState(): ClientState = steerableState().copy(queuedMessages = listOf(
        "Also bump the version and update the changelog before you finish.", "Then open the PR.", "Run the screenshot tests on the dark theme too.",
        "Tidy the imports in the files you touched.", "Squash the fixup commits.", "Post the summary in the thread.").mapIndexed { i, text ->
        QueuedMessage("queued-${i + 1}", "qm-${i + 1}", text, canSendNow = true) })

    /** Held automatic wakes count as held but are not rows: the paused header with Resume shows alone. */
    fun heldNoRowsState(): ClientState = chatState().copy(queueHeld = true, queuedMessages = emptyList())

    private val longThreadPrompts = listOf("Add a dark theme preview.", "Cover the thread list too.", "Now the settings screen.",
        "Fix the clipped title.", "Run lint.", "Update the README.", "Bump the version.", "Check the widget colors.",
        "Record the screenshots.", "Run the unit tests.", "Tidy the imports.", "Rename the fixture.", "Squash the commits.", "Open the PR.")

    /** Fourteen settled turns: the latest ten show, with "Load earlier turns" at the top. */
    fun longThreadState(): ClientState = chatState().copy(
        messages = longThreadPrompts.flatMapIndexed { i, prompt ->
            listOf(msg("lu$i", "user", prompt, i * 2), msg("la$i", "assistant", "Done: ${prompt.lowercase().removeSuffix(".")}.", i * 2 + 1))
        },
        activities = emptyList(),
    )

    fun disconnectedState(): ClientState = chatState().copy(connection = ConnectionStatus.Disconnected, canWrite = false, canWriteThreads = false)

    val approval = RuntimeRequest(id = "req-1", title = "Run command", detail = "./gradlew :core:ssh:test")
    val choiceRequest = RuntimeRequest(id = "req-2", title = "Which test suite should I run?",
        questions = listOf(Question(id = "q1", title = "Pick one", options = listOf("Unit tests", "Screenshot tests", "Everything"))))

    fun approvalState(): ClientState = chatState().copy(requests = listOf(approval))
    fun choiceState(): ClientState = chatState().copy(requests = listOf(choiceRequest))

    // ---- Subagents ----
    private fun agent(id: String, title: String, status: String, driver: String = "codex", result: String? = null, started: String? = null, completed: String? = null) =
        Subagent(id = id, threadId = "t-chat", runId = "run-1", origin = "app_owned", driver = driver, childThreadId = "child-$id", title = title,
            model = "gpt-5", status = status, result = result, startedAt = started, completedAt = completed, prompt = title)

    val subagents = listOf(
        agent("s1", "Audit SSH host key handling", "running"),
        agent("s2", "Update screenshot README", "completed", result = "README updated with the new commands.",
            started = "2026-01-01T10:00:00Z", completed = "2026-01-01T10:02:05Z"),
        agent("s3", "Migrate usage widget tests", "failed", driver = "claudeAgent", result = "Gradle build failed: unresolved reference UsageRow.",
            started = "2026-01-01T10:00:00Z", completed = "2026-01-01T10:00:42Z"),
        agent("s4", "Review dark theme tokens", "running", driver = "claudeAgent"),
    )

    internal fun roster(list: List<Subagent> = subagents) = SubagentRoster(
        runId = "run-1", turnActive = true, latestTurn = true, subagents = list,
        liveCount = list.count { it.isActive }, settledCount = list.count { it.isTerminal },
    )

    val subagentThread: ThreadSummary = fx.thread("t-child", "Audit SSH host key handling", status = "idle", updatedMinutesAgo = 2)
        .copy(model = "gpt-5", parentThreadId = "t-chat", relationshipToParent = "subagent")

    // ---- Files ----
    val apk = RemoteFileReference(path = "/home/dev/trefoil/app-debug.apk", fileName = "app-debug.apk", mimeType = "application/vnd.android.package-archive", sizeBytes = 18_400_000)
    val report = RemoteFileReference(path = "/home/dev/trefoil/report.pdf", fileName = "report.pdf", mimeType = "application/pdf", sizeBytes = 482_000)
    val screenshot = RemoteFileReference(path = "/home/dev/trefoil/screen.png", fileName = "screen.png", mimeType = "image/png", sizeBytes = 120_000)
    val fileMessage = msg("mf", "user", "Attached build output.", 1, attachments = listOf(apk, report))

    private const val ENV = "env-workstation"
    private const val THREAD = "t-chat"
    private fun key(r: RemoteFileReference) = DownloadKey(ENV, THREAD, r.identity)

    fun downloadUi(vararg states: Pair<RemoteFileReference, FileDownloadState>) = FileDownloadUi(
        environmentId = ENV, threadId = THREAD, canDownload = true, downloads = states.associate { key(it.first) to it.second },
    )
    fun downloading(r: RemoteFileReference) = r to FileDownloadState(r, DownloadStatus.Downloading, bytesDownloaded = 7_400_000, totalBytes = r.sizeBytes)
    fun failed(r: RemoteFileReference) = r to FileDownloadState(r, DownloadStatus.Failed, error = "Connection lost. Tap Retry to try again.")
    fun done(r: RemoteFileReference) = r to FileDownloadState(r, DownloadStatus.Complete,
        completed = CompletedDownload(Uri.parse("content://example.test/${r.fileName}"), r.fileName, r.mimeType ?: "application/octet-stream", r.sizeBytes ?: 0))

    /** A synthetic gradient "photo" so the loaded inline image is visibly non-blank. */
    fun sampleImage(): ImageBitmap {
        val bitmap = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 640f, 400f, Color.rgb(46, 125, 226), Color.rgb(236, 120, 80), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 640f, 400f, paint)
        paint.shader = null
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawCircle(200f, 160f, 70f, paint)
        canvas.drawRect(300f, 220f, 560f, 340f, paint)
        return bitmap.asImageBitmap()
    }
}

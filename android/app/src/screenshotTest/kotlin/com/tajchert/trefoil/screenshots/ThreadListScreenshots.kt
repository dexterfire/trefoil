package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.ui.ComposerDraftUi
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.TrefoilTheme

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_Populated_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_Populated_Dark() {
    TrefoilTheme(darkTheme = true) { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_Refreshing_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(ScreenshotFixtures.connectedState(), select = {}, create = {}, isRefreshing = true) }
}

/** Waiting threads name what they want: amber kind label, then the preview; a long preview gives way, the label never does. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 420)
@Composable
fun ThreadList_NeedsYouPreview_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(ScreenshotFixtures.connectedState(needsYouThreads()), select = {}, create = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 420, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_NeedsYouPreview_Dark() {
    TrefoilTheme(darkTheme = true) { ThreadList(ScreenshotFixtures.connectedState(needsYouThreads()), select = {}, create = {}) }
}

private fun needsYouThreads() = listOf(
    ScreenshotFixtures.thread("t-ask", "Add SSH host key check", pendingRequestKind = "approval", updatedMinutesAgo = 3,
        latestMessage = "Run ./gradlew :core:ssh:test :core:session:test --tests '*HostKeyTest' to verify the pinned fingerprint?", branch = "feat/host-keys"),
    ScreenshotFixtures.thread("t-input", "Pick a release name", pendingRequestKind = "user_input", updatedMinutesAgo = 12,
        latestMessage = "Which codename should 0.15 use?"),
    ScreenshotFixtures.thread("t-bare", "Rotate pairing tokens", pendingRequestKind = "approval", updatedMinutesAgo = 20),
)

/** Unsent prompts: a "Drafts" block above the threads, and a thread with an unsent reply shows a pencil "Draft:" preview. */
private fun draftsUi(): ComposerDraftUi {
    val env = ScreenshotFixtures.workstation.id
    val now = System.currentTimeMillis()
    val drafts = listOf(
        ComposerDraft(ComposerDraftKey.NewThread(env, ScreenshotFixtures.website.id), "Add a dark mode toggle to the settings page\nand remember the choice", updatedAtMillis = now - 12 * 60_000),
        ComposerDraft(ComposerDraftKey.Reply(env, "t-reply"), "Sounds good, but keep the old endpoint working", updatedAtMillis = now - 5 * 60_000),
    )
    return ComposerDraftUi(drafts.associateBy { it.key })
}

private fun draftThreads() = listOf(
    ScreenshotFixtures.thread("t-reply", "Migrate the sync endpoint", updatedMinutesAgo = 25, latestMessage = "Done: the new endpoint is behind a flag.", branch = "feat/sync"),
    ScreenshotFixtures.thread("t-other", "Tidy the release notes", updatedMinutesAgo = 90, latestMessage = "Notes are updated."),
)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 520)
@Composable
fun ThreadList_Drafts_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(ScreenshotFixtures.connectedState(draftThreads()), select = {}, create = {}, composerDrafts = draftsUi()) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 520, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_Drafts_Dark() {
    TrefoilTheme(darkTheme = true) { ThreadList(ScreenshotFixtures.connectedState(draftThreads()), select = {}, create = {}, composerDrafts = draftsUi()) }
}

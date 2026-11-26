package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.core.session.ComposerDraft
import com.tajchert.trefoil.core.session.ComposerDraftKey
import com.tajchert.trefoil.ui.ComposerDraftUi
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome

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

/** Rows restored from the on-device cache (or kept from the last connection) while connecting: shown, not live, under the progress bar. */
private fun cachedWhileConnectingState() = ScreenshotFixtures.connectedState().copy(connection = ConnectionStatus.Connecting, shellLive = false,
    canRead = false, canWrite = false, canWriteThreads = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_CachedWhileConnecting_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(cachedWhileConnectingState(), select = {}, create = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_CachedWhileConnecting_Dark() {
    TrefoilTheme(darkTheme = true) { ThreadList(cachedWhileConnectingState(), select = {}, create = {}) }
}

/** Connected (or connecting) before the first shell snapshot: placeholder rows and the progress bar, never "no threads". */
private fun loadingState() = ScreenshotFixtures.connectedState(emptyList()).copy(projects = emptyList(), shellLoaded = false, canWrite = false, canWriteThreads = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun ThreadList_Loading_Light() {
    TrefoilTheme(darkTheme = false) { ThreadList(loadingState(), select = {}, create = {}) }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_Loading_Dark() {
    TrefoilTheme(darkTheme = true) { ThreadList(loadingState(), select = {}, create = {}) }
}

/* ---------- Adaptive chrome (tablets and foldables) ---------- */

/** A busy list for wide panes: a waiting thread, a working one, a pinned one, recents and a folded Settled shelf, plus a usage limit for the header chip. */
private fun wideState() = ScreenshotFixtures.connectedState(
    ScreenshotFixtures.threads + listOf(
        ScreenshotFixtures.thread("t-working", "Wire the pane divider to the keyboard", status = "running", updatedMinutesAgo = 2, latestMessage = "Running the screenshot tests.", branch = "feat/adaptive-shell"),
        ScreenshotFixtures.thread("t-recent-2", "Check the fold posture on the Pixel Fold", project = ScreenshotFixtures.website, updatedMinutesAgo = 80, latestMessage = "Hinge band reported as separating."),
        ScreenshotFixtures.thread("t-settled", "Rename the machines screen", updatedMinutesAgo = 60 * 30, latestMessage = "Merged.").copy(settledOverride = "settled", settledAt = "2026-11-06T08:00:00Z"),
    ),
).copy(providers = listOf(ProviderInstance("claude", "claudeAgent", "Claude", usageLimits = ProviderUsageLimits("", listOf(UsageLimitWindow("weekly", "weekly", "Weekly", 62.0))))))

private val railChrome = ThreadListChrome(onCollapse = {}, railPresent = true)
private val navChrome = ThreadListChrome(onCollapse = {}, onOpenNavigation = {})

@Composable
private fun WideList(dark: Boolean = false, chrome: ThreadListChrome = railChrome, selectedId: String? = null, compact: Boolean = false, fontScale: Float = 1f) = TrefoilTheme(darkTheme = dark) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalCompactHeight provides compact, LocalDensity provides Density(density.density, fontScale)) {
        Box(Modifier.fillMaxSize()) { ThreadList(wideState(), select = {}, create = {}, shell = ShellFixtures.noActions, selectedId = selectedId, chrome = chrome) }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 300, heightDp = 1280)
@Composable
fun ThreadList_RailChrome_300x1280() = WideList()

@PreviewTest
@Preview(showBackground = true, widthDp = 340, heightDp = 800)
@Composable
fun ThreadList_RailChrome_340x800_Light() = WideList()

@PreviewTest
@Preview(showBackground = true, widthDp = 340, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_RailChrome_340x800_Dark() = WideList(dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 380, heightDp = 1080)
@Composable
fun ThreadList_RailChrome_380x1080() = WideList()

/** No rail: Menu, machine pill, limit chip and search; the list is too narrow (336dp) for the collapse button. */
@PreviewTest
@Preview(showBackground = true, widthDp = 336, heightDp = 841)
@Composable
fun ThreadList_NavChrome_336x841() = WideList(chrome = navChrome)

/** The widest list (480dp, the drag maximum) with every header control. */
@PreviewTest
@Preview(showBackground = true, widthDp = 480, heightDp = 800)
@Composable
fun ThreadList_NavChrome_480x800() = WideList(chrome = navChrome)

/** Window shorter than 480dp: 48dp header and two-line rows. */
@PreviewTest
@Preview(showBackground = true, widthDp = 300, heightDp = 400)
@Composable
fun ThreadList_CompactHeight_300x400() = WideList(compact = true)

/** The open thread: 12dp container, 8dp side margin and a 3x24dp accent bar at the start edge. */
@PreviewTest
@Preview(showBackground = true, widthDp = 340, heightDp = 800)
@Composable
fun ThreadList_SelectedRow_340x800() = WideList(selectedId = "t-recent")

@PreviewTest
@Preview(showBackground = true, widthDp = 340, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ThreadList_SelectedRow_340x800_Dark() = WideList(dark = true, selectedId = "t-recent")

@PreviewTest
@Preview(showBackground = true, widthDp = 340, heightDp = 800)
@Composable
fun ThreadList_FontScale13_340x800() = WideList(chrome = navChrome, selectedId = "t-recent", fontScale = 1.3f)

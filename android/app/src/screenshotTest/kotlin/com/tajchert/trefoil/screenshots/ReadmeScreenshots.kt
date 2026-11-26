package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.ui.Conversation
import com.tajchert.trefoil.ui.LocalFixedClock
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.ThreadAgentsSheetContent
import com.tajchert.trefoil.ui.ThreadList
import com.tajchert.trefoil.ui.TrefoilApp
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.UsageScreen
import com.tajchert.trefoil.ui.agentRows
import com.tajchert.trefoil.ui.subagentRoster

/*
 * The README screenshots. Each phone preview is a full 400x880 dp screen (20:9); docs/screenshots/ holds the
 * same renders scaled to 540 px wide. The two large-screen previews at the end are the whole app at a tablet's and an
 * unfolded foldable's size. See docs/SCREENSHOT_TESTING.md, "README screenshots".
 */

private val rx = ReadmeFixtures

@Composable
private fun ReadmeFrame(dark: Boolean, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) {
        CompositionLocalProvider(LocalFixedClock provides rx.now) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

@Composable
private fun ReadmeConversation(state: ClientState, showAgentsChip: Boolean = false) {
    Conversation(state, send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = if (showAgentsChip) ({}) else null, onOpenThread = {})
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Readme_Threads_Dark() {
    // ThreadList honors LocalFixedClock, so the frozen fixture clock keeps every relative label and the group order stable.
    TrefoilTheme(darkTheme = true) {
        CompositionLocalProvider(LocalFixedClock provides ScreenshotFixtures.listClock()) {
            ThreadList(rx.threadListState(), select = {}, create = {})
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Readme_Orchestration_Dark() = ReadmeFrame(dark = true) { ReadmeConversation(rx.orchestrationState(), showAgentsChip = true) }

/** A bottom sheet drawn over a dimmed screen, as the app's modal sheets look when open. */
@Composable
private fun SheetOver(background: @Composable () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        background()
        Box(Modifier.fillMaxSize().background(Tf.colors.scrim))
        Surface(Modifier.fillMaxWidth().align(Alignment.BottomCenter), color = Tf.colors.sheet,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(Tf.colors.s3, RoundedCornerShape(2.dp)))
                content()
            }
        }
    }
}

/** The Agents sheet over the same turn: provider, model and effort for each subagent. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Readme_Agents_Dark() = ReadmeFrame(dark = true) {
    val state = rx.orchestrationState()
    val roster = requireNotNull(subagentRoster(state.runs, state.subagents))
    val rows = agentRows(roster, state.providers, state.models, rx.now, canWrite = true)
    SheetOver({ ReadmeConversation(state, showAgentsChip = true) }) {
        ThreadAgentsSheetContent(rows, onOpen = {}, onStop = {}, Modifier.padding(bottom = 12.dp))
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880)
@Composable
fun Readme_Approval_Light() = ReadmeFrame(dark = false) { ReadmeConversation(rx.approvalState()) }

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 880, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Readme_Usage_Dark() {
    TrefoilTheme(darkTheme = true) {
        UsageScreen(rx.usageState(), UsageFixtures.usage(), onBack = {}, onRefresh = {}, onSelectRange = {}, onRetry = {}, initialTab = 0)
    }
}

/**
 * The whole app around the full thread list: the README's tablet and foldable images. The frozen fixture clock now
 * serves both surfaces: the thread list (via ThreadList honoring LocalFixedClock) and the conversation's live timers.
 */
@Composable
private fun ReadmeShell(state: ClientState) {
    CompositionLocalProvider(LocalFixedClock provides rx.now) {
        TrefoilApp(
            state.copy(threads = rx.threads), listOf(ScreenshotFixtures.workstation),
            { _, _, _ -> }, {}, {}, {}, {}, { _, _, _ -> }, null, {}, {}, {}, {}, {}, {}, {},
        )
    }
}

/** Tablet landscape, 1280x800 dp: rail, thread list and the approval conversation side by side. */
@PreviewTest
@Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Readme_Tablet_Dark() = ReadmeShell(rx.approvalState())

/** Unfolded foldable, 673x841 dp: the thread list beside the threads that need you. */
@PreviewTest
@Preview(showBackground = true, widthDp = 673, heightDp = 841, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Readme_Foldable_Dark() = ReadmeShell(rx.threadListState())

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
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.UsageScreen
import com.tajchert.trefoil.ui.agentRows
import com.tajchert.trefoil.ui.subagentRoster

/*
 * The README screenshots. Each preview is a full 400x880 dp phone screen (20:9); docs/screenshots/ holds the
 * same renders scaled to 540 px wide. See docs/SCREENSHOT_TESTING.md, "README screenshots".
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
    // Thread rows count time from the real clock, so they use relative fixture times instead of the frozen clock.
    TrefoilTheme(darkTheme = true) { ThreadList(rx.threadListState(), select = {}, create = {}) }
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

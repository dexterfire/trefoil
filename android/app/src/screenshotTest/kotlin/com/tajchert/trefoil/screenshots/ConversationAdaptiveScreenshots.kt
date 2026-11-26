package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.ui.ComposerDraftUi
import com.tajchert.trefoil.ui.Conversation
import com.tajchert.trefoil.ui.LocalFixedClock
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight

/**
 * The conversation alone, at the size of the pane it is given on tablets and foldables (the shell, list and rail are not drawn).
 * Pane widths: 336 (fold cover), 419 (tablet portrait with the list open), 859 and 1499 (single wide pane), 579x400 (compact height).
 */
private val fx = ConversationFixtures
private const val NIGHT = Configuration.UI_MODE_NIGHT_YES

@Composable
private fun Pane(state: ClientState, dark: Boolean = false, compactHeight: Boolean = false, drafts: ComposerDraftUi = ComposerDraftUi()) {
    TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
        CompositionLocalProvider(LocalFixedClock provides fx.now, LocalCompactHeight provides compactHeight) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Conversation(state, send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = null, onOpenThread = {}, composerDrafts = drafts)
            }
        }
            }
}

private const val MULTILINE = "Use the existing helper instead of adding a new one.\nKeep the public signature as it is.\nAnd add a test for the empty case."
private const val MULTILINE_LONG = "First line of the plan.\nSecond line with a bit more detail.\nThird line, still short.\nFourth line.\nFifth line."

// ---------- Mixed timeline: prose, heading, list, code, table, quote, user bubbles, work log ----------

@PreviewTest @Preview(showBackground = true, widthDp = 336, heightDp = 841)
@Composable fun Conversation_Mixed_Pane336() = Pane(fx.mixedState())

@PreviewTest @Preview(showBackground = true, widthDp = 419, heightDp = 1280)
@Composable fun Conversation_Mixed_Pane419() = Pane(fx.mixedState())

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800)
@Composable fun Conversation_Mixed_Pane859() = Pane(fx.mixedState())

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800, uiMode = NIGHT)
@Composable fun Conversation_Mixed_Pane859_Dark() = Pane(fx.mixedState(), dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 1499, heightDp = 1080)
@Composable fun Conversation_Mixed_Pane1499() = Pane(fx.mixedState())

/** Landscape phone-height pane: 48dp single-line header, composer capped at two lines. */
@PreviewTest @Preview(showBackground = true, widthDp = 579, heightDp = 400)
@Composable fun Conversation_Mixed_Compact579() = Pane(fx.mixedState(), compactHeight = true)

// ---------- A pending approval ----------

@PreviewTest @Preview(showBackground = true, widthDp = 419, heightDp = 1280)
@Composable fun Conversation_Approval_Pane419() = Pane(fx.approvalState())

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800)
@Composable fun Conversation_Approval_Pane859() = Pane(fx.approvalState())

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800, uiMode = NIGHT)
@Composable fun Conversation_Approval_Pane859_Dark() = Pane(fx.approvalState(), dark = true)

// ---------- The composer holding several lines ----------

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800)
@Composable fun Conversation_ComposerMultiline_Pane859() = Pane(fx.chatState(), drafts = fx.withDraft(MULTILINE))

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800, uiMode = NIGHT)
@Composable fun Conversation_ComposerMultiline_Pane859_Dark() = Pane(fx.chatState(), dark = true, drafts = fx.withDraft(MULTILINE))

/** Five lines typed into a compact-height pane: the field stops at two lines and scrolls. */
@PreviewTest @Preview(showBackground = true, widthDp = 579, heightDp = 400)
@Composable fun Conversation_ComposerMultiline_Compact579() = Pane(fx.chatState(), compactHeight = true, drafts = fx.withDraft(MULTILINE_LONG))

// ---------- Steer | Queue while a turn runs ----------

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800)
@Composable fun Conversation_FollowUpSteerQueue_Pane859() = Pane(fx.steerableState(), drafts = fx.withDraft("Use the existing helper instead."))

@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800, uiMode = NIGHT)
@Composable fun Conversation_FollowUpSteerQueue_Pane859_Dark() = Pane(fx.steerableState(), dark = true, drafts = fx.withDraft("Use the existing helper instead."))

/** The agent cannot be steered right now: Queue alone. */
@PreviewTest @Preview(showBackground = true, widthDp = 859, heightDp = 800)
@Composable fun Conversation_FollowUpQueueOnly_Pane859() = Pane(fx.workingState(), drafts = fx.withDraft("Then open the PR."))

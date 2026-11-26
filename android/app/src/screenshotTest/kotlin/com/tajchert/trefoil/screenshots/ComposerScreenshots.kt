package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import androidx.compose.runtime.CompositionLocalProvider
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ConversationRun
import com.tajchert.trefoil.core.model.PromptSuggestion
import com.tajchert.trefoil.ui.PromptSuggestionUi
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.ConversationComposer
import com.tajchert.trefoil.ui.TrefoilTheme

private val fx = ConversationFixtures

@Composable
private fun Composer(state: ClientState, text: String = "", dark: Boolean = false, stopPending: Boolean = false, compactHeight: Boolean = false, hint: PromptSuggestionUi = PromptSuggestionUi()) {
    TrefoilTheme(darkTheme = dark) {
        WithRuLocale {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalCompactHeight provides compactHeight) {
                ConversationComposer(state, null, text, {}, false, stopPending, {}, {}, { _, _ -> }, stop = {}, onSend = {},
                    onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {}, promptSuggestions = hint)
            }
        }
            }
}

/** The hint shows from the first frame here (no 350 ms wait), since a render has no time for the delay. */
private val shownNow = PromptSuggestionUi(appearDelayMs = 0)
/** An idle thread whose latest run completed and left [text] as the predicted next message (what `offeredPromptSuggestion` requires). */
private fun suggesting(text: String): ClientState {
    val chat = fx.chatState()
    return chat.copy(
        threads = chat.threads.map { if (it.id == chat.selectedThreadId) it.copy(latestRunId = "run-fixture") else it },
        runs = listOf(ConversationRun("run-fixture", "completed", startedAt = "2026-11-24T10:00:00Z", completedAt = "2026-11-24T10:01:00Z", ordinal = 1)),
        promptSuggestion = PromptSuggestion("run-fixture", text),
    )
}
private const val ShortSuggestion = "Now add tests for it."
private const val LongSuggestion = "Looks good. Can you also update the README to describe the new setting, add a short note to the changelog, and then open a pull request against main?"

/** Running, nothing typed: the primary button is Stop. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_RunningEmpty_Stop_Light() = Composer(fx.steerableState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_RunningEmpty_Stop_Dark() = Composer(fx.steerableState(), dark = true)

/** Running, text typed, the turn accepts steering: Steer is the default action. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_RunningText_Steer_Light() = Composer(fx.steerableState(), "Use the existing helper instead.")

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_RunningText_Steer_Dark() = Composer(fx.steerableState(), "Use the existing helper instead.", dark = true)

/** Running, text typed, steering unavailable: only Queue. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_RunningText_QueueOnly_Light() = Composer(fx.workingState(), "Then open the PR.")

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_RunningText_QueueOnly_Dark() = Composer(fx.workingState(), "Then open the PR.", dark = true)

/** Follow-ups waiting behind the running turn. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_QueuedStrip_Light() = Composer(fx.queuedState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_QueuedStrip_Dark() = Composer(fx.queuedState(), dark = true)

/** A Stop held the queue: "Queue paused" with Resume; Send now needs an active run that accepts steering, or none. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_QueuePaused_Light() = Composer(fx.heldQueueState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_QueuePaused_Dark() = Composer(fx.heldQueueState(), dark = true)

/** Six queued follow-ups: capped at about three rows, scrolling inside the strip; the editor and Stop stay on screen. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_LongQueue_Light() = Composer(fx.longQueueState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_LongQueue_Dark() = Composer(fx.longQueueState(), dark = true)

/** Queue held with no listed rows (held automatic wakes): "Queue paused" with Resume still shows. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_QueueHeldNoRows_Light() = Composer(fx.heldNoRowsState())

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_QueueHeldNoRows_Dark() = Composer(fx.heldNoRowsState(), dark = true)

/** Prompt suggestion in the empty composer: a muted sparkle, the predicted next message and an accept pill; one line keeps the field's height. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_Suggestion_OneLine_Light() = Composer(suggesting(ShortSuggestion), hint = shownNow)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_Suggestion_OneLine_Dark() = Composer(suggesting(ShortSuggestion), dark = true, hint = shownNow)

/** A long suggestion wraps to two lines and ends in an ellipsis; the text stays clear of the pill. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_Suggestion_TwoLines_Light() = Composer(suggesting(LongSuggestion), hint = shownNow)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Composer_Suggestion_TwoLines_Dark() = Composer(suggesting(LongSuggestion), dark = true, hint = shownNow)

/** Compact height (landscape phone): one line at most. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_Suggestion_Compact_Light() = Composer(suggesting(LongSuggestion), compactHeight = true, hint = shownNow)

/** Before the hint has been stable for 350 ms the field still shows its placeholder. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400)
@Composable
fun Composer_Suggestion_Pending_Light() = Composer(suggesting(ShortSuggestion))

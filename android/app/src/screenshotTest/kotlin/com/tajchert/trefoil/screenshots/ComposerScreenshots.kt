package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.ui.ConversationComposer
import com.tajchert.trefoil.ui.TrefoilTheme

private val fx = ConversationFixtures

@Composable
private fun Composer(state: ClientState, text: String = "", dark: Boolean = false, stopPending: Boolean = false) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            ConversationComposer(state, null, text, {}, false, stopPending, {}, {}, { _, _ -> }, stop = {}, onSend = {},
                onCancelQueued = {}, onSteerQueued = {}, onResumeQueue = {})
        }
    }
}

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

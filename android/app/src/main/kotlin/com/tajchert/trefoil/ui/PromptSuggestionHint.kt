package com.tajchert.trefoil.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.PromptSuggestion
import com.tajchert.trefoil.core.model.offeredPromptSuggestion
import com.tajchert.trefoil.ui.adaptive.focusRing
import com.tajchert.trefoil.ui.adaptive.handPointer
import kotlinx.coroutines.delay
import kotlin.math.abs

/** A suggestion has to stay offered this long before the hint shows, so a run that finishes and starts again never flashes one. */
internal const val PROMPT_SUGGESTION_APPEAR_MS = 350L

/** Horizontal drag, in dp, that accepts (right) or dismisses (left) the suggestion on release. */
internal const val PROMPT_SUGGESTION_SWIPE_DP = 56f

/**
 * What the composer needs from the view model: the suggestions dismissed this process (see [promptSuggestionKey]) and how to
 * dismiss one. [appearDelayMs] is how long a suggestion must be stable before it shows; 0 shows it from the first frame.
 */
data class PromptSuggestionUi(
    val dismissed: Set<String> = emptySet(),
    val onDismiss: (String) -> Unit = {},
    val appearDelayMs: Long = PROMPT_SUGGESTION_APPEAR_MS,
)

/** Dismissal identity: run ids derive from thread ids, which can repeat across environments, so the environment is part of the key. */
internal fun promptSuggestionKey(environmentId: String?, runId: String): String = "${environmentId.orEmpty()}\u0000$runId"

/**
 * The suggestion the empty composer may show, or null. Only for a thread that can be written (not a new-thread draft, not while the
 * send waits for the server, not while a run is active), with an empty draft, and not yet dismissed. The server-side rule that the
 * suggestion belongs to the latest completed run is [offeredPromptSuggestion]'s.
 */
internal fun promptSuggestionCandidate(
    state: ClientState, isDraft: Boolean, text: String, waiting: Boolean, canWriteNow: Boolean, dismissed: Set<String>,
): PromptSuggestion? {
    if (isDraft || waiting || !canWriteNow || text.isNotEmpty() || state.runningFollowUp(isDraft)) return null
    val offered = state.offeredPromptSuggestion ?: return null
    if (offered.text.isBlank()) return null
    return offered.takeUnless { promptSuggestionKey(state.environment?.id, it.runId) in dismissed }
}

/**
 * The text to put in the draft when the hint is accepted, or null. The hint unmounts a render after typing starts, so a late
 * accept must never prepend the suggestion to a draft that already has text.
 */
internal fun promptSuggestionToAccept(suggestion: String?, draft: String): String? = if (draft.isEmpty()) suggestion else null

/** The suggestion's lines: one in compact height, two otherwise; longer text ends in an ellipsis. */
internal fun promptSuggestionMaxLines(compactHeight: Boolean): Int = if (compactHeight) 1 else 2

internal enum class PromptSuggestionSwipe { Accept, Dismiss, None }

/** What a horizontal drag of [totalDx] pixels does on release: right accepts, left dismisses, less than [thresholdPx] either way springs back. */
internal fun promptSuggestionSwipe(totalDx: Float, thresholdPx: Float): PromptSuggestionSwipe = when {
    totalDx >= thresholdPx -> PromptSuggestionSwipe.Accept
    totalDx <= -thresholdPx -> PromptSuggestionSwipe.Dismiss
    else -> PromptSuggestionSwipe.None
}

/** The suggestion being shown ([shown] true once it has been stable for the delay) and the last one shown, kept so it can fade out. */
internal class SettledPromptSuggestion(val display: PromptSuggestion?, val shown: Boolean)

/**
 * Settles [candidate]: [SettledPromptSuggestion.shown] turns true after it has stayed the same for [delayMs], and false the
 * moment it is gone or changes (a newer run replaces the old one only after the delay). A non-positive delay shows it at once.
 */
@Composable
internal fun rememberSettledPromptSuggestion(candidate: PromptSuggestion?, delayMs: Long): SettledPromptSuggestion {
    var display by remember { mutableStateOf(candidate.takeIf { delayMs <= 0 }) }
    var settled by remember { mutableStateOf(candidate != null && delayMs <= 0) }
    LaunchedEffect(candidate) {
        if (candidate == null) { settled = false; return@LaunchedEffect }
        if (settled && display == candidate) return@LaunchedEffect
        settled = false
        delay(delayMs)
        display = candidate
        settled = true
    }
    return SettledPromptSuggestion(display, settled && candidate != null && display == candidate)
}

/** Space the sparkle takes in front of the suggestion text; the field's own text starts here while the hint shows, so accepting does not move it. */
internal val PromptSuggestionIndent = 20.dp

/**
 * The hint in the field's placeholder slot: a muted sparkle, the suggestion (muted, as the placeholder) and a small accept pill
 * at the end. The pill is 28dp with Compose's 48dp minimum touch area, and sits in a line-high slot so it never makes the field taller.
 * Merged semantics read "Suggested message: …" with Use and Dismiss as custom actions. [offset] is the swipe in pixels.
 */
@Composable
internal fun PromptSuggestionGhost(
    suggestion: PromptSuggestion, maxLines: Int, onAccept: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, offset: () -> Float = { 0f },
) {
    val c = Tf.colors
    val style = MaterialTheme.typography.bodyLarge
    val lineHeight = with(LocalDensity.current) { style.lineHeight.takeIf { it.isSpecified }?.toDp() } ?: 24.dp
    val travel = with(LocalDensity.current) { PROMPT_SUGGESTION_SWIPE_DP.dp.toPx() * 2 }
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer { val dx = offset(); translationX = dx; alpha = 1f - (abs(dx) / travel).coerceIn(0f, .6f) }
            .semantics(mergeDescendants = true) {
                contentDescription = "Suggested message: ${suggestion.text}"
                customActions = listOf(
                    CustomAccessibilityAction("Use suggestion") { onAccept(); true },
                    CustomAccessibilityAction("Dismiss suggestion") { onDismiss(); true },
                )
            }.testTag("prompt-suggestion"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(lineHeight), contentAlignment = Alignment.Center) { Icon(TfIcons.Sparkle, null, Modifier.size(14.dp), tint = c.muted) }
        Text(suggestion.text, Modifier.weight(1f).clearAndSetSemantics {}, style = style, color = c.muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(width = 28.dp, height = lineHeight), contentAlignment = Alignment.Center) {
            Box(
                Modifier.requiredSize(28.dp).clip(CircleShape).background(c.s2).focusRing(CircleShape).handPointer()
                    .semantics { contentDescription = "Use suggestion" }
                    .clickable(role = Role.Button, onClickLabel = "Use suggestion", onClick = onAccept),
                contentAlignment = Alignment.Center,
            ) { Icon(TfIcons.Insert, null, Modifier.size(14.dp), tint = c.muted) }
        }
    }
}

/** Fades the hint in (with a 4dp upward slide unless [reducedMotion]) and out; a plain function so no layout scope's AnimatedVisibility overload is picked. */
@Composable
internal fun PromptSuggestionPresence(visible: Boolean, reducedMotion: Boolean, content: @Composable () -> Unit) {
    val slide = with(LocalDensity.current) { 4.dp.roundToPx() }
    AnimatedVisibility(visible,
        enter = fadeIn(tween(220)) + (if (reducedMotion) EnterTransition.None else slideInVertically(tween(220)) { slide }),
        exit = fadeOut(tween(120))) { content() }
}

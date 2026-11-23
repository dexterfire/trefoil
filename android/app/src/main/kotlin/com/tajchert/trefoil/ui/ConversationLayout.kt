package com.tajchert.trefoil.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec

/**
 * How the conversation lays itself out inside the pane it is given, decided from the CONTAINER width only (never the window).
 * Below [AdaptiveSpec.MediumMinWidth] the conversation is exactly the phone UI ([Phone]); from there up it reads in a centred column.
 *
 * All numbers are dp. [columnWidth] is the width of the reading column itself (side padding excluded); [proseMax] caps prose,
 * lists, quotes, work logs and request cards (start-aligned inside the column); code blocks, tables and HTML cards use the
 * whole column; [bubbleMax] caps a user bubble (end-aligned, shrink-wrapped).
 */
internal data class ConversationLayout(
    val wide: Boolean,
    val containerWidth: Float,
    val columnWidth: Float,
    val sidePadding: Float,
    val proseMax: Float,
    val bubbleMax: Float,
) {
    /** Horizontal list padding that centres [columnWidth] inside the container: the scrollable area itself still spans the pane. */
    val contentPadding: Float get() = ((containerWidth - columnWidth) / 2f).coerceAtLeast(sidePadding)

    companion object {
        /** Today's phone layout: no caps, no centring. */
        val Phone = ConversationLayout(wide = false, containerWidth = 0f, columnWidth = 0f, sidePadding = 16f, proseMax = Float.MAX_VALUE, bubbleMax = Float.MAX_VALUE)
    }
}

/** Fraction of the column a user bubble may take at most, like the phone's 86%: keeps a bubble from filling a narrow column. */
private const val BubbleColumnFraction = .86f

internal fun conversationLayoutFor(containerWidthDp: Float): ConversationLayout {
    if (containerWidthDp < AdaptiveSpec.MediumMinWidth) return ConversationLayout.Phone
    val side = 24f
    val column = minOf(AdaptiveSpec.ConversationColumnMax, containerWidthDp - 2 * side)
    return ConversationLayout(
        wide = true, containerWidth = containerWidthDp, columnWidth = column, sidePadding = side,
        proseMax = minOf(AdaptiveSpec.ProseMax, column), bubbleMax = minOf(AdaptiveSpec.UserBubbleMax, column * BubbleColumnFraction),
    )
}

/** Set by [Conversation] from its own container; standalone pieces (previews, tests) read the phone layout. */
internal val LocalConversationLayout = staticCompositionLocalOf { ConversationLayout.Phone }

/** Caps the block at the prose width, start-aligned. A no-op for the phone layout, so phone UI trees stay unchanged. */
internal fun Modifier.proseWidth(layout: ConversationLayout): Modifier = if (layout.wide) widthIn(max = layout.proseMax.dp) else this

/**
 * A timeline block that reads at prose width inside the wide column (no cap on a phone). The composition structure is the same
 * for both, so remembered and saved state inside the block survives a rotation or fold between a narrow and a wide pane.
 */
@Composable
internal fun ProseBlock(content: @Composable () -> Unit) {
    val layout = LocalConversationLayout.current
    Box(Modifier.proseWidth(layout)) { content() }
}

/**
 * A strip docked under the timeline (composer, quick replies, read-only bar). Phone: full width as before. Wide: centred and at
 * most as wide as the reading column, so its edges line up with the text above. Same composition structure either way.
 */
@Composable
internal fun ReadingDock(phoneHorizontal: Dp = 0.dp, top: Dp = 0.dp, bottom: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    val layout = LocalConversationLayout.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (layout.wide) Alignment.TopCenter else Alignment.TopStart) {
        val width = if (layout.wide) Modifier.widthIn(max = (layout.columnWidth + 2 * layout.sidePadding).dp) else Modifier
        Column(width.padding(start = if (layout.wide) layout.sidePadding.dp else phoneHorizontal, end = if (layout.wide) layout.sidePadding.dp else phoneHorizontal, top = top, bottom = bottom), content = content)
    }
}

/** What the composer does with an Enter key press. */
internal enum class ComposerEnterDecision {
    /** Not ours: the text field, the IME or the system handles it. */
    Ignore,
    /** Send like the primary button would. */
    Send,
    /** Insert a line break at the selection. */
    Newline,
    /** Handled by doing nothing (Enter while sending is not possible), so no stray newline reaches the field. */
    Swallow,
}

/** The facts about one key event that decide what Enter does, free of Android types so they are unit-testable. */
internal data class ComposerEnterKey(
    val isEnter: Boolean, val isDown: Boolean, val repeatCount: Int = 0,
    val shift: Boolean = false, val alt: Boolean = false, val ctrl: Boolean = false, val meta: Boolean = false,
    val hardware: Boolean, val composing: Boolean = false,
)

/** The value with a line break replacing the selection, cursor after it (Shift/Alt+Enter in the composer). */
internal fun TextFieldValue.withLineBreak(): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(start, text.length)
    return TextFieldValue(text.replaceRange(start, end, "\n"), TextRange(start + 1))
}

/** A key event came from a physical keyboard, not the on-screen one (which reports the virtual keyboard device and the soft-keyboard flag). */
internal fun isHardwareKeyboardEvent(deviceId: Int, flags: Int): Boolean =
    deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && flags and AndroidKeyEvent.FLAG_SOFT_KEYBOARD == 0

/**
 * Hardware keyboard Enter in the composer: Enter, Ctrl+Enter and Meta+Enter send; Shift+Enter and Alt+Enter insert a newline. The
 * on-screen keyboard keeps its newline, and nothing is decided while an IME composition is in progress. Ctrl/Meta win over Shift/Alt.
 * [canSend] is whether the primary button would send right now (not Stop, not disabled): without it a send key is swallowed, never
 * turned into a newline, so a second Enter during a send in flight cannot edit the text that is about to be cleared.
 */
internal fun composerEnterDecision(key: ComposerEnterKey, canSend: Boolean): ComposerEnterDecision {
    if (!key.isEnter || !key.hardware || key.composing) return ComposerEnterDecision.Ignore
    val sends = key.ctrl || key.meta || !(key.shift || key.alt)
    return when {
        !sends -> ComposerEnterDecision.Newline
        !key.isDown || key.repeatCount > 0 || !canSend -> ComposerEnterDecision.Swallow
        else -> ComposerEnterDecision.Send
    }
}

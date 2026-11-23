package com.tajchert.trefoil.ui.adaptive

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

/** System horizontal-resize cursor for vertical splitters (pane dividers). */
internal val HorizontalResizePointer: PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)

/** Hand cursor for custom clickable surfaces that are not Material buttons (links, chips drawn by hand); default cursor while disabled. */
internal fun Modifier.handPointer(enabled: Boolean = true): Modifier = pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)

/** Text (I-beam) cursor for custom selectable text such as `SelectionContainer` regions; text fields already have it. */
internal fun Modifier.textPointer(): Modifier = pointerHoverIcon(PointerIcon.Text)

/** Horizontal-resize cursor for a divider; no override while it is locked. */
internal fun Modifier.horizontalResizePointer(enabled: Boolean = true): Modifier = if (enabled) pointerHoverIcon(HorizontalResizePointer) else this

/**
 * Calls [onPress] with the pointer position (px, relative to this element) when the secondary (right) mouse button goes down, and
 * consumes that press so the element's own click does not also fire. Touch long-press is untouched; pass the same enabled rule the
 * long-press uses. [onPress] must read state through State objects, as it is only re-captured when its identity changes.
 */
internal fun Modifier.onSecondaryPress(enabled: Boolean = true, onPress: (Offset) -> Unit): Modifier =
    if (!enabled) this else pointerInput(onPress) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    onPress(event.changes.first().position)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }

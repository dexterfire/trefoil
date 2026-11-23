package com.tajchert.trefoil.ui.adaptive

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.Tf

/**
 * 2dp accent outline while the focusable below it in the modifier chain has focus and the last input was a key press (never after
 * a tap or click). Put it in front of the `clickable`/`selectable` it should mark; draw-only, so layout is unchanged.
 */
@Composable
internal fun Modifier.focusRing(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val accent = Tf.colors.accent
    return onFocusChanged { focused = it.isFocused }.then(if (focused && keyboard) Modifier.border(2.dp, accent, shape) else Modifier)
}

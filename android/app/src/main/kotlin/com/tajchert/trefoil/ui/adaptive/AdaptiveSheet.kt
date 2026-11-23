package com.tajchert.trefoil.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositeKeyHashCode
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tajchert.trefoil.ui.Tf

/** True when a sheet should open as a centred dialog: the window (not the pane) is at least [AdaptiveSpec.DialogMinWindowWidth] wide. */
internal fun usesDialog(windowWidthDp: Float): Boolean = windowWidthDp >= AdaptiveSpec.DialogMinWindowWidth

/** Current window size in dp (zero before the window is measured, which picks the sheet). */
@Composable
internal fun windowSizeDp(): DpSize {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
}

/**
 * Today's bottom sheet below 600dp window width, a centred dialog at 600dp and above. The [content] is the same composable in both:
 * a column at most [dialogMaxWidth] wide (and [dialogMaxHeight], capped at 90% of the window) in the dialog. [insetContent] adds the
 * IME and navigation-bar padding that today's sheet bodies apply themselves; pass false when the content already does.
 * Dismisses on scrim tap, Back and Escape.
 *
 * Crossing 600dp while open (unfolding, a split-screen drag) swaps the presentation. Only `rememberSaveable` state inside [content]
 * (a draft, a selection, a query) moves across the swap; plain `remember` state, scroll positions held without `rememberSaveable`
 * and focus start over. The same saveable state also survives Activity recreation while the sheet is open. See [PortableSaveableState]
 * for why a plain `SaveableStateHolder` is not enough here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TfAdaptiveSheet(
    onDismiss: () -> Unit, dialogMaxWidth: Dp, modifier: Modifier = Modifier, dialogMaxHeight: Dp = Dp.Unspecified, insetContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Tf.colors
    val window = windowSizeDp()
    val dialog = usesDialog(window.width.value)
    val portable = rememberSaveable(saver = PortableSaveableState.Saver) { PortableSaveableState() }
    portable.onPresentation(dialog)
    val parent = LocalSaveableStateRegistry.current
    val body: @Composable ColumnScope.() -> Unit = {
        val base = currentCompositeKeyHashCode
        val registry = remember(portable) { portable.newRegistry(base) { parent?.canBeSaved(it) ?: true } }
        DisposableEffect(registry) { portable.active = registry; onDispose { if (portable.active === registry) portable.active = null } }
        CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { content() }
    }
    if (!dialog) {
        ModalBottomSheet(
            onDismissRequest = onDismiss, modifier = modifier, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            contentWindowInsets = { WindowInsets(0) },
            dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
        ) { Column(if (insetContent) Modifier.imePadding().navigationBarsPadding() else Modifier, content = body) }
        return
    }
    // On a separating or half-open fold the dialog sits centred in one half (see dialogRegion); a flat window changes nothing.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val region = dialogRegion(window.width.value, window.height.value, LocalFold.current, rtl, imeVisible)
    val maxHeight = dialogMaxHeightDp(window.height.value, region, dialogMaxHeight.takeIf { it != Dp.Unspecified }?.value).dp
    val maxWidth = dialogMaxWidthDp(dialogMaxWidth.value, region).dp
    // The keyboard only needs making room for when the dialog spans the window's height; in a tabletop top half it sits below.
    val imeAware = region == null || region.height >= window.height.value
    // Same composables in both cases (a Box around the Surface), so a fold change keeps the content's state.
    // The region is in window coordinates (left and right, as the hinge is), so the padding must not mirror in RTL.
    val outer = if (region == null) Modifier else Modifier.fillMaxSize()
        .absolutePadding(left = region.left.dp, top = region.top.dp, right = (window.width.value - region.right).dp, bottom = (window.height.value - region.bottom).dp)
        // The region's Box covers the window, so outside taps arrive here (the window's own outside-tap never fires); taps on the dialog are consumed below.
        .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(outer, contentAlignment = Alignment.Center) {
            Surface(
                modifier.padding(horizontal = 24.dp).widthIn(max = maxWidth).fillMaxWidth().heightIn(max = maxHeight)
                    .then(if (imeAware) Modifier.imePadding() else Modifier)
                    .then(if (region == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { } })
                    .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onDismiss(); true } else false },
                shape = RoundedCornerShape(28.dp), color = c.sheet, contentColor = c.text, tonalElevation = 0.dp,
            ) { Column(content = body) }
        }
    }
}

/**
 * Carries the sheet content's `rememberSaveable` values between the bottom sheet and the dialog. Each presentation hosts the content
 * in its own window composition, so the same `rememberSaveable` call gets a different key in each (keys are composite key hashes of the
 * whole composition path); a shared `SaveableStateHolder` therefore restores nothing. Instead each presentation gets a
 * [PortableRegistry] that remembers the composite key hash at its own root ([PortableRegistry.base]). When the presentation flips, the
 * leaving registry is saved; the entering one matches a requested key to a saved key from the same call site: the two differ exactly by
 * the difference of the bases rotated by the call site's depth (Compose mixes group keys with rotate and xor). A key that matches no
 * rotation is not restored, so if Compose ever changes its key mixing the content just starts fresh, as it did before this class.
 */
private class PortableSaveableState(var saved: Map<String, List<Any?>>? = null, var savedBase: Long = 0L) {
    var active: PortableRegistry? = null
    private var dialog: Boolean? = null

    /** Called in composition; on a flip saves the leaving presentation before the entering one restores (whatever the window order). */
    fun onPresentation(dialog: Boolean) {
        if (this.dialog != null && this.dialog != dialog) active?.let { saved = it.performSave(); savedBase = it.base }
        this.dialog = dialog
    }

    fun newRegistry(base: Long, canBeSaved: (Any) -> Boolean) = PortableRegistry(base, saved, savedBase, canBeSaved)

    companion object {
        val Saver = Saver<PortableSaveableState, Any>(
            save = { state -> state.active?.let { arrayListOf(it.base, it.performSave()) } ?: arrayListOf(state.savedBase, state.saved) },
            restore = { value ->
                val list = value as List<*>
                @Suppress("UNCHECKED_CAST") PortableSaveableState(list[1] as Map<String, List<Any?>>?, list[0] as Long)
            },
        )
    }
}

private class PortableRegistry(val base: Long, restored: Map<String, List<Any?>>?, private val restoredBase: Long, canBeSaved: (Any) -> Boolean) :
    SaveableStateRegistry {
    private val delegate = SaveableStateRegistry(null, canBeSaved)
    private val pending: MutableMap<String, List<Any?>> = restored?.toMutableMap() ?: mutableMapOf()

    override fun canBeSaved(value: Any) = delegate.canBeSaved(value)
    override fun registerProvider(key: String, valueProvider: () -> Any?) = delegate.registerProvider(key, valueProvider)

    override fun consumeRestored(key: String): Any? {
        val match = if (key in pending) key else pending.keys.firstOrNull { sameCallSite(it, key) } ?: return null
        val values = pending.remove(match).orEmpty()
        if (values.size > 1) pending[match] = values.drop(1)
        return values.firstOrNull()
    }

    // Unconsumed values only stay valid under the base they were saved with (content not composed yet after recreation).
    override fun performSave(): Map<String, List<Any?>> =
        if (restoredBase == base) pending + delegate.performSave() else delegate.performSave()

    private fun sameCallSite(saved: String, requested: String): Boolean {
        val shift = restoredBase xor base
        if (shift == 0L) return false
        val diff = (saved.toLongOrNull(36) ?: return false) xor (requested.toLongOrNull(36) ?: return false)
        return (0 until 64).any { shift.rotateLeft(it) == diff }
    }
}

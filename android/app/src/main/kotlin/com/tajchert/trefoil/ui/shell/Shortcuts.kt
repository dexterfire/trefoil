package com.tajchert.trefoil.ui.shell

import android.content.Context
import android.content.res.Configuration
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ThreadSummary
import com.tajchert.trefoil.ui.ComposerButton
import com.tajchert.trefoil.ui.ComposerPrimary
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.ThreadFilter
import com.tajchert.trefoil.ui.TrefoilType
import com.tajchert.trefoil.R
import com.tajchert.trefoil.ui.adaptive.TfAdaptiveSheet
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.threadSections
import com.tajchert.trefoil.ui.threadShelves
import com.tajchert.trefoil.core.model.FollowUpMode

/**
 * Every hardware-keyboard shortcut of the app, in one table. The root key handler in `TrefoilApp`, the system Keyboard Shortcut Helper
 * (`MainActivity.onProvideKeyboardShortcuts`), the cheat sheet dialog and the tooltip hints ([shortcutHint]) are all generated from
 * [ShortcutTable], so they cannot drift. A shortcut calls the same function its button calls and obeys the same enabled rule; a
 * target that does not exist (or is disabled) leaves the key unhandled. Only chords with Ctrl, Meta or Alt, function keys and Escape
 * are global, so plain typing in a text field is never taken. Allow and Deny of an approval have no entry here, by design.
 */
internal enum class ShortcutId {
    ShowShortcuts, Dismiss, Refresh,
    NewThread, SearchThreads, NextThread, PreviousThread, NextNeedsYou,
    GoThreads, GoScreens, GoServers, GoUsage, MachineSwitcher,
    ToggleThreadList, ToggleSidePanel, OpenAgents, ModelPicker,
    ComposerSend, ComposerNewline, ComposerSendOther, StartThread,
}

/** Where a shortcut is handled: [Global] ones are dispatched by the app's root key handler, the others by the surface that owns them. */
internal enum class ShortcutScope { Global, Composer, NewThread }

internal data class Chord(
    val key: Key, val keyName: String, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false, val meta: Boolean = false,
) {
    /** The chord as shown to people: "Ctrl+Shift+A". */
    val label: String get() = (listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, "Meta".takeIf { meta }) + keyName).joinToString("+")

    /** The parts of [label], one per key cap. */
    val parts: List<String> get() = label.split('+')

    /** A global chord must not collide with typing: a modifier other than Shift, a function key, or Escape. */
    val safeForGlobal: Boolean get() = ctrl || alt || meta || key == Key.Escape || key in FunctionKeys

    fun matches(event: KeyEvent): Boolean = event.key == key &&
        event.isCtrlPressed == ctrl && event.isShiftPressed == shift && event.isAltPressed == alt && event.isMetaPressed == meta

    /** `KeyEvent.META_*` flags for the system Keyboard Shortcut Helper. */
    val modifiers: Int get() = (if (ctrl) android.view.KeyEvent.META_CTRL_ON else 0) or (if (shift) android.view.KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) android.view.KeyEvent.META_ALT_ON else 0) or (if (meta) android.view.KeyEvent.META_META_ON else 0)
}

private val FunctionKeys = setOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12)

internal data class ShortcutEntry(val id: ShortcutId, @androidx.annotation.StringRes val labelRes: Int, @androidx.annotation.StringRes val groupRes: Int, val chords: List<Chord>, val scope: ShortcutScope = ShortcutScope.Global)

internal val GroupGeneral = R.string.shortcut_group_general
internal val GroupThreads = R.string.shortcut_group_threads
internal val GroupNavigation = R.string.shortcut_group_navigation
internal val GroupPanels = R.string.shortcut_group_panels
internal val GroupMessage = R.string.shortcut_group_message

/** Group order of the cheat sheet and of the system Helper. */
internal val ShortcutGroups = listOf(GroupGeneral, GroupThreads, GroupNavigation, GroupPanels, GroupMessage)

private fun ctrl(key: Key, name: String, shift: Boolean = false) = Chord(key, name, ctrl = true, shift = shift)

/**
 * The table. Search threads, the model picker and the machine switcher act on state that lives inside a screen (the list's query, the
 * conversation's picker, the rail's menu): that screen registers the action with the [ShortcutRegistry] while it is composed and enabled.
 * Alt+letter chords are avoided on purpose: AltGr layouts type letters with them.
 */
internal val ShortcutTable: List<ShortcutEntry> = listOf(
    ShortcutEntry(ShortcutId.ShowShortcuts, R.string.sc_keyboard_shortcuts, GroupGeneral, listOf(ctrl(Key.Slash, "/"), Chord(Key.F1, "F1"))),
    ShortcutEntry(ShortcutId.Dismiss, R.string.sc_close_or_back, GroupGeneral, listOf(Chord(Key.Escape, "Esc"))),
    ShortcutEntry(ShortcutId.Refresh, R.string.sc_refresh, GroupGeneral, listOf(ctrl(Key.R, "R"), Chord(Key.F5, "F5"))),

    ShortcutEntry(ShortcutId.NewThread, R.string.sc_new_thread, GroupThreads, listOf(ctrl(Key.N, "N"))),
    ShortcutEntry(ShortcutId.SearchThreads, R.string.sc_search_threads, GroupThreads, listOf(ctrl(Key.K, "K"), ctrl(Key.F, "F"))),
    ShortcutEntry(ShortcutId.NextThread, R.string.sc_next_thread, GroupThreads, listOf(Chord(Key.DirectionDown, "Down", alt = true), ctrl(Key.RightBracket, "]"))),
    ShortcutEntry(ShortcutId.PreviousThread, R.string.sc_prev_thread, GroupThreads, listOf(Chord(Key.DirectionUp, "Up", alt = true), ctrl(Key.LeftBracket, "["))),
    ShortcutEntry(ShortcutId.NextNeedsYou, R.string.sc_next_needs_you, GroupThreads, listOf(ctrl(Key.U, "U", shift = true))),

    ShortcutEntry(ShortcutId.GoThreads, R.string.dest_threads, GroupNavigation, listOf(ctrl(Key.One, "1"))),
    ShortcutEntry(ShortcutId.GoScreens, R.string.dest_screens, GroupNavigation, listOf(ctrl(Key.Two, "2"))),
    ShortcutEntry(ShortcutId.GoServers, R.string.dest_servers, GroupNavigation, listOf(ctrl(Key.Three, "3"))),
    ShortcutEntry(ShortcutId.GoUsage, R.string.menu_limits_usage, GroupNavigation, listOf(ctrl(Key.Four, "4"))),

    ShortcutEntry(ShortcutId.MachineSwitcher, R.string.switch_machine, GroupNavigation, listOf(ctrl(Key.M, "M", shift = true))),

    ShortcutEntry(ShortcutId.ToggleThreadList, R.string.sc_toggle_thread_list, GroupPanels, listOf(ctrl(Key.Backslash, "\\"))),
    ShortcutEntry(ShortcutId.ToggleSidePanel, R.string.sc_toggle_side_panel, GroupPanels, listOf(ctrl(Key.Period, "."))),
    ShortcutEntry(ShortcutId.OpenAgents, R.string.sc_agents, GroupPanels, listOf(ctrl(Key.A, "A", shift = true))),

    ShortcutEntry(ShortcutId.ModelPicker, R.string.sc_choose_model, GroupMessage, listOf(ctrl(Key.L, "L", shift = true))),
    ShortcutEntry(ShortcutId.ComposerSend, R.string.sc_send, GroupMessage, listOf(Chord(Key.Enter, "Enter"), ctrl(Key.Enter, "Enter")), ShortcutScope.Composer),
    ShortcutEntry(ShortcutId.ComposerNewline, R.string.sc_new_line, GroupMessage, listOf(Chord(Key.Enter, "Enter", shift = true), Chord(Key.Enter, "Enter", alt = true)), ShortcutScope.Composer),
    ShortcutEntry(ShortcutId.ComposerSendOther, R.string.sc_send_other, GroupMessage, listOf(ctrl(Key.Enter, "Enter", shift = true)), ShortcutScope.Composer),
    ShortcutEntry(ShortcutId.StartThread, R.string.sc_start_thread, GroupMessage, listOf(ctrl(Key.Enter, "Enter")), ShortcutScope.NewThread),
)

private val ById = ShortcutTable.associateBy { it.id }

/** The chord as shown to users ("Ctrl+N") for a tooltip or a menu hint; null when the id has no chord. */
internal fun shortcutHint(id: ShortcutId): String? = ById[id]?.chords?.firstOrNull()?.label

/** "New thread (Ctrl+N)": [label] with the shortcut of [id] when there is one. */
internal fun labelWithShortcut(label: String, id: ShortcutId): String = shortcutHint(id)?.let { "$label ($it)" } ?: label

/** A tooltip's text: [label] with the shortcut of [id] while a hardware keyboard is attached, the bare label on a touch-only device. */
@Composable
internal fun tooltipWithShortcut(label: String, id: ShortcutId): String =
    if (LocalConfiguration.current.keyboard == Configuration.KEYBOARD_QWERTY) labelWithShortcut(label, id) else label

/** The global entry whose chord [event] presses exactly (modifiers included), or null. */
internal fun shortcutFor(event: KeyEvent): ShortcutEntry? =
    ShortcutTable.firstOrNull { it.scope == ShortcutScope.Global && it.chords.any { chord -> chord.matches(event) } }

/**
 * The app root's decision for one key event. Only a first KeyDown is considered (no key up, no repeat); [bindings] holds the actions
 * whose target exists and is enabled right now, each returning whether it handled the key, and an unbound or disabled shortcut leaves
 * the event to whoever has focus. While a dialog, sheet or the drawer is open ([modalOpen]) only the cheat sheet and Escape stay global.
 */
internal fun dispatchShortcut(
    event: KeyEvent, modalOpen: Boolean, bindings: Map<ShortcutId, () -> Boolean>, registered: (ShortcutId) -> (() -> Boolean)? = { null },
): Boolean {
    if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount > 0) return false
    val entry = shortcutFor(event) ?: return false
    if (modalOpen && entry.id != ShortcutId.ShowShortcuts && entry.id != ShortcutId.Dismiss) return false
    return (bindings[entry.id] ?: registered(entry.id))?.invoke() ?: false
}

/**
 * Actions owned by a screen. The screen that holds the state registers while it is composed and enabled ([RegisterShortcut]) and the
 * registration goes when the screen leaves or the control is disabled, so a key never reaches a screen that is gone. Main thread only.
 */
internal class ShortcutRegistry {
    class Registration internal constructor(internal val action: () -> Boolean)

    private val actions = HashMap<ShortcutId, Registration>()

    /** Makes [action] the one that answers [id]; a later registration replaces an earlier one (a screen that moved). */
    fun register(id: ShortcutId, action: () -> Boolean): Registration = Registration(action).also { actions[id] = it }

    /** Removes [registration] only if it is still the current one, so a late dispose never removes its replacement. */
    fun unregister(id: ShortcutId, registration: Registration) { if (actions[id] === registration) actions.remove(id) }

    fun action(id: ShortcutId): (() -> Boolean)? = actions[id]?.action
}

internal val LocalShortcutRegistry = staticCompositionLocalOf<ShortcutRegistry?> { null }

/**
 * Registers [action] for [id] while this call is composed with [enabled] true: use the same enabled rule as the control that does the
 * same thing. Without a registry (a preview, a test of one screen) it does nothing.
 */
@Composable
internal fun RegisterShortcut(id: ShortcutId, enabled: Boolean = true, action: () -> Unit) {
    val registry = LocalShortcutRegistry.current
    val latest = rememberUpdatedState(action)
    if (registry != null && enabled) DisposableEffect(registry, id) {
        val registration = registry.register(id) { latest.value(); true }
        onDispose { registry.unregister(id, registration) }
    }
}

/**
 * Whether the pane a registered action lives in can be seen right now. A hidden pane stays composed (it keeps its state), so its screen
 * still registers; the key must not act on what the person cannot see. [listHidden] is a collapsed list or one a panel replaced.
 */
internal fun registeredShortcutVisible(id: ShortcutId, listHidden: Boolean, transient: TransientList): Boolean = when (id) {
    ShortcutId.SearchThreads -> !listHidden && transient != TransientList.HideList && transient != TransientList.PanelOpen
    // With the compact-height keyboard layout the list fills the window and the conversation, still composed, is out of sight.
    ShortcutId.ModelPicker -> transient != TransientList.ListOnly
    else -> true
}

/** What the root reads at key time: plain fields updated after each composition, so a key press never waits for a recomposition. */
internal class ShortcutHost {
    var bindings: Map<ShortcutId, () -> Boolean> = emptyMap()
    var modalOpen: () -> Boolean = { false }
    val registry = ShortcutRegistry()

    /** The root's veto over a registered action whose screen is composed but not visible (a hidden pane); the screen's own rule is not repeated. */
    var visible: (ShortcutId) -> Boolean = { true }

    fun dispatch(event: KeyEvent): Boolean = dispatchShortcut(event, modalOpen(), bindings) { id -> registry.action(id)?.takeIf { visible(id) } }
}

/** Entries of the table as the system Keyboard Shortcut Helper shows them: one group per [ShortcutGroups] entry, one info per chord. */
internal fun keyboardShortcutGroups(context: Context): List<KeyboardShortcutGroup> = ShortcutGroups.mapNotNull { groupRes ->
    val groupName = context.getString(groupRes)
    val infos = ShortcutTable.filter { it.groupRes == groupRes }.flatMap { entry -> entry.chords.map { KeyboardShortcutInfo(context.getString(entry.labelRes), it.key.nativeKeyCode, it.modifiers) } }
    infos.takeIf { it.isNotEmpty() }?.let { KeyboardShortcutGroup(groupName, it) }
}

// ---- Thread order helpers (the keys step through the same order the list shows with the All filter and no search) ----

/** Ids in list order: Needs you, Pinned, Working, Recent, Snoozed. Settled and archived threads sit in folded groups and are skipped. */
internal fun listedThreadIds(threads: List<ThreadSummary>, now: Long = System.currentTimeMillis()): List<String> =
    threadSections(threadShelves(threads, now), ThreadFilter.All).filter { !it.folded }.flatMap { section -> section.threads.map { it.id } }

/** The thread [step] places from [currentId] in [order], stopping at the ends; with nothing open Next opens the first and Previous the last. */
internal fun adjacentThreadId(order: List<String>, currentId: String?, step: Int): String? {
    if (order.isEmpty()) return null
    val index = order.indexOf(currentId)
    if (index < 0) return if (step > 0) order.first() else order.last()
    return order.getOrNull(index + step)
}

/** The next thread that needs you after [currentId], wrapping at the end; never the open thread itself. Null when no other thread needs you. */
internal fun nextNeedsYouId(needsYou: List<String>, currentId: String?): String? {
    val others = needsYou.filter { it != currentId }
    if (others.isEmpty()) return null
    val index = needsYou.indexOf(currentId)
    if (index < 0) return others.first()
    return (1..needsYou.size).map { needsYou[(index + it) % needsYou.size] }.firstOrNull { it != currentId }
}

// ---- Composer and New thread keys ----

/** The follow-up delivery Ctrl+Shift+Enter picks: the one the primary button does not. Null when the button has no alternate. */
internal fun alternateFollowUp(button: ComposerButton, canSteer: Boolean): FollowUpMode? = when {
    !button.enabled -> null
    button.action == ComposerPrimary.Steer -> FollowUpMode.Queue
    button.action == ComposerPrimary.Queue && canSteer -> FollowUpMode.Steer
    else -> null
}

/** Ctrl or Meta with Enter (no Shift, no Alt): starts the thread in the New thread dialog. */
internal fun isStartThreadChord(event: KeyEvent): Boolean =
    (event.key == Key.Enter || event.key == Key.NumPadEnter) && (event.isCtrlPressed || event.isMetaPressed) && !event.isShiftPressed && !event.isAltPressed

// ---- Cheat sheet ----

/** The cheat sheet: a bottom sheet below 600dp, a centred dialog above; Ctrl+/ and F1 close it again, as Escape does. */
@Composable
internal fun KeyboardShortcutsDialog(onDismiss: () -> Unit) {
    TfAdaptiveSheet(onDismiss = onDismiss, dialogMaxWidth = 720.dp, dialogMaxHeight = 640.dp) { KeyboardShortcutsContent(onDismiss) }
}

private val CheatSheetTwoColumns = 640.dp

/** Sheet body, separate from the sheet chrome so it renders and tests standalone. Generated from [ShortcutTable] only. */
@Composable
internal fun KeyboardShortcutsContent(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tf.colors
    // The dialog window starts without focus, so its keys (Ctrl+/, F1, Escape, Tab) would go nowhere: take focus once it is laid out.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(modifier.fillMaxWidth().onPreviewKeyEvent { event ->
        val entry = ById[ShortcutId.ShowShortcuts]
        if (event.type == KeyEventType.KeyDown && entry != null && entry.chords.any { it.matches(event) }) { onClose(); true } else false
    }.focusRequester(focus).focusTarget()) {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keyboard shortcuts", style = MaterialTheme.typography.headlineSmall, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
            TfIconButton(TfIcons.Close, "Close", onClose, Modifier.size(48.dp), tint = c.text)
        }
        BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            val two = maxWidth >= CheatSheetTwoColumns
            val scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                if (two) Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f)) { ShortcutGroups.take(3).forEach { ShortcutGroupBlock(it) } }
                    Column(Modifier.weight(1f)) { ShortcutGroups.drop(3).forEach { ShortcutGroupBlock(it) } }
                } else ShortcutGroups.forEach { ShortcutGroupBlock(it) }
            }
            // A hairline on the edge the list can still scroll towards, so a list that is cut off does not look complete.
            if (scroll.canScrollBackward) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(1.dp).background(c.line))
            if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(c.line))
        }
    }
}

@Composable
private fun ColumnScope.ShortcutGroupBlock(groupRes: Int) {
    val c = Tf.colors
    val groupLabel = stringResource(groupRes)
    Text(groupLabel.uppercase(), Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() }, style = TrefoilType.section, color = c.muted)
    ShortcutTable.filter { it.groupRes == groupRes }.forEach { entry ->
        val entryLabel = stringResource(entry.labelRes)
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$entryLabel: ${entry.chords.joinToString(" or ") { it.label.replace("+", " plus ") }}" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(entryLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.chords.forEach { chord ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { chord.parts.forEach { KeyCap(it) } }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(text: String) {
    val c = Tf.colors
    Surface(shape = RoundedCornerShape(6.dp), color = c.s2, contentColor = c.text, border = BorderStroke(1.dp, c.line)) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = TrefoilType.meta, maxLines = 1)
    }
}

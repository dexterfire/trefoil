package com.tajchert.trefoil.ui.shell

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.adaptive.HingeAt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.adaptive.AdaptiveSpec
import com.tajchert.trefoil.ui.adaptive.PaneWidths
import com.tajchert.trefoil.ui.adaptive.ShellPolicy
import com.tajchert.trefoil.ui.adaptive.TfTwoPane
import com.tajchert.trefoil.ui.adaptive.TwoPaneState
import com.tajchert.trefoil.ui.adaptive.rememberTwoPaneState

/**
 * The saved list pane state. The width follows the policy default (rotation, fold, unfold) until the user drags the divider;
 * a dragged width is kept and clamped by the new policy. A collapsed list belongs to one machine: it opens again when
 * [environmentId] changes, but survives rotation, folding and process death for the same machine.
 */
@Composable
internal fun rememberShellListState(policy: ShellPolicy, environmentId: String?, collapsed: Boolean = false): TwoPaneState {
    val state = rememberTwoPaneState(policy.defaultListWidth.dp, collapsed && policy.listCollapsible)
    var collapsedFor by rememberSaveable { mutableStateOf(environmentId) }
    // A missing environment (before the connection returns after process death) neither resets nor replaces the remembered one.
    if (environmentId != null && collapsedFor != environmentId) { collapsedFor = environmentId; state.collapsed = false }
    var lastDefault by rememberSaveable { mutableFloatStateOf(policy.defaultListWidth) }
    LaunchedEffect(policy.defaultListWidth) {
        if (state.startWidth.value == lastDefault) state.startWidth = policy.defaultListWidth.dp
        lastDefault = policy.defaultListWidth
    }
    return state
}

/**
 * List and conversation beside each other, sized from [panes] ([com.tajchert.trefoil.ui.adaptive.resolvePanes]).
 *
 * Flat window: one resizable, collapsible divider; [banner] sits once above both panes (the status bar inset is applied here, so the
 * panes' own status bar padding resolves to zero). Split window (a separating or half-opened vertical fold): the list fills the left
 * region, the conversation the right, nothing is drawn under the hinge, the divider is locked and the list cannot collapse; the
 * banner then belongs to the conversation region (the caller draws it there) so it never spans the hinge.
 */
@Composable
internal fun ShellPanes(
    policy: ShellPolicy, panes: PaneWidths, listState: TwoPaneState, banner: @Composable () -> Unit,
    list: @Composable () -> Unit, detail: @Composable () -> Unit, modifier: Modifier = Modifier, transient: TransientList = TransientList.None,
    /** The docked side panel, drawn after the conversation at `panes.sidePanel`; null when no panel is docked. */
    sidePanel: (@Composable () -> Unit)? = null,
) {
    // The conversation keeps one composition whether it sits beside the list or beside the panel (a hinge moves it between slots).
    val detailContent = rememberMovable(detail)
    if (policy.splitFold != null && sidePanel != null && panes.hingeAt == HingeAt.BeforeSidePanel) {
        // Book posture with the panel open: conversation in the start region beside the rail, the panel fills the end region.
        val fixed = remember(panes.conversation) { TwoPaneState(panes.conversation.dp) }
        TfTwoPane(fixed, panes.conversation.dp, detailContent, sidePanel, modifier.fillMaxSize(),
            minStartWidth = panes.conversation.dp, maxStartWidth = panes.conversation.dp, collapseBelow = null, locked = true, hingeWidth = panes.hinge.dp,
            minEndWidth = minOf(AdaptiveSpec.TwoPaneEndMinWidth, panes.sidePanel).dp, dividerLabel = "Side panel divider")
        return
    }
    if (policy.splitFold != null) {
        val fixed = remember(panes.list) { TwoPaneState(panes.list.dp) }
        TfTwoPane(fixed, panes.list.dp, list, detailContent, modifier.fillMaxSize(),
            minStartWidth = panes.list.dp, maxStartWidth = panes.list.dp, collapseBelow = null, locked = true, hingeWidth = panes.hinge.dp,
            minEndWidth = minOf(AdaptiveSpec.TwoPaneEndMinWidth, panes.conversation).dp, dividerLabel = "Thread list divider")
        return
    }
    // The keyboard layouts are throwaway states: the saved width and collapsed flag are never written, so they return with the keyboard.
    // Both panes stay composed (a hidden pane keeps its state); only their widths and what can take focus change.
    val listOnly = transient == TransientList.ListOnly
    val hiddenState = remember(listState.startWidth) { TwoPaneState(listState.startWidth, collapsed = true) }
    val fullState = remember { TwoPaneState(FullWidth) }
    val shown = when (transient) {
        TransientList.None -> listState
        TransientList.HideList, TransientList.PanelOpen -> hiddenState
        TransientList.ListOnly -> fullState
    }
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        banner()
        Row(Modifier.weight(1f)) {
            TfTwoPane(shown, policy.defaultListWidth.dp, list, {
                Box(if (listOnly) Modifier.clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else Modifier) { detailContent() }
            }, Modifier.weight(1f),
                minStartWidth = policy.minListWidth.dp, maxStartWidth = if (listOnly) FullWidth else policy.maxListWidth.dp,
                collapseBelow = if (policy.listCollapsible && transient == TransientList.None) AdaptiveSpec.ListCollapseBelow.dp else null,
                locked = policy.dividerLocked || transient != TransientList.None, showDivider = !listOnly, dividerLabel = "Resize thread list",
                // A third pane keeps the conversation readable: the list gives way (and finally hides) before the conversation drops below 360dp.
                minEndWidth = when { listOnly -> 0.dp; sidePanel != null -> AdaptiveSpec.ConversationMinWidth.dp; else -> AdaptiveSpec.TwoPaneEndMinWidth.dp },
                dividerTraversalIndex = (PaneOrder.List + PaneOrder.Detail) / 2)
            if (sidePanel != null) {
                VerticalDivider(color = Tf.colors.line)
                Box(Modifier.width(panes.sidePanel.dp).fillMaxHeight()) { sidePanel() }
            }
        }
    }
}

/** A composable slot that keeps one composition wherever it is called from; the latest [content] is always the one drawn. */
@Composable
internal fun rememberMovable(content: @Composable () -> Unit): @Composable () -> Unit {
    val current by rememberUpdatedState(content)
    return remember { movableContentOf { current() } }
}

/** [rememberMovable] for a surface that takes the modifier of the slot it is drawn in. */
@Composable
internal fun rememberMovableSurface(content: @Composable (Modifier) -> Unit): @Composable (Modifier) -> Unit {
    val current by rememberUpdatedState(content)
    return remember { movableContentOf { modifier: Modifier -> current(modifier) } }
}

private val FullWidth = 10_000.dp

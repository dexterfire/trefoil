package com.tajchert.trefoil.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

// Two-pane picker for windows >= 600 dp: provider rail | search + list | options. It reuses the phone's rows, trays, stars and search through [PickerUi].

/** Fixed (not content-sized) so switching rail items never resizes the sheet; the list scrolls inside it. */
private val TABLET_SHEET_HEIGHT = 560.dp
private val OPTIONS_PANE_WIDTH = 224.dp
private val OPTIONS_PANE_WIDTH_NARROW = 200.dp
private val NARROW_DIALOG_WIDTH = 640.dp

@Composable
internal fun TabletPickerBody(ui: PickerUi, closeSheet: () -> Unit, loading: Boolean, modifier: Modifier, onRail: (String) -> Unit) {
    val c = Tf.colors
    val height = minOf((LocalConfiguration.current.screenHeightDp * 0.88f).dp, TABLET_SHEET_HEIGHT)
    Column(modifier.fillMaxWidth().height(height)) {
        PickerTitleRow(closeSheet)
        HorizontalDivider(color = c.line)
        Crossfade(loading && ui.models.isEmpty(), Modifier.weight(1f).fillMaxWidth(), label = "modelPickerBody") { skeleton ->
            when {
                skeleton -> TabletSkeleton()
                ui.models.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(TfIcons.Sparkle, "No models available", "Reconnect to refresh configuration.")
                }
                // The options pane is 200dp in a dialog narrower than 640dp (a 673dp window leaves 625dp after the 24dp margins), else 224dp.
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val optionsWidth = if (maxWidth < NARROW_DIALOG_WIDTH) OPTIONS_PANE_WIDTH_NARROW else OPTIONS_PANE_WIDTH
                    Row(Modifier.fillMaxSize()) {
                        if (ui.multiProvider) ProviderRail(ui, onRail)
                        Column(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) {
                            Spacer(Modifier.height(8.dp))
                            PickerSearchField(ui)
                            ui.singleSection?.takeIf { !ui.multiProvider && !ui.searching }?.let { SingleProviderBanner(it, ui.threadSelection) }
                            Box(Modifier.weight(1f).fillMaxWidth()) { PickerLazyList(ui) }
                        }
                        val highlighted = ui.highlighted
                        if (highlighted != null && ui.hasOptions) {
                            VerticalDivider(color = c.line)
                            OptionsPane(highlighted, providerDriver(highlighted.instanceId, ui.providers), ui.values, ui.applied, ui.optionsEnabled, ui.threadSelection, ui.showFast, ui.onSetModelOption, optionsWidth)
                        }
                    }
                }
            }
        }
    }
}

/** Favourites plus one button per provider; the indicator bar sits on the rail's start edge. While searching nothing is selected and a tap leaves the search. */
@Composable
private fun ProviderRail(ui: PickerUi, onRail: (String) -> Unit) {
    val c = Tf.colors
    val favourites = ui.catalog.favorites
    Row(Modifier.fillMaxHeight()) {
        Column(Modifier.width(56.dp).fillMaxHeight().background(c.rail).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val onFavourites = !ui.searching && ui.activeTab == FAVOURITES_TAB
            RailButton(onFavourites, if (favourites.isEmpty()) "Favourites" else "Favourites, ${favourites.size} models", { onRail(FAVOURITES_TAB) }) {
                Icon(if (onFavourites) TfIcons.StarFilled else TfIcons.Star, null, Modifier.size(20.dp), tint = if (onFavourites) c.amber else c.muted)
            }
            ui.catalog.sections.forEach { section ->
                RailButton(!ui.searching && ui.activeTab == section.instanceId, section.title, { onRail(section.instanceId) }) { ProviderMark(section.driver, null, Modifier.size(22.dp)) }
            }
        }
        VerticalDivider(color = c.line)
    }
}

@Composable
private fun RailButton(selected: Boolean, description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val c = Tf.colors
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        if (selected) Box(Modifier.align(Alignment.CenterStart).size(3.dp, 24.dp).background(c.accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)))
        Box(
            Modifier.align(Alignment.Center).size(48.dp).focusRing().handPointer().selectable(selected, role = Role.Tab, onClick = onClick).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) c.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) { icon() }
        }
    }
}

/** Reasoning as vertical radio rows with the Standard | Fast control pinned below; options are disabled until the pick has been applied. */
@Composable
private fun OptionsPane(
    model: ModelOption, driver: String, values: ModelOptionValues, applied: Boolean, optionsEnabled: Boolean, threadSelection: Boolean, showFast: Boolean,
    onSetModelOption: (String, Any) -> Unit, width: Dp,
) {
    val c = Tf.colors
    val reasoning = model.reasoning
    val fast = model.fastMode?.takeIf { showFast }
    Column(Modifier.width(width).fillMaxHeight().background(if (c.isDark) c.s1 else c.bg)) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(driver, null, Modifier.size(14.dp))
            Text(model.shortName ?: model.label, Modifier.padding(start = 6.dp).weight(1f), style = TrefoilType.meta.copy(fontWeight = FontWeight.SemiBold), color = if (optionsEnabled) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!applied) WorkingSpinner(Modifier.padding(start = 8.dp), size = 12.dp)
        }
        if (reasoning != null) {
            val choices = reasoning.selectableChoices
            val choice = reasoningChoice(model, values, threadSelection)
            SectionLabel(reasoning.label.ifBlank { "Reasoning" }, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEachIndexed { index, option ->
                    val selected = option.id == choice?.id
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(if (selected) (if (optionsEnabled) c.accentSoft else c.s2) else Color.Transparent)
                            .focusRing().handPointer().selectable(selected, optionsEnabled, Role.RadioButton) { onSetModelOption(reasoning.id, option.id) }
                            .padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "${reasoning.label} ${option.label}" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(TfIcons.effort(effortBarCount(index, choices.size)), null, Modifier.size(16.dp), tint = if (selected && optionsEnabled) c.accent else c.muted)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                option.label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                                color = if (!optionsEnabled) c.muted else if (selected) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            option.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                        if (selected) Icon(TfIcons.Check, null, Modifier.padding(start = 6.dp).size(16.dp), tint = if (optionsEnabled) c.accent else c.muted)
                    }
                }
            }
        } else Spacer(Modifier.weight(1f))
        if (fast != null) {
            val on = fastModeOn(model, values)
            val label = fast.label.ifBlank { "Fast mode" }
            if (reasoning != null) HorizontalDivider(color = c.line)
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
                SectionLabel(label, Modifier.padding(start = 4.dp, bottom = 6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
                    val colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = c.accentSoft, activeContentColor = c.accent, activeBorderColor = c.accent,
                        inactiveContainerColor = Color.Transparent, inactiveContentColor = c.text, inactiveBorderColor = c.line,
                        disabledActiveContainerColor = c.s2, disabledActiveContentColor = c.muted, disabledActiveBorderColor = c.line,
                        disabledInactiveContainerColor = Color.Transparent, disabledInactiveContentColor = c.muted, disabledInactiveBorderColor = c.line.copy(alpha = .5f),
                    )
                    SegmentedButton(
                        !on, { if (on) onSetModelOption(fast.id, false) }, SegmentedButtonDefaults.itemShape(0, 2), Modifier.heightIn(min = 48.dp), optionsEnabled, colors,
                        border = SegmentedButtonDefaults.borderStroke(segmentBorder(!on, optionsEnabled)), icon = {}, label = { Text("Standard", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    SegmentedButton(
                        on, { if (!on) onSetModelOption(fast.id, true) }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.heightIn(min = 48.dp), optionsEnabled, colors,
                        border = SegmentedButtonDefaults.borderStroke(segmentBorder(on, optionsEnabled)),
                        icon = { Icon(TfIcons.Bolt, null, Modifier.size(SegmentedButtonDefaults.IconSize)) }, label = { Text("Fast", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/** Accent outline only for the active segment while options are enabled; disabled or pending segments stay on the quiet line colours. */
@Composable
private fun segmentBorder(active: Boolean, enabled: Boolean): Color {
    val c = Tf.colors
    return when {
        enabled && active -> c.accent
        enabled || active -> c.line
        else -> c.line.copy(alpha = .5f)
    }
}

/** Loading state for the two-pane layout: placeholder rail, disabled search and four pulsing rows. */
@Composable
private fun TabletSkeleton() {
    val c = Tf.colors
    val alpha = rememberSkeletonAlpha()
    Row(Modifier.fillMaxSize().testTag("model-picker-loading").semantics(mergeDescendants = true) { contentDescription = "Loading models" }) {
        Column(Modifier.width(56.dp).fillMaxHeight().background(c.rail).navigationBarsPadding().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val dot = if (c.isDark) c.s3 else c.muted.copy(alpha = .22f)
            repeat(3) { Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(22.dp).graphicsLayer { this.alpha = alpha() }.background(dot, CircleShape)) } }
        }
        VerticalDivider(color = c.line)
        Column(Modifier.weight(1f).fillMaxHeight().navigationBarsPadding()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                "", {}, enabled = false, singleLine = true, placeholder = { Text("Search models…") }, shape = TfFieldShape, colors = tfFieldColors(),
                leadingIcon = { Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted.copy(alpha = .5f)) },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { Box(Modifier.fillMaxWidth().height(52.dp).graphicsLayer { this.alpha = alpha() }.background(c.s3, RoundedCornerShape(12.dp))) }
            }
        }
    }
}

package com.tajchert.trefoil.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.Settings
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ModelKey
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.core.model.ModelPreferences
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.resolvedOptionValue

// ---------- Presentation helpers (pure, unit-tested) ----------

/** The catalog entry for [model]: drafts may carry a model without descriptors. */
internal fun catalogModel(model: ModelOption?, catalog: List<ModelOption>): ModelOption? =
    model?.let { m -> catalog.firstOrNull { it.sameModelAs(m) } ?: m }

/** Models the picker offers: a locked selected thread stays on its provider instance. */
internal fun pickerModels(models: List<ModelOption>, current: ModelOption?, providerLocked: Boolean, isDraft: Boolean): List<ModelOption> =
    if (providerLocked && !isDraft && current != null) models.filter { it.instanceId == current.instanceId } else models

/** Models grouped by provider instance, keeping catalog order. */
internal fun groupModelsByInstance(models: List<ModelOption>): List<Pair<String, List<ModelOption>>> =
    models.groupBy { it.instanceId }.map { (id, list) -> id to list }

internal fun instanceHeader(instanceId: String, providers: List<ProviderInstance>): String =
    providers.firstOrNull { it.instanceId == instanceId }?.displayName?.takeIf { it.isNotBlank() } ?: instanceId

/** Resolved reasoning choice of [model], or null when it has no reasoning control or no value resolves. */
internal fun reasoningChoice(model: ModelOption?, explicit: ModelOptionValues, threadSelection: Boolean = false): ModelOptionChoice? {
    val d = model?.reasoning ?: return null
    val value = model.resolvedOptionValue(d, explicit, threadSelection) as? String ?: return null
    return d.choices.firstOrNull { it.id == value } ?: ModelOptionChoice(value, value)
}

internal fun fastModeOn(model: ModelOption?, explicit: ModelOptionValues): Boolean {
    val d = model?.fastMode ?: return false
    return model.resolvedOptionValue(d, explicit) == true
}

/** Composer pill text: `<model>` plus ` · Fast` when Fast mode is on; effort has its own icon pill. */
internal fun modelPillLabel(model: ModelOption?, explicit: ModelOptionValues): String {
    if (model == null) return "Model"
    val parts = listOfNotNull(model.label, if (fastModeOn(model, explicit)) "Fast" else null)
    return parts.joinToString(" · ")
}

/** Whether picking [model] leaves the sheet open for effort/Fast choices; [showFast] false means Fast mode has no control. */
internal fun hasModelOptions(model: ModelOption?, showFast: Boolean = true): Boolean =
    model != null && (model.reasoning != null || (showFast && model.fastMode != null))

/** Whether the picker should show its loading skeleton: no catalog yet, but one is on its way. */
internal fun modelPickerLoading(models: List<ModelOption>, connection: ConnectionStatus): Boolean =
    models.isEmpty() && (connection == ConnectionStatus.Connecting || connection == ConnectionStatus.Reconnecting)

// ---------- Sheet ----------

/** Provider tab key of the favourites tab; instance ids key the others. */
internal const val FAVOURITES_TAB = "★favourites"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    models: List<ModelOption>, providers: List<ProviderInstance>, current: ModelOption?, explicit: ModelOptionValues, enabled: Boolean,
    dismiss: () -> Unit, onSetModel: (ModelOption) -> Unit, onSetModelOption: (String, Any) -> Unit, threadSelection: Boolean = false,
    preferences: ModelPreferences = ModelPreferences(), canWriteSettings: Boolean = false, onToggleFavorite: (ModelKey, Boolean) -> Unit = { _, _ -> },
    showFast: Boolean = true, loading: Boolean = false,
) {
    val c = Tf.colors
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        sheetMaxWidth = 680.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) {
        ModelPickerContent(
            models, providers, current, explicit, enabled, { dismiss() }, onSetModel, onSetModelOption, Modifier, threadSelection,
            preferences, canWriteSettings, onToggleFavorite, showFast, loading = loading,
        )
    }
}

/**
 * Sheet body, separate from the [ModalBottomSheet] chrome so it renders standalone. [closeSheet] runs after a model pick without
 * options and from the close button. Favourites are toggled through [onToggleFavorite] and disabled unless [canWriteSettings] and
 * [preferences] are synced. [showFast] false hides the Fast mode control (callers with nowhere to keep it). [loading] (with no models yet) shows a skeleton that crossfades to the catalog when models arrive. [initialQuery] seeds search and [startOnFavourites] opens the favourites tab (previews).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelPickerContent(
    models: List<ModelOption>, providers: List<ProviderInstance>, current: ModelOption?, explicit: ModelOptionValues, enabled: Boolean,
    closeSheet: () -> Unit, onSetModel: (ModelOption) -> Unit, onSetModelOption: (String, Any) -> Unit, modifier: Modifier = Modifier, threadSelection: Boolean = false,
    preferences: ModelPreferences = ModelPreferences(), canWriteSettings: Boolean = false, onToggleFavorite: (ModelKey, Boolean) -> Unit = { _, _ -> },
    showFast: Boolean = true, initialQuery: String = "", startOnFavourites: Boolean = false, loading: Boolean = false,
) {
    val c = Tf.colors
    // A pick on a thread lands asynchronously: keep showing the picked model's options until the selection catches up.
    var picked by remember { mutableStateOf<ModelOption?>(null) }
    LaunchedEffect(current?.instanceId, current?.model) { picked = null }
    val highlighted = catalogModel(picked ?: current, models)
    val applied = highlighted != null && highlighted.sameModelAs(current)
    val values = if (applied) explicit else emptyMap()
    val optionsEnabled = enabled && applied
    val canStar = canWriteSettings && preferences.synced
    val starsEnabled = enabled && canStar

    val catalog = remember(models, providers, preferences, current) { buildModelCatalog(models, providers, preferences, current) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    val searching = query.isNotBlank()
    val results = remember(models, providers, preferences, query) { searchModels(models, providers, preferences, query) }
    val multiProvider = catalog.sections.size > 1

    // Opens on favourites when they hold the current model (or nothing is current), else on the current model's provider. Recomputed once
    // when the catalog first arrives (loading skeleton), then kept; a tab the user picked wins while it still exists.
    val initialTab = remember(catalog.sections.isEmpty()) {
        val favouriteHit = catalog.favorites.isNotEmpty() && (highlighted == null || catalog.favorites.any { it.sameModelAs(highlighted) })
        when {
            favouriteHit || startOnFavourites -> FAVOURITES_TAB
            else -> catalog.sections.firstOrNull { it.instanceId == highlighted?.instanceId }?.instanceId ?: catalog.sections.firstOrNull()?.instanceId ?: FAVOURITES_TAB
        }
    }
    var tab by rememberSaveable { mutableStateOf<String?>(null) }
    val activeTab = tab?.takeIf { it == FAVOURITES_TAB || catalog.sections.any { s -> s.instanceId == it } } ?: initialTab
    val expandedFamilies = remember { mutableStateMapOf<String, Boolean>() }

    fun pick(option: ModelOption) {
        picked = option
        onSetModel(option)
        if (!hasModelOptions(option, showFast)) closeSheet()
    }

    val ui = PickerUi(
        models, providers, preferences, catalog, results, query, { query = it }, activeTab, highlighted, applied, values, enabled, optionsEnabled, canStar, starsEnabled,
        showFast, threadSelection, expandedFamilies, ::pick, onToggleFavorite, onSetModelOption,
    )
    // Two panes once the window is 600 dp wide (the sheet is at most 680 dp); the phone layout below that. Read from the configuration, like the
    // height cap, rather than BoxWithConstraints: a subcompose layout would change how the phone body is measured.
    if (LocalConfiguration.current.screenWidthDp >= TABLET_MIN_WIDTH.value) TabletPickerBody(ui, closeSheet, loading, modifier) { tab = it; query = "" }
    else PhonePickerBody(ui, closeSheet, loading, modifier) { tab = it }
}

/** Width from which the picker lays out as rail + list + options panes. */
internal val TABLET_MIN_WIDTH = 600.dp

/** State resolved once by [ModelPickerContent] and shared by the phone and tablet bodies, so both apply the same pick, star, search and enablement rules. */
internal class PickerUi(
    val models: List<ModelOption>, val providers: List<ProviderInstance>, val preferences: ModelPreferences, val catalog: ModelCatalog, val results: List<ModelOption>,
    val query: String, val setQuery: (String) -> Unit, val activeTab: String, val highlighted: ModelOption?, val applied: Boolean, val values: ModelOptionValues,
    val enabled: Boolean, val optionsEnabled: Boolean, val canStar: Boolean, val starsEnabled: Boolean, val showFast: Boolean, val threadSelection: Boolean,
    val expandedFamilies: MutableMap<String, Boolean>, val pick: (ModelOption) -> Unit, val onToggleFavorite: (ModelKey, Boolean) -> Unit,
    val onSetModelOption: (String, Any) -> Unit,
) {
    val searching: Boolean get() = query.isNotBlank()
    val multiProvider: Boolean get() = catalog.sections.size > 1
    val singleSection: ProviderSection? get() = catalog.sections.firstOrNull()
    val hasOptions: Boolean get() = highlighted != null && hasModelOptions(highlighted, showFast)

    fun favouriteRow(option: ModelOption, label: String = option.label): StarSpec {
        val starred = preferences.isFavorite(option)
        return StarSpec(starred, starsEnabled, label) { onToggleFavorite(ModelKey.of(option), !starred) }
    }
}

@Composable
private fun PhonePickerBody(ui: PickerUi, closeSheet: () -> Unit, loading: Boolean, modifier: Modifier, onTab: (String) -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp
    Column(modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        PickerTitleRow(closeSheet)
        Crossfade(loading && ui.models.isEmpty(), label = "modelPickerBody") { skeleton ->
            if (skeleton) {
                ModelPickerSkeleton()
                return@Crossfade
            }
            Column(Modifier.fillMaxWidth()) {
                if (ui.models.isEmpty()) {
                    EmptyState(TfIcons.Sparkle, "No models available", "Reconnect to refresh configuration.", Modifier.padding(bottom = 24.dp))
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    return@Column
                }
                PickerSearchField(ui)
                if (ui.multiProvider && !ui.searching) ProviderTabs(ui.catalog, ui.activeTab, onTab)
                ui.singleSection?.takeIf { !ui.multiProvider && !ui.searching }?.let { SingleProviderBanner(it, ui.threadSelection) }
                Box(Modifier.weight(1f, fill = false)) { PickerLazyList(ui) }
                val highlighted = ui.highlighted
                if (highlighted != null && ui.hasOptions) {
                    ControlsDock(highlighted, providerDriver(highlighted.instanceId, ui.providers), ui.values, ui.applied, ui.optionsEnabled, ui.threadSelection, ui.showFast, ui.onSetModelOption)
                } else {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

@Composable
internal fun PickerTitleRow(closeSheet: () -> Unit) {
    val c = Tf.colors
    Row(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Model", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = c.text)
        IconButton(onClick = closeSheet, Modifier.size(48.dp)) { Icon(TfIcons.Close, "Close", tint = c.text) }
    }
}

/** The search field, shown once the catalog is big enough (or a query is active). */
@Composable
internal fun PickerSearchField(ui: PickerUi) {
    if (!(ui.models.size >= 5 || ui.query.isNotEmpty())) return
    val c = Tf.colors
    val focus = LocalFocusManager.current
    OutlinedTextField(
        ui.query, ui.setQuery, singleLine = true, placeholder = { Text("Search models…") }, shape = TfFieldShape, colors = tfFieldColors(),
        leadingIcon = { Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted) },
        trailingIcon = { if (ui.query.isNotEmpty()) IconButton(onClick = { ui.setQuery("") }) { Icon(TfIcons.Close, "Clear search", Modifier.size(18.dp), tint = c.muted) } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).semantics { contentDescription = "Search models" },
    )
}

/** Provider banner above the list when only one provider is offered (e.g. a thread locked to it). */
@Composable
internal fun SingleProviderBanner(section: ProviderSection, threadSelection: Boolean) {
    val c = Tf.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderMark(section.driver, null, Modifier.size(16.dp))
        Text(section.title, style = TrefoilType.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        if (threadSelection) Text("· Locked to this thread", style = TrefoilType.meta, color = c.muted)
    }
    HorizontalDivider(color = c.line)
}

/** The scrolling list: search results, the favourites tab, or one provider's families, per [PickerUi.activeTab]. */
@Composable
internal fun PickerLazyList(ui: PickerUi) {
    val pick = ui.pick
    val highlighted = ui.highlighted
    val enabled = ui.enabled
    // No top content padding: sticky headers pin at the padding edge, so the 6 dp gap above the first row is a leading item instead.
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "top-gap") { Spacer(Modifier.height(4.dp)) }
        when {
            ui.searching -> {
                if (ui.results.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No models match \"${ui.query.trim()}\"", style = MaterialTheme.typography.bodyLarge, color = Tf.colors.muted, textAlign = TextAlign.Center)
                        TextButton(onClick = { ui.setQuery("") }) { Text("Clear search", color = Tf.colors.accent) }
                    }
                }
                itemsIndexed(ui.results, key = { _, m -> "s:${m.instanceId}:${m.model}" }) { _, option ->
                    val driver = providerDriver(option.instanceId, ui.providers)
                    ModelRow(option.label, instanceHeader(option.instanceId, ui.providers).takeIf { ui.multiProvider }, driver, option.badge, option.sameModelAs(highlighted), enabled,
                        "Model ${option.label}", null, ui.favouriteRow(option), onClick = { pick(option) })
                }
            }
            !ui.multiProvider -> {
                val section = ui.singleSection
                if (ui.catalog.favorites.isNotEmpty()) {
                    stickyHeader(key = "h:favourites") { PickerHeader("Favourites", top = 8.dp) }
                    itemsIndexed(ui.catalog.favorites, key = { _, m -> "f:${m.instanceId}:${m.model}" }) { _, option ->
                        ModelRow(option.label, null, null, option.badge, option.sameModelAs(highlighted), enabled, "Model ${option.label}", null, ui.favouriteRow(option), onClick = { pick(option) })
                    }
                    stickyHeader(key = "h:all") { PickerHeader("All models", top = 12.dp) }
                }
                if (section != null) familyItems(section, highlighted, enabled, ui.expandedFamilies, pick, ui::favouriteRow)
            }
            ui.activeTab == FAVOURITES_TAB -> {
                if (ui.catalog.favorites.isEmpty()) item {
                    EmptyState(TfIcons.Star, "No favourites yet",
                        if (ui.canStar) "Star models in any provider tab to pin them here and sync with desktop." else "Connect with settings write access to edit favourites.",
                        Modifier.padding(bottom = 16.dp))
                }
                itemsIndexed(ui.catalog.favorites, key = { _, m -> "f:${m.instanceId}:${m.model}" }) { _, option ->
                    ModelRow(option.label, instanceHeader(option.instanceId, ui.providers), providerDriver(option.instanceId, ui.providers), option.badge, option.sameModelAs(highlighted),
                        enabled, "Model ${option.label}", null, ui.favouriteRow(option), onClick = { pick(option) })
                }
            }
            else -> ui.catalog.sections.firstOrNull { it.instanceId == ui.activeTab }?.let { familyItems(it, highlighted, enabled, ui.expandedFamilies, pick, ui::favouriteRow) }
        }
    }
}

/** The driver of [instanceId], or the id itself when the instance is not listed (as [buildModelCatalog] does). */
internal fun providerDriver(instanceId: String, providers: List<ProviderInstance>): String = providers.firstOrNull { it.instanceId == instanceId }?.driver ?: instanceId

/** In-list section label; opaque so rows scrolling underneath a pinned header don't show through. */
@Composable
private fun PickerHeader(text: String, top: Dp) {
    SectionLabel(text, Modifier.fillMaxWidth().background(Tf.colors.sheet).padding(start = 12.dp, top = top, bottom = 4.dp))
}

/** True when the system animator duration scale is 0 (animations off). */
@Composable
internal fun rememberAnimationsOff(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Pulsing alpha for placeholder bars (0.4..0.75), read lazily from a graphics layer; static when animations are off. */
@Composable
internal fun rememberSkeletonAlpha(): () -> Float {
    val pulse = if (rememberAnimationsOff()) null else rememberInfiniteTransition(label = "modelSkeleton")
        .animateFloat(.4f, .75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "modelSkeletonAlpha")
    return { pulse?.value ?: .55f }
}

/** Loading state: disabled search and tabs plus four pulsing placeholder rows (static when animations are off). */
@Composable
private fun ModelPickerSkeleton() {
    val c = Tf.colors
    val alpha = rememberSkeletonAlpha()
    val barShape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().testTag("model-picker-loading").semantics(mergeDescendants = true) { contentDescription = "Loading models" }) {
        OutlinedTextField(
            "", {}, enabled = false, singleLine = true, placeholder = { Text("Search models…") }, shape = TfFieldShape, colors = tfFieldColors(),
            leadingIcon = { Icon(TfIcons.Search, null, Modifier.size(18.dp), tint = c.muted.copy(alpha = .5f)) },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(24.dp, 88.dp, 72.dp).forEach { width -> Box(Modifier.size(width, 16.dp).graphicsLayer { this.alpha = alpha() }.background(c.s2, RoundedCornerShape(8.dp))) }
        }
        HorizontalDivider(color = c.line)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(4) { Box(Modifier.fillMaxWidth().height(52.dp).graphicsLayer { this.alpha = alpha() }.background(c.s2, barShape)) }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

internal class StarSpec(val starred: Boolean, val enabled: Boolean, val label: String, val toggle: () -> Unit)

private class VersionPillSpec(val label: String, val expanded: Boolean, val description: String, val toggle: () -> Unit)

/** One provider's families as lazy items: a row per family, its version tray when expanded, and an OLDER divider before legacy-only families. */
@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.familyItems(
    section: ProviderSection, highlighted: ModelOption?, enabled: Boolean, expandedFamilies: MutableMap<String, Boolean>,
    pick: (ModelOption) -> Unit, star: (ModelOption, String) -> StarSpec,
) {
    val firstLegacy = section.families.indexOfFirst { f -> f.versions.all { it.isLegacy } }.takeIf { it > 0 }
    section.families.forEachIndexed { index, family ->
        if (index == firstLegacy) stickyHeader(key = "older:${section.instanceId}") { PickerHeader("Older", top = 12.dp) }
        item(key = "fam:${section.instanceId}:${family.key}") {
            val multi = family.versions.size > 1
            val selectedMember = family.versions.firstOrNull { it.sameModelAs(highlighted) }
            val shown = selectedMember ?: family.latest
            val stateKey = "${section.instanceId}:${family.key}"
            val expanded = expandedFamilies[stateKey] ?: (selectedMember != null && selectedMember != family.latest)
            val title = if (multi) family.name else family.latest.label
            val pill = if (multi) VersionPillSpec(versionLabel(shown) ?: shown.label, expanded, "${family.name} versions, ${versionLabel(shown) ?: shown.label}") { expandedFamilies[stateKey] = !expanded } else null
            Column {
                ModelRow(title, null, null, shown.badge, selectedMember != null, enabled, "Model ${if (multi) family.name else family.latest.label}", pill, star(shown, shown.label), onClick = { pick(shown) })
                AnimatedVisibility(multi && expanded, enter = expandVertically(tween(180)) + fadeIn(tween(140)), exit = shrinkVertically(tween(180)) + fadeOut(tween(100))) {
                    FlowRow(Modifier.padding(start = 24.dp, end = 16.dp, top = 2.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        family.versions.forEachIndexed { vi, v -> VersionChip(family, v, vi == 0 && selectedMember != null && selectedMember != family.latest, v.sameModelAs(selectedMember), enabled, star(v, v.label).starred) { pick(v) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionChip(family: ModelFamily, version: ModelOption, offerLatest: Boolean, selected: Boolean, enabled: Boolean, favourite: Boolean, onClick: () -> Unit) {
    val c = Tf.colors
    val versionText = versionLabel(version) ?: version.label
    val text = versionText + (if (offerLatest) " · Latest" else "") + (if (version.isLegacy) " · Legacy" else "")
    FilterChip(
        selected, onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = TrefoilType.mono.copy(fontSize = 13.sp))
                version.badge?.let { NewBadge(it, selected, enabled) }
            }
        },
        enabled = enabled,
        modifier = Modifier.semantics { role = Role.RadioButton; contentDescription = "${family.name} version $versionText" },
        leadingIcon = if (selected) ({ Icon(TfIcons.Check, null, Modifier.size(14.dp)) }) else null,
        trailingIcon = if (favourite) ({ Icon(TfIcons.StarFilled, null, Modifier.size(12.dp), tint = if (enabled) c.amber else c.amber.copy(alpha = .4f)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent, labelColor = if (version.isLegacy) c.muted else c.text, iconColor = c.accent,
            selectedContainerColor = c.accentSoft, selectedLabelColor = c.accent, selectedLeadingIconColor = c.accent,
            disabledContainerColor = Color.Transparent, disabledLabelColor = c.muted, disabledLeadingIconColor = c.muted, disabledSelectedContainerColor = c.s2,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled, selected, borderColor = c.line, selectedBorderColor = c.accent, disabledBorderColor = c.line.copy(alpha = .5f), disabledSelectedBorderColor = c.line,
        ),
    )
}

/** The NEW-style badge; tinted so it stays visible on a selected (accentSoft) row and muted when the picker is disabled. */
@Composable
private fun NewBadge(badge: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = Tf.colors
    val background = when {
        !enabled -> c.s3
        selected -> c.accent.copy(alpha = .16f)
        else -> c.accentSoft
    }
    Text(
        badge.uppercase(), modifier.padding(start = 6.dp).height(18.dp).background(background, RoundedCornerShape(6.dp)).padding(horizontal = 5.dp).wrapContentHeight(Alignment.CenterVertically),
        style = TrefoilType.section.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp), color = if (enabled) c.accent else c.muted, maxLines = 1,
    )
}

@Composable
private fun ProviderTabs(catalog: ModelCatalog, active: String, onTab: (String) -> Unit) {
    val c = Tf.colors
    val keys = listOf(FAVOURITES_TAB) + catalog.sections.map { it.instanceId }
    PrimaryScrollableTabRow(
        selectedTabIndex = keys.indexOf(active).coerceAtLeast(0), edgePadding = 16.dp, containerColor = c.sheet, contentColor = c.text, minTabWidth = 48.dp,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier.tabIndicatorOffset(keys.indexOf(active).coerceAtLeast(0), matchContentSize = true), width = Dp.Unspecified, height = 3.dp, color = c.accent,
            )
        },
        divider = { HorizontalDivider(color = c.line) },
    ) {
        Tab(
            active == FAVOURITES_TAB, { onTab(FAVOURITES_TAB) }, Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = if (catalog.favorites.isEmpty()) "Favourites" else "Favourites, ${catalog.favorites.size} models"
            },
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val on = active == FAVOURITES_TAB
                Icon(if (on) TfIcons.StarFilled else TfIcons.Star, null, Modifier.size(18.dp), tint = if (on) c.amber else c.muted)
                if (catalog.favorites.isNotEmpty()) Text(catalog.favorites.size.toString(), style = TrefoilType.meta, color = if (on) c.text else c.muted)
            }
        }
        catalog.sections.forEach { section ->
            val on = active == section.instanceId
            Tab(on, { onTab(section.instanceId) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = section.title }) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderMark(section.driver, null, Modifier.size(18.dp))
                    Text(section.title, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = if (on) c.text else c.muted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A model or family row: optional provider mark, title (+ check, NEW badge) and optional subtitle, an optional version pill and the star.
 * The pill and star are siblings of the selectable body, so each keeps its own 48 dp target.
 */
@Composable
private fun ModelRow(
    title: String, subtitle: String?, driver: String?, badge: String?, selected: Boolean, enabled: Boolean, description: String,
    pill: VersionPillSpec?, star: StarSpec, onClick: () -> Unit,
) {
    val c = Tf.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().background(if (selected) (if (enabled) c.accentSoft else c.s2) else Color.Transparent, shape).padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp).clip(shape).selectable(selected, enabled, Role.RadioButton, onClick = onClick)
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp).semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (driver != null) { ProviderMark(driver, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = if (enabled) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) Icon(TfIcons.Check, null, Modifier.padding(start = 6.dp).size(16.dp), tint = if (enabled) c.accent else c.muted)
                    if (badge != null) NewBadge(badge, selected, enabled)
                }
                if (subtitle != null) Text(subtitle, style = TrefoilType.meta.copy(fontSize = 12.sp), color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (pill != null) VersionPill(pill, selected, enabled)
        StarButton(star)
    }
}

@Composable
private fun VersionPill(pill: VersionPillSpec, selected: Boolean, enabled: Boolean) {
    val c = Tf.colors
    val rotation by animateFloatAsState(if (pill.expanded) 180f else 0f, tween(180), label = "versionChevron")
    Box(
        Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = pill.toggle).semantics { contentDescription = pill.description; stateDescription = if (pill.expanded) "Expanded" else "Collapsed" },
        contentAlignment = Alignment.Center,
    ) {
        val pillShape = RoundedCornerShape(8.dp)
        val highlighted = selected && enabled
        Row(
            Modifier.height(28.dp).background(if (highlighted) c.accent.copy(alpha = .16f) else c.s2, pillShape).then(if (highlighted) Modifier else Modifier.border(1.dp, c.line, pillShape)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(pill.label, style = TrefoilType.mono.copy(fontSize = 12.sp), color = if (highlighted) c.accent else if (enabled) c.text else c.muted, maxLines = 1)
            Icon(TfIcons.ChevronDown, null, Modifier.size(12.dp).rotate(rotation), tint = if (highlighted) c.accent else c.muted)
        }
    }
}

@Composable
private fun StarButton(star: StarSpec) {
    val c = Tf.colors
    val description = if (star.starred) "Remove ${star.label} from favourites" else "Favourite ${star.label}"
    // The pop plays on a user tap only: it starts at rest (1f), so first composition and list recycling never animate.
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val animationsOff = rememberAnimationsOff()
    IconToggleButton(
        star.starred, {
            haptics.performHapticFeedback(if (star.starred) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            if (!animationsOff) scope.launch { scale.snapTo(.6f); scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 600f)) }
            star.toggle()
        },
        Modifier.size(48.dp).semantics { contentDescription = if (star.enabled) description else "$description, unavailable" }, enabled = star.enabled,
    ) {
        Icon(
            if (star.starred) TfIcons.StarFilled else TfIcons.Star, null, Modifier.size(18.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }.alpha(if (star.enabled) 1f else .3f),
            tint = if (star.starred) c.amber else c.muted,
        )
    }
}

@Composable
internal fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, Modifier.size(28.dp), tint = c.muted)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text, textAlign = TextAlign.Center)
        Text(detail, style = TrefoilType.preview, color = c.muted, textAlign = TextAlign.Center)
    }
}

/** Pinned reasoning chips and Fast switch for [model]; options are disabled until the pick has been applied. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlsDock(
    model: ModelOption, driver: String, values: ModelOptionValues, applied: Boolean, optionsEnabled: Boolean, threadSelection: Boolean, showFast: Boolean,
    onSetModelOption: (String, Any) -> Unit,
) {
    val c = Tf.colors
    val reasoning = model.reasoning
    val fast = model.fastMode?.takeIf { showFast }
    Column(Modifier.fillMaxWidth().background(if (c.isDark) c.s1 else c.bg).navigationBarsPadding()) {
        HorizontalDivider(color = c.line)
        val on = fastModeOn(model, values)
        val both = fast != null && reasoning != null
        // The Fast chip's 48 dp target already pads the header, so trim the row padding to match the no-Fast gap.
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = if (both) 2.dp else 10.dp, bottom = if (both) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(driver, null, Modifier.size(14.dp))
            Text(model.shortName ?: model.label, Modifier.padding(start = 6.dp).weight(1f), style = TrefoilType.meta.copy(fontWeight = FontWeight.SemiBold), color = if (optionsEnabled) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!applied) WorkingSpinner(Modifier.padding(horizontal = 8.dp), size = 12.dp)
            if (fast != null && reasoning != null) FilterChip(
                on, { onSetModelOption(fast.id, !on) }, label = { Text("Fast", style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)) }, enabled = optionsEnabled,
                modifier = Modifier.semantics { role = Role.Switch; contentDescription = fast.label.ifBlank { "Fast mode" } },
                leadingIcon = { Icon(TfIcons.Bolt, null, Modifier.size(15.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent, labelColor = c.muted, iconColor = c.muted, selectedContainerColor = c.accentSoft, selectedLabelColor = c.accent, selectedLeadingIconColor = c.accent,
                    disabledContainerColor = Color.Transparent, disabledLabelColor = c.muted, disabledLeadingIconColor = c.muted, disabledSelectedContainerColor = c.s2,
                ),
                border = FilterChipDefaults.filterChipBorder(optionsEnabled, on, borderColor = c.line, selectedBorderColor = c.accent, disabledBorderColor = c.line.copy(alpha = .5f), disabledSelectedBorderColor = c.line),
            )
        }
        if (reasoning != null) {
            val choices = reasoning.selectableChoices
            val choice = reasoningChoice(model, values, threadSelection)
            val listState = rememberLazyListState()
            val activeIndex = choices.indexOfFirst { it.id == choice?.id }
            // Start at the first chip; only when the active chip would sit under an edge fade, centre it (neighbours then peek under the fades).
            val fadePx = with(LocalDensity.current) { 24.dp.toPx() }
            var positioned by remember(model.instanceId, model.model) { mutableStateOf(false) }
            LaunchedEffect(model.instanceId, model.model, activeIndex) {
                if (activeIndex < 0) return@LaunchedEffect
                if (!positioned) { listState.scrollToItem(0); positioned = true }
                var item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex }
                if (item == null) { listState.scrollToItem(activeIndex); item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeIndex } }
                val viewport = listState.layoutInfo.viewportSize.width
                if (item != null && (item.offset < fadePx && listState.canScrollBackward || item.offset + item.size > viewport - fadePx && listState.canScrollForward)) {
                    listState.scrollBy(item.offset - (viewport - item.size) / 2f)
                }
            }
            val dockBackground = if (c.isDark) c.s1 else c.bg
            Box {
                LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(choices, key = { _, o -> o.id }) { index, option ->
                        val selected = option.id == choice?.id
                        FilterChip(
                            selected, { onSetModelOption(reasoning.id, option.id) }, label = { Text(option.label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)) }, enabled = optionsEnabled,
                            modifier = Modifier.semantics { role = Role.RadioButton; contentDescription = "${reasoning.label} ${option.label}" },
                            leadingIcon = { Icon(TfIcons.effort(effortBarCount(index, choices.size)), null, Modifier.size(14.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = c.sheet, labelColor = c.text, iconColor = c.muted,
                                selectedContainerColor = c.accentSoft, selectedLabelColor = c.accent, selectedLeadingIconColor = c.accent,
                                disabledContainerColor = c.sheet, disabledLabelColor = c.muted, disabledLeadingIconColor = c.muted,
                                disabledSelectedContainerColor = c.s2,
                            ),
                            border = FilterChipDefaults.filterChipBorder(optionsEnabled, selected, borderColor = c.line, selectedBorderColor = c.accent, disabledBorderColor = c.line.copy(alpha = .5f), disabledSelectedBorderColor = c.line),
                        )
                    }
                }
                // The solid start of the leading fade (≈11 dp) covers the previous chip's sliver left in the content padding.
                Box(Modifier.matchParentSize()) {
                    if (listState.canScrollBackward) Box(Modifier.align(Alignment.CenterStart).width(24.dp).fillMaxHeight().background(Brush.horizontalGradient(0f to dockBackground, 0.45f to dockBackground, 1f to Color.Transparent)))
                    if (listState.canScrollForward) Box(Modifier.align(Alignment.CenterEnd).width(24.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color.Transparent, dockBackground))))
                }
            }
            choice?.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (fast != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(on, optionsEnabled, Role.Switch) { onSetModelOption(fast.id, !on) }.padding(horizontal = 16.dp).semantics { contentDescription = fast.label.ifBlank { "Fast mode" } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(fast.label.ifBlank { "Fast mode" }, style = MaterialTheme.typography.bodyLarge, color = if (optionsEnabled) c.text else c.muted)
                    fast.description?.let { Text(it, style = TrefoilType.preview, color = c.muted) }
                }
                Switch(on, null, enabled = optionsEnabled)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

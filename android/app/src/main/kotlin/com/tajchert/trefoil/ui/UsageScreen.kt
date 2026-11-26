package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.adaptive.maxContentWidth
import com.tajchert.trefoil.ui.adaptive.handPointer
import com.tajchert.trefoil.ui.adaptive.focusRing

/**
 * Full-pane "Лимиты и использование" page: Limits | Cost | Tokens for the connected environment.
 * Limits come from [ClientState.providers]; cost/tokens from [usage]. While disconnected or without usage access
 * it shows cached data only and calls none of the fetch callbacks ([onRefresh], [onSelectRange], [onRetry]).
 */
@Composable
internal fun UsageScreen(
    state: ClientState,
    usage: UsageUiState,
    /** Null when the shell hosts this screen beside its navigation rail: no back arrow, and Back is the shell's. */
    onBack: (() -> Unit)?,
    onRefresh: () -> Unit,
    onSelectRange: (UsageRange) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
) {
    var tab by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }
    val c = Tf.colors
    val offlineNotice = usageOfflineNotice(state.connection, state.canReadUsage)
    val offline = offlineNotice != null
    // Offline the range chips only switch which cached report is shown; the ViewModel range (and its fetch) is untouched.
    var offlineRange by rememberSaveable(offline) { mutableStateOf<UsageRange?>(null) }
    val range = if (offline) offlineRange ?: usage.range else usage.range
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    // Laid out from this container's width, not the window: beside the rail it is narrower than the screen.
    BoxWithConstraints(modifier.fillMaxSize().background(c.bg)) {
        val containerWidth = maxWidth.value
        val mode = usageLayoutMode(containerWidth)
        val wide = mode == UsageLayoutMode.TwoColumn
        // From 600dp the page title is a pane title (the thread list's style); the phone keeps its row title.
        val paneTitle = mode != UsageLayoutMode.Tabs
        val setRange: (UsageRange) -> Unit = { if (offline) offlineRange = it else onSelectRange(it) }
        Column(Modifier.fillMaxSize()) {
            Row(
                (if (wide) Modifier.maxContentWidth(UsageLayoutSpec.TwoColumnMaxWidth.dp) else Modifier.fillMaxWidth())
                    .heightIn(min = 64.dp).padding(horizontal = if (wide) 12.dp else if (paneTitle && onBack == null) 8.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) TfIconButton(TfIcons.Back, "Back", onBack, iconSize = 22.dp, tint = c.text)
                Column(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 12.dp)) {
                    if (paneTitle) Text("Лимиты и использование", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.text)
                    else Text("Лимиты и использование", style = TrefoilType.rowTitle, color = c.text)
                    state.environment?.name?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (!offline) TfIconButton(TfIcons.Refresh, "Refresh usage", onRefresh, iconSize = 22.dp, tint = c.text)
            }
            if (!offline && (usage.limitsRefreshing || usage.refreshing)) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            HorizontalDivider(color = c.line)
            if (!wide) TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
                listOf("Limits", "Cost", "Tokens").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            offlineNotice?.let {
                Text(it, (if (wide) Modifier.maxContentWidth(UsageLayoutSpec.TwoColumnMaxWidth.dp) else Modifier.fillMaxWidth())
                    .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 10.dp), style = TrefoilType.meta, color = c.muted)
            }
            when (mode) {
                UsageLayoutMode.TwoColumn -> UsageTwoColumn(state, usage, range, offline, cost = tab != 2, onCost = { tab = if (it) 1 else 2 }, setRange, onRetry, Modifier.weight(1f))
                UsageLayoutMode.TabsWide ->
                    if (tab == 0) UsageLimits(state.providers, if (offline) null else usage.limitsError, offline, columns = usageLimitColumns(containerWidth, sidePaddingDp = UsageLayoutSpec.TabsWideSidePadding),
                        contentPadding = PaddingValues(UsageLayoutSpec.TabsWideSidePadding.dp))
                    else UsageHistory(usage, range, offline, tab == 1, setRange, onRetry, Modifier.fillMaxHeight().maxContentWidth(UsageLayoutSpec.HistoryColumnMax.dp),
                        wideCards = true, sidePadding = usageHistorySidePadding(containerWidth).dp)
                UsageLayoutMode.Tabs ->
                    if (tab == 0) UsageLimits(state.providers, if (offline) null else usage.limitsError, offline)
                    else UsageHistory(usage, range, offline, tab == 1, setRange, onRetry)
            }
        }
    }
}

/** Wide container: provider limits in their own column beside Cost | Tokens; the two columns scroll independently. */
@Composable
private fun UsageTwoColumn(
    state: ClientState, usage: UsageUiState, range: UsageRange, offline: Boolean, cost: Boolean, onCost: (Boolean) -> Unit,
    onSelectRange: (UsageRange) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = Tf.colors
    Row(modifier.maxContentWidth(UsageLayoutSpec.TwoColumnMaxWidth.dp).padding(horizontal = UsageLayoutSpec.ColumnGap.dp), horizontalArrangement = Arrangement.spacedBy(UsageLayoutSpec.ColumnGap.dp)) {
        // The column heading and the toolbar share one height and the same top and bottom space, so the first card of each column starts on one line.
        val toolbar = UsageLayoutSpec.ToolbarHeight.dp
        UsageLimits(state.providers, if (offline) null else usage.limitsError, offline, Modifier.width(UsageLayoutSpec.LimitsColumnWidth.dp).fillMaxHeight(),
            contentPadding = PaddingValues(top = 20.dp, bottom = 20.dp), title = "Provider limits", titleHeight = toolbar)
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val split = usageSplitsBreakdown(maxWidth.value)
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp).height(toolbar), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SingleChoiceSegmentedButtonRow {
                        listOf("Cost" to true, "Tokens" to false).forEachIndexed { index, (label, isCost) ->
                            SegmentedButton(
                                selected = cost == isCost, onClick = { onCost(isCost) }, shape = SegmentedButtonDefaults.itemShape(index, 2), icon = {},
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = c.accentSoft, activeContentColor = c.accent, activeBorderColor = c.accent.copy(alpha = .45f),
                                    inactiveContainerColor = Color.Transparent, inactiveContentColor = c.text, inactiveBorderColor = c.line,
                                ),
                                label = { Text(label) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UsageRange.entries.forEach { option -> TfChip(option.label, { onSelectRange(option) }, Modifier.minimumInteractiveComponentSize(), selected = range == option) }
                    }
                }
                UsageHistoryBody(usage, range, offline, cost, onRetry, split, Modifier.weight(1f))
            }
        }
    }
}

private sealed interface LimitEntry {
    data class Note(val text: String) : LimitEntry
    data class Card(val provider: ProviderInstance) : LimitEntry
}

/**
 * Provider limit cards. [columns] 1 is the phone list; more than one lays the cards out as a grid.
 * [title] adds a section label on top (the wide layout's column heading).
 */
@Composable
private fun UsageLimits(
    providers: List<ProviderInstance>, error: String?, offline: Boolean, modifier: Modifier = Modifier.fillMaxSize(),
    columns: Int = 1, contentPadding: PaddingValues = PaddingValues(16.dp), title: String? = null, titleHeight: Dp? = null,
) {
    val titleModifier = Modifier.padding(start = 2.dp).let { if (titleHeight != null) it.height(titleHeight) else it }
    val visible = providers.filter { it.enabled && it.usageLimits != null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val freshness = usageFreshness(visible.mapNotNull { it.usageLimits }, now)
    val leading = listOfNotNull(error, (if (offline) "No limits cached yet." else "No provider limits reported.").takeIf { visible.isEmpty() }).map { LimitEntry.Note(it) }
    val entries = leading + visible.map { LimitEntry.Card(it) } + listOfNotNull((freshness as? UsageFreshness.Shared)?.let { LimitEntry.Note(it.text) })
    val key: (LimitEntry) -> Any = { when (it) { is LimitEntry.Note -> "note:${it.text}"; is LimitEntry.Card -> it.provider.instanceId } }
    val content: @Composable (LimitEntry) -> Unit = { entry ->
        when (entry) {
            is LimitEntry.Note -> UsageNote(entry.text)
            is LimitEntry.Card -> ProviderLimitCard(entry.provider, freshness, now)
        }
    }
    if (columns <= 1) LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        title?.let { item { SectionLabel(it, titleModifier) } }
        items(entries, key = key) { content(it) }
    } else LazyVerticalStaggeredGrid(
        StaggeredGridCells.Fixed(columns), modifier, contentPadding = contentPadding,
        verticalItemSpacing = 12.dp, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        title?.let { item(span = StaggeredGridItemSpan.FullLine) { SectionLabel(it, titleModifier) } }
        staggeredItems(entries, key = key, span = { if (it is LimitEntry.Note) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane }) { content(it) }
    }
}

@Composable
private fun ProviderLimitCard(provider: ProviderInstance, freshness: UsageFreshness, now: Long) {
    val limits = requireNotNull(provider.usageLimits)
    Surface(Modifier.testTag("usage-limit-card"), shape = RoundedCornerShape(16.dp), color = Tf.colors.s1, border = BorderStroke(1.dp, Tf.colors.line)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProviderMark(provider.driver, null, Modifier.size(22.dp))
                Text(provider.displayName ?: provider.driver, style = TrefoilType.rowTitle, color = Tf.colors.text)
            }
            limits.windows.forEach { UsageWindow(it, now) }
            if (limits.windows.isEmpty()) limits.notice?.let { UsageNote(it) }
            if (freshness == UsageFreshness.PerProvider) usageCheckedAgo(limits.checkedAt, now)?.let { UsageNote(it) }
        }
    }
}

@Composable
private fun UsageWindow(window: UsageLimitWindow, now: Long) {
    val c = Tf.colors
    val remaining = window.remainingPercent
    val color = when { remaining <= 10 -> c.red; remaining <= 30 -> c.amber; else -> c.green }
    val reached = usageLimitReached(window)
    val remainingLabel = usageRemainingLabel(window)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(window.label, Modifier.weight(1f), style = TrefoilType.meta, color = c.text)
            Text(remainingLabel, style = TrefoilType.meta, color = color)
        }
        // A reached limit shows a full red bar: the pace tick says nothing once nothing is left.
        UsageBar(if (reached) 1f else remaining / 100f, color, Modifier.semantics { stateDescription = remainingLabel },
            if (reached) null else usageElapsedShare(window, now)?.let { (1 - it).toFloat() })
        if (reached) usageResetLabel(window, now)?.let { Text(it, style = TrefoilType.meta, color = c.text) }
        else listOfNotNull(usagePace(window, now), formatUsageReset(window, now)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { UsageNote(it) }
    }
}

@Composable
private fun UsageBar(share: Float, color: Color, modifier: Modifier = Modifier, tick: Float? = null) {
    val track = Tf.colors.s3
    val tickColor = Tf.colors.text
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(color, size = Size(size.width * share.coerceIn(0f, 1f), size.height), cornerRadius = radius)
        tick?.let {
            val x = (size.width * it.coerceIn(0f, 1f)).coerceIn(1.dp.toPx(), (size.width - 1.dp.toPx()).coerceAtLeast(1.dp.toPx()))
            drawLine(tickColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun UsageHistory(
    usage: UsageUiState, range: UsageRange, offline: Boolean, cost: Boolean, onSelectRange: (UsageRange) -> Unit, onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(), wideCards: Boolean = false, sidePadding: Dp = 16.dp,
) {
    // Tabbed layout from 600dp: the cards of the two-column layout, with Providers and Models side by side.
    val cards = if (wideCards) HistoryCards(split = true, dense = true, chartHeight = UsageLayoutSpec.ChartHeightTabsWide.dp) else null
    LazyColumn(modifier, contentPadding = if (wideCards) PaddingValues(horizontal = sidePadding, vertical = 16.dp) else PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(if (wideCards) 12.dp else 14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageRange.entries.forEach { option ->
                    TfChip(option.label, { onSelectRange(option) }, if (wideCards) Modifier.minimumInteractiveComponentSize() else Modifier, selected = range == option)
                }
            }
        }
        historyItems(usage, range, offline, cost, onRetry, cards)
    }
}

/** Cost/Tokens body of the wide layout: hero and chart in one card, Providers and Models as cards (side by side when [split]). */
@Composable
private fun UsageHistoryBody(usage: UsageUiState, range: UsageRange, offline: Boolean, cost: Boolean, onRetry: () -> Unit, split: Boolean, modifier: Modifier = Modifier) {
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        historyItems(usage, range, offline, cost, onRetry, HistoryCards(split = split, dense = false, chartHeight = UsageLayoutSpec.ChartHeightWide.dp))
    }
}

/** The card layout of Cost/Tokens (null: the phone's plain list). [dense] is the medium container's tighter spacing. */
private data class HistoryCards(val split: Boolean, val dense: Boolean, val chartHeight: Dp)

private fun LazyListScope.historyItems(usage: UsageUiState, range: UsageRange, offline: Boolean, cost: Boolean, onRetry: () -> Unit, cards: HistoryCards?) {
    val report = usage.reports[range]
    if (report == null) {
        when {
            offline -> item { UsageNote("Not available offline") }
            usage.loading -> item { CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "Loading usage" }) }
            usage.failure != null -> item { UsageFailureNote(usage, onRetry) }
            else -> item { UsageNote("No usage reported yet.") }
        }
        return
    }
    if (usage.failure != null && !offline) item { UsageFailureNote(usage, onRetry) }
    val updating = !offline && (report.refreshing || usage.refreshing)
    if (cards != null) {
        item { UsageHistoryCard(report, cost, range, updating, cards) }
        if (report.providers.isNotEmpty() || report.models.isNotEmpty()) item { UsageBreakdownCards(report, cost, cards) }
    } else {
        item { UsageHero(report, cost) }
        if (updating) item { UsageNote("Updating…") }
        if (range.days > 1) item { UsageDailyChart(report.days, cost, range) }
        if (report.providers.isNotEmpty()) item { SectionLabel("Providers") }
        items(report.providers) { UsageBreakdownRow(it, report, cost) }
        if (report.models.isNotEmpty()) item { SectionLabel("Models") }
        items(report.models) { UsageBreakdownRow(it, report, cost) }
    }
    if (report.unpricedRecords > 0) item { UsageNote("${report.unpricedRecords} unpriced records; estimated cost may be incomplete.") }
    if (report.pricingStatus == "unavailable") item { UsageNote("Model pricing is unavailable.") }
    items(report.sources.filter { it.status in setOf("missing", "partial", "failed") }) {
        UsageNote("${usageProviderLabel(it.provider)} usage: ${it.status}")
    }
}

@Composable
private fun UsageHero(report: UsageReport, cost: Boolean, modifier: Modifier = Modifier, wide: Boolean = false) {
    val c = Tf.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (cost) formatUsageUsd(report.totalCostUsd) else formatUsageTokens(report.tokens.total), style = MaterialTheme.typography.headlineLarge, color = c.text)
        if (cost && wide) {
            // One wrapped line instead of two stacked notes.
            Text(buildAnnotatedString {
                append("Estimated from model pricing")
                if (report.cacheSavingsUsd > 0) {
                    append(" · ")
                    withStyle(SpanStyle(color = c.green, fontWeight = FontWeight.Medium)) { append("Cache saved ${formatUsageUsd(report.cacheSavingsUsd)}") }
                }
            }, style = TrefoilType.meta, color = c.muted)
        } else if (cost) {
            UsageNote("Estimated from model pricing")
            if (report.cacheSavingsUsd > 0) UsageNote("Cache saved ${formatUsageUsd(report.cacheSavingsUsd)}")
        } else UsageNote("Input ${formatUsageTokens(report.tokens.input)} · Output ${formatUsageTokens(report.tokens.output)} · Cached ${formatUsageTokens(report.tokens.cachedInput)}")
    }
}

/** Hero and daily chart in one card; the day readout sits opposite the total instead of above the chart. */
@Composable
private fun UsageHistoryCard(report: UsageReport, cost: Boolean, range: UsageRange, updating: Boolean, cards: HistoryCards) {
    val format = rememberUsageDayFormat()
    val selection = rememberSaveable(range) { mutableStateOf<String?>(null) }
    Surface(Modifier.testTag("usage-hero-card"), shape = RoundedCornerShape(16.dp), color = Tf.colors.s1, border = BorderStroke(1.dp, Tf.colors.line)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (cards.dense) 16.dp else 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                UsageHero(report, cost, Modifier.weight(1f), wide = true)
                if (range.days > 1) UsageDayReadout(report.days.firstOrNull { it.day == selection.value }, cost, format, Modifier.padding(start = 12.dp))
            }
            if (updating) UsageNote("Updating…")
            if (range.days > 1) UsageDailyChart(report.days, cost, range, cards.chartHeight, selection = selection, readout = false, maxBarWidth = UsageLayoutSpec.ChartMaxBarWidth.dp)
        }
    }
}

/** What the daily chart says about the tapped day: a hint at rest, an accent pill once a day is selected. */
@Composable
private fun UsageDayReadout(day: UsageDayTotal?, cost: Boolean, format: UsageDayFormat, modifier: Modifier = Modifier) {
    val c = Tf.colors
    Text(
        day?.let { "${usageDayTitle(it.day, format.locale, format.patterns)} · ${usageDayAmount(it, cost)}" } ?: "Tap a bar to see a day's ${if (cost) "cost" else "tokens"}",
        modifier.semantics { liveRegion = LiveRegionMode.Polite }
            .then(if (day != null) Modifier.background(c.accentSoft, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp) else Modifier),
        style = TrefoilType.meta, color = if (day != null) c.accent else c.muted,
    )
}

private class UsageDayFormat(val locale: java.util.Locale, val patterns: UsageDatePatterns)

@Composable
private fun rememberUsageDayFormat(): UsageDayFormat {
    val locale = LocalLocale.current.platformLocale
    return remember(locale) {
        UsageDayFormat(locale, UsageDatePatterns(
            monthDay = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"),
            weekday = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEE"),
            weekdayMonthDay = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"),
        ))
    }
}

private fun usageDayAmount(day: UsageDayTotal, cost: Boolean) = if (cost) formatUsageUsd(day.costUsd) else formatUsageTokens(day.tokens)

/** Providers and Models as cards: side by side at equal height when [HistoryCards.split], stacked otherwise. */
@Composable
private fun UsageBreakdownCards(report: UsageReport, cost: Boolean, cards: HistoryCards) {
    val gap = if (cards.dense) 12.dp else 16.dp
    @Composable
    fun Card(label: String, rows: List<UsageBreakdown>, modifier: Modifier) {
        Surface(modifier.testTag("usage-breakdown-$label"), shape = RoundedCornerShape(16.dp), color = Tf.colors.s1, border = BorderStroke(1.dp, Tf.colors.line)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (cards.dense) 16.dp else 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionLabel(label)
                rows.forEach { UsageBreakdownRow(it, report, cost, singleLine = true) }
            }
        }
    }
    if (cards.split) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(gap)) {
        if (report.providers.isNotEmpty()) Card("Providers", report.providers, Modifier.weight(1f).fillMaxHeight())
        if (report.models.isNotEmpty()) Card("Models", report.models, Modifier.weight(1f).fillMaxHeight())
    } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        if (report.providers.isNotEmpty()) Card("Providers", report.providers, Modifier.fillMaxWidth())
        if (report.models.isNotEmpty()) Card("Models", report.models, Modifier.fillMaxWidth())
    }
}

@Composable
private fun UsageBreakdownRow(row: UsageBreakdown, report: UsageReport, cost: Boolean, singleLine: Boolean = false) {
    val value = if (cost) row.costUsd else row.tokens.total.toDouble()
    val total = if (cost) report.totalCostUsd else report.tokens.total.toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(usageProviderDriver(row.provider), null, Modifier.size(18.dp))
            Text(row.model ?: usageProviderLabel(row.provider), Modifier.weight(1f), style = TrefoilType.meta, color = Tf.colors.text,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE, overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip)
            Text(usageBreakdownValue(if (cost) formatUsageUsd(row.costUsd) else formatUsageTokens(row.tokens.total), value, total), style = TrefoilType.mono, color = Tf.colors.text)
        }
        UsageBar(usageShare(value, total), Tf.colors.green)
    }
}

@Composable
private fun UsageDailyChart(
    days: List<UsageDayTotal>, cost: Boolean, range: UsageRange, height: Dp = 112.dp,
    /** The selected day when the caller shows the readout itself ([readout] false); otherwise the chart keeps its own. */
    selection: MutableState<String?>? = null, readout: Boolean = true,
    /** Unspecified: bars take 70% of their slot. Wide containers cap them so the 7-day range does not draw blocks. */
    maxBarWidth: Dp = Dp.Unspecified,
) {
    val format = rememberUsageDayFormat()
    val locale = format.locale
    val patterns = format.patterns
    fun value(day: UsageDayTotal) = if (cost) day.costUsd else day.tokens.toDouble()
    fun amount(day: UsageDayTotal) = usageDayAmount(day, cost)
    val ownSelection = rememberSaveable(range) { mutableStateOf<String?>(null) }
    var selectedDay by (selection ?: ownSelection)
    val selected = days.firstOrNull { it.day == selectedDay }
    val max = days.maxOfOrNull { value(it) } ?: 0.0
    val peak = days.maxByOrNull { value(it) }?.takeIf { value(it) > 0 }
    val what = if (cost) "cost" else "tokens"
    val description = peak?.let { "Daily $what, ${range.label}. Peak ${usageDayTitle(it.day, locale, patterns)}: ${amount(it)}" } ?: "No daily usage"
    val color = Tf.colors.green
    val baseline = Tf.colors.line
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (readout) Text(
            selected?.let { "${usageDayTitle(it.day, locale, patterns)} · ${amount(it)}" } ?: "Tap a bar to see a day's $what",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = TrefoilType.meta, color = if (selected != null) Tf.colors.text else Tf.colors.muted,
        )
        Row(Modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
            days.forEach { day ->
                val isSelected = day.day == selectedDay
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .semantics { contentDescription = "${usageDayTitle(day.day, locale, patterns)}: ${amount(day)}"; this.selected = isSelected }
                        .focusRing().handPointer().clickable(onClickLabel = "Show value", role = Role.Button) { selectedDay = if (isSelected) null else day.day },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height))
                        val capped = maxBarWidth.isSpecified && maxBarWidth.toPx() < size.width * .7f
                        val barWidth = if (capped) maxBarWidth.toPx() else size.width * .7f
                        val left = if (capped) (size.width - barWidth) / 2f else size.width * .15f
                        val empty = value(day) <= 0
                        val height = if (empty) 3.dp.toPx() else (size.height * usageShare(value(day), max)).coerceAtLeast(3.dp.toPx())
                        val radius = CornerRadius(minOf(4.dp.toPx(), barWidth / 2, height))
                        val bar = Path().apply { addRoundRect(RoundRect(Rect(left, size.height - height, left + barWidth, size.height), radius, radius, CornerRadius.Zero, CornerRadius.Zero)) }
                        // Empty days keep a faint stub so the axis stays readable; with a selection the others step back.
                        drawPath(bar, color.copy(alpha = if (empty) .25f else if (selectedDay != null && !isSelected) .45f else 1f))
                    }
                }
            }
        }
        // The labels repeat what each bar already announces, so TalkBack skips them.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            if (range == UsageRange.Week) days.forEach {
                Text(usageDayLabel(it.day, range, locale, patterns), Modifier.weight(1f), style = TrefoilType.meta, color = Tf.colors.muted, textAlign = TextAlign.Center, maxLines = 1)
            } else {
                Text(days.firstOrNull()?.let { usageDayLabel(it.day, range, locale, patterns) }.orEmpty(), style = TrefoilType.meta, color = Tf.colors.muted)
                Text(days.lastOrNull()?.let { usageDayLabel(it.day, range, locale, patterns) }.orEmpty(), style = TrefoilType.meta, color = Tf.colors.muted)
            }
        }
    }
}

@Composable
private fun UsageFailureNote(usage: UsageUiState, onRetry: () -> Unit) {
    Column {
        UsageNote(when (usage.failure) {
            UsageFailure.NoPermission -> "This connection can't read usage. Pair again with a token that has diagnostics access."
            UsageFailure.NotConnected -> "Connect to this machine to view usage."
            UsageFailure.Unsupported -> "This T3 server doesn't report usage."
            UsageFailure.TimedOut -> usage.failureMessage ?: "Reading usage timed out. Try again."
            else -> usage.failureMessage ?: "This T3 server could not report usage."
        })
        if (usage.failure == UsageFailure.Failed || usage.failure == UsageFailure.TimedOut) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun UsageNote(text: String) { Text(text, style = TrefoilType.meta, color = Tf.colors.muted) }

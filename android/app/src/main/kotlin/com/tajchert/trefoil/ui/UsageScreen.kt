package com.tajchert.trefoil.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*

/**
 * Full-pane "Limits & usage" page: Limits | Cost | Tokens for the connected environment.
 * Limits come from [ClientState.providers]; cost/tokens from [usage]. While disconnected or without usage access
 * it shows cached data only and calls none of the fetch callbacks ([onRefresh], [onSelectRange], [onRetry]).
 */
@Composable
internal fun UsageScreen(
    state: ClientState,
    usage: UsageUiState,
    onBack: () -> Unit,
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
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(TfIcons.Back, "Back", Modifier.size(22.dp), tint = c.text) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Limits & usage", style = TrefoilType.rowTitle, color = c.text)
                state.environment?.name?.let { Text(it, style = TrefoilType.meta, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (!offline) IconButton(onClick = onRefresh) { Icon(TfIcons.Refresh, "Refresh usage", Modifier.size(22.dp), tint = c.text) }
        }
        if (!offline && (usage.limitsRefreshing || usage.refreshing)) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        HorizontalDivider(color = c.line)
        TabRow(selectedTabIndex = tab, containerColor = c.bg, contentColor = c.text) {
            listOf("Limits", "Cost", "Tokens").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        offlineNotice?.let { Text(it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), style = TrefoilType.meta, color = c.muted) }
        if (tab == 0) UsageLimits(state.providers, if (offline) null else usage.limitsError, offline)
        else UsageHistory(usage, range, offline, tab == 1, { if (offline) offlineRange = it else onSelectRange(it) }, onRetry)
    }
}

@Composable
private fun UsageLimits(providers: List<ProviderInstance>, error: String?, offline: Boolean) {
    val visible = providers.filter { it.enabled && it.usageLimits != null }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val freshness = usageFreshness(visible.mapNotNull { it.usageLimits }, now)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { item { UsageNote(it) } }
        if (visible.isEmpty()) item { UsageNote(if (offline) "No limits cached yet." else "No provider limits reported.") }
        items(visible, key = { it.instanceId }) { provider ->
            val limits = requireNotNull(provider.usageLimits)
            Surface(shape = RoundedCornerShape(16.dp), color = Tf.colors.s1, border = BorderStroke(1.dp, Tf.colors.line)) {
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
        (freshness as? UsageFreshness.Shared)?.let { item { UsageNote(it.text) } }
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
private fun UsageHistory(usage: UsageUiState, range: UsageRange, offline: Boolean, cost: Boolean, onSelectRange: (UsageRange) -> Unit, onRetry: () -> Unit) {
    val report = usage.reports[range]
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageRange.entries.forEach { option -> TfChip(option.label, { onSelectRange(option) }, selected = range == option) }
            }
        }
        if (report == null) {
            when {
                offline -> item { UsageNote("Not available offline") }
                usage.loading -> item { CircularProgressIndicator(Modifier.size(28.dp).semantics { contentDescription = "Loading usage" }) }
                usage.failure != null -> item { UsageFailureNote(usage, onRetry) }
                else -> item { UsageNote("No usage reported yet.") }
            }
        } else {
            if (usage.failure != null && !offline) item { UsageFailureNote(usage, onRetry) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (cost) formatUsageUsd(report.totalCostUsd) else formatUsageTokens(report.tokens.total), style = MaterialTheme.typography.headlineLarge, color = Tf.colors.text)
                    if (cost) {
                        UsageNote("Estimated from model pricing")
                        if (report.cacheSavingsUsd > 0) UsageNote("Cache saved ${formatUsageUsd(report.cacheSavingsUsd)}")
                    } else UsageNote("Input ${formatUsageTokens(report.tokens.input)} · Output ${formatUsageTokens(report.tokens.output)} · Cached ${formatUsageTokens(report.tokens.cachedInput)}")
                }
            }
            if (!offline && (report.refreshing || usage.refreshing)) item { UsageNote("Updating…") }
            if (range.days > 1) item { UsageDailyChart(report.days, cost, range) }
            if (report.providers.isNotEmpty()) item { SectionLabel("Providers") }
            items(report.providers) { UsageBreakdownRow(it, report, cost) }
            if (report.models.isNotEmpty()) item { SectionLabel("Models") }
            items(report.models) { UsageBreakdownRow(it, report, cost) }
            if (report.unpricedRecords > 0) item { UsageNote("${report.unpricedRecords} unpriced records; estimated cost may be incomplete.") }
            if (report.pricingStatus == "unavailable") item { UsageNote("Model pricing is unavailable.") }
            items(report.sources.filter { it.status in setOf("missing", "partial", "failed") }) {
                UsageNote("${usageProviderLabel(it.provider)} usage: ${it.status}")
            }
        }
    }
}

@Composable
private fun UsageBreakdownRow(row: UsageBreakdown, report: UsageReport, cost: Boolean) {
    val value = if (cost) row.costUsd else row.tokens.total.toDouble()
    val total = if (cost) report.totalCostUsd else report.tokens.total.toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProviderMark(usageProviderDriver(row.provider), null, Modifier.size(18.dp))
            Text(row.model ?: usageProviderLabel(row.provider), Modifier.weight(1f), style = TrefoilType.meta, color = Tf.colors.text)
            Text(usageBreakdownValue(if (cost) formatUsageUsd(row.costUsd) else formatUsageTokens(row.tokens.total), value, total), style = TrefoilType.mono, color = Tf.colors.text)
        }
        UsageBar(usageShare(value, total), Tf.colors.green)
    }
}

@Composable
private fun UsageDailyChart(days: List<UsageDayTotal>, cost: Boolean, range: UsageRange) {
    val locale = LocalLocale.current.platformLocale
    val patterns = remember(locale) {
        UsageDatePatterns(
            monthDay = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"),
            weekday = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEE"),
            weekdayMonthDay = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"),
        )
    }
    fun value(day: UsageDayTotal) = if (cost) day.costUsd else day.tokens.toDouble()
    fun amount(day: UsageDayTotal) = if (cost) formatUsageUsd(day.costUsd) else formatUsageTokens(day.tokens)
    var selectedDay by rememberSaveable(range) { mutableStateOf<String?>(null) }
    val selected = days.firstOrNull { it.day == selectedDay }
    val max = days.maxOfOrNull { value(it) } ?: 0.0
    val peak = days.maxByOrNull { value(it) }?.takeIf { value(it) > 0 }
    val what = if (cost) "cost" else "tokens"
    val description = peak?.let { "Daily $what, ${range.label}. Peak ${usageDayTitle(it.day, locale, patterns)}: ${amount(it)}" } ?: "No daily usage"
    val color = Tf.colors.green
    val baseline = Tf.colors.line
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            selected?.let { "${usageDayTitle(it.day, locale, patterns)} · ${amount(it)}" } ?: "Tap a bar to see a day's $what",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = TrefoilType.meta, color = if (selected != null) Tf.colors.text else Tf.colors.muted,
        )
        Row(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }) {
            days.forEach { day ->
                val isSelected = day.day == selectedDay
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .semantics { contentDescription = "${usageDayTitle(day.day, locale, patterns)}: ${amount(day)}"; this.selected = isSelected }
                        .clickable(onClickLabel = "Show value", role = Role.Button) { selectedDay = if (isSelected) null else day.day },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height))
                        val barWidth = size.width * .7f
                        val left = size.width * .15f
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

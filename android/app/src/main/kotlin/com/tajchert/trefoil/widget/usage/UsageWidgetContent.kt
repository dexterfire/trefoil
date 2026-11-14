package com.tajchert.trefoil.widget.usage

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.*
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.TextAlign
import com.tajchert.trefoil.R
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.formatUsageTokens
import com.tajchert.trefoil.ui.formatUsageUsd
import com.tajchert.trefoil.ui.usageProviderLabel
import com.tajchert.trefoil.ui.usageShare
import com.tajchert.trefoil.widget.*
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun UsageWidgetContent(kind: String, snapshot: UsageWidgetSnapshot?, prefs: Preferences) {
    val context = LocalContext.current
    val size = LocalSize.current
    val fit =
        UsageFit(size.width.value, size.height.value, context.resources.configuration.fontScale)
    val metric = if (kind == "summary") UsageMetric.Cost else UsageWidgetKeys.metric(prefs)
    val tab = if (metric == UsageMetric.Cost) UsageWidgetTab.Cost else UsageWidgetTab.Tokens
    TrefoilWidgetTheme {
        if (snapshot == null) WidgetMessage(WIDGET_SIGNED_OUT_TEXT, tab)
        else
            when (kind) {
                "summary" -> SummaryContent(snapshot, fit, tab)
                "chart" -> ChartContent(snapshot, prefs, metric, fit, tab)
                else -> TableContent(snapshot, prefs, metric, fit, tab)
            }
    }
}

/** The one large number of a widget, sized to fit [widthDp]. */
@Composable
private fun Hero(text: String, widthDp: Float, fit: UsageFit, maxSp: Int = 26) {
    WidgetText(
        text,
        color = WidgetPalette.text,
        sizeSp = usageNumberFont(text, widthDp, fit.fontScale, maxSp),
        bold = true,
    )
}

@Composable
private fun Missing(range: UsageRange) = WidgetStateLine("No data yet for ${range.label.lowercase()}")

@Composable
private fun SummaryContent(snapshot: UsageWidgetSnapshot, fit: UsageFit, tab: UsageWidgetTab) {
    val now = System.currentTimeMillis()
    val s = fit.scale
    val columns = fit.summaryColumns && !fit.tight
    val parts =
        widgetChromeParts(
            fit.height,
            fit.fontScale,
            36f * s + if (columns) 44f * s else 0f,
            widgetAlert(snapshot, snapshot.updatedAtMillis, now),
        )
    val body = widgetContentHeight(fit.height, fit.fontScale, parts)
    val width = fit.contentWidth
    WidgetScaffold(
        "Today",
        snapshot,
        tab,
        parts,
        snapshot.updatedAtMillis,
        now,
        bodyAlignment = if (parts.header) Alignment.Top else Alignment.CenterVertically,
    ) {
        val report = snapshot.reports[UsageRange.Today]
        if (report == null) Missing(UsageRange.Today)
        else {
            val cost = formatUsageUsd(report.totalCostUsd)
            val tokens = formatUsageTokens(report.tokens.total)
            val inline = width >= 150 * s
            val detail = if (width >= 230 * s) "est. · $tokens tokens" else if (inline) "est. · $tokens tok" else "est."
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Hero(cost, width - detail.length * 7f * s, fit, if (inline) 26 else 24)
                Spacer(GlanceModifier.width(6.dp))
                WidgetText(detail, GlanceModifier.padding(bottom = 3.dp))
            }
            // Narrow widgets move tokens to their own line when it fits under the hero.
            if (!inline && !columns && body >= 48f * s) WidgetText("$tokens tokens")
        }
        if (columns) {
            Row(GlanceModifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf(UsageRange.Week, UsageRange.Month).forEach { range ->
                    Column(GlanceModifier.defaultWeight()) {
                        WidgetText(range.label)
                        WidgetText(
                            snapshot.reports[range]?.let {
                                formatUsageUsd(it.totalCostUsd) +
                                    if (width >= 260 * s) " · ${formatUsageTokens(it.tokens.total)} tok" else ""
                            } ?: "—",
                            color = WidgetPalette.text,
                            sizeSp = WIDGET_TITLE_SP,
                        )
                    }
                }
            }
            val extras = if (fit.expanded) 4 * 16f * s else 0f
            val bars = body - 36f * s - 44f * s - extras - 8f
            snapshot.reports[UsageRange.Week]?.let { report ->
                if (bars >= 20f) ChartBars(report, UsageMetric.Cost, 7, bars.coerceAtMost(48f), false, GlanceModifier.height(bars.coerceAtMost(48f).dp + 8.dp))
            }
            if (fit.expanded) {
                snapshot.reports[UsageRange.Month]?.let { report ->
                    WidgetText("Cache saved ${formatUsageUsd(report.cacheSavingsUsd)} · 30 days")
                    sortedUsage(report, UsageMetric.Cost, UsageGrouping.Providers).firstOrNull()?.let {
                        WidgetText("Top provider · ${usageProviderLabel(it.provider)}")
                    }
                    sortedUsage(report, UsageMetric.Cost, UsageGrouping.Models).firstOrNull()?.let {
                        WidgetText("Top model · ${it.model ?: it.provider}")
                    }
                    PricingNote(report)
                }
            }
        }
    }
}

private fun selectAction(widget: String, field: String, value: String): Action =
    actionRunCallback<SelectUsageWidgetAction>(
        actionParametersOf(
            UsageActionKeys.widget to widget,
            UsageActionKeys.field to field,
            UsageActionKeys.value to value,
        )
    )

private fun metricPills(widget: String, metric: UsageMetric) =
    UsageMetric.entries.map { WidgetPill(it.name, metric == it, selectAction(widget, "metric", it.name)) }

/** Pill groups on one row where they fit, wrapping otherwise. Returns nothing when [groups] is empty. */
@Composable
private fun PillRows(groups: List<List<WidgetPill>>, fit: UsageFit) {
    val rows = packPillGroups(groups.map { group -> widgetPillGroupWidth(group.map { it.label }, fit.fontScale) }, fit.contentWidth)
    rows.forEach { row ->
        Row(GlanceModifier.fillMaxWidth().padding(bottom = WIDGET_PILL_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            row.forEachIndexed { position, index ->
                if (position > 0) Spacer(GlanceModifier.width(WIDGET_PILL_GAP_DP.dp))
                WidgetPillGroup(groups[index])
            }
        }
    }
}

private fun pillRowCount(groups: List<List<String>>, fit: UsageFit): Int =
    packPillGroups(groups.map { widgetPillGroupWidth(it, fit.fontScale) }, fit.contentWidth).size

@Composable
private fun ChartContent(
    snapshot: UsageWidgetSnapshot,
    prefs: Preferences,
    metric: UsageMetric,
    fit: UsageFit,
    tab: UsageWidgetTab,
) {
    val now = System.currentTimeMillis()
    val s = fit.scale
    val selected =
        UsageWidgetKeys.range(prefs).let { if (it == UsageRange.Today) UsageRange.Week else it }
    val range = if (fit.chartDays == 7) UsageRange.Week else selected
    val groups = buildList {
        if (fit.controls && fit.chartDays == 30)
            add(listOf(UsageRange.Week, UsageRange.Month).map {
                WidgetPill(if (it == UsageRange.Week) "7d" else "30d", range == it, selectAction("chart", "range", it.name))
            })
        if (fit.controls) add(metricPills("chart", metric))
    }
    val controlsDp = pillRowCount(groups.map { g -> g.map { it.label } }, fit).let { if (groups.isEmpty()) 0f else it * (WIDGET_PILL_ROW_DP + WIDGET_PILL_GAP_DP) }
    val labels = fit.barLabels && range.days == 7
    val labelsDp = if (labels) 18f * s else 0f
    val totalDp = 32f * s
    val parts =
        widgetChromeParts(fit.height, fit.fontScale, controlsDp + totalDp + labelsDp + 40f, widgetAlert(snapshot, snapshot.updatedAtMillis, now))
    val barArea =
        (widgetContentHeight(fit.height, fit.fontScale, parts) - controlsDp - totalDp - labelsDp - 10f).coerceIn(4f, 160f)
    WidgetScaffold(
        if (range == UsageRange.Week) "Last 7 days" else "Last 30 days",
        snapshot,
        tab,
        parts,
        snapshot.updatedAtMillis,
        now,
    ) {
        PillRows(groups, fit)
        val report = snapshot.reports[range]
        if (report == null) Missing(range)
        else {
            val total = metric.format(metric.total(report))
            val peak = report.days.maxOfOrNull { metric.value(it) } ?: 0.0
            val detail =
                if (fit.tight) if (metric == UsageMetric.Cost) "est." else "tokens"
                else "${if (metric == UsageMetric.Cost) "est." else "tokens"} · peak ${metric.format(peak)}"
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Hero(total, fit.contentWidth - detail.length * 7f * s, fit)
                Spacer(GlanceModifier.width(6.dp))
                WidgetText(detail, GlanceModifier.padding(bottom = 3.dp))
            }
            if (report.days.isEmpty()) WidgetStateLine("No daily usage yet")
            else ChartBars(report, metric, range.days, barArea, labels, GlanceModifier.defaultWeight())
        }
    }
}

/**
 * Accent bars with gaps and rounded tops: past days lighter, the latest day full strength, empty
 * days a short stub. Day labels sit outside the bar area so they never clip. Thirty bars are nested
 * in three groups because each RemoteViews container has at most ten children.
 */
@Composable
private fun ChartBars(
    report: UsageReport,
    metric: UsageMetric,
    count: Int,
    barAreaDp: Float,
    labels: Boolean,
    modifier: GlanceModifier,
) {
    val days = report.days.takeLast(count)
    val max = days.maxOfOrNull { metric.value(it) } ?: 0.0
    val latest = days.lastOrNull()?.day
    val today =
        try {
            Instant.ofEpochMilli(System.currentTimeMillis())
                .atZone(ZoneId.of(report.timeZone))
                .toLocalDate()
                .toString()
        } catch (_: Exception) {
            ""
        }
    val gap = if (count > 10) 1.dp else 3.dp
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Bottom) {
            days.chunked(10).forEach { group ->
                Row(GlanceModifier.defaultWeight().fillMaxHeight(), verticalAlignment = Alignment.Bottom) {
                    group.forEach { day ->
                        val value = metric.value(day)
                        Box(
                            GlanceModifier.defaultWeight().fillMaxHeight().padding(horizontal = gap).semantics {
                                contentDescription =
                                    "${day.day}: ${metric.format(value)}${if (day.day == today) ", today" else ""}"
                            },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Image(
                                ImageProvider(R.drawable.widget_bar),
                                null,
                                GlanceModifier.fillMaxWidth()
                                    .height(if (value <= 0) 3.dp else barHeight(value, max, barAreaDp).coerceAtLeast(3f).dp),
                                contentScale = ContentScale.FillBounds,
                                colorFilter =
                                    ColorFilter.tint(
                                        when {
                                            value <= 0 -> WidgetPalette.track
                                            day.day == latest -> WidgetPalette.accent
                                            else -> WidgetPalette.pastBar
                                        }
                                    ),
                            )
                        }
                    }
                }
            }
        }
        if (labels)
            Row(GlanceModifier.fillMaxWidth().padding(top = 2.dp)) {
                days.forEach { day ->
                    WidgetText(day.day.takeLast(2), GlanceModifier.defaultWeight(), align = TextAlign.Center)
                }
            }
    }
}

@Composable
private fun TableContent(
    snapshot: UsageWidgetSnapshot,
    prefs: Preferences,
    metric: UsageMetric,
    fit: UsageFit,
    tab: UsageWidgetTab,
) {
    val now = System.currentTimeMillis()
    val s = fit.scale
    val range = UsageWidgetKeys.range(prefs)
    val grouping = UsageWidgetKeys.grouping(prefs)
    val groups =
        if (!fit.controls) emptyList()
        else
            listOf(
                UsageRange.entries.map {
                    WidgetPill(
                        when (it) {
                            UsageRange.Today -> "Today"
                            UsageRange.Week -> "7d"
                            UsageRange.Month -> "30d"
                        },
                        range == it,
                        selectAction("table", "range", it.name),
                    )
                },
                metricPills("table", metric),
                UsageGrouping.entries.map { WidgetPill(it.name, grouping == it, selectAction("table", "grouping", it.name)) },
            )
    val controlsDp =
        if (groups.isEmpty()) 0f
        else pillRowCount(groups.map { g -> g.map { it.label } }, fit) * (WIDGET_PILL_ROW_DP + WIDGET_PILL_GAP_DP)
    val parts = widgetChromeParts(fit.height, fit.fontScale, controlsDp + 40f * s, widgetAlert(snapshot, snapshot.updatedAtMillis, now))
    WidgetScaffold(
        if (fit.controls) "Top usage" else "Top usage · ${range.label}",
        snapshot,
        tab,
        parts,
        snapshot.updatedAtMillis,
        now,
        titleAction = if (fit.controls) null else selectAction("table", "cycle", "next"),
    ) {
        PillRows(groups, fit)
        // Without pills the header title cycles the range; keep that target when the header is dropped.
        val rangeLine = !fit.controls && !parts.header
        if (rangeLine)
            WidgetText(
                range.label,
                GlanceModifier.fillMaxWidth().clickable(selectAction("table", "cycle", "next")),
                sizeSp = WIDGET_NOTE_SP,
            )
        val report = snapshot.reports[range]
        if (report == null) Missing(range)
        else {
            val rows = sortedUsage(report, metric, grouping)
            if (rows.isEmpty()) WidgetStateLine("No usage reported yet")
            else {
                if (fit.expanded) TableColumns(fit)
                val body = widgetContentHeight(fit.height, fit.fontScale, parts) - controlsDp - if (rangeLine) 18f * s else 0f
                val visible = if (fit.controls) rows else rows.take(minOf(3, fit.rows(body)))
                LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                    items(visible, itemId = ::usageItemId) { row -> TableRow(row, report, metric, fit) }
                }
            }
            if (fit.expanded) PricingNote(report)
        }
    }
}

@Composable
private fun TableColumns(fit: UsageFit) {
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 2.dp)) {
        WidgetText("Name", GlanceModifier.defaultWeight())
        WidgetText("Tokens", GlanceModifier.width((58 * fit.fontScale).dp), align = TextAlign.End)
        WidgetText("Est. cost", GlanceModifier.width((70 * fit.fontScale).dp), align = TextAlign.End)
        WidgetText("Records", GlanceModifier.width((58 * fit.fontScale).dp), align = TextAlign.End)
    }
}

@Composable
private fun TableRow(row: UsageBreakdown, report: UsageReport, metric: UsageMetric, fit: UsageFit) {
    val tab = if (metric == UsageMetric.Cost) UsageWidgetTab.Cost else UsageWidgetTab.Tokens
    Column(
        GlanceModifier.fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(openUsageAction(LocalContext.current, tab))
    ) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val driver = if (row.provider == "claude") "claudeAgent" else row.provider
            Image(
                ImageProvider(widgetProviderIcon(driver) ?: R.drawable.widget_usage_provider),
                row.provider,
                GlanceModifier.size(16.dp),
                colorFilter = ColorFilter.tint(WidgetPalette.text),
            )
            WidgetText(
                row.model ?: usageProviderLabel(row.provider),
                GlanceModifier.defaultWeight().padding(horizontal = 8.dp),
                color = WidgetPalette.text,
                sizeSp = WIDGET_TITLE_SP,
            )
            if (fit.expanded) {
                WidgetText(formatUsageTokens(row.tokens.total), GlanceModifier.width((58 * fit.fontScale).dp), WidgetPalette.text, WIDGET_TITLE_SP, align = TextAlign.End)
                WidgetText(formatUsageUsd(row.costUsd), GlanceModifier.width((70 * fit.fontScale).dp), WidgetPalette.text, WIDGET_TITLE_SP, align = TextAlign.End)
                WidgetText(row.records.toString(), GlanceModifier.width((58 * fit.fontScale).dp), align = TextAlign.End)
            } else
                WidgetText(
                    metric.format(metric.value(row)) +
                        if (metric == UsageMetric.Cost && fit.width >= 280 * fit.fontScale) " est." else "",
                    color = WidgetPalette.text,
                    sizeSp = WIDGET_TITLE_SP,
                    bold = true,
                    align = TextAlign.End,
                )
        }
        Spacer(GlanceModifier.height(4.dp))
        Box(GlanceModifier.fillMaxWidth().padding(start = 24.dp)) {
            WidgetMeter(usageShare(metric.value(row), metric.total(report)), WidgetPalette.accent, fit.contentWidth - 24f, heightDp = 3)
        }
    }
}

@Composable
private fun PricingNote(report: UsageReport) {
    if (report.unpricedRecords > 0)
        WidgetText("${report.unpricedRecords} unpriced records · cost may be incomplete")
    else if (report.pricingStatus == "unavailable") WidgetText("Model pricing unavailable")
}

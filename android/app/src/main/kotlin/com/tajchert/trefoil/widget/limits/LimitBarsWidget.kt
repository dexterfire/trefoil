package com.tajchert.trefoil.widget.limits

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.ui.usagePace
import com.tajchert.trefoil.widget.*

class LimitBarsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = UsageWidgetStore.get(context)
        store.read()
        provideContent {
            val snapshot by store.snapshot.collectAsState()
            TrefoilWidgetTheme { LimitBarsContent(snapshot) }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideContent {
        TrefoilWidgetTheme { LimitBarsContent(UsageWidgetSamples.snapshot()) }
    }
}

@Composable
internal fun LimitBarsContent(
    snapshot: UsageWidgetSnapshot?,
    now: Long = System.currentTimeMillis(),
) {
    if (snapshot == null) {
        WidgetMessage(WIDGET_SIGNED_OUT_TEXT, UsageWidgetTab.Limits)
        return
    }
    val size = LocalSize.current
    val scale = LocalContext.current.resources.configuration.fontScale
    val compact = size.height.value < 150 * scale || size.width.value < 150
    val updatedAt = snapshot.limitsUpdatedAtMillis ?: snapshot.updatedAtMillis
    val alert = widgetAlert(snapshot, updatedAt, now)
    if (compact) {
        // Two tightest limits by default; the header appears once a row fits under it.
        val parts = widgetChromeParts(size.height.value, scale, 2 * compactLimitRowDp(scale), alert)
        val gauges =
            compactGauges(
                snapshot.providers,
                compactLimitCapacity(widgetContentHeight(size.height.value, scale, parts), scale),
                now,
            )
        WidgetScaffold(
            "Lowest limits",
            snapshot,
            UsageWidgetTab.Limits,
            parts,
            updatedAt,
            now,
            bodyAlignment = Alignment.CenterVertically,
        ) {
            if (gauges.isEmpty())
                WidgetStateLine(
                    snapshot.providers.firstOrNull()?.let(::limitNotice) ?: "No provider limits reported"
                )
            gauges.forEach { CompactLimitRow(it, now, size.width.value, scale) }
        }
        return
    }
    val parts = widgetChromeParts(size.height.value, scale, 60f * scale, alert)
    WidgetScaffold("Limits", snapshot, UsageWidgetTab.Limits, parts, updatedAt, now) {
        if (snapshot.providers.isEmpty()) WidgetStateLine("No provider limits reported")
        Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            val columns = if (size.width.value >= 400) 2 else 1
            val columnWidth = (widgetContentWidth(size.width.value) - (columns - 1) * 16f) / columns
            repeat(columns) { column ->
                if (column > 0) Spacer(GlanceModifier.width(16.dp))
                ProviderBarList(
                    snapshot.providers.filterIndexed { index, _ -> index % columns == column },
                    now,
                    GlanceModifier.defaultWeight(),
                    size.width.value / columns >= 250 && scale <= 1.3f,
                    columnWidth,
                )
            }
        }
    }
}

/** "12% left" when the number has room, otherwise "12%". */
private fun limitValueText(window: UsageLimitWindow, now: Long, long: Boolean): String =
    if (hasReset(window, now)) "Reset" else if (!window.usedPercent.isFinite()) "—"
    else if (long) "${window.remainingPercent}% left" else "${window.remainingPercent}%"

private fun limitColor(window: UsageLimitWindow, now: Long) =
    if (hasReset(window, now) || !window.usedPercent.isFinite()) WidgetPalette.muted
    else WidgetPalette.remaining(window.remainingPercent)

@Composable
private fun CompactLimitRow(gauge: LimitGauge, now: Long, widthDp: Float, scale: Float) {
    val window = gauge.window
    val color = limitColor(window, now)
    val meter = widthDp >= 200 * scale
    val hasMark = widgetProviderIcon(gauge.provider.driver) != null
    Row(
        GlanceModifier.fillMaxWidth().height(compactLimitRowDp(scale).dp).semantics {
            contentDescription =
                "${gauge.provider.displayName}, ${window.label}, " +
                    if (hasReset(window, now)) "Reset since last check"
                    else if (!window.usedPercent.isFinite()) "Unavailable"
                    else "${window.remainingPercent}% left"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WidgetProviderMark(gauge.provider.driver)
        WidgetText(
            if (hasMark) window.label else "${gauge.provider.displayName} · ${window.label}",
            GlanceModifier.defaultWeight(),
            color = WidgetPalette.text,
            sizeSp = WIDGET_NOTE_SP,
        )
        if (meter) {
            Spacer(GlanceModifier.width(8.dp))
            WidgetMeter(
                if (hasReset(window, now) || !window.usedPercent.isFinite()) 0f else window.remainingPercent / 100f,
                color,
                48f,
                GlanceModifier.width(48.dp),
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        WidgetText(
            limitValueText(window, now, widthDp >= 260 * scale),
            color = color,
            sizeSp = WIDGET_TITLE_SP,
            bold = true,
        )
    }
}

// Canonical scrolling-list pattern: headers and windows are separate stable lazy items so no
// provider can overflow Glance's ten-child container limit, even with new server window kinds.
private data class BarEntry(
    val provider: UsageWidgetProvider,
    val window: UsageLimitWindow? = null,
)

@Composable
private fun ProviderBarList(
    providers: List<UsageWidgetProvider>,
    now: Long,
    modifier: GlanceModifier,
    pace: Boolean,
    widthDp: Float,
) {
    val openUsage = openUsageAction(LocalContext.current, UsageWidgetTab.Limits)
    val entries =
        providers.flatMap { provider ->
            listOf(BarEntry(provider)) +
                provider.limits?.windows.orEmpty().map { BarEntry(provider, it) }
        }
    LazyColumn(modifier) {
        itemsIndexed(
            entries,
            itemId = { _, entry ->
                (entry.provider.instanceId + ":" + (entry.window?.id ?: "header")).fold(17L) {
                    hash,
                    char ->
                    hash * 31 + char.code
                } and Long.MAX_VALUE
            },
        ) { _, entry ->
            Column(GlanceModifier.fillMaxWidth().clickable(openUsage).padding(vertical = 4.dp)) {
                if (entry.window != null)
                    LimitBar(entry.window, now, entry.window.label, widthDp, showPace = pace)
                else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WidgetProviderMark(entry.provider.driver)
                        WidgetText(entry.provider.displayName, color = WidgetPalette.text, sizeSp = WIDGET_TITLE_SP, bold = true)
                    }
                    if (entry.provider.limits?.windows.isNullOrEmpty())
                        WidgetText(limitNotice(entry.provider))
                }
            }
        }
    }
}

@Composable
private fun LimitBar(
    window: UsageLimitWindow,
    now: Long,
    label: String,
    widthDp: Float,
    showPace: Boolean = false,
) {
    val expired = hasReset(window, now)
    val stale = expired || !window.usedPercent.isFinite()
    val color = limitColor(window, now)
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WidgetText(
                label,
                GlanceModifier.defaultWeight(),
                color = if (stale) WidgetPalette.muted else WidgetPalette.text,
                sizeSp = WIDGET_TITLE_SP,
            )
            WidgetText(
                if (expired) "Reset since last check"
                else if (stale) "Unavailable" else "${window.remainingPercent}% left",
                color = color,
                sizeSp = WIDGET_TITLE_SP,
                bold = !stale,
            )
        }
        Spacer(GlanceModifier.height(4.dp))
        WidgetMeter(if (stale) 0f else window.remainingPercent / 100f, color, widthDp, heightDp = 6)
        if (!expired) {
            val reset = limitResetText(window, now)
            WidgetText(
                if (showPace && !stale)
                    listOfNotNull(reset, usagePace(window, now)).joinToString(" · ")
                else reset,
                GlanceModifier.padding(top = 2.dp),
            )
        }
    }
}

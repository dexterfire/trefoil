package com.tajchert.trefoil.widget.limits

import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.ui.formatUsageReset
import com.tajchert.trefoil.widget.UsageWidgetProvider
import com.tajchert.trefoil.widget.WidgetChromeParts
import com.tajchert.trefoil.widget.WIDGET_HEADER_DP
import com.tajchert.trefoil.widget.WIDGET_PAD_V
import com.tajchert.trefoil.widget.widgetFooterDp
import com.tajchert.trefoil.widget.widgetContentHeight
import com.tajchert.trefoil.widget.widgetContentWidth
import java.time.Instant
import kotlin.math.floor

internal data class LimitGauge(val provider: UsageWidgetProvider, val window: UsageLimitWindow)

internal fun hasReset(window: UsageLimitWindow, now: Long): Boolean =
    runCatching { window.resetsAt?.let { Instant.parse(it).toEpochMilli() <= now } ?: false }
        .getOrDefault(false)

internal fun limitResetText(window: UsageLimitWindow, now: Long, compact: Boolean = false): String =
    if (hasReset(window, now)) "Reset since last check"
    else
        formatUsageReset(window, now)?.let { if (compact) it.removePrefix("resets in ") else it }
            ?: "Reset time unavailable"

/** Expired measurements never displace a valid constrained window. Ties retain server order. */
internal fun orderedGauges(providers: List<UsageWidgetProvider>, now: Long): List<LimitGauge> =
    providers
        .flatMap { provider -> provider.limits?.windows.orEmpty().map { LimitGauge(provider, it) } }
        .sortedWith(
            compareBy<LimitGauge> { hasReset(it.window, now) || !it.window.usedPercent.isFinite() }
                .thenBy { it.window.remainingPercent }
        )

internal fun limitNotice(provider: UsageWidgetProvider): String =
    when (provider.limits?.unavailableReason) {
        "unsupported" -> provider.limits.unavailableMessage ?: "No subscription limits"
        else -> provider.limits?.notice ?: "Limits not available"
    }

/** Height of one compact limit row (mark, label, meter, percent on one line). */
internal fun compactLimitRowDp(fontScale: Float): Float = 20f * fontScale.coerceAtLeast(1f)

/** Compact rows that fit in [bodyDp]: the two tightest limits at the default 1-row size, up to three. */
internal fun compactLimitCapacity(bodyDp: Float, fontScale: Float): Int =
    floor(bodyDp.coerceAtLeast(0f) / compactLimitRowDp(fontScale)).toInt().coerceIn(1, 3)

/** The tightest limits across all providers (expired or unavailable windows last). */
internal fun compactGauges(providers: List<UsageWidgetProvider>, capacity: Int, now: Long): List<LimitGauge> =
    orderedGauges(providers, now).take(capacity.coerceAtLeast(1))

internal data class GaugeGrid(
    val columns: Int,
    val rows: Int,
    val showHeader: Boolean,
    val detailed: Boolean,
    val showFooter: Boolean,
) {
    val capacity: Int
        get() = columns * rows

    fun visibleCount(total: Int): Int =
        if (total > capacity && capacity > 1) capacity - 1 else minOf(total, capacity)
}

/** Ring cell height: gauge plus two 12sp label lines. */
internal fun gaugeCellDp(fontScale: Float): Float = 80f * fontScale.coerceAtLeast(1f)

/** Rows win over the footer: the header shows when one row fits under it, the footer only in leftover space. */
internal fun gaugeGrid(widthDp: Float, heightDp: Float, fontScale: Float): GaugeGrid {
    val scale = fontScale.coerceAtLeast(1f)
    val cell = gaugeCellDp(fontScale)
    val header = widthDp >= 120 && heightDp >= WIDGET_HEADER_DP + WIDGET_PAD_V + cell
    val body = widgetContentHeight(heightDp, fontScale, WidgetChromeParts(header, footer = false))
    val columns = floor(widgetContentWidth(widthDp) / (72 * scale)).toInt().coerceIn(1, 5)
    val rows = floor(body / cell).toInt().coerceIn(1, 8)
    return GaugeGrid(
        columns,
        rows,
        header,
        widthDp >= 144 * scale && heightDp >= 108 * scale,
        header && body - rows * cell >= widgetFooterDp(fontScale),
    )
}

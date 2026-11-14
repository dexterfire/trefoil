package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun formatUsageUsd(value: Double): String = when {
    value == 0.0 -> "$0"
    value > 0 && value < 0.01 -> "<$0.01"
    else -> String.format(Locale.US, "$%.2f", value)
}

internal fun formatUsageTokens(value: Long): String {
    val (divisor, suffix) = when {
        value >= 1_000_000_000 -> 1_000_000_000.0 to "B"
        value >= 1_000_000 -> 1_000_000.0 to "M"
        value >= 1_000 -> 1_000.0 to "K"
        else -> return value.toString()
    }
    return String.format(Locale.US, "%.1f%s", value / divisor, suffix).replace(".0$suffix", suffix)
}

// Adapted from T3 Code packages/shared/src/usageLimits.ts (MIT; see protocol/T3-LICENSE.txt).
private fun resetMillis(window: UsageLimitWindow): Long? = try {
    window.resetsAt?.let { Instant.parse(it).toEpochMilli() }
} catch (_: Exception) { null }

internal fun usageElapsedShare(window: UsageLimitWindow, now: Long): Double? {
    val reset = resetMillis(window) ?: return null
    val length = (window.windowDurationMins ?: return null) * 60_000.0
    if (length <= 0) return null
    return ((length - (reset - now)) / length).coerceIn(0.0, 1.0)
}

internal fun usagePace(window: UsageLimitWindow, now: Long): String? {
    val elapsed = usageElapsedShare(window, now) ?: return null
    val gap = window.usedPercent - elapsed * 100
    return when { gap > 5 -> "ahead of pace"; gap < -5 -> "under pace"; else -> "on pace" }
}

internal fun formatUsageDuration(ms: Long): String {
    val minutes = ms.coerceAtLeast(0) / 60_000
    val days = minutes / 1440
    val hours = minutes % 1440 / 60
    return when { days > 0 -> "${days}d ${hours}h"; hours > 0 -> "${hours}h ${minutes % 60}m"; else -> "${minutes}m" }
}

internal fun formatUsageReset(window: UsageLimitWindow, now: Long): String? {
    val reset = resetMillis(window) ?: return null
    return when { reset <= now -> "resets now"; reset - now < 60_000 -> "Resets momentarily"; else -> "resets in ${formatUsageDuration(reset - now)}" }
}

/** Usage page state owned by the ViewModel. [reports] keeps the last answer per range so switching back is instant. */
data class UsageUiState(
    val range: UsageRange = UsageRange.Week,
    val reports: Map<UsageRange, UsageReport> = emptyMap(),
    /** No report yet for [range] and a request is in flight. */
    val loading: Boolean = false,
    /** A report is shown but a fresher one (awaitRefresh) is being fetched. */
    val refreshing: Boolean = false,
    val failure: UsageFailure? = null,
    val failureMessage: String? = null,
    val limitsRefreshing: Boolean = false,
    /** Last limits refresh failed; the last good limits stay visible. */
    val limitsError: String? = null,
) {
    val report: UsageReport? get() = reports[range]
}

/**
 * One line for the connected machine card: lowest remaining window per enabled provider with limits,
 * e.g. "Claude 62% left · Codex 80% left"; "Usage" when no provider reports windows.
 */
internal fun usageSummaryLine(providers: List<ProviderInstance>): String = providers.mapNotNull { provider ->
    val remaining = provider.usageLimits?.windows?.minOfOrNull { it.remainingPercent }
    if (!provider.enabled || remaining == null) null
    else "${provider.displayName ?: provider.driver.replaceFirstChar { it.titlecase(Locale.ROOT) }} $remaining% left"
}.joinToString(" · ").ifEmpty { "Usage" }

internal fun usageProviderLabel(driver: String): String = driver.replaceFirstChar { it.titlecase(Locale.ROOT) }

internal fun usageShare(value: Double, total: Double): Float =
    if (!value.isFinite() || !total.isFinite() || total <= 0) 0f else (value / total).coerceIn(0.0, 1.0).toFloat()

internal fun usageCheckedMinutes(checkedAt: String, now: Long): Long? = try {
    (now - Instant.parse(checkedAt).toEpochMilli()).coerceAtLeast(0) / 60_000
} catch (_: Exception) { null }

internal fun usageCheckedAgo(checkedAt: String, now: Long): String? = usageCheckedMinutes(checkedAt, now)?.let { "Checked $it min ago" }

/** Where the "checked N min ago" freshness belongs: once under the list, on each card, or nowhere. */
internal sealed interface UsageFreshness {
    data object None : UsageFreshness
    data class Shared(val text: String) : UsageFreshness
    data object PerProvider : UsageFreshness
}

/** One shared line when every provider with a readable check time was checked at the same minute; otherwise per card. */
internal fun usageFreshness(limits: List<ProviderUsageLimits>, now: Long): UsageFreshness {
    val minutes = limits.mapNotNull { usageCheckedMinutes(it.checkedAt, now) }
    val first = minutes.firstOrNull() ?: return UsageFreshness.None
    if (minutes.any { it != first }) return UsageFreshness.PerProvider
    val subject = if (minutes.size == 1) "Limits" else "All limits"
    return UsageFreshness.Shared(if (first < 1) "$subject checked just now" else "$subject checked $first min ago")
}

internal fun usageLimitReached(window: UsageLimitWindow): Boolean = window.remainingPercent == 0

internal fun usageRemainingLabel(window: UsageLimitWindow): String =
    if (usageLimitReached(window)) "Limit reached" else "${window.remainingPercent}% left"

/** Sentence-case reset time ("Resets in 40m"). */
internal fun usageResetLabel(window: UsageLimitWindow, now: Long): String? =
    formatUsageReset(window, now)?.replaceFirstChar { it.titlecase(Locale.ROOT) }

/** Share of the total as a whole percent; tiny non-zero shares read "<1%". */
internal fun usageSharePercent(value: Double, total: Double): String {
    val share = usageShare(value, total)
    return if (share > 0f && share < .005f) "<1%" else "${Math.round(share * 100)}%"
}

internal fun usageBreakdownValue(amount: String, value: Double, total: Double): String =
    if (total.isFinite() && total > 0) "$amount · ${usageSharePercent(value, total)}" else amount

/** Report breakdowns use T3 provider kinds ("claude"); provider instances use drivers ("claudeAgent"). */
internal fun usageProviderDriver(provider: String): String = if (provider == "claude") "claudeAgent" else provider

/** Date patterns for the daily chart; the screen swaps in the device's best-fit patterns, tests use the English defaults. */
internal data class UsageDatePatterns(val monthDay: String = "MMM d", val weekday: String = "EEE", val weekdayMonthDay: String = "EEE, MMM d")

private fun usageDayFormat(day: String, pattern: String, locale: Locale): String = try {
    LocalDate.parse(day).format(DateTimeFormatter.ofPattern(pattern, locale))
} catch (_: Exception) { day }

/** Axis label for a day: weekday for the 7-day range, short date otherwise. */
internal fun usageDayLabel(day: String, range: UsageRange, locale: Locale, patterns: UsageDatePatterns = UsageDatePatterns()): String =
    usageDayFormat(day, if (range == UsageRange.Week) patterns.weekday else patterns.monthDay, locale)

/** Full short label for one day ("Sat, Jun 13"). */
internal fun usageDayTitle(day: String, locale: Locale, patterns: UsageDatePatterns = UsageDatePatterns()): String =
    usageDayFormat(day, patterns.weekdayMonthDay, locale)

internal fun usageOfflineNotice(connection: ConnectionStatus, canReadUsage: Boolean): String? = when {
    connection != ConnectionStatus.Connected -> "Offline · showing last known data"
    !canReadUsage -> "Usage can't be read on this connection · showing last known data"
    else -> null
}

package com.tajchert.trefoil.widget

import androidx.annotation.DrawableRes
import com.tajchert.trefoil.R
import com.tajchert.trefoil.ui.formatUsageDuration

// Pure helpers shared by all usage widgets. Formatting of money, tokens, pace and reset text
// lives in ui/UsagePresentation.kt (formatUsageUsd, formatUsageTokens, usagePace, formatUsageReset); reuse those.

/** Snapshots older than this are labelled stale (the periodic refresh runs every 30 minutes). */
const val WIDGET_STALE_AFTER_MS = 2 * 60 * 60_000L

/** "just now" / "5m ago" / "3h 10m ago". */
fun widgetFreshness(updatedAtMillis: Long, now: Long): String {
    val age = (now - updatedAtMillis).coerceAtLeast(0)
    return if (age < 60_000) "just now" else "${formatUsageDuration(age)} ago"
}

/**
 * Footer line: "Studio · 3m ago". The last refresh problem (e.g. "Open Trefoil to refresh this
 * machine.") replaces the machine name so the hint is never truncated away.
 */
fun widgetFooterText(snapshot: UsageWidgetSnapshot, updatedAtMillis: Long, now: Long): String =
    "${snapshot.lastError?.removeSuffix(".") ?: snapshot.machineName} · ${widgetFreshness(updatedAtMillis, now)}"

/** Stale data or a failed refresh: the footer stays visible and is marked. */
fun widgetAlert(snapshot: UsageWidgetSnapshot, updatedAtMillis: Long, now: Long): Boolean =
    snapshot.lastError != null || isWidgetStale(updatedAtMillis, now)

fun isWidgetStale(updatedAtMillis: Long, now: Long): Boolean = now - updatedAtMillis > WIDGET_STALE_AFTER_MS

/** Monochrome provider mark (tint it), or null for unknown drivers. Same mapping as ui/ProviderIconUi.kt. */
@DrawableRes
fun widgetProviderIcon(driver: String): Int? = when (driver) {
    "codex" -> R.drawable.ic_provider_codex
    "claudeAgent" -> R.drawable.ic_provider_claude
    "cursor" -> R.drawable.ic_provider_cursor
    "grok" -> R.drawable.ic_provider_grok
    "opencode" -> R.drawable.ic_provider_opencode
    else -> null
}

package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.UsageLimitWindow
import com.tajchert.trefoil.core.model.UsageRange
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UsagePresentationTest {
    @Test fun checkedTimeAndSharesHandleMissingOrZeroData() {
        val now = java.time.Instant.parse("2026-01-01T00:03:00Z").toEpochMilli()
        assertEquals("Checked 3 min ago", usageCheckedAgo("2026-01-01T00:00:00Z", now))
        assertEquals(null, usageCheckedAgo("unknown", now))
        assertEquals(0f, usageShare(0.0, 0.0))
        assertEquals(.5f, usageShare(5.0, 10.0))
        assertEquals(1f, usageShare(15.0, 10.0))
    }
    @Test fun formatsAmounts() {
        assertEquals("$0", formatUsageUsd(0.0))
        assertEquals("<$0.01", formatUsageUsd(0.001))
        assertEquals("$12.34", formatUsageUsd(12.34))
        assertEquals("1.2K", formatUsageTokens(1200))
        assertEquals("3.4M", formatUsageTokens(3400000))
    }
    @Test fun calculatesPaceAndReset() {
        val window = UsageLimitWindow("a", "session", "Session", 70.0, "2026-01-01T01:00:00Z", 120)
        val now = java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals("ahead of pace", usagePace(window, now))
        assertEquals("resets in 1h 0m", formatUsageReset(window, now))
    }

    private val now = java.time.Instant.parse("2026-01-01T00:10:00Z").toEpochMilli()
    private fun limits(checkedAt: String) = ProviderUsageLimits(checkedAt)

    @Test fun freshnessIsSharedWhenAllCheckedInTheSameMinute() {
        assertEquals(UsageFreshness.Shared("All limits checked 2 min ago"),
            usageFreshness(listOf(limits("2026-01-01T00:08:00Z"), limits("2026-01-01T00:07:30Z")), now))
        assertEquals(UsageFreshness.Shared("Limits checked 2 min ago"), usageFreshness(listOf(limits("2026-01-01T00:08:00Z")), now))
        assertEquals(UsageFreshness.Shared("All limits checked just now"), usageFreshness(listOf(limits("2026-01-01T00:10:00Z"), limits("2026-01-01T00:10:20Z")), now))
    }

    @Test fun freshnessStaysPerProviderWhenTimesDifferAndIgnoresUnreadableOnes() {
        assertEquals(UsageFreshness.PerProvider, usageFreshness(listOf(limits("2026-01-01T00:08:00Z"), limits("2026-01-01T00:01:00Z")), now))
        assertEquals(UsageFreshness.Shared("Limits checked 2 min ago"), usageFreshness(listOf(limits("2026-01-01T00:08:00Z"), limits("")), now))
        assertEquals(UsageFreshness.None, usageFreshness(listOf(limits("")), now))
        assertEquals(UsageFreshness.None, usageFreshness(emptyList(), now))
    }

    @Test fun reachedLimitReadsLimitReachedWithCapitalisedReset() {
        val reached = UsageLimitWindow("a", "session", "Session", 100.0, "2026-01-01T00:50:00Z")
        val almost = UsageLimitWindow("b", "session", "Session", 99.0)
        assertEquals(true, usageLimitReached(reached))
        assertEquals("Limit reached", usageRemainingLabel(reached))
        assertEquals("Resets in 40m", usageResetLabel(reached, now))
        assertEquals(false, usageLimitReached(almost))
        assertEquals("1% left", usageRemainingLabel(almost))
        assertEquals(null, usageResetLabel(almost, now))
    }

    @Test fun sharesReadAsWholePercentWithTinySharesFloored() {
        assertEquals("64%", usageSharePercent(28.8, 45.0))
        assertEquals("<1%", usageSharePercent(0.001, 10.0))
        assertEquals("0%", usageSharePercent(0.0, 10.0))
        assertEquals("$28.80 · 64%", usageBreakdownValue("$28.80", 28.8, 45.0))
        assertEquals("$0", usageBreakdownValue("$0", 0.0, 0.0))
        assertEquals("claudeAgent", usageProviderDriver("claude"))
        assertEquals("codex", usageProviderDriver("codex"))
    }

    @Test fun dayLabelsAreShortAndLocaleAware() {
        assertEquals("Tue", usageDayLabel("2026-06-09", UsageRange.Week, Locale.US))
        assertEquals("Jun 9", usageDayLabel("2026-06-09", UsageRange.Month, Locale.US))
        assertEquals("Sat, Jun 13", usageDayTitle("2026-06-13", Locale.US))
        assertEquals("not-a-day", usageDayLabel("not-a-day", UsageRange.Week, Locale.US))
        assertEquals("13 Jun", usageDayLabel("2026-06-13", UsageRange.Month, Locale.UK, UsageDatePatterns(monthDay = "d MMM")))
    }

    @Test fun offlineNoticeOnlyWhenDisconnectedOrUnreadable() {
        assertEquals(null, usageOfflineNotice(ConnectionStatus.Connected, true))
        assertEquals("Offline · showing last known data", usageOfflineNotice(ConnectionStatus.Reconnecting, true))
        assertEquals("Offline · showing last known data", usageOfflineNotice(ConnectionStatus.Disconnected, false))
        assertEquals("Usage can't be read on this connection · showing last known data", usageOfflineNotice(ConnectionStatus.Connected, false))
    }

    @Test fun layoutModeFollowsTheContainerWidth() {
        assertEquals(UsageLayoutMode.Tabs, usageLayoutMode(400f))
        assertEquals(UsageLayoutMode.Tabs, usageLayoutMode(599.9f))
        assertEquals(UsageLayoutMode.TabsWide, usageLayoutMode(600f))
        assertEquals(UsageLayoutMode.TabsWide, usageLayoutMode(999.9f))
        assertEquals(UsageLayoutMode.TwoColumn, usageLayoutMode(1000f))
        assertEquals(UsageLayoutMode.TwoColumn, usageLayoutMode(1840f))
    }

    @Test fun limitGridUsesTwoColumnsOnlyWhenTwoCellsFit() {
        assertEquals(1, usageLimitColumns(600f))
        assertEquals(1, usageLimitColumns(643f))
        assertEquals(2, usageLimitColumns(644f))
        assertEquals(2, usageLimitColumns(999f))
    }

    @Test fun breakdownsSitSideBySideFromFiveHundredSixtyAndRightColumnWidthIsDerived() {
        assertEquals(false, usageSplitsBreakdown(559f))
        assertEquals(true, usageSplitsBreakdown(560f))
        // 1000: 1000 - 2*24 - 380 - 24 = 548 (stacked); 1120 and wider: 1120 - 48 - 404 = 668 (side by side).
        assertEquals(548f, usageRightColumnWidth(1000f))
        assertEquals(668f, usageRightColumnWidth(1840f))
        assertEquals(false, usageSplitsBreakdown(usageRightColumnWidth(1000f)))
        assertEquals(true, usageSplitsBreakdown(usageRightColumnWidth(1200f)))
    }
}

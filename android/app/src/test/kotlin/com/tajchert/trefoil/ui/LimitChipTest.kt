package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderUsageLimits
import com.tajchert.trefoil.core.model.UsageLimitWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LimitChipTest {
    private fun provider(id: String, vararg used: Double, enabled: Boolean = true) = ProviderInstance(id, "codex", enabled = enabled,
        usageLimits = ProviderUsageLimits("", used.mapIndexed { i, u -> UsageLimitWindow("$id$i", "w", "Window $i", u) }))

    @Test fun picksTheMinimumRemainingAcrossProvidersAndWindows() {
        assertEquals(38, tightestLimitPercent(listOf(provider("a", 20.0, 30.0), provider("b", 62.0, 10.0))))
    }

    @Test fun ignoresDisabledProviders() {
        assertEquals(80, tightestLimitPercent(listOf(provider("a", 20.0), provider("off", 99.0, enabled = false))))
    }

    @Test fun ignoresClaudeModelScopedWeeklyWindows() {
        val claude = ProviderInstance("c", "claudeAgent", usageLimits = ProviderUsageLimits("", listOf(
            UsageLimitWindow("five_hour", "session", "Session", 40.0),
            UsageLimitWindow("seven_day", "weekly", "Weekly", 30.0),
            UsageLimitWindow("seven_day_fable", "weekly", "Weekly · Fable", 100.0),
        )))
        assertEquals(60, tightestLimitPercent(listOf(claude)))
        val fableOnly = claude.copy(usageLimits = ProviderUsageLimits("", listOf(UsageLimitWindow("seven_day_fable", "weekly", "Weekly · Fable", 100.0))))
        assertNull(tightestLimitPercent(listOf(fableOnly)))
    }

    @Test fun noWindowsMeansNoLimit() {
        assertNull(tightestLimitPercent(emptyList()))
        assertNull(tightestLimitPercent(listOf(ProviderInstance("a", "codex"), ProviderInstance("b", "codex", usageLimits = ProviderUsageLimits("")))))
        assertNull(tightestLimitPercent(listOf(provider("off", 90.0, enabled = false))))
    }

    @Test fun severityUsesTheLimitsTabThresholds() {
        assertEquals(LimitSeverity.Critical, limitSeverity(0))
        assertEquals(LimitSeverity.Critical, limitSeverity(10))
        assertEquals(LimitSeverity.Low, limitSeverity(11))
        assertEquals(LimitSeverity.Low, limitSeverity(30))
        assertEquals(LimitSeverity.Healthy, limitSeverity(31))
    }

    @Test fun descriptionNamesTheLowestLimitWhenKnown() {
        // Plain JVM test: the caller resolves the resources and passes them in.
        val title = "Limits & usage"
        assertEquals("$title · 38% left", limitChipDescription(38, title, "38% left"))
        assertEquals(title, limitChipDescription(null, title, ""))
    }
}

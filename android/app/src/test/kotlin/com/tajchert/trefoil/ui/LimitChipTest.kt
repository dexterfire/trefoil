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
        assertEquals("Limits and usage, lowest limit 38% left", limitChipDescription(38))
        assertEquals("Limits and usage", limitChipDescription(null))
    }
}

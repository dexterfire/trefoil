package com.tajchert.trefoil.ui

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Limits & usage in containers wider than a phone: the layout comes from the container width. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1200dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageAdaptiveTest {
    @get:Rule val compose = createComposeRule()
    private val state = ClientState(
        environment = SavedEnvironment("m", "Mac mini", "https://fixture.example", "fixture"), connection = ConnectionStatus.Connected, canReadUsage = true,
        providers = listOf(ProviderInstance("a", "codex", "Work", usageLimits = ProviderUsageLimits("2026-01-01T00:00:00Z", listOf(UsageLimitWindow("w", "session", "Session", 38.0))))),
    )
    private val report = UsageReport(
        "m", UsageRange.Week, "2026-01-01", "2026-01-07", "UTC", "2026-01-07T12:00:00Z",
        totalCostUsd = 12.34, tokens = UsageTokens(uncachedInput = 1000, cachedInput = 200, output = 300), cacheSavingsUsd = 2.0,
        providers = listOf(UsageBreakdown("codex", costUsd = 10.0)), models = listOf(UsageBreakdown("codex", "model-fixture", costUsd = 10.0)),
        days = listOf(UsageDayTotal("2026-01-01", 2.34, 500), UsageDayTotal("2026-01-07", 10.0, 1000)), pricingStatus = "fresh",
    )
    private val usage = UsageUiState(reports = mapOf(UsageRange.Week to report))

    @Test fun wideContainerShowsLimitsAndCostTogetherWithoutTabsOrBackArrow() {
        compose.setContent { TrefoilTheme { UsageScreen(state, usage, null, {}, {}, {}) } }
        compose.onNodeWithText("62% left").assertExists()
        compose.onNodeWithText("$12.34").assertExists()
        compose.onNodeWithText("model-fixture").assertExists()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.onNode(hasText("Limits") and hasClickAction()).assertDoesNotExist()
    }

    private val unevenReport = report.copy(
        providers = listOf(UsageBreakdown("codex", costUsd = 10.0)),
        models = listOf(UsageBreakdown("codex", "model-fixture", costUsd = 6.0), UsageBreakdown("codex", "model-other", costUsd = 4.0), UsageBreakdown("claude", "model-third", costUsd = 2.0)),
    )
    private val unevenUsage = UsageUiState(reports = mapOf(UsageRange.Week to unevenReport))

    private fun showAt(width: androidx.compose.ui.unit.Dp, tab: Int, back: (() -> Unit)? = null, usage: UsageUiState = unevenUsage) =
        compose.setContent { TrefoilTheme { androidx.compose.foundation.layout.Box(Modifier.width(width)) { UsageScreen(state, usage, back, {}, {}, {}, initialTab = tab) } } }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot()
    private fun assertEqualHeights() = bounds("usage-breakdown-Providers").let { p -> bounds("usage-breakdown-Models").let { m -> assertEquals(p.bottom - p.top, m.bottom - m.top) } }

    @Test fun costAt673dpShowsProvidersAndModelsSideBySideWithEqualHeights() {
        showAt(673.dp, tab = 1)
        compose.onNodeWithText("$12.34").assertExists()
        val providers = bounds("usage-breakdown-Providers")
        val models = bounds("usage-breakdown-Models")
        assertEquals(providers.top, models.top)
        assertTrue("Providers sits left of Models", providers.right <= models.left)
        assertEquals(providers.bottom - providers.top, models.bottom - models.top)
        val providerWidth = (providers.right - providers.left).value
        assertTrue("both cards are about half the column: $providers $models", kotlin.math.abs(providerWidth - (models.right - models.left).value) < 2f && providerWidth in 250f..340f)
        // The hero card holds the total and the chart; the tab row stays.
        compose.onNodeWithTag("usage-hero-card").assertExists()
        compose.onNode(hasText("Cost") and hasClickAction()).assertExists()
    }

    @Test fun tokensAt673dpUseTheSameCardLayout() {
        showAt(673.dp, tab = 2)
        compose.onNodeWithText("1.5K").assertExists()
        assertEqualHeights()
    }

    @Test fun twoColumnCardsHaveEqualHeightsAtOneThousandTwoHundredDp() {
        showAt(1200.dp, tab = 1)
        assertEqualHeights()
    }

    @Test fun twoColumnLimitCardAndHeroCardStartOnOneLine() {
        showAt(1200.dp, tab = 1)
        assertEquals(bounds("usage-limit-card").top, bounds("usage-hero-card").top)
    }

    @Test fun dayReadoutSitsInTheHeroCardAndNamesTheTappedDay() {
        showAt(1200.dp, tab = 1)
        compose.onNodeWithText("Tap a bar to see a day's cost").assertExists()
        compose.onNodeWithContentDescription("Wed, Jan 7: $10.00").performClick()
        compose.onNodeWithText("Wed, Jan 7 · $10.00").assertExists()
        compose.onNodeWithText("Tap a bar to see a day's cost").assertDoesNotExist()
    }

    @Test fun mediumContainerKeepsTheTabsAndTheChartSelectionWorks() {
        showAt(673.dp, tab = 1)
        compose.onNodeWithText("Tap a bar to see a day's cost").assertExists()
        compose.onNodeWithContentDescription("Wed, Jan 7: $10.00").performClick()
        compose.onNodeWithText("Wed, Jan 7 · $10.00").assertExists()
    }

    @Test fun limitsAt673dpUseTwoColumnsAndTheTitleIsAPaneTitle() {
        val two = state.copy(providers = state.providers + ProviderInstance("b", "claudeAgent", "Personal", usageLimits = ProviderUsageLimits("2026-01-01T00:00:00Z", listOf(UsageLimitWindow("w2", "weekly", "Weekly", 80.0)))))
        compose.setContent { TrefoilTheme { androidx.compose.foundation.layout.Box(Modifier.width(673.dp)) { UsageScreen(two, unevenUsage, null, {}, {}, {}, initialTab = 0) } } }
        val cards = compose.onAllNodesWithTag("usage-limit-card")
        cards.assertCountEquals(2)
        assertEquals(cards[0].getBoundsInRoot().top, cards[1].getBoundsInRoot().top)
        compose.onNodeWithText("Limits & usage").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
    }

    @Test fun backArrowStaysWhenAHandlerIsGiven() {
        var backs = 0
        compose.setContent { TrefoilTheme { UsageScreen(state, usage, { backs++ }, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun rangeChipsStillSelectTheRange() {
        var selected: UsageRange? = null
        compose.setContent { TrefoilTheme { UsageScreen(state, usage, null, {}, { selected = it }, {}) } }
        compose.onNodeWithText("30 days").performClick()
        assertEquals(UsageRange.Month, selected)
    }

    @Test fun costTokensChoiceSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { TrefoilTheme { UsageScreen(state, usage, null, {}, {}, {}) } }
        compose.onNodeWithText("Tokens").performClick()
        compose.onNodeWithText("1.5K").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("1.5K").assertExists()
        compose.onNodeWithText("$12.34").assertDoesNotExist()
    }
}

package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.adaptive.LocalCompactHeight
import com.tajchert.trefoil.ui.adaptive.ThreadListChrome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The thread list header variants the shell asks for through [ThreadListChrome], and the pointer and compact-height behaviour. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadListChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val environment = SavedEnvironment("env", "Workstation", "https://example.test", "fixture")
    private val state = ClientState(
        environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canWriteThreads = true, threadActionsConfigured = true,
        providers = listOf(ProviderInstance("claude", "claudeAgent", "Claude", usageLimits = ProviderUsageLimits("", listOf(UsageLimitWindow("weekly", "weekly", "Weekly", 62.0))))),
        projects = listOf(Project("trefoil", "Trefoil")),
        threads = listOf(
            ThreadSummary("a", "trefoil", "Polish the Android thread list", "completed", latestMessage = "Looks good", branch = "feat/list"),
            ThreadSummary("b", "trefoil", "Review the connection flow", "completed"),
        ))
    private val shell = ShellActions(onMachines = {}, onConnectMachine = {}, onScreens = {}, onUsage = {}, onDisconnect = {})

    private fun show(width: Int, chrome: ThreadListChrome, selectedId: String? = null, compact: Boolean = false, shellActions: ShellActions? = shell,
                     state: ClientState = this.state) {
        compose.setContent {
            TrefoilTheme {
                CompositionLocalProvider(LocalCompactHeight provides compact) {
                    Box(Modifier.width(width.dp)) { ThreadList(state, {}, {}, selectedId = selectedId, shell = shellActions, chrome = chrome) }
                }
            }
        }
    }

    private fun percentText() = compose.onAllNodesWithText("%", substring = true, useUnmergedTree = true)

    @Test fun defaultChromeIsThePhoneHeader() {
        show(380, ThreadListChrome())
        compose.onNodeWithContentDescription("Сменить машину").assertExists()
        compose.onNodeWithContentDescription("Меню").assertExists()
        compose.onNodeWithContentDescription("Новый тред").assertExists()
        compose.onNodeWithContentDescription("Скрыть список тредов").assertDoesNotExist()
        compose.onNodeWithContentDescription("Открыть навигацию").assertDoesNotExist()
    }

    @Test fun machinePillDescribesItsRouteInRussian() {
        show(380, ThreadListChrome(), state = state.copy(activeRoute = EnvironmentRoute(RouteKind.Ssh, "https://example.test/", RouteSource.Learned)))
        val description = compose.onNodeWithContentDescription("Сменить машину").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(description, description.endsWith("через SSH"))
    }

    @Test fun railChromeShowsTitleSearchAndCollapseOnly() {
        var collapses = 0
        show(300, ThreadListChrome(onCollapse = { collapses++ }, railPresent = true))
        compose.onNodeWithText("Workstation").assertExists()
        compose.onNodeWithContentDescription("Поиск").assertExists()
        compose.onNodeWithContentDescription("Сменить машину").assertDoesNotExist()
        compose.onNodeWithContentDescription("Меню").assertDoesNotExist()
        compose.onNodeWithContentDescription("Новый тред").assertDoesNotExist()
        compose.onNodeWithContentDescription("Открыть навигацию").assertDoesNotExist()
        compose.onNode(hasContentDescription("Limits and usage", substring = true)).assertDoesNotExist()
        compose.onNodeWithContentDescription("Скрыть список тредов").performClick()
        assertEquals(1, collapses)
    }

    @Test fun railChromeWithoutACollapseCallbackHasNoCollapseButton() {
        show(340, ThreadListChrome(railPresent = true))
        compose.onNodeWithContentDescription("Поиск").assertExists()
        compose.onNodeWithContentDescription("Скрыть список тредов").assertDoesNotExist()
    }

    @Test fun navigationChromeShowsMenuMachineLimitAndSearch() {
        var navigations = 0
        show(336, ThreadListChrome(onOpenNavigation = { navigations++ }, onCollapse = {}))
        compose.onNodeWithContentDescription("Открыть навигацию").performClick()
        assertEquals(1, navigations)
        compose.onNodeWithContentDescription("Сменить машину").assertExists()
        compose.onNode(hasContentDescription("Limits and usage", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Поиск").assertExists()
        // The menu replaces the overflow button, and the list is too narrow for a collapse button.
        compose.onNodeWithContentDescription("Меню").assertDoesNotExist()
        compose.onNodeWithContentDescription("Скрыть список тредов").assertDoesNotExist()
        compose.onNodeWithContentDescription("Новый тред").assertExists()
    }

    @Test fun limitNumberAppearsFrom340dp() {
        show(339, ThreadListChrome(onOpenNavigation = {}))
        assertEquals(0, percentText().fetchSemanticsNodes().size)
    }

    @Test fun limitNumberShownAt340dp() {
        show(340, ThreadListChrome(onOpenNavigation = {}))
        assertEquals(1, percentText().fetchSemanticsNodes().size)
    }

    @Test fun collapseAppearsFrom360dpOnly() {
        show(359, ThreadListChrome(onOpenNavigation = {}, onCollapse = {}))
        compose.onNodeWithContentDescription("Скрыть список тредов").assertDoesNotExist()
    }

    @Test fun collapseShownAt360dpAndCallsBack() {
        var collapses = 0
        show(360, ThreadListChrome(onOpenNavigation = {}, onCollapse = { collapses++ }))
        compose.onNodeWithContentDescription("Скрыть список тредов").performClick()
        assertEquals(1, collapses)
    }

    @Test fun navigationChromeWithoutMenuKeepsTheOverflowButton() {
        show(336, ThreadListChrome(onCollapse = {}))
        compose.onNodeWithContentDescription("Открыть навигацию").assertDoesNotExist()
        compose.onNodeWithContentDescription("Меню").assertExists()
    }

    @Test fun selectedRowKeepsItsSelectedSemantics() {
        show(340, ThreadListChrome(railPresent = true), selectedId = "a")
        compose.onNode(isSelected() and hasText("Polish the Android thread list")).assertExists()
        compose.onNode(isSelected() and hasText("Review the connection flow")).assertDoesNotExist()
    }

    @Test fun rightClickOpensTheThreadActionsMenuWithoutOpeningTheThread() {
        var opened: String? = null
        compose.setContent { TrefoilTheme { Box(Modifier.width(340.dp)) { ThreadList(state, { opened = it }, {}, chrome = ThreadListChrome(railPresent = true)) } } }
        compose.onNodeWithText("Polish the Android thread list").performMouseInput { rightClick() }
        compose.onNodeWithText("Settle").assertExists()
        compose.onNodeWithText("Copy thread ID").assertExists()
        assertNull(opened)
    }

    @Test fun leftClickStillOpensTheThread() {
        var opened: String? = null
        compose.setContent { TrefoilTheme { Box(Modifier.width(340.dp)) { ThreadList(state, { opened = it }, {}, chrome = ThreadListChrome(railPresent = true)) } } }
        compose.onNodeWithText("Polish the Android thread list").performMouseInput { click() }
        assertEquals("a", opened)
        compose.onNodeWithText("Settle").assertDoesNotExist()
    }

    @Test fun compactHeightDropsTheMetaRowAndShrinksTheHeader() {
        show(340, ThreadListChrome(railPresent = true), compact = true)
        // The project name is the group heading now, so its appearance no longer marks the thread meta row.
        compose.onNodeWithText("feat/list").assertDoesNotExist()
        compose.onNodeWithText("Polish the Android thread list").assertExists()
        assertEquals(24.dp, compose.onNodeWithContentDescription("Поиск").getBoundsInRoot().let { (it.top + it.bottom) / 2 })
    }

    @Test fun regularHeightKeepsTheMetaRowAndTheTallHeader() {
        show(340, ThreadListChrome(railPresent = true))
        compose.onNodeWithText("feat/list").assertExists()
        assertEquals(32.dp, compose.onNodeWithContentDescription("Поиск").getBoundsInRoot().let { (it.top + it.bottom) / 2 })
    }

    // ---- Round-3a items 10 and 15 ----

    @Test fun selectedBarSitsBesideTheProjectTile() {
        // Tile top is the row's 12dp padding (8dp compact); the bar is centred on the 40dp (32dp) tile.
        assertEquals(SelectedBar(20.dp, 24.dp), tileBarTop(compact = false))
        assertEquals(SelectedBar(14.dp, 20.dp), tileBarTop(compact = true))
        assertEquals(12.dp + (40.dp - 24.dp) / 2, tileBarTop(false).top)
        assertEquals(8.dp + (32.dp - 20.dp) / 2, tileBarTop(true).top)
    }

    @Test fun filterChipsKeepA48dpTouchAreaInsideTheStripInRailChrome() {
        show(340, ThreadListChrome(railPresent = true))
        val chip = compose.onNodeWithText("Все").fetchSemanticsNode()
        with(compose.density) {
            val t = chip.touchBoundsInRoot
            assertTrue("touch height ${t.height.toDp()}", t.height.toDp() >= 48.dp)
            val look = chip.boundsInRoot
            assertEquals(36.dp, look.height.toDp())
        }
    }
}

package com.tajchert.trefoil.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tajchert.trefoil.R
import com.tajchert.trefoil.testString
import com.tajchert.trefoil.core.model.*
import com.tajchert.trefoil.ui.theme.ThemeFamily
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings screen as a specification: every control writes through to the store (and the file behind it), the chosen
 * order is what the thread list renders, and both entry points — the overflow menu and the 600-779dp drawer — open it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("trefoil.settings.test", Context.MODE_PRIVATE)
    private lateinit var store: PreferenceStore

    @Before fun clearPreferences() {
        prefs.edit().clear().commit()
        store = PreferenceStore(prefs)
    }

    private val environment = SavedEnvironment("env", "Workstation", "https://example.test", "fixture")
    private val state = ClientState(
        environment = environment, connection = ConnectionStatus.Connected, canWrite = true, canWriteThreads = true, shellLoaded = true, selectedThreadId = "a",
        projects = listOf(Project("trefoil", "Trefoil")),
        threads = listOf(
            ThreadSummary("a", "trefoil", "Polish the Android thread list", "completed", latestMessage = "Looks good"),
            ThreadSummary("b", "trefoil", "Review the connection flow", "completed"),
        ))

    /** Four projects, each with one thread: the two bands' orders differ whichever rule they take from the defaults. */
    private val ordered = ClientState(
        connection = ConnectionStatus.Connected, canWrite = true,
        projects = listOf(Project("zeta", "Zeta"), Project("alpha", "Alpha"), Project("bravo", "Bravo"), Project("delta", "Delta")),
        threads = listOf(
            ThreadSummary("t-zeta", "zeta", "Zeta thread", "completed", createdAt = "2026-11-06T08:00:00Z"),
            ThreadSummary("t-delta", "delta", "Delta thread", "completed", createdAt = "2026-11-06T07:00:00Z"),
            ThreadSummary("t-alpha", "alpha", "Alpha thread", "completed", createdAt = "2026-11-06T06:00:00Z"),
            ThreadSummary("t-bravo", "bravo", "Bravo thread", "completed", createdAt = "2026-11-06T05:00:00Z"),
        ))

    private fun showSettings() {
        compose.setContent {
            val preferences by store.state.collectAsState()
            TrefoilTheme { SettingsScreen(preferences, currentWidthDp = 411f, onChange = store::update, onBack = {}) }
        }
    }

    /** A setting must reach the flow the UI reads *and* the file, so a fresh store opens the screen where it was left. */
    private fun assertWritten(message: String, check: (AppPreferences) -> Unit) {
        compose.waitForIdle()
        check(store.state.value)
        check(PreferenceStore(prefs).state.value)
    }

    /**
     * The three order rows repeat once per band, so they are picked by position: the pinned band is drawn first and takes
     * the lower indices. Labels alone would be ambiguous ("По алфавиту" is offered by both bands).
     */
    private fun orderRow(label: String, band: Int) = compose.onAllNodesWithContentDescription(label)[band]

    private fun systemBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The overflow control; ticket #5 may translate its description, so either spelling opens the menu. */
    private fun overflowMenu() = compose.onNode(hasContentDescription("More") or hasContentDescription("Меню"))

    private fun systemBackToTheList() {
        systemBack()
        compose.onNodeWithText("К проекту").assertExists()
    }

    @Composable
    private fun TestApp(preferences: AppPreferences, start: ShellStart = ShellStart(), onPreferencesChange: ((AppPreferences) -> AppPreferences) -> Unit = {}) {
        TrefoilApp(
            state = state, savedEnvironments = emptyList(), onPair = { _, _, _ -> }, onConnect = {},
            onSelectThread = {}, onSend = {}, onStop = {}, onRespond = { _, _, _ -> }, draft = null,
            onStartDraft = {}, onCancelDraft = {}, onSetRuntimeMode = {}, onSetModel = {},
            onReconnect = {}, onDisconnect = {}, onForget = {},
            preferences = preferences, onPreferencesChange = onPreferencesChange, start = start,
        )
    }

    @Test fun everyControlWritesStraightThrough() {
        showSettings()
        // Pinned band: activity instead of its alphabetical default.
        orderRow("По активности", band = 0).performScrollTo().performClick()
        assertWritten("pinned order") { assertEquals(ProjectOrder.Activity, it.list.pinnedOrder) }
        // The other band keeps its own rule, so it takes the alphabetical one.
        orderRow("По алфавиту", band = 1).performScrollTo().performClick()
        assertWritten("other order") { assertEquals(ProjectOrder.Alphabetical, it.list.otherOrder) }
        compose.onNodeWithContentDescription("По дате создания").performScrollTo().performClick()
        assertWritten("thread order") { assertEquals(ThreadOrder.Created, it.list.threadOrder) }
        compose.onNodeWithContentDescription(ThemeFamily.Violet.label).performScrollTo().performClick()
        assertWritten("theme") { assertEquals(ThemeFamily.Violet, it.themeFamily) }
        compose.onNodeWithContentDescription("Тёмная").performScrollTo().performClick()
        assertWritten("brightness") { assertEquals(BrightnessMode.Dark, it.brightness) }
        compose.onNodeWithContentDescription("Боковая панель").performScrollTo().performClick()
        assertWritten("rail switch") { assertTrue(it.forceRail) }
    }

    @Test fun widthSliderStartsAtTheWindowAndResetsToIt() {
        showSettings()
        // The slider's value is the real window width until the user overrides it.
        compose.onNodeWithText("411dp").performScrollTo().assertExists()
        compose.onNodeWithText("Сбросить").assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { setProgress -> setProgress(840f) }
        assertWritten("forced width") { assertEquals(840f, it.forcedWidthDp ?: 0f, 0.5f) }
        compose.onNodeWithText("840dp").assertExists()
        compose.onNodeWithText("Сбросить").performScrollTo().performClick()
        assertWritten("width reset") { assertNull(it.forcedWidthDp) }
        compose.onNodeWithText("411dp").assertExists()
    }

    @Test @Config(sdk = [35], qualifiers = "w411dp-h1400dp") fun theChosenOrderIsWhatTheListRenders() {
        store.togglePinnedProject("zeta")
        store.togglePinnedProject("alpha")
        // Both screens read the same store: the list is shown, the settings screen edits, then the list is shown again.
        var showList by mutableStateOf(true)
        compose.setContent {
            val preferences by store.state.collectAsState()
            TrefoilTheme {
                if (showList) ThreadList(ordered, {}, {}, preferences = preferences.list)
                else SettingsScreen(preferences, currentWidthDp = 411f, onChange = store::update, onBack = {})
            }
        }
        // Defaults: pinned alphabetically, the rest by activity.
        assertEquals(listOf("Alpha", "Zeta", "Delta", "Bravo"), headingOrder())
        compose.runOnIdle { showList = false }
        compose.waitForIdle()
        orderRow("По активности", band = 0).performScrollTo().performClick()
        orderRow("По алфавиту", band = 1).performScrollTo().performClick()
        compose.runOnIdle { showList = true }
        compose.waitForIdle()
        assertEquals(listOf("Zeta", "Alpha", "Bravo", "Delta"), headingOrder())
    }

    /** Project titles in the order their headings are drawn: top to bottom, so an order change is visible as a re-order. */
    private fun headingOrder(): List<String> = listOf("Zeta", "Alpha", "Bravo", "Delta")
        .map { it to compose.onNodeWithText(it).getBoundsInRoot().top.value }
        .sortedBy { (_, top) -> top }
        .map { (title, _) -> title }

    @Test fun overflowMenuOpensSettingsAndBackReturnsToTheList() {
        compose.setContent { TestApp(AppPreferences()) }
        overflowMenu().performClick()
        compose.onNodeWithText(testString(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(testString(R.string.settings)).assertExists()
        compose.onNodeWithText("ЗАКРЕПЛЁННЫЕ ПРОЕКТЫ").assertExists()
        compose.onNodeWithText("ОСТАЛЬНЫЕ ПРОЕКТЫ").assertExists()
        systemBackToTheList()
        compose.onNodeWithText(testString(R.string.settings)).assertDoesNotExist()
    }

    @Test @Config(sdk = [35], qualifiers = "w700dp-h900dp") fun drawerOffersTheSettingsEntry() {
        compose.setContent { TestApp(AppPreferences()) }
        compose.onNodeWithContentDescription("Открыть навигацию").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(testString(R.string.settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("ЗАКРЕПЛЁННЫЕ ПРОЕКТЫ").assertExists()
        compose.onNodeWithText("ОСТАЛЬНЫЕ ПРОЕКТЫ").assertExists()
    }

    @Test fun forcedRailShowsTheRailAndHidesThePhoneHeader() {
        val preferences = mutableStateOf(AppPreferences())
        compose.setContent { TestApp(preferences.value) { preferences.value = it(preferences.value) } }
        // A phone width keeps the header and the list's own New action.
        compose.onNodeWithContentDescription(testString(R.string.machine_switch)).assertExists()
        compose.onNodeWithContentDescription("Новый тред").assertExists()
        compose.onNodeWithContentDescription("New thread").assertDoesNotExist()
        compose.runOnIdle { preferences.value = AppPreferences(forceRail = true) }
        compose.waitForIdle()
        // The rail is the real one for the window, not a drawing of it: same New thread control and no list FAB.
        compose.onNodeWithContentDescription("New thread").assertExists()
        compose.onNodeWithContentDescription("Новый тред").assertDoesNotExist()
        compose.onNodeWithContentDescription(testString(R.string.machine_switch)).assertDoesNotExist()
    }

    @Test fun forcedWidthSwitchesTheLayoutToTwoPanes() {
        val preferences = mutableStateOf(AppPreferences(forcedWidthDp = 840f))
        compose.setContent {
            TestApp(preferences.value, start = ShellStart(showThread = true)) { preferences.value = it(preferences.value) }
        }
        // 840dp is a two-pane window: rail beside the list, the conversation beside it, and no way "back" from it.
        compose.onNodeWithContentDescription("New thread").assertExists()
        compose.onNodeWithContentDescription("Back to threads").assertDoesNotExist()
        compose.runOnIdle { preferences.value = AppPreferences() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to threads").assertExists()
        compose.onNodeWithContentDescription("New thread").assertDoesNotExist()
    }
}

package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** New thread and the model picker: bottom sheet below 600dp, centred dialog from 600dp, two panes in the new-thread dialog from 840dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class DialogSurfacesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = listOf(ModelOption("codex", "gpt-a", "Model A"), ModelOption("claude", "sonnet", "Model B"))
    private val state = ClientState(projects = listOf(Project("p1", "Alpha"), Project("p2", "Beta")), models = models, canWrite = true)

    private fun newThread() = compose.setContent { TrefoilTheme { NewThreadSheet(state, {}, { _, _ -> }) } }

    /** Close button right edge minus the PROJECT label's left edge: the width of the surface less its paddings (dialog 20 + 16, sheet 20 + 16). */
    private fun spanBetweenLabelAndClose() = compose.onNodeWithText("PROJECT").getBoundsInRoot().left.let { left -> compose.onNodeWithContentDescription("Close").getBoundsInRoot().right - left }

    @Test fun phoneKeepsTheBottomSheetWithOneColumn() {
        newThread()
        compose.onNodeWithText("New thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search projects").assertDoesNotExist()
        assertEquals(400.dp - 20.dp - 16.dp, spanBetweenLabelAndClose())
    }

    @Test @Config(qualifiers = "w700dp-h900dp") fun mediumWindowShowsA520DialogInOneColumn() {
        newThread()
        compose.onNodeWithText("New thread").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search projects").assertDoesNotExist()
        assertEquals(520.dp - 20.dp - 16.dp, spanBetweenLabelAndClose())
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun expandedWindowShowsATwoPaneDialogWithSearchableProjects() {
        newThread()
        compose.onNodeWithContentDescription("Search projects").assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        // PROJECT sits in the 216dp column; the close button ends the 640dp dialog (16dp end padding).
        assertEquals(640.dp - 20.dp - 16.dp, spanBetweenLabelAndClose())
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun twoPaneDialogStartsWithTheTypedPrompt() {
        var started: Pair<String, String>? = null
        compose.setContent { TrefoilTheme { NewThreadSheet(state, {}, { draft, prompt -> started = draft.projectId to prompt }) } }
        compose.onNodeWithText("What should the agent do?").performTextInput("Fix the build")
        compose.onNodeWithText("Beta").performClick()
        compose.onNodeWithContentDescription("Start").performClick()
        compose.runOnIdle { assertEquals("p2" to "Fix the build", started) }
    }

    private val windowPx = mutableStateOf(IntSize.Zero)

    @Composable private fun CrossingSheet() {
        val density = LocalDensity.current
        val info = remember(density) { object : WindowInfo { override val isWindowFocused = true; override val containerSize: IntSize get() = windowPx.value } }
        CompositionLocalProvider(LocalWindowInfo provides info) { TrefoilTheme { NewThreadSheet(state, {}, { _, _ -> }) } }
    }

    private fun setWindowDp(widthDp: Int) { windowPx.value = with(compose.density) { IntSize(widthDp.dp.roundToPx(), 800.dp.roundToPx()) } }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun typedPromptSurvivesEveryPresentationChange() {
        setWindowDp(1000)
        compose.setContent { CrossingSheet() }
        compose.onNodeWithText("What should the agent do?").performTextInput("keep me")
        for (width in listOf(700, 400, 1000, 400, 700)) {
            setWindowDp(width)
            compose.waitForIdle()
            compose.onNodeWithText("keep me").assertIsDisplayed()
        }
    }

    // ---- model picker ----

    private val effort = ModelOptionDescriptor.Select("effort", "Reasoning", choices = listOf(ModelOptionChoice("low", "Low"), ModelOptionChoice("high", "High", isDefault = true)), isReasoning = true)
    private val fast = ModelOptionDescriptor.Toggle("fastMode", "Fast mode")
    private val opus = ModelOption("claude", "opus", "Opus", descriptors = listOf(effort, fast))
    private val gpt = ModelOption("codex", "gpt-5", "GPT-5")
    private val providers = listOf(ProviderInstance("claude", "claudeAgent", displayName = "Claude"), ProviderInstance("codex", "codex", displayName = "Codex"))
    private val isTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun picker() = compose.setContent {
        TrefoilTheme { ModelPickerSheet(listOf(opus, gpt), providers, opus, emptyMap(), true, {}, {}, { _, _ -> }) }
    }

    @Test fun pickerOnAPhoneIsTheSingleColumnSheet() {
        picker()
        // The phone body lays its provider tabs in a row that starts 16dp in; the dialog's rail button sits 4dp from the dialog's edge.
        assertEquals(16.dp, compose.onNode(hasContentDescription("Favourites") and isTab).getBoundsInRoot().left)    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun pickerFromSixHundredDpIsA680DpDialogWithTheTwoPaneBody() {
        picker()
        compose.onNode(hasContentDescription("Claude") and isTab).assertIsDisplayed()
        compose.onNodeWithContentDescription("Reasoning High").assertIsDisplayed()
        // Rail button (4dp inside the 56dp rail) to the options pane's Fast control (12dp inside the right edge): 680 - 4 - 12.
        val left = compose.onNode(hasContentDescription("Favourites") and isTab).getBoundsInRoot().left
        assertEquals(680.dp - 4.dp - 12.dp, compose.onNodeWithContentDescription("Fast mode").getBoundsInRoot().right - left)
    }
}

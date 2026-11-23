package com.tajchert.trefoil.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class AdaptiveSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialogFromSixHundredDp() {
        assertFalse(usesDialog(599f)); assertTrue(usesDialog(600f)); assertTrue(usesDialog(1920f))
    }

    private fun sheet() = compose.setContent {
        TrefoilTheme { TfAdaptiveSheet(onDismiss = {}, dialogMaxWidth = 560.dp) { Text("Sheet body"); Box(Modifier.fillMaxWidth().height(40.dp).testTag("body")) } }
    }

    @Test fun phoneWindowShowsABottomSheet() {
        sheet()
        compose.onNodeWithText("Sheet body").assertExists()
        assertEquals(400.dp, compose.onNodeWithTag("body").getBoundsInRoot().let { it.right - it.left })
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun tabletWindowShowsACappedDialog() {
        sheet()
        compose.onNodeWithText("Sheet body").assertExists()
        // 560, not the 640dp a ModalBottomSheet would cap at: this is the dialog.
        assertEquals(560.dp, compose.onNodeWithTag("body").getBoundsInRoot().let { it.right - it.left })
    }

    @Test fun iconButtonExposesItsLabelAndTooltip() {
        var clicks = 0
        compose.setContent { TrefoilTheme { TfIconButton(TfIcons.Search, "Search", onClick = { clicks++ }) } }
        compose.onNodeWithContentDescription("Search").assertHasClickAction().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithContentDescription("Search").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onAllNodesWithText("Search").assertCountEquals(1)
    }

    @Test fun tooltipLabelIsInSemantics() {
        compose.setContent { TrefoilTheme { TfTooltip("Hide list") { IconButton(onClick = {}) { Icon(TfIcons.Back, "Hide list") } } } }
        compose.onNodeWithContentDescription("Hide list").assertExists()
        compose.onNodeWithContentDescription("Hide list").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNode(hasText("Hide list"), useUnmergedTree = true).assertExists()
    }

    private val windowPx = mutableStateOf(IntSize.Zero)

    @Composable private fun SaveableSheet() {
        val density = LocalDensity.current
        val info = remember(density) {
            object : WindowInfo {
                override val isWindowFocused = true
                override val containerSize: IntSize get() = windowPx.value
            }
        }
        CompositionLocalProvider(LocalWindowInfo provides info) {
            TrefoilTheme {
                TfAdaptiveSheet(onDismiss = {}, dialogMaxWidth = 560.dp) {
                    var draft by rememberSaveable { mutableStateOf("") }
                    Text("Draft: $draft")
                    Button(onClick = { draft += "x" }, Modifier.testTag("type")) { Text("Type") }
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("body"))
                }
            }
        }
    }

    private fun setWindowDp(widthDp: Int) { windowPx.value = with(compose.density) { IntSize(widthDp.dp.roundToPx(), 800.dp.roundToPx()) } }
    private fun bodyWidth() = compose.onNodeWithTag("body").getBoundsInRoot().let { it.right - it.left }

    private fun drafts() = compose.onAllNodes(hasText("Draft:", substring = true)).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].joinToString() }
    private fun assertDraft(text: String) = assertEquals(listOf("Draft: $text"), drafts())

    // Real window 1000dp so the two presentations differ in width: the sheet caps at 640, the dialog at 560.
    @Test @Config(qualifiers = "w1000dp-h800dp") fun saveableContentStateCrossesTheBreakpointBothWays() {
        setWindowDp(400)
        compose.setContent { SaveableSheet() }
        assertEquals(640.dp, bodyWidth())
        compose.onNodeWithTag("type").performClick(); compose.onNodeWithTag("type").performClick()
        assertDraft("xx")
        setWindowDp(1000) // unfold: sheet -> dialog
        compose.waitForIdle()
        assertEquals(560.dp, bodyWidth())
        assertDraft("xx")
        compose.onNodeWithTag("type").performClick()
        setWindowDp(400) // fold: dialog -> sheet
        compose.waitForIdle()
        assertEquals(640.dp, bodyWidth())
        assertDraft("xxx")
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun saveableContentStateSurvivesRecreationInBothPresentations() {
        val restoration = StateRestorationTester(compose)
        setWindowDp(400)
        restoration.setContent { SaveableSheet() }
        compose.onNodeWithTag("type").performClick()
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("x")
        setWindowDp(1000)
        compose.waitForIdle()
        compose.onNodeWithTag("type").performClick()
        restoration.emulateSavedInstanceStateRestore()
        assertDraft("xx")
        assertEquals(560.dp, bodyWidth())
    }
}

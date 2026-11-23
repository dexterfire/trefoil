package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.tajchert.trefoil.ui.ProjectTile
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Font scale 2.0 caps (QA item 7): the rail and collapsed-list badge text, and the monogram of a project tile in the wide list.
 * Robolectric does not measure real glyphs, so the tests read the text style the composables resolve (size in dp = sp × font scale).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp")
class LargeFontCapsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var labelSmallSp = 0f
    private var labelSmallLineSp = 0f

    @Composable private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            TrefoilTheme(darkTheme = false) {
                labelSmallSp = MaterialTheme.typography.labelSmall.fontSize.value
                labelSmallLineSp = MaterialTheme.typography.labelSmall.lineHeight.value
                content()
            }
        }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            node.children.forEach(::visit)
        }
        visit(compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode())
        return results.single()
    }

    private fun badge(fontScale: Float): TextLayoutResult {
        compose.setContent { Scaled(fontScale) { Box(Modifier.testTag("b")) { BadgeText("2") } } }
        return textLayout("b")
    }

    private fun tile(fontScale: Float, cap: Boolean): TextLayoutResult {
        compose.setContent { Scaled(fontScale) { ProjectTile("website", Modifier.testTag("tile"), capFontScale = cap) } }
        return textLayout("tile")
    }

    private fun TextLayoutResult.fontDp(scale: Float) = layoutInput.style.fontSize.value * scale
    private fun TextLayoutResult.lineDp(scale: Float) = layoutInput.style.lineHeight.value * scale

    @Test fun aBadgeKeepsItsLabelStyleAtFontScale1() {
        val layout = badge(1f)
        assertEquals(labelSmallSp, layout.fontDp(1f), 0.01f)
        assertEquals(labelSmallLineSp, layout.lineDp(1f), 0.01f)
    }

    @Test fun aBadgeLabelStopsAt12dpAtFontScale2() {
        val layout = badge(2f)
        assertTrue("font ${layout.fontDp(2f)}dp", layout.fontDp(2f) <= 12.01f)
        assertTrue("line ${layout.lineDp(2f)}dp", layout.lineDp(2f) <= 16.01f)
    }

    @Test fun theMonogramStopsAt14dpAtFontScale2WhenCapped() {
        val layout = tile(2f, cap = true)
        assertEquals("WE", layout.layoutInput.text.text)
        assertEquals(14f, layout.fontDp(2f), 0.01f)
        assertEquals(false, layout.layoutInput.softWrap)
    }

    @Test fun theMonogramIsTodaysThirteenAtFontScale1() {
        assertEquals(13f, tile(1f, cap = true).fontDp(1f), 0.01f)
    }

    @Test fun anUncappedMonogramStillScalesLikeBefore() {
        assertEquals(26f, tile(2f, cap = false).fontDp(2f), 0.01f)
    }
}

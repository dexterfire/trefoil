package com.tajchert.trefoil.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where a centred dialog goes on a flat window, a book-posture fold (vertical hinge) and a tabletop fold (horizontal hinge). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp")
class DialogPlacementTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val book = Fold(FoldOrientation.Vertical, 480f, 520f, separating = true, halfOpened = true)
    private val tabletop = Fold(FoldOrientation.Horizontal, 380f, 420f, separating = true, halfOpened = true)

    @Test fun aFlatWindowKeepsTheWholeWindow() {
        assertNull(dialogRegion(1000f, 800f, null, rtl = false, imeVisible = false))
        assertNull(dialogRegion(1000f, 800f, book.copy(separating = false, halfOpened = false), rtl = false, imeVisible = false))
        assertNull(dialogRegion(1000f, 800f, tabletop.copy(separating = false, halfOpened = false), rtl = false, imeVisible = true))
    }

    @Test fun aBookFoldUsesTheEndHalf() {
        assertEquals(DialogRegion(520f, 0f, 480f, 800f), dialogRegion(1000f, 800f, book, rtl = false, imeVisible = false))
        assertEquals(DialogRegion(0f, 0f, 480f, 800f), dialogRegion(1000f, 800f, book, rtl = true, imeVisible = false))
        // The keyboard does not move a vertical split.
        assertEquals(DialogRegion(520f, 0f, 480f, 800f), dialogRegion(1000f, 800f, book, rtl = false, imeVisible = true))
    }

    @Test fun halfOpenAloneOrSeparatingAloneSplitsToo() {
        assertEquals(520f, dialogRegion(1000f, 800f, book.copy(separating = false), rtl = false, imeVisible = false)!!.left)
        assertEquals(520f, dialogRegion(1000f, 800f, book.copy(halfOpened = false), rtl = false, imeVisible = false)!!.left)
    }

    @Test fun aTabletopUsesTheTallerHalfAndTheTopHalfWithTheKeyboard() {
        // Hinge 380..420: top half 380 tall, bottom half 380 tall (a tie goes to the top).
        assertEquals(DialogRegion(0f, 0f, 1000f, 380f), dialogRegion(1000f, 800f, tabletop, rtl = false, imeVisible = false))
        val lowHinge = tabletop.copy(start = 500f, end = 540f)
        assertEquals(DialogRegion(0f, 0f, 1000f, 500f), dialogRegion(1000f, 800f, lowHinge, rtl = false, imeVisible = false))
        val highHinge = tabletop.copy(start = 200f, end = 240f)
        assertEquals(DialogRegion(0f, 240f, 1000f, 560f), dialogRegion(1000f, 800f, highHinge, rtl = false, imeVisible = false))
        // With the keyboard open it is the top half even when the bottom one is taller.
        assertEquals(DialogRegion(0f, 0f, 1000f, 200f), dialogRegion(1000f, 800f, highHinge, rtl = false, imeVisible = true))
        // RTL changes nothing for a horizontal hinge.
        assertEquals(DialogRegion(0f, 240f, 1000f, 560f), dialogRegion(1000f, 800f, highHinge, rtl = true, imeVisible = false))
    }

    @Test fun aHingeAtTheEdgeFallsBackToTheWholeWindow() {
        assertNull(dialogRegion(1000f, 800f, book.copy(start = 980f, end = 1000f), rtl = false, imeVisible = false))
        assertNull(dialogRegion(1000f, 800f, tabletop.copy(start = 0f, end = 40f), rtl = false, imeVisible = true))
    }

    @Test fun widthIsTheRequestedOneInsideTheRegionLessTwoMargins() {
        assertEquals(640f, dialogMaxWidthDp(640f, null))
        assertEquals(480f - 48f, dialogMaxWidthDp(640f, DialogRegion(520f, 0f, 480f, 800f)))
        assertEquals(400f, dialogMaxWidthDp(400f, DialogRegion(520f, 0f, 480f, 800f)))
        assertEquals(0f, dialogMaxWidthDp(640f, DialogRegion(0f, 0f, 20f, 800f)))
    }

    @Test fun heightIsNinetyPercentOfTheWindowOrTheRequestedOneAndFitsTheRegion() {
        assertEquals(720f, dialogMaxHeightDp(800f, null, null), 0.001f)
        assertEquals(560f, dialogMaxHeightDp(800f, null, 560f), 0.001f)
        assertEquals(380f - 48f, dialogMaxHeightDp(800f, DialogRegion(0f, 0f, 1000f, 380f), null), 0.001f)
        assertEquals(332f, dialogMaxHeightDp(800f, DialogRegion(0f, 0f, 1000f, 380f), 560f), 0.001f)
        // A vertical half spans the window: only the usual cap applies.
        assertEquals(720f, dialogMaxHeightDp(800f, DialogRegion(520f, 0f, 480f, 800f), null), 0.001f)
    }

    private fun dialog(fold: Fold?, direction: LayoutDirection = LayoutDirection.Ltr) = compose.setContent {
        TrefoilTheme {
            CompositionLocalProvider(LocalFold provides fold, LocalLayoutDirection provides direction) {
                TfAdaptiveSheet(onDismiss = {}, dialogMaxWidth = 560.dp) { Box(Modifier.fillMaxWidth().height(40.dp).testTag("body")) }
            }
        }
    }

    @Test fun aBookFoldPutsTheDialogInTheEndHalfAtMostHalfWidthLessMargins() {
        dialog(book)
        val b = compose.onNodeWithTag("body").getBoundsInRoot()
        assertTrue("left ${b.left}", b.left >= 520.dp + 24.dp)
        assertTrue("right ${b.right}", b.right <= 1000.dp - 24.dp)
        assertEquals(480.dp - 48.dp, b.right - b.left)
        // Centred in the half: the 24dp margins sit on both sides (the half is 520..1000).
        assertEquals(520.dp + 24.dp, b.left)
        assertEquals(1000.dp - 24.dp, b.right)
    }

    @Test fun inRtlABookFoldPutsTheDialogInTheLeftHalfOfAnOffCentreHinge() {
        // Hinge 380..420: the end half in RTL is the left one, 0..380. The region is in window coordinates and must not be mirrored.
        dialog(book.copy(start = 380f, end = 420f), LayoutDirection.Rtl)
        val b = compose.onNodeWithTag("body").getBoundsInRoot()
        assertEquals(24.dp, b.left)
        assertEquals(380.dp - 24.dp, b.right)
    }

    @Test fun aFlatFoldKeepsTheCentredFiveSixtyDialog() {
        dialog(book.copy(separating = false, halfOpened = false))
        val b = compose.onNodeWithTag("body").getBoundsInRoot()
        assertEquals(560.dp, b.right - b.left)
    }

    @Test fun aTabletopKeepsTheDialogInsideTheTopHalf() {
        dialog(tabletop)
        val b = compose.onNodeWithTag("body").getBoundsInRoot()
        assertTrue("bottom ${b.bottom}", b.bottom <= 380.dp)
        assertEquals(560.dp, b.right - b.left)
    }
}

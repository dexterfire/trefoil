package com.tajchert.trefoil.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w673dp-h841dp")
class WindowPostureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun verticalHingeMapsToWindowDp() {
        // 1346 x 1682 px window at density 2 = 673 x 841 dp; a 0-wide fold at x = 673 px sits at 336.5 dp.
        assertEquals(Fold(FoldOrientation.Vertical, 336.5f, 336.5f, separating = false, halfOpened = false),
            foldOf(673, 0, 673, 1682, 1346, 1682, 2f, vertical = true, halfOpened = false, separating = false))
        assertEquals(Fold(FoldOrientation.Vertical, 410.5f, 430.5f, separating = true, halfOpened = true),
            foldOf(821, 0, 861, 1346, 1682, 1346, 2f, vertical = true, halfOpened = true, separating = true))
    }

    @Test fun horizontalHingeUsesTheYAxis() {
        assertEquals(Fold(FoldOrientation.Horizontal, 400f, 410f, separating = true, halfOpened = true),
            foldOf(0, 800, 1346, 820, 1346, 1682, 2f, vertical = false, halfOpened = true, separating = true))
    }

    @Test fun featuresThatDoNotSpanOrLieOutsideTheWindowAreIgnored() {
        assertNull(foldOf(673, 100, 673, 1682, 1346, 1682, 2f, vertical = true, halfOpened = false, separating = false))
        assertNull(foldOf(1400, 0, 1400, 1682, 1346, 1682, 2f, vertical = true, halfOpened = false, separating = false))
        assertNull(foldOf(-10, 0, 0, 1682, 1346, 1682, 2f, vertical = true, halfOpened = false, separating = false))
        assertNull(foldOf(0, 800, 1000, 800, 1346, 1682, 2f, vertical = false, halfOpened = true, separating = false))
        assertNull(foldOf(0, 0, 0, 0, 0, 0, 2f, vertical = true, halfOpened = false, separating = false))
    }

    @Test fun providerWithoutAFoldProvidesNullAndDoesNotCrash() {
        compose.setContent { ProvideWindowFold { Text(if (LocalFold.current == null) "no fold" else "fold") } }
        compose.onNodeWithText("no fold").assertExists()
    }

    @Test fun localFoldCanBeOverriddenForPreviews() {
        val fold = Fold(FoldOrientation.Vertical, 336f, 337f, separating = true, halfOpened = false)
        compose.setContent { CompositionLocalProvider(LocalFold provides fold) { Text("width ${LocalFold.current?.width}") } }
        compose.onNodeWithText("width 1.0").assertExists()
    }
}

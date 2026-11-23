package com.tajchert.trefoil.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ScreenCatalog
import com.tajchert.trefoil.core.model.ScreenTarget
import com.tajchert.trefoil.ui.TrefoilTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Screens catalog lays its session cards out in two columns from 600 to 839dp of container width, and in one elsewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h900dp")
class ScreenCatalogColumnsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val first = ScreenTarget.Device("thread", "host-a", "device-1", "android", "Pixel 8")
    private val second = ScreenTarget.Device("thread", "host-b", "device-2", "ios", "Another Phone")
    private val catalog = ScreenCatalog(devices = listOf(first, second), loaded = true)

    private fun show(widthDp: Int) = compose.setContent {
        TrefoilTheme { Box(Modifier.width(widthDp.dp).height(700.dp)) { ScreenCatalogList(catalog, null, true, {}, Modifier.fillMaxSize()) } }
    }

    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test fun columnCountByContainerWidth() {
        assertEquals(1, screenCatalogColumns(399f))
        assertEquals(1, screenCatalogColumns(599f))
        assertEquals(2, screenCatalogColumns(600f))
        assertEquals(2, screenCatalogColumns(839f))
        assertEquals(1, screenCatalogColumns(840f))
    }

    @Test fun at599TheCardsStackInOneColumn() { show(599); assertNotEquals(top("Pixel 8"), top("Another Phone")) }

    @Test fun at600TheCardsShareARow() { show(600); assertEquals(top("Pixel 8"), top("Another Phone")) }

    @Test fun at839TheCardsShareARow() { show(839); assertEquals(top("Pixel 8"), top("Another Phone")) }

    @Test fun at840TheCardsStackInOneColumn() { show(840); assertNotEquals(top("Pixel 8"), top("Another Phone")) }

    @Test fun theTwoCardsOfARowSplitTheWidthEvenly() {
        show(673)
        val a = compose.onNodeWithText("Pixel 8").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithText("Another Phone").getUnclippedBoundsInRoot()
        assertTrue("second column starts after the first, a=$a b=$b", b.left > a.right)
        // Row, not column: the section label stays on its own line above both cards.
        assertTrue(top("DEVICES") < top("Pixel 8"))
    }
}

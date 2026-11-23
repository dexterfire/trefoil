package com.tajchert.trefoil.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.SshConnectionInput
import com.tajchert.trefoil.core.model.SshHostKey
import com.tajchert.trefoil.ui.adaptive.Fold
import com.tajchert.trefoil.ui.adaptive.FoldOrientation
import com.tajchert.trefoil.ui.adaptive.LocalFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The SSH dialogs: the unchanged AlertDialog below 600dp, a 560dp (host key 520dp) dialog with two-column rows from 600dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SshDialogsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var submitted: SshConnectionInput? = null
    private var dismissed = 0

    /** Applies [fontScale] only when asked for: the phone tests run with the Robolectric default density untouched. */
    @Composable private fun Scaled(fontScale: Float?, content: @Composable () -> Unit) {
        if (fontScale == null) content()
        else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale), content)
    }

    private fun ssh(fontScale: Float? = null) = compose.setContent {
        Scaled(fontScale) { TrefoilTheme { SshConnectionDialog(false, null, { dismissed++ }, {}) { submitted = it } } }
    }

    private fun fill() {
        compose.onNodeWithText("Display name").performTextInput("My server")
        compose.onNodeWithText("IP address or hostname").performTextInput(" host.local ")
        compose.onNodeWithText("SSH username").performTextInput("alice ")
        compose.onNodeWithText("SSH password").performScrollTo().performTextInput("secret")
    }

    private fun bounds(label: String) = compose.onNodeWithText(label).getBoundsInRoot()

    private val expected = SshConnectionInput(name = "My server", host = "host.local", username = "alice", port = 22, password = "secret", t3Home = "~/.t3")

    @Test fun phoneIsOneFieldPerRow() {
        ssh()
        val name = bounds("Display name"); val host = bounds("IP address or hostname")
        assertEquals(name.left, host.left)
        assertEquals(name.right, host.right)
        assertTrue(host.top >= name.bottom)
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun wideRowsPairNameWithUsernameAndHostWithPort() {
        ssh()
        val name = bounds("Display name"); val user = bounds("SSH username"); val host = bounds("IP address or hostname"); val port = bounds("SSH port")
        assertEquals(name.top, user.top)
        assertEquals(host.top, port.top)
        assertTrue(host.top > name.top)
        assertTrue(user.left > name.right)
        // Host about 70%, port about 30% of the row.
        val share = (host.right - host.left).value / ((port.right - host.left).value)
        assertEquals(0.7f, share, 0.03f)
        // Dialog 560dp with 24dp inside padding: the row spans 512dp.
        assertEquals(560.dp - 48.dp, user.right - name.left)
    }

    private fun assertLargeFontKeepsEverythingReachable(scale: Float) {
        ssh(scale)
        // One column, so no label is squeezed; the pinned actions stay inside the window and the last field scrolls into view.
        assertTrue(bounds("SSH username").top > bounds("Display name").top)
        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        assertTrue(bounds("Connect").bottom <= 800.dp)
        compose.onNodeWithText("Advanced settings").performScrollTo().assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun fontScale130KeepsTheActionsReachable() = assertLargeFontKeepsEverythingReachable(1.3f)

    @Test @Config(qualifiers = "w1000dp-h800dp") fun fontScale200KeepsTheActionsReachable() = assertLargeFontKeepsEverythingReachable(2f)

    @Test @Config(qualifiers = "w1280dp-h800dp") fun advancedSettingsIsReachableAndFitsOnA1280x800Tablet() {
        ssh()
        // The tightened wide dialog is up to 720dp high, so the toggle is visible without scrolling and, scrolled to, still displayed.
        compose.onNodeWithText("Advanced settings").assertIsDisplayed()
        compose.onNodeWithText("Advanced settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun sameTypedValuesSubmitTheSameInputOnEveryWidth() {
        ssh(); fill()
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { assertEquals(expected, submitted) }
    }

    @Test fun sameTypedValuesSubmitTheSameInputOnAPhone() {
        ssh(); fill()
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { assertEquals(expected, submitted) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun escapeAndCancelDismissTheWideDialog() {
        ssh()
        compose.onNodeWithText("Display name").performKeyInput { pressKey(Key.Escape) }
        compose.runOnIdle { assertEquals(1, dismissed) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(2, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun actionsAreAtLeast48DpTall() {
        ssh()
        for (label in listOf("Connect", "Cancel", "Advanced settings")) {
            if (label == "Advanced settings") compose.onNodeWithText(label).performScrollTo()
            val b = bounds(label)
            assertTrue("$label ${b.bottom - b.top}", b.bottom - b.top >= 48.dp)
        }
    }

    private val windowPx = mutableStateOf(IntSize.Zero)

    @Composable private fun CrossingSsh() {
        val density = LocalDensity.current
        val info = remember(density) { object : WindowInfo { override val isWindowFocused = true; override val containerSize: IntSize get() = windowPx.value } }
        CompositionLocalProvider(LocalWindowInfo provides info) { TrefoilTheme { SshConnectionDialog(false, null, {}, {}) { submitted = it } } }
    }

    private fun setWindowDp(widthDp: Int) { windowPx.value = with(compose.density) { IntSize(widthDp.dp.roundToPx(), 800.dp.roundToPx()) } }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun typedFieldsSurviveCrossingSixHundredDpBothWays() {
        setWindowDp(1000)
        compose.setContent { CrossingSsh() }
        compose.onNodeWithText("Display name").performTextInput("kept")
        for (width in listOf(400, 1000, 700, 400)) {
            setWindowDp(width)
            compose.waitForIdle()
            compose.onNodeWithText("kept").assertIsDisplayed()
        }
    }

    // ---- host key ----

    private val key = SshHostKey("host.local", 22, "ssh-ed25519", "SHA256:" + "A".repeat(43))

    private fun hostKey(fontScale: Float? = null) = compose.setContent {
        Scaled(fontScale) { TrefoilTheme { SshHostKeyDialog(key, { dismissed += 10 }, { dismissed++ }) } }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun hostKeyIs520DpFromSixHundredDp() {
        hostKey()
        // The fingerprint text spans the dialog less its 24dp paddings and the code box's own 12dp padding on each side.
        val block = bounds("host.local:22\nssh-ed25519\nSHA256:" + "A".repeat(43))
        assertEquals(520.dp - 48.dp - 24.dp, block.right - block.left)
        compose.onNodeWithText("Trust and connect").assertIsDisplayed()
        compose.onNodeWithText("Trust and connect").performClick()
        compose.runOnIdle { assertEquals(10, dismissed) }
    }

    // ---- folds: the wide dialogs stay clear of a separating hinge, as TfAdaptiveSheet's dialog does ----

    @Test @Config(qualifiers = "w1000dp-h800dp") fun aBookFoldKeepsTheHostKeyDialogInTheEndHalf() {
        compose.setContent {
            CompositionLocalProvider(LocalFold provides Fold(FoldOrientation.Vertical, 400f, 420f, separating = true, halfOpened = true)) {
                TrefoilTheme { SshHostKeyDialog(key, { dismissed += 10 }, { dismissed++ }) }
            }
        }
        // Hinge 400..420: the 520dp dialog is centred in the end half (420..1000) with at least its 24dp margins.
        val block = bounds("host.local:22\nssh-ed25519\nSHA256:" + "A".repeat(43))
        assertTrue("left ${block.left}", block.left >= 420.dp + 24.dp)
        assertTrue("right ${block.right}", block.right <= 1000.dp - 24.dp)
        assertEquals(520.dp - 48.dp - 24.dp, block.right - block.left)
        assertTrue(bounds("Cancel").left >= 420.dp + 24.dp)
        // A tap on the dialog reaches its button and is not taken for a tap outside.
        compose.onNodeWithText("Trust and connect").performClick()
        compose.runOnIdle { assertEquals(10, dismissed) }
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun aTabletopKeepsTheSshDialogAboveTheHinge() {
        compose.setContent {
            CompositionLocalProvider(LocalFold provides Fold(FoldOrientation.Horizontal, 400f, 420f, separating = true, halfOpened = true)) {
                TrefoilTheme { SshConnectionDialog(false, null, { dismissed++ }, {}) { submitted = it } }
            }
        }
        // Hinge 400..420: the dialog fits the top half, its pinned actions above the hinge and the fields scrolling inside.
        compose.onNodeWithText("Connect").assertIsDisplayed()
        assertTrue("bottom ${bounds("Connect").bottom}", bounds("Connect").bottom <= 400.dp - 24.dp)
        compose.onNodeWithText("SSH password").performScrollTo().assertIsDisplayed()
        assertTrue(bounds("SSH password").bottom <= 400.dp)
    }

    @Test @Config(qualifiers = "w1000dp-h800dp") fun aFlatFoldKeepsTheCentredSshDialog() {
        compose.setContent {
            CompositionLocalProvider(LocalFold provides Fold(FoldOrientation.Vertical, 500f, 500f, separating = false, halfOpened = false)) {
                TrefoilTheme { SshConnectionDialog(false, null, { dismissed++ }, {}) { submitted = it } }
            }
        }
        assertEquals(560.dp - 48.dp, bounds("SSH username").right - bounds("Display name").left)
    }

    @Test fun hostKeyOnAPhoneKeepsTheAlertDialog() {
        hostKey()
        compose.onNodeWithText("Trust and connect").assertIsDisplayed()
        assertNotEquals(520.dp - 48.dp - 24.dp, bounds("host.local:22\nssh-ed25519\nSHA256:" + "A".repeat(43)).let { it.right - it.left })
    }
}

package com.tajchert.trefoil.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A tooltip names its shortcut only while a hardware keyboard is attached; a touch-only device keeps the bare label. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TooltipShortcutHintTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Config(sdk = [35], qualifiers = "qwerty")
    @Test fun aHardwareKeyboardAddsTheChordToTheTooltip() {
        compose.setContent { Text(tooltipWithShortcut("New thread", ShortcutId.NewThread)) }
        compose.onNodeWithText("New thread (Ctrl+N)").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "nokeys")
    @Test fun aTouchOnlyDeviceKeepsTheBareLabel() {
        compose.setContent { Text(tooltipWithShortcut("New thread", ShortcutId.NewThread)) }
        compose.onNodeWithText("New thread").assertIsDisplayed()
    }

    @Config(sdk = [35], qualifiers = "qwerty")
    @Test fun aShortcutWithoutAChordKeepsTheBareLabel() {
        val withoutChord = ShortcutId.entries.firstOrNull { shortcutHint(it) == null } ?: return
        compose.setContent { Text(tooltipWithShortcut("Label", withoutChord)) }
        compose.onNodeWithText("Label").assertIsDisplayed()
    }
}

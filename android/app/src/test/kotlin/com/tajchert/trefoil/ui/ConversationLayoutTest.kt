package com.tajchert.trefoil.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

class ConversationLayoutTest {
    @Test
    fun belowMediumTheConversationIsTheUnchangedPhoneLayout() {
        listOf(0f, 320f, 411f, 599f, 599.9f).forEach { assertSame("at $it", ConversationLayout.Phone, conversationLayoutFor(it)) }
        assertFalse(ConversationLayout.Phone.wide)
    }

    @Test
    fun atMediumTheColumnFillsThePaneLessItsSidePadding() {
        val layout = conversationLayoutFor(600f)
        assertTrue(layout.wide)
        assertEquals(552f, layout.columnWidth)
        assertEquals(24f, layout.sidePadding)
        assertEquals(24f, layout.contentPadding)
        // Prose never exceeds the column; a bubble stays inside its 86% share of it.
        assertEquals(552f, layout.proseMax)
        assertEquals(552f * .86f, layout.bubbleMax, .001f)
    }

    @Test
    fun theColumnCapsAt880AndProseAt680AndBubblesAt560() {
        val layout = conversationLayoutFor(1499f)
        assertEquals(880f, layout.columnWidth)
        assertEquals(680f, layout.proseMax)
        assertEquals(560f, layout.bubbleMax)
        // The list spans the pane: the padding centres the column.
        assertEquals((1499f - 880f) / 2f, layout.contentPadding, .001f)
    }

    @Test
    fun theColumnStartsWhereTheCapsDo() {
        // 928 = 880 + 2 * 24: the first width at which the column is fully capped.
        assertEquals(879f, conversationLayoutFor(927f).columnWidth)
        assertEquals(24f, conversationLayoutFor(928f).contentPadding)
        assertEquals(880f, conversationLayoutFor(928f).columnWidth)
        assertEquals(680f, conversationLayoutFor(859f).proseMax) // 859 - 48 = 811 wide column, prose capped at 680
    }

    @Test
    fun proseFitsTheColumnWhenTheColumnIsNarrowerThanTheCap() {
        assertEquals(600f - 48f, conversationLayoutFor(600f).proseMax)
        assertEquals(680f, conversationLayoutFor(728f).proseMax)
        assertEquals(679f, conversationLayoutFor(727f).proseMax)
    }

    @Test
    fun containerWidthDecidesNotTheWindow() {
        // A 420dp pane inside a wide window is still the phone layout.
        assertSame(ConversationLayout.Phone, conversationLayoutFor(419f))
        assertTrue(conversationLayoutFor(859f).wide)
    }

    @Test
    fun lineBreakReplacesTheSelectionAndMovesTheCursorPastIt() {
        val atEnd = TextFieldValue("hello", TextRange(5)).withLineBreak()
        assertEquals("hello\n", atEnd.text)
        assertEquals(TextRange(6), atEnd.selection)

        val middle = TextFieldValue("hello", TextRange(2)).withLineBreak()
        assertEquals("he\nllo", middle.text)
        assertEquals(TextRange(3), middle.selection)

        val selected = TextFieldValue("hello world", TextRange(5, 11)).withLineBreak()
        assertEquals("hello\n", selected.text)
        assertEquals(TextRange(6), selected.selection)

        val reversed = TextFieldValue("hello world", TextRange(11, 5)).withLineBreak()
        assertEquals("hello\n", reversed.text)

        assertEquals("\n", TextFieldValue("").withLineBreak().text)
    }

    // --- Hardware keyboard Enter ---

    private fun key(
        shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false, meta: Boolean = false,
        down: Boolean = true, repeat: Int = 0, hardware: Boolean = true, composing: Boolean = false, enter: Boolean = true,
    ) = ComposerEnterKey(enter, down, repeat, shift, alt, ctrl, meta, hardware, composing)

    @Test
    fun plainEnterSendsAndCtrlOrMetaEnterSendToo() {
        assertEquals(ComposerEnterDecision.Send, composerEnterDecision(key(), canSend = true))
        assertEquals(ComposerEnterDecision.Send, composerEnterDecision(key(ctrl = true), canSend = true))
        assertEquals(ComposerEnterDecision.Send, composerEnterDecision(key(meta = true), canSend = true))
        assertEquals(ComposerEnterDecision.Send, composerEnterDecision(key(ctrl = true, shift = true), canSend = true))
        assertEquals(ComposerEnterDecision.Send, composerEnterDecision(key(meta = true, alt = true), canSend = true))
    }

    @Test
    fun shiftOrAltEnterInsertsANewline() {
        assertEquals(ComposerEnterDecision.Newline, composerEnterDecision(key(shift = true), canSend = true))
        assertEquals(ComposerEnterDecision.Newline, composerEnterDecision(key(alt = true), canSend = true))
        assertEquals(ComposerEnterDecision.Newline, composerEnterDecision(key(shift = true, alt = true), canSend = true))
        // A newline needs no send permission: the user can keep writing while a send is in flight or the button is Stop.
        assertEquals(ComposerEnterDecision.Newline, composerEnterDecision(key(shift = true), canSend = false))
    }

    @Test
    fun enterNeverSendsWhenThePrimaryActionCannot() {
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(), canSend = false))
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(ctrl = true), canSend = false))
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(meta = true), canSend = false))
    }

    @Test
    fun keyRepeatAndKeyUpNeverSendTwice() {
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(repeat = 1), canSend = true))
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(repeat = 5, ctrl = true), canSend = true))
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(down = false), canSend = true))
        assertEquals(ComposerEnterDecision.Swallow, composerEnterDecision(key(down = false, ctrl = true), canSend = true))
        // The matching key up of a newline is consumed as well, so the field never sees half the pair.
        assertEquals(ComposerEnterDecision.Newline, composerEnterDecision(key(down = false, shift = true), canSend = true))
    }

    @Test
    fun theOnScreenKeyboardKeepsItsNewline() {
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(hardware = false), canSend = true))
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(hardware = false, shift = true), canSend = true))
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(hardware = false, ctrl = true), canSend = true))
    }

    @Test
    fun nothingIsDecidedDuringImeComposition() {
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(composing = true), canSend = true))
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(composing = true, shift = true), canSend = true))
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(composing = true, ctrl = true), canSend = true))
    }

    @Test
    fun otherKeysAreNotOurs() {
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(enter = false), canSend = true))
        assertEquals(ComposerEnterDecision.Ignore, composerEnterDecision(key(enter = false, shift = true), canSend = false))
    }

    @Test
    fun virtualKeyboardAndSoftKeyboardFlagMeanOnScreen() {
        assertTrue(isHardwareKeyboardEvent(deviceId = 3, flags = 0))
        assertFalse(isHardwareKeyboardEvent(deviceId = KeyCharacterMap.VIRTUAL_KEYBOARD, flags = 0))
        assertFalse(isHardwareKeyboardEvent(deviceId = 3, flags = KeyEvent.FLAG_SOFT_KEYBOARD))
        assertFalse(isHardwareKeyboardEvent(deviceId = 3, flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_EDITOR_ACTION))
        assertTrue(isHardwareKeyboardEvent(deviceId = 3, flags = KeyEvent.FLAG_EDITOR_ACTION))
    }
}

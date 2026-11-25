package com.tajchert.trefoil.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The data contract behind the project look: a fixed set of glyphs and colours, both addressed by a stable id, and ink
 * that stays readable on every background in both brightnesses.
 */
class ProjectAppearanceTest {

    @Test fun theGlyphSetOffersThirtyTwoStableIds() {
        assertEquals(32, ProjectIcon.entries.size)
        // Golden list: stored preferences hold these ids, so renaming one silently repaints every project that used it.
        assertEquals(
            listOf(
                "folder", "home", "terminal", "branch", "bolt", "star", "shield", "globe",
                "cube", "server", "database", "cloud", "game", "music", "camera", "map",
                "book", "chart", "wrench", "rocket", "robot", "sparkle", "palette", "file",
                "image", "megaphone", "flask", "puzzle", "mail", "phone", "key", "tag",
            ),
            ProjectIcon.entries.map { it.id },
        )
        // Eight per row in the picker, so thirty-two glyphs draw as four even rows.
        assertEquals(0, ProjectIcon.entries.size % 8)
        assertEquals(32, ProjectIcon.entries.map { it.id }.toSet().size)
        assertTrue(ProjectIcon.entries.all { it.id.isNotBlank() && it.paths.isNotEmpty() })
    }

    @Test fun thePaletteOffersTwelveStableIds() {
        assertEquals(12, ProjectColor.entries.size)
        assertEquals(
            listOf("amber", "pink", "teal", "blue", "violet", "slate", "green", "orange", "red", "cyan", "magenta", "olive"),
            ProjectColor.entries.map { it.id },
        )
        assertEquals(12, ProjectColor.entries.map { it.id }.toSet().size)
        assertTrue(ProjectColor.entries.all { it.id.isNotBlank() })
    }

    @Test fun storedIdsResolveBackToTheirEntryAndUnknownOnesResolveToNothing() {
        assertEquals(ProjectIcon.Tag, ProjectIcon.byId("tag"))
        assertEquals(ProjectColor.Teal, ProjectColor.byId("teal"))
        // A stored id from a newer build, a blank entry or a missing value leaves the project on its default look.
        assertNull(ProjectIcon.byId("no-such-icon"))
        assertNull(ProjectIcon.byId(""))
        assertNull(ProjectIcon.byId(null))
        assertNull(ProjectColor.byId("no-such-colour"))
        assertNull(ProjectColor.byId(""))
        assertNull(ProjectColor.byId(null))
    }

    @Test fun settingALookStoresItsIdAndClearingItRemovesTheEntry() {
        // Stored ids are the enum ids, never ordinals: reordering the set must not repaint a saved project.
        val picked = ListPreferences().withProjectIcon("alpha", ProjectIcon.Tag.id).withProjectColor("alpha", ProjectColor.Teal.id)
        assertEquals(mapOf("alpha" to "tag"), picked.projectIcons)
        assertEquals(mapOf("alpha" to "teal"), picked.projectColors)
        assertEquals(ProjectIcon.Tag, ProjectIcon.byId(picked.projectIcons["alpha"]))
        assertEquals(ProjectColor.Teal, ProjectColor.byId(picked.projectColors["alpha"]))
        // Cleared means gone: an empty id would hash the project back to its initials by luck, not by intent.
        val cleared = picked.withProjectIcon("alpha", null).withProjectColor("alpha", null)
        assertEquals(emptyMap<String, String>(), cleared.projectIcons)
        assertEquals(emptyMap<String, String>(), cleared.projectColors)
    }

    @Test fun everyColourCarriesInkThatStaysReadableInItsOwnBrightness() {
        for (color in ProjectColor.entries) {
            val lightContrast = contrast(color.light, color.lightInk)
            val darkContrast = contrast(color.dark, color.darkInk)
            // WCAG AA for body text: 4.5:1. The tile fills its background with the variant, so the ink is what is read.
            assertTrue("${color.id} light ink at $lightContrast:1", lightContrast >= 4.5)
            assertTrue("${color.id} dark ink at $darkContrast:1", darkContrast >= 4.5)
            // The pair is a light and a dark half of one hue, not two arbitrary values.
            assertTrue("${color.id} light variant is the lighter one", luminance(color.light) > luminance(color.dark))
        }
    }

    /* ---------- WCAG relative luminance, written here so the palette is checked against the spec, not against itself ---------- */

    private fun contrast(a: Long, b: Long): Double = (max(luminance(a), luminance(b)) + 0.05) / (min(luminance(a), luminance(b)) + 0.05)

    private fun luminance(argb: Long): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    private fun channel(value: Long): Double {
        val srgb = value / 255.0
        return if (srgb <= 0.03928) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
    }
}

package com.tajchert.trefoil.ui.theme

import androidx.compose.ui.graphics.Color
import com.tajchert.trefoil.ui.AppPreferences
import com.tajchert.trefoil.ui.BrightnessMode
import com.tajchert.trefoil.ui.TrefoilColors
import com.tajchert.trefoil.ui.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The palette set is data, so it is asserted rather than rendered: six families, both halves of each, every token
 * filled, and copy that stays readable on every surface it is drawn on. Contrast is computed here from the WCAG 2.1
 * formula instead of from anything in production code, so the test can disagree with a palette rather than agree with
 * the way it was written.
 */
class ThemeFamiliesTest {

    private val tokens: List<Pair<String, (TrefoilColors) -> Color>> = listOf(
        "bg" to { it.bg }, "s1" to { it.s1 }, "s2" to { it.s2 }, "s3" to { it.s3 }, "line" to { it.line },
        "text" to { it.text }, "muted" to { it.muted },
        "accent" to { it.accent }, "accentSoft" to { it.accentSoft }, "onAccent" to { it.onAccent },
        "amber" to { it.amber }, "amberSoft" to { it.amberSoft },
        "blue" to { it.blue }, "blueSoft" to { it.blueSoft },
        "green" to { it.green }, "red" to { it.red },
        "sheet" to { it.sheet }, "rail" to { it.rail }, "scrim" to { it.scrim },
    )

    @Test fun shipsSixFamiliesLabelledForThePicker() {
        assertEquals(listOf("Trefoil", "T3 Midnight", "Claude", "Hermes", "Violet", "Teal"), ThemeFamily.entries.map { it.label })
    }

    @Test fun trefoilIsTheFamilyAFreshInstallGets() {
        // The original green is what every screenshot and preview renders, so it stays the default.
        assertEquals(ThemeFamily.Trefoil, AppPreferences().themeFamily)
    }

    @Test fun everyFamilyHasALightAndADarkHalf() {
        for (family in ThemeFamily.entries) {
            assertFalse("${family.label} light", family.light.isDark)
            assertTrue("${family.label} dark", family.dark.isDark)
            assertEquals("${family.label} picks its half", family.light, family.palette(dark = false))
            assertEquals("${family.label} picks its half", family.dark, family.palette(dark = true))
        }
    }

    @Test fun everyPaletteFillsEveryToken() {
        for (family in ThemeFamily.entries) {
            for ((half, palette) in listOf("light" to family.light, "dark" to family.dark)) {
                for ((name, value) in tokens) {
                    val color = value(palette)
                    assertTrue("${family.label} $half $name is unset", color != Color.Unspecified)
                    assertTrue("${family.label} $half $name is transparent", color.alpha > 0f)
                }
            }
        }
    }

    @Test fun bodyAndMutedCopyStayReadableOnEverySurface() {
        for (family in ThemeFamily.entries) {
            for ((half, palette) in listOf("light" to family.light, "dark" to family.dark)) {
                for (surface in listOf("bg" to palette.bg, "s1" to palette.s1, "s2" to palette.s2, "s3" to palette.s3, "sheet" to palette.sheet)) {
                    assertContrast("${family.label} $half text on ${surface.first}", palette.text, surface.second, 4.5)
                }
                for (surface in listOf("bg" to palette.bg, "s1" to palette.s1, "s2" to palette.s2)) {
                    assertContrast("${family.label} $half muted on ${surface.first}", palette.muted, surface.second, 4.5)
                }
                assertContrast("${family.label} $half onAccent on accent", palette.onAccent, palette.accent, 4.5)
            }
        }
    }

    @Test fun accentsAndStatusHuesStandOutFromTheGround() {
        for (family in ThemeFamily.entries) {
            for ((half, palette) in listOf("light" to family.light, "dark" to family.dark)) {
                for (accent in listOf("accent" to palette.accent, "amber" to palette.amber, "blue" to palette.blue, "green" to palette.green, "red" to palette.red)) {
                    // 3.0 is the WCAG minimum for large text and UI edges, which is what these hues carry.
                    assertContrast("${family.label} $half ${accent.first} on bg", accent.second, palette.bg, 3.0)
                }
            }
        }
    }

    @Test fun brightnessResolvesEveryCombination() {
        // System is the only mode that reads the device; Dark and Light override it. Six combinations, one answer each.
        val cases = listOf(
            Triple(BrightnessMode.System, true, true),
            Triple(BrightnessMode.System, false, false),
            Triple(BrightnessMode.Dark, true, true),
            Triple(BrightnessMode.Dark, false, true),
            Triple(BrightnessMode.Light, true, false),
            Triple(BrightnessMode.Light, false, false),
        )
        for ((mode, systemDark, expected) in cases) {
            assertEquals("$mode with systemDark=$systemDark", expected, resolveDarkTheme(mode, systemDark))
        }
    }

    private fun assertContrast(what: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$what is $ratio:1, below $minimum:1", ratio >= minimum)
    }

    /** WCAG 2.1 contrast ratio: `(lighter + 0.05) / (darker + 0.05)` over relative luminance. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG 2.1 relative luminance: linearise each sRGB channel, then weight red 0.2126, green 0.7152, blue 0.0722. */
    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}

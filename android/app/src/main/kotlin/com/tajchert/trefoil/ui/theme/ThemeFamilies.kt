package com.tajchert.trefoil.ui.theme

import androidx.compose.ui.graphics.Color
import com.tajchert.trefoil.ui.TrefoilColors

/**
 * A theme family is one accent identity with a light and a dark half. The brightness switch in settings picks a half;
 * "System" follows the device. Keeping the pair together is what makes "System" meaningful for every family, and it
 * matches how the original two palettes were already structured.
 */
enum class ThemeFamily(val label: String) {
    Trefoil("Trefoil"),
    Midnight("T3 Midnight"),
    Claude("Claude"),
    Hermes("Hermes"),
    Violet("Violet"),
    Teal("Teal"),
    ;

    val light: TrefoilColors get() = lightPalettes.getValue(this)
    val dark: TrefoilColors get() = darkPalettes.getValue(this)

    fun palette(dark: Boolean): TrefoilColors = if (dark) this.dark else this.light
}

/**
 * Every family's dark half. Token names follow the design canvas: `bg` ground, `s1`–`s3` raised surfaces, `line`
 * hairlines, `text`/`muted` copy, `accent`, and the status hues amber (needs you), blue (working), green (done), red
 * (error).
 */
private val darkPalettes: Map<ThemeFamily, TrefoilColors> = mapOf(
    ThemeFamily.Trefoil to TrefoilColors(
        isDark = true,
        bg = Color(0xFF0F1111), s1 = Color(0xFF161919), s2 = Color(0xFF1F2323), s3 = Color(0xFF2A2F2F), line = Color(0xFF272C2C),
        text = Color(0xFFECEFEE), muted = Color(0xFF9CA4A2),
        accent = Color(0xFF8EDB9B), accentSoft = Color(0xFF1B3222), onAccent = Color(0xFF08200F),
        amber = Color(0xFFF4B55B), amberSoft = Color(0xFF35280F),
        blue = Color(0xFF82BCFF), blueSoft = Color(0xFF15243A),
        green = Color(0xFF74D08F), red = Color(0xFFF28B82),
        sheet = Color(0xFF181B1B), rail = Color(0xFF0B0D0D), scrim = Color(0x9E000000),
    ),
    ThemeFamily.Midnight to TrefoilColors(
        isDark = true,
        bg = Color(0xFF0B0F14), s1 = Color(0xFF121821), s2 = Color(0xFF161D26), s3 = Color(0xFF1F2833), line = Color(0xFF1E2732),
        text = Color(0xFFE8EDF4), muted = Color(0xFF8C99A8),
        accent = Color(0xFF7FB2F0), accentSoft = Color(0xFF132033), onAccent = Color(0xFF04121F),
        amber = Color(0xFFF0B860), amberSoft = Color(0xFF2F2614),
        blue = Color(0xFF7FB2F0), blueSoft = Color(0xFF132033),
        green = Color(0xFF6FCB8C), red = Color(0xFFEC8A80),
        sheet = Color(0xFF131A23), rail = Color(0xFF080C11), scrim = Color(0x9E000000),
    ),
    // Warm terracotta on a soft charcoal ground; the light half is the cream paper Claude uses.
    ThemeFamily.Claude to TrefoilColors(
        isDark = true,
        bg = Color(0xFF262624), s1 = Color(0xFF2C2C2A), s2 = Color(0xFF2F2F2C), s3 = Color(0xFF3A3A36), line = Color(0xFF3A3A36),
        text = Color(0xFFF0EEE8), muted = Color(0xFFA8A49B),
        accent = Color(0xFFD97757), accentSoft = Color(0xFF3A2A22), onAccent = Color(0xFF1A0F0A),
        amber = Color(0xFFE0A05C), amberSoft = Color(0xFF352718),
        blue = Color(0xFF8FB4D9), blueSoft = Color(0xFF1E2A33),
        green = Color(0xFF8FBF7F), red = Color(0xFFE08575),
        sheet = Color(0xFF2B2B29), rail = Color(0xFF1E1E1C), scrim = Color(0x9E000000),
    ),
    // Near-black ground with a neon green accent; cold surfaces with a green bias.
    ThemeFamily.Hermes to TrefoilColors(
        isDark = true,
        bg = Color(0xFF0A0F0D), s1 = Color(0xFF101713), s2 = Color(0xFF121A16), s3 = Color(0xFF1A2620), line = Color(0xFF18241F),
        text = Color(0xFFE6F0E9), muted = Color(0xFF87988D),
        accent = Color(0xFF4ADE80), accentSoft = Color(0xFF0F2A1C), onAccent = Color(0xFF04150B),
        amber = Color(0xFFE8C468), amberSoft = Color(0xFF2C2612),
        blue = Color(0xFF6FC6E8), blueSoft = Color(0xFF10242C),
        green = Color(0xFF4ADE80), red = Color(0xFFEF8577),
        sheet = Color(0xFF111916), rail = Color(0xFF070B09), scrim = Color(0x9E000000),
    ),
    ThemeFamily.Violet to TrefoilColors(
        isDark = true,
        bg = Color(0xFF111013), s1 = Color(0xFF17161A), s2 = Color(0xFF1C1A20), s3 = Color(0xFF262330), line = Color(0xFF241F2C),
        text = Color(0xFFEFEBF5), muted = Color(0xFF9C93AB),
        accent = Color(0xFFC9A6FF), accentSoft = Color(0xFF2A2342), onAccent = Color(0xFF150A24),
        amber = Color(0xFFF0B860), amberSoft = Color(0xFF31260F),
        blue = Color(0xFF9BB8F5), blueSoft = Color(0xFF1A2340),
        green = Color(0xFF8FD6A5), red = Color(0xFFEE8D8D),
        sheet = Color(0xFF161519), rail = Color(0xFF0C0B0E), scrim = Color(0x9E000000),
    ),
    ThemeFamily.Teal to TrefoilColors(
        isDark = true,
        bg = Color(0xFF0D1412), s1 = Color(0xFF121A18), s2 = Color(0xFF151E1C), s3 = Color(0xFF1E2A27), line = Color(0xFF1C2825),
        text = Color(0xFFE7F1EF), muted = Color(0xFF8B9B98),
        accent = Color(0xFF6ED8C7), accentSoft = Color(0xFF0F332E), onAccent = Color(0xFF041815),
        amber = Color(0xFFEABB6A), amberSoft = Color(0xFF2E2614),
        blue = Color(0xFF7FC4E8), blueSoft = Color(0xFF112630),
        green = Color(0xFF7FD6A8), red = Color(0xFFED8C82),
        sheet = Color(0xFF121A18), rail = Color(0xFF080F0E), scrim = Color(0x9E000000),
    ),
)

/** Every family's light half: the same accent identity on a bright ground. */
private val lightPalettes: Map<ThemeFamily, TrefoilColors> = mapOf(
    ThemeFamily.Trefoil to TrefoilColors(
        isDark = false,
        bg = Color(0xFFF6F7F6), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFECEFED), s3 = Color(0xFFE1E6E3), line = Color(0xFFDDE2DF),
        text = Color(0xFF111514), muted = Color(0xFF56605D),
        accent = Color(0xFF1E7A3A), accentSoft = Color(0xFFDCF0E0), onAccent = Color(0xFFFFFFFF),
        amber = Color(0xFF955700), amberSoft = Color(0xFFFBEACB),
        blue = Color(0xFF1C5DB5), blueSoft = Color(0xFFDCE8FA),
        green = Color(0xFF1E7A3A), red = Color(0xFFB3261E),
        sheet = Color(0xFFFFFFFF), rail = Color(0xFFEDF0EE), scrim = Color(0x61111514),
    ),
    ThemeFamily.Midnight to TrefoilColors(
        isDark = false,
        bg = Color(0xFFF4F7FA), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFE9EEF4), s3 = Color(0xFFDDE5EE), line = Color(0xFFD8E0E9),
        text = Color(0xFF0E1419), muted = Color(0xFF525F6D),
        accent = Color(0xFF1B5FA8), accentSoft = Color(0xFFDCE8FA), onAccent = Color(0xFFFFFFFF),
        amber = Color(0xFF8A5A00), amberSoft = Color(0xFFFBEACB),
        blue = Color(0xFF1B5FA8), blueSoft = Color(0xFFDCE8FA),
        green = Color(0xFF1B6E3C), red = Color(0xFFAD2A20),
        sheet = Color(0xFFFFFFFF), rail = Color(0xFFEAF0F6), scrim = Color(0x61111514),
    ),
    ThemeFamily.Claude to TrefoilColors(
        isDark = false,
        bg = Color(0xFFFAF9F5), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFF0EEE7), s3 = Color(0xFFE6E3DA), line = Color(0xFFE2DED4),
        text = Color(0xFF1F1E1B), muted = Color(0xFF6B675E),
        accent = Color(0xFFB8552F), accentSoft = Color(0xFFF6E3D9), onAccent = Color(0xFFFFFFFF),
        amber = Color(0xFF8A5A00), amberSoft = Color(0xFFFBEACB),
        blue = Color(0xFF2C5C7A), blueSoft = Color(0xFFDDEAF2),
        green = Color(0xFF4A6B32), red = Color(0xFFA83A28),
        sheet = Color(0xFFFFFFFF), rail = Color(0xFFF3F1EA), scrim = Color(0x61111514),
    ),
    ThemeFamily.Hermes to TrefoilColors(
        isDark = false,
        bg = Color(0xFFF5FAF6), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFE8F2EA), s3 = Color(0xFFDCEAE0), line = Color(0xFFD5E5D9),
        text = Color(0xFF0C1310), muted = Color(0xFF4F6156),
        accent = Color(0xFF17712F), accentSoft = Color(0xFFD4F0DC), onAccent = Color(0xFFFFFFFF),
        amber = Color(0xFF8A5A00), amberSoft = Color(0xFFFBEACB),
        blue = Color(0xFF1B5C7A), blueSoft = Color(0xFFD8EAF2),
        green = Color(0xFF17712F), red = Color(0xFFA83024),
        sheet = Color(0xFFFFFFFF), rail = Color(0xFFEBF3ED), scrim = Color(0x61111514),
    ),
    ThemeFamily.Violet to TrefoilColors(
        isDark = false,
        bg = Color(0xFFF8F6FB), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFEFEBF6), s3 = Color(0xFFE4DEEE), line = Color(0xFFE0D9EA),
        text = Color(0xFF15101C), muted = Color(0xFF5F566B),
        accent = Color(0xFF6B3FA0), accentSoft = Color(0xFFEDE2FA), onAccent = Color(0xFFFFFFFF),
        amber = Color(0xFF8A5A00), amberSoft = Color(0xFFFBEACB),
        blue = Color(0xFF2A4FA6), blueSoft = Color(0xFFDCE8FA),
        green = Color(0xFF33691E), red = Color(0xFFA8332B),
        sheet = Color(0xFFFFFFFF), rail = Color(0xFFF1EEF7), scrim = Color(0x61111514),
    ),
    ThemeFamily.Teal to TrefoilColors(
        isDark = false,
        bg = Color(0xFFF4FAF9), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFE7F2F0), s3 = Color(0xFFDAE9E6), line = Color(0xFFD3E3E0),
        text = Color(0xFF0B1513), muted = Color(0xFF4D605D),
        accent = Color(0xFF0B6A5E), accentSoft = Color(0xFFD6F2EE), onAccent = Color(0xFFFFFFFF),
        amber = Color(0xFF8A5A00), amberSoft = Color(0xFFFBEACB),
        blue = Color(0xFF1C5DB5), blueSoft = Color(0xFFDCE8FA),
        green = Color(0xFF12695A), red = Color(0xFFA83028),
        sheet = Color(0xFFFFFFFF), rail = Color(0xFFEBF4F2), scrim = Color(0x61111514),
    ),
)

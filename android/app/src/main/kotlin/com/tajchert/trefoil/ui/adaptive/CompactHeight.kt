package com.tajchert.trefoil.ui.adaptive

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the WINDOW is shorter than [AdaptiveSpec.CompactHeightBelow] (phone landscape, a short split-screen window).
 * The shell provides it from [ShellPolicy.compactHeight]. Screens read this instead of measuring their own height,
 * which also shrinks whenever the keyboard opens.
 */
internal val LocalCompactHeight = staticCompositionLocalOf { false }

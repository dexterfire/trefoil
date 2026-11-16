/* HTML render frame height and theme bridge adapted from T3 Code shared/htmlRender.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui.htmlrender

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tajchert.trefoil.core.model.HTML_RENDER_MAX_FIT_HEIGHT
import com.tajchert.trefoil.core.model.HTML_RENDER_MAX_HEIGHT
import com.tajchert.trefoil.core.model.HTML_RENDER_MIN_HEIGHT
import com.tajchert.trefoil.core.model.HtmlRenderReference
import com.tajchert.trefoil.ui.TrefoilColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** T3's reply column width: the measurement that decides whether the agent's height caps the fit. */
internal const val HTML_RENDER_COLUMN_WIDTH = 728

/** JavaScript `Math.round` (half up), then clamp to 80..2000. */
internal fun clampHtmlRenderHeight(height: Float): Int =
    min(HTML_RENDER_MAX_HEIGHT, max(HTML_RENDER_MIN_HEIGHT, floor(height + 0.5f).toInt()))

// Use the taller neighboring measurement because breakpoints can select either height.
private fun measuredHeight(heights: List<Pair<Int, Int>>, width: Float): Int {
    val above = heights.indexOfFirst { (measuredWidth) -> measuredWidth >= width }
    val high = if (above == -1) heights.lastIndex else above
    val low = if (heights[high].first.toFloat() == width) high else max(0, high - 1)
    return max(heights[low].second, heights[high].second)
}

/**
 * Port of T3 `htmlRenderFrameHeight`. CSS px are treated as dp. A measured live [contentHeight] wins over
 * the agent's hint, up to [HTML_RENDER_MAX_FIT_HEIGHT]; otherwise the server measurements for [width] are
 * used, capped by the agent height when the page is taller than that at the reply column width.
 */
internal fun htmlRenderFrameHeight(reference: HtmlRenderReference, width: Float, contentHeight: Float? = null): Int {
    if (contentHeight != null) return min(HTML_RENDER_MAX_FIT_HEIGHT, max(HTML_RENDER_MIN_HEIGHT, ceil(contentHeight).toInt()))
    val heights = reference.heights
    if (heights.isEmpty()) return clampHtmlRenderHeight(reference.height.toFloat())
    val cap = if (measuredHeight(heights, HTML_RENDER_COLUMN_WIDTH.toFloat()) > reference.height) reference.height else HTML_RENDER_MAX_HEIGHT
    return clampHtmlRenderHeight(min(cap, measuredHeight(heights, width)).toFloat())
}

/** Appearance plus the CSS variables T3's bootstrap applies to `:root`. */
internal data class HtmlRenderTheme(val dark: Boolean, val variables: Map<String, String>, val backgroundArgb: Int = 0xFF000000.toInt()) {
    val appearance: String get() = if (dark) "dark" else "light"
}

private const val SANS = "-apple-system, BlinkMacSystemFont, \"Segoe UI\", system-ui, sans-serif"
private const val MONO = "\"SF Mono\", \"SFMono-Regular\", Menlo, Consolas, \"Liberation Mono\", monospace"
private val DARK_CHART = listOf("#fbbf24", "#c084fc", "#fb7185", "#a3e635", "#2dd4bf")
private val LIGHT_CHART = listOf("#d97706", "#9333ea", "#e11d48", "#65a30d", "#0d9488")

private fun Color.css(): String = String.format("#%06x", toArgb() and 0xFFFFFF)

/** Maps Trefoil tokens onto every variable name T3 `htmlRenderTheme` emits, in the same order. */
internal fun htmlRenderTheme(colors: TrefoilColors): HtmlRenderTheme {
    val chart = if (colors.isDark) DARK_CHART else LIGHT_CHART
    val variables = linkedMapOf(
        "--background" to colors.bg.css(),
        "--foreground" to colors.text.css(),
        "--muted" to colors.s2.css(),
        "--muted-foreground" to colors.muted.css(),
        "--card" to colors.s1.css(),
        "--card-foreground" to colors.text.css(),
        "--popover" to colors.sheet.css(),
        "--popover-foreground" to colors.text.css(),
        "--secondary" to colors.s2.css(),
        "--secondary-foreground" to colors.text.css(),
        "--border" to colors.line.css(),
        "--input" to colors.s3.css(),
        "--ring" to colors.accent.css(),
        "--primary" to colors.accent.css(),
        "--primary-foreground" to colors.onAccent.css(),
        "--accent" to colors.accent.css(),
        "--accent-foreground" to colors.onAccent.css(),
        "--accent-surface" to colors.accentSoft.css(),
        "--accent-surface-foreground" to colors.text.css(),
        "--destructive" to colors.red.css(),
        "--destructive-foreground" to colors.red.css(),
        "--destructive-surface" to (if (colors.isDark) Color(0xFF3A1A18) else Color(0xFFFADEDC)).css(),
        "--warning" to colors.amber.css(),
        "--warning-foreground" to colors.amber.css(),
        "--warning-surface" to colors.amberSoft.css(),
        "--success" to colors.green.css(),
        "--success-foreground" to colors.green.css(),
        "--info" to colors.blue.css(),
        "--info-foreground" to colors.blue.css(),
        "--code-background" to colors.s2.css(),
        "--code-foreground" to colors.text.css(),
        "--chart-1" to colors.accent.css(),
    )
    chart.forEachIndexed { index, color -> variables["--chart-${index + 2}"] = color }
    variables["--radius"] = "0.625rem"
    variables["--font-sans"] = SANS
    variables["--font-mono"] = MONO
    return HtmlRenderTheme(colors.isDark, variables, colors.bg.toArgb())
}

private fun HtmlRenderTheme.json(): JsonObject = buildJsonObject {
    put("appearance", appearance)
    put("variables", buildJsonObject { variables.forEach { (name, value) -> put(name, value) } })
}

/** `#t3-theme=<encodeURIComponent(JSON.stringify(theme))>`, read by T3's bootstrap before first paint. */
internal fun htmlRenderThemeFragment(theme: HtmlRenderTheme): String = "#t3-theme=" + encodeUriComponent(theme.json().toString())

/** `ui/notifications/host-context-changed` message for a live theme change. */
internal fun htmlRenderThemeMessage(theme: HtmlRenderTheme): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("method", "ui/notifications/host-context-changed")
    put("params", buildJsonObject {
        put("theme", theme.appearance)
        put("styles", buildJsonObject { put("variables", buildJsonObject { theme.variables.forEach { (name, value) -> put(name, value) } }) })
    })
}

/** Posts the theme message to the page's own window, as T3's parent frame would. */
internal fun htmlRenderThemeScript(theme: HtmlRenderTheme): String = "window.postMessage(${htmlRenderThemeMessage(theme)},\"*\");true;"

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

/** JavaScript `encodeURIComponent` over UTF-8. */
internal fun encodeUriComponent(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val char = (byte.toInt() and 0xFF)
        if (char < 0x80 && UNRESERVED.indexOf(char.toChar()) >= 0) append(char.toChar())
        else append('%').append("0123456789ABCDEF"[char shr 4]).append("0123456789ABCDEF"[char and 0xF])
    }
}

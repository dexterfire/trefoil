package com.tajchert.trefoil.ui.htmlrender

import com.tajchert.trefoil.core.model.HtmlRenderReference
import com.tajchert.trefoil.ui.TrefoilDark
import com.tajchert.trefoil.ui.TrefoilLight
import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class HtmlRenderThemeTest {
    private fun ref(height: Int, vararg heights: Pair<Int, Int>) = HtmlRenderReference("fixture", "Fixture", height, heights.toList())

    @Test fun contentHeightWinsAndClampsToFit() {
        val reference = ref(300, 320 to 900)
        assertEquals(124, htmlRenderFrameHeight(reference, 360f, 123.2f))
        assertEquals(80, htmlRenderFrameHeight(reference, 360f, 12f))
        assertEquals(20_000, htmlRenderFrameHeight(reference, 360f, 30_000f))
        assertEquals(4_000, htmlRenderFrameHeight(reference, 360f, 4_000f))
    }

    @Test fun withoutMeasurementsUsesClampedAgentHeight() {
        assertEquals(300, htmlRenderFrameHeight(ref(300), 360f))
        assertEquals(2_000, htmlRenderFrameHeight(ref(5_000), 360f))
        assertEquals(80, htmlRenderFrameHeight(ref(10), 360f))
    }

    @Test fun measuredHeightsUseTallerNeighbour() {
        val reference = ref(600, 320 to 900, 728 to 400, 1144 to 300)
        assertEquals(900, htmlRenderFrameHeight(reference, 320f))
        assertEquals(900, htmlRenderFrameHeight(reference, 360f))
        assertEquals(400, htmlRenderFrameHeight(reference, 728f))
        assertEquals(400, htmlRenderFrameHeight(reference, 900f))
        assertEquals(400, htmlRenderFrameHeight(reference, 2_000f))
        assertEquals(900, htmlRenderFrameHeight(reference, 100f))
    }

    @Test fun agentHeightCapsWhenPageIsTallerAtColumnWidth() {
        val reference = ref(500, 320 to 1_200, 728 to 800)
        assertEquals(500, htmlRenderFrameHeight(reference, 360f))
        assertEquals(500, htmlRenderFrameHeight(reference, 728f))
        // Not taller at the column width: measurements may exceed the agent's hint, up to 2000.
        assertEquals(2_000, htmlRenderFrameHeight(ref(500, 320 to 2_400, 728 to 400), 320f))
    }

    @Test fun clampRoundsHalfUpLikeJavaScript() {
        assertEquals(101, clampHtmlRenderHeight(100.5f))
        assertEquals(100, clampHtmlRenderHeight(100.49f))
        assertEquals(80, clampHtmlRenderHeight(-4f))
    }

    @Test fun themeEmitsEveryT3Variable() {
        val expected = listOf(
            "--background", "--foreground", "--muted", "--muted-foreground", "--card", "--card-foreground", "--popover",
            "--popover-foreground", "--secondary", "--secondary-foreground", "--border", "--input", "--ring", "--primary",
            "--primary-foreground", "--accent", "--accent-foreground", "--accent-surface", "--accent-surface-foreground",
            "--destructive", "--destructive-foreground", "--destructive-surface", "--warning", "--warning-foreground",
            "--warning-surface", "--success", "--success-foreground", "--info", "--info-foreground", "--code-background",
            "--code-foreground", "--chart-1", "--chart-2", "--chart-3", "--chart-4", "--chart-5", "--chart-6", "--radius",
            "--font-sans", "--font-mono",
        )
        val dark = htmlRenderTheme(TrefoilDark)
        val light = htmlRenderTheme(TrefoilLight)
        assertEquals(expected, dark.variables.keys.toList())
        assertEquals(expected, light.variables.keys.toList())
        assertEquals("dark", dark.appearance)
        assertEquals("light", light.appearance)
        assertEquals("#0f1111", dark.variables["--background"])
        assertEquals("#f6f7f6", light.variables["--background"])
        assertEquals("#8edb9b", dark.variables["--accent"])
    }

    @Test fun encodeUriComponentMatchesJavaScript() {
        assertEquals("a%20b%2F%C3%A9%3B'()*!~-_.%23%26%3D%2B%22%7B%7D%3A%2C", encodeUriComponent("a b/é;'()*!~-_.#&=+\"{}:,"))
        assertEquals("%F0%9F%8C%BF", encodeUriComponent("🌿"))
    }

    @Test fun themeFragmentRoundTripsToT3Shape() {
        val theme = htmlRenderTheme(TrefoilDark)
        val fragment = htmlRenderThemeFragment(theme)
        assertTrue(fragment.startsWith("#t3-theme=%7B%22appearance%22%3A%22dark%22%2C%22variables%22%3A%7B%22--background%22%3A%22%230f1111%22"))
        assertFalse(fragment.substringAfter('=').any { it == '#' || it == '&' || it == ' ' || it == '"' })
        val decoded = Json.parseToJsonElement(URLDecoder.decode(fragment.substringAfter('=').replace("+", "%2B"), "UTF-8")).jsonObject
        assertEquals("dark", decoded["appearance"]!!.jsonPrimitive.content)
        assertEquals(theme.variables, decoded["variables"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    @Test fun themeMessageMatchesHostContextChanged() {
        val theme = htmlRenderTheme(TrefoilLight)
        val message = htmlRenderThemeMessage(theme)
        assertEquals("2.0", message["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("ui/notifications/host-context-changed", message["method"]!!.jsonPrimitive.content)
        val params = message["params"]!!.jsonObject
        assertEquals("light", params["theme"]!!.jsonPrimitive.content)
        assertEquals(theme.variables["--foreground"], params["styles"]!!.jsonObject["variables"]!!.jsonObject["--foreground"]!!.jsonPrimitive.content)
        assertTrue(htmlRenderThemeScript(theme).startsWith("window.postMessage({\"jsonrpc\":\"2.0\""))
    }

    @Test fun themeChangedBeforeTheDocumentLoadsIsPostedWhenItFinishes() {
        val dark = htmlRenderTheme(TrefoilDark)
        val light = htmlRenderTheme(TrefoilLight)
        val sync = HtmlRenderThemeSync(dark)
        assertFalse(sync.update(dark))
        // Not loaded yet: record, don't post into a page that isn't there.
        assertFalse(sync.update(light))
        assertEquals(light, sync.latest)
        assertEquals(light, sync.documentFinished())
        // Loaded: later changes post immediately.
        assertTrue(sync.update(dark))
        assertFalse(sync.update(dark))
    }

    @Test fun unchangedThemeIsNotRepostedOnLoad() {
        val dark = htmlRenderTheme(TrefoilDark)
        val sync = HtmlRenderThemeSync(dark)
        assertNull(sync.documentFinished())
        // Changed and changed back before load: the fragment already matches.
        val other = HtmlRenderThemeSync(dark)
        other.update(htmlRenderTheme(TrefoilLight))
        other.update(dark)
        assertNull(other.documentFinished())
    }
}

package com.tajchert.trefoil.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlRenderParsingTest {
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    private fun ref(value: String) = readHtmlRenderReference(json(value))
    private val chart = HtmlRenderReference("render-thread-chart.html", "Chart", 420)
    private val chartJson = """{"attachmentId":"render-thread-chart.html","title":"Chart","height":420}"""
    private fun render(toolName: String?, output: String) = htmlRenderFromToolOutput(toolName, json(output))

    @Test fun compactedWireOutput() {
        assertEquals(chart, render("mcp__t3-code__html_render", """{"htmlRender":$chartJson}"""))
    }

    @Test fun rawMcpEnvelopeStructuredContent() {
        assertEquals(chart, render("mcp__t3-code__html_render", """{"structuredContent":{"htmlRender":$chartJson,"message":"ok"},"content":[]}"""))
    }

    @Test fun contentBlockEnvelopeForms() {
        val text = """{"htmlRender":$chartJson}"""
        // Content block whose text is stringified JSON, and a text object wrapper.
        assertEquals(chart, render("html_render", """{"content":[{"type":"text","text":${JsonPrimitive(text)}}]}"""))
        assertEquals(chart, render("html_render", """[{"type":"text","text":${JsonPrimitive(text)}}]"""))
        assertEquals(chart, render("html_render", """[{"text":{"text":${JsonPrimitive(text)}}}]"""))
        assertEquals(chart, render("html_render", JsonPrimitive(text).toString()))
        assertNull(render("html_render", """{"content":[{"type":"text","text":"not json"}]}"""))
    }

    @Test fun toolNameResolution() {
        val output = """{"htmlRender":$chartJson}"""
        listOf("html_render", "mcp__t3-code__html_render", "mcp__t3_code__html_render", "mcp__T3-Code__html_render",
            "t3-code.html_render", "t3_code:html_render", "t3code/html_render", "mcp__t3-code__html_render complete",
            "t3-code-thread_abc_html_render", "execute").forEach {
            assertEquals(it, chart, render(it, output))
        }
        listOf(null, "", "html_preview", "mcp__other__html_render", "mcp__t3-code__html_preview", "shell", "html_render2").forEach {
            assertNull(it ?: "null", render(it, output))
        }
    }

    @Test fun errorsNeverRender() {
        assertNull(render("html_render", """{"htmlRender":$chartJson,"isError":true}"""))
        assertNull(render("html_render", """{"structuredContent":{"htmlRender":$chartJson},"isError":true}"""))
        assertNull(render("html_render", """{"structuredContent":{"htmlRender":$chartJson,"isError":true}}"""))
        assertNull(render("html_render", """{"is_error":true,"content":[{"type":"text","text":"boom"}],"htmlRender":$chartJson}"""))
        assertNull(render("html_render", """{"htmlRender":$chartJson,"error":{"message":"x"}}"""))
        assertEquals(chart, render("html_render", """{"htmlRender":$chartJson,"isError":false,"error":null}"""))
    }

    @Test fun itemRequiresCompletedDynamicTool() {
        val output = json("""{"htmlRender":$chartJson}""")
        assertEquals(chart, htmlRenderFromToolItem("dynamic_tool", "completed", "mcp__t3-code__html_render", output))
        assertNull(htmlRenderFromToolItem("dynamic_tool", "running", "mcp__t3-code__html_render", output))
        assertNull(htmlRenderFromToolItem("dynamic_tool", "failed", "mcp__t3-code__html_render", output))
        assertNull(htmlRenderFromToolItem("command_execution", "completed", "mcp__t3-code__html_render", output))
        assertNull(htmlRenderFromToolItem("dynamic_tool", "completed", "mcp__t3-code__html_render", null))
    }

    @Test fun attachmentIdBounds() {
        assertNull(ref("""{"attachmentId":"","title":"t","height":100}"""))
        assertNull(ref("""{"attachmentId":"${"a".repeat(257)}","title":"t","height":100}"""))
        assertEquals("a".repeat(256), ref("""{"attachmentId":"${"a".repeat(256)}","title":"t","height":100}""")?.attachmentId)
        assertNull(ref("""{"attachmentId":7,"title":"t","height":100}"""))
        assertNull(ref("""{"title":"t","height":100}"""))
    }

    @Test fun titleTrimFallbackAndCap() {
        assertEquals("Chart", ref("""{"attachmentId":"a","title":"  Chart \n","height":100}""")?.title)
        assertEquals("HTML", ref("""{"attachmentId":"a","title":"   ","height":100}""")?.title)
        assertEquals("HTML", ref("""{"attachmentId":"a","title":"","height":100}""")?.title)
        assertEquals("x".repeat(200), ref("""{"attachmentId":"a","title":"${"x".repeat(250)}","height":100}""")?.title)
        assertNull(ref("""{"attachmentId":"a","title":null,"height":100}"""))
        assertNull(ref("""{"attachmentId":"a","title":5,"height":100}"""))
    }

    @Test fun heightClampAndValidity() {
        fun height(raw: String) = ref("""{"attachmentId":"a","title":"t","height":$raw}""")?.height
        assertEquals(80, height("10"))
        assertEquals(80, height("-5"))
        assertEquals(2000, height("99999"))
        assertEquals(2000, height("1e9"))
        assertEquals(300, height("299.5"))
        assertEquals(299, height("299.4"))
        assertEquals(420, height("420"))
        assertNull(height("\"420\""))
        assertNull(height("null"))
        assertNull(height("true"))
        assertNull(ref("""{"attachmentId":"a","title":"t"}"""))
    }

    @Test fun measuredHeightsSortedAndClamped() {
        val parsed = ref("""{"attachmentId":"a","title":"t","height":400,"heights":[[728,500],[320,9000],[375,40.4]]}""")!!
        assertEquals(listOf(320 to 2000, 375 to 80, 728 to 500), parsed.heights)
    }

    @Test fun measuredHeightsCapAndRejectInvalid() {
        fun heights(count: Int) = (1..count).joinToString(",", "[", "]") { "[${it * 10},200]" }
        assertEquals(24, ref("""{"attachmentId":"a","title":"t","height":400,"heights":${heights(24)}}""")?.heights?.size)
        val tooMany = ref("""{"attachmentId":"a","title":"t","height":400,"heights":${heights(25)}}""")!!
        assertTrue(tooMany.heights.isEmpty())
        assertEquals(400, tooMany.height)
        listOf("[]", "5", "[[320]]", "[[320,200,1]]", "[[0,200]]", "[[10001,200]]", "[[320.5,200]]", "[[\"320\",200]]", "[[320,\"200\"]]",
            "[[320,200],[375]]", "[[320,200],null]").forEach {
            assertTrue(it, ref("""{"attachmentId":"a","title":"t","height":400,"heights":$it}""")!!.heights.isEmpty())
        }
        assertEquals(listOf(320 to 200), ref("""{"attachmentId":"a","title":"t","height":400,"heights":[[320.0,200]]}""")?.heights)
    }

    @Test fun nonObjectReferenceIsRejected() {
        assertNull(readHtmlRenderReference(null))
        assertNull(readHtmlRenderReference(json("[]")))
        assertNull(readHtmlRenderReference(json("\"x\"")))
    }

    @Test fun oversizedEnvelopeStringIsIgnored() {
        val padding = "p".repeat(20_000)
        val text = """{"htmlRender":$chartJson,"pad":"$padding"}"""
        assertNull(render("html_render", """{"content":[{"type":"text","text":${JsonPrimitive(text)}}]}"""))
    }

    @Test fun toStringStaysRedacted() {
        assertFalse(chart.toString().contains("Chart"))
        assertFalse(chart.toString().contains("render-thread"))
    }
}

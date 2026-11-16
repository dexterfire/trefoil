package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.HtmlRenderReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HtmlRenderProjectionTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun projection(item: String) = json("""{"thread":{"id":"t"},"turnItems":[$item]}""")
    private val chart = HtmlRenderReference("render-thread-chart.html", "Chart", 420)
    private val ref = """{"attachmentId":"render-thread-chart.html","title":"Chart","height":420}"""
    private fun item(status: String = "completed", toolName: String = "mcp__t3-code__html_render", output: String? = """{"htmlRender":$ref}""", extra: String = "") =
        """{"id":"render","type":"dynamic_tool","status":"$status","toolName":"$toolName","input":{"title":"Chart","height":420}${output?.let { ""","output":$it""" }.orEmpty()}$extra}"""

    @Test fun compactedOutputSetsReferenceWithoutOutputText() {
        val activity = toolActivities(projection(item(extra = ""","outputOmitted":true"""))).single()
        assertEquals(chart, activity.htmlRender)
        assertNull(activity.output)
        assertNull(activity.input)
        assertFalse(activity.detail.contains("render-thread-chart"))
        assertTrue(activity.outputOmitted)
    }

    @Test fun rawEnvelopeSetsReferenceAndNeverDumpsLargeText() {
        val big = "<html>" + "x".repeat(50_000) + "</html>"
        val output = """{"structuredContent":{"htmlRender":$ref,"message":${JsonPrimitive(big)}},"content":[{"type":"text","text":${JsonPrimitive(big)}}]}"""
        val activity = toolActivities(projection(item(output = output, extra = ""","input":{"html":${JsonPrimitive(big)}}"""))).single()
        assertEquals(chart, activity.htmlRender)
        assertNull(activity.output)
        assertTrue(activity.detail.length < 1000)
    }

    @Test fun executeToolNameDecodes() {
        assertEquals(chart, toolActivities(projection(item(toolName = "execute"))).single().htmlRender)
    }

    @Test fun errorRunningAndFailedStatusProduceNoReference() {
        val isError = toolActivities(projection(item(output = """{"htmlRender":$ref,"isError":true}"""))).single()
        assertNull(isError.htmlRender)
        assertTrue(isError.output.orEmpty().contains("isError"))
        assertNull(toolActivities(projection(item(status = "running"))).single().htmlRender)
        assertNull(toolActivities(projection(item(status = "failed"))).single().htmlRender)
        assertNull(toolActivities(projection(item(output = null))).single().htmlRender)
    }

    @Test fun invalidReferenceProducesNone() {
        val bad = """{"htmlRender":{"attachmentId":"${"a".repeat(257)}","title":"Chart","height":420}}"""
        assertNull(toolActivities(projection(item(output = bad))).single().htmlRender)
    }

    @Test fun normalizationAppliesThroughProjection() {
        val output = """{"htmlRender":{"attachmentId":"a","title":"  ","height":5,"heights":[[728,500],[320,300]]}}"""
        val reference = toolActivities(projection(item(output = output))).single().htmlRender!!
        assertEquals("HTML", reference.title)
        assertEquals(80, reference.height)
        assertEquals(listOf(320 to 300, 728 to 500), reference.heights)
    }

    @Test fun unrelatedDynamicToolsAreUnaffected() {
        val other = toolActivities(projection("""{"id":"o","type":"dynamic_tool","status":"completed","toolName":"mcp__t3-code__html_preview","input":"look","output":{"text":"shot"}}""")).single()
        assertNull(other.htmlRender)
        assertEquals("look", other.input)
        assertTrue(other.output.orEmpty().contains("shot"))
        val shell = toolActivities(projection("""{"id":"c","type":"command_execution","status":"completed","toolName":"mcp__t3-code__html_render","output":{"htmlRender":$ref}}""")).single()
        assertNull(shell.htmlRender)
    }

    @Test fun hydrationReplacesCompactedOutputWithFullEnvelope() {
        val compact = projection(item(output = """{"htmlRender":$ref}""", extra = ""","outputOmitted":true"""))
        val full = json(item(output = """{"structuredContent":{"htmlRender":$ref,"message":"done"},"content":[]}"""))
        val activity = toolActivities(hydrateActivityProjection(compact, "render", full)).single()
        assertEquals(chart, activity.htmlRender)
        assertFalse(activity.outputOmitted)
        assertNull(activity.output)
    }

    @Test fun hydrationOfOmittedOutputWithoutReferenceThenLoadsReference() {
        // Wire item with no output at all (omitted) yields no reference until the body is loaded.
        val compact = projection(item(output = null, extra = ""","outputOmitted":true"""))
        assertNull(toolActivities(compact).single().htmlRender)
        val full = json(item(output = """{"structuredContent":{"htmlRender":$ref}}"""))
        assertEquals(chart, toolActivities(hydrateActivityProjection(compact, "render", full)).single().htmlRender)
    }

    @Test fun visibleTurnItemsRowsKeepOrdinalAndReference() {
        val projection = json("""{"thread":{"id":"t"},"visibleTurnItems":[{"position":3,"item":${item()}}]}""")
        val activity = toolActivities(projection).single()
        assertEquals(chart, activity.htmlRender)
        assertEquals(3, activity.ordinal)
    }
}

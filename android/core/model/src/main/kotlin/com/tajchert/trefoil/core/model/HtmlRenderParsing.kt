/* HTML render parsing adapted from T3 Code shared/htmlRender.ts, toolOutput.ts and
 * t3McpToolPresentation.ts (MIT). Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

const val HTML_RENDER_TOOL_NAME = "html_render"
const val HTML_RENDER_MAX_TITLE_LENGTH = 200
private const val MAX_ATTACHMENT_ID_LENGTH = 256
private const val MAX_MEASURED_HEIGHTS = 24
private const val MAX_MEASURED_WIDTH = 10_000

// Envelope read limits mirror T3's toolOutput.ts so hostile outputs stay bounded.
private const val MAX_PARSED_BYTES = 16_384
private const val MAX_CONTENT_BLOCKS = 32
private const val MAX_ENVELOPE_DEPTH = 4
private const val MAX_ENVELOPE_NODES = 128

/** OpenCode code mode reports a script's nested T3 tool calls as one `execute` item. */
private const val OPENCODE_CODE_MODE_TOOL_NAME = "execute"

private val t3McpServerAliases = setOf("t3-code", "t3_code", "t3code", "t3-code-direct", "t3_code_direct")
private val completedSuffix = Regex("\\s+(?:complete|completed)\\s*$", RegexOption.IGNORE_CASE)
private val mcpToolName = Regex("^mcp__(.+?)__(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val t3Namespace = Regex("^(?:t3[-_]code[-_]direct|t3-code|t3_code|t3code)(?:[.:/]|\\s*·\\s*)(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val t3Prefixed = Regex("^(?:mcp[-_]{1,2})?t3[-_ ]?code(?:__|[-_.:/ ])(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * True when [toolName] is T3's `html_render` MCP tool (any MCP/namespace prefix T3 recognises, such as
 * `mcp__t3-code__html_render`) or OpenCode code mode's `execute`.
 */
fun isHtmlRenderToolName(toolName: String?): Boolean {
    if (toolName == null) return false
    if (toolName == OPENCODE_CODE_MODE_TOOL_NAME) return true
    val label = toolName.replace(completedSuffix, "").trim()
    mcpToolName.find(label)?.let { match ->
        return match.groupValues[1].lowercase() in t3McpServerAliases && match.groupValues[2] == HTML_RENDER_TOOL_NAME
    }
    t3Namespace.find(label)?.let { return it.groupValues[1] == HTML_RENDER_TOOL_NAME }
    val candidate = t3Prefixed.find(label)?.groupValues?.get(1) ?: label
    if (candidate == HTML_RENDER_TOOL_NAME) return true
    // OpenCode 2 thread prefixes can contain underscores: T3 matches the known tool name as a suffix.
    return label.startsWith("t3-code-", ignoreCase = true) && label.endsWith("_$HTML_RENDER_TOOL_NAME")
}

/** T3's `clampHtmlRenderHeight`: round half up, then clamp to 80..2000. */
fun clampHtmlRenderHeight(height: Double): Int =
    floor(height + 0.5).coerceIn(HTML_RENDER_MIN_HEIGHT.toDouble(), HTML_RENDER_MAX_HEIGHT.toDouble()).toInt()

/** The finite JSON number held by [element], or null for strings, booleans, null and non-finite values. */
private fun JsonElement?.finiteNumber(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun readMeasuredHeights(value: JsonElement?): List<Pair<Int, Int>>? {
    val array = value as? JsonArray ?: return null
    if (array.isEmpty() || array.size > MAX_MEASURED_HEIGHTS) return null
    val heights = array.map { entry ->
        val pair = entry as? JsonArray ?: return null
        if (pair.size != 2) return null
        val width = pair[0].finiteNumber()?.takeIf { it == floor(it) && it >= 1.0 && it <= MAX_MEASURED_WIDTH } ?: return null
        val height = pair[1].finiteNumber() ?: return null
        width.toInt() to clampHtmlRenderHeight(height)
    }
    return heights.sortedBy { it.first }
}

/** Port of T3's `readHtmlRenderReference`: null when [value] is not a valid reference. */
fun readHtmlRenderReference(value: JsonElement?): HtmlRenderReference? {
    val obj = value as? JsonObject ?: return null
    val attachmentId = (obj["attachmentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (attachmentId.isEmpty() || attachmentId.length > MAX_ATTACHMENT_ID_LENGTH) return null
    val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val height = obj["height"].finiteNumber() ?: return null
    return HtmlRenderReference(
        attachmentId = attachmentId,
        title = title.trim().take(HTML_RENDER_MAX_TITLE_LENGTH).ifEmpty { "HTML" },
        height = clampHtmlRenderHeight(height),
        heights = readMeasuredHeights(obj["heights"]).orEmpty(),
    )
}

private class ResultReadBudget(var remainingBytes: Int = MAX_PARSED_BYTES, var remainingNodes: Int = MAX_ENVELOPE_NODES, var exceeded: Boolean = false)
private class ResultEnvelope(val data: JsonObject? = null, val failed: Boolean = false)

/** Port of `readResult` in T3's toolOutput.ts: unwraps stringified JSON, content blocks and MCP envelopes. */
private fun readResult(value: JsonElement?, budget: ResultReadBudget, depth: Int = 0): ResultEnvelope {
    if (depth > MAX_ENVELOPE_DEPTH || budget.remainingNodes-- <= 0) {
        budget.exceeded = true
        return ResultEnvelope()
    }
    when (value) {
        is JsonPrimitive -> {
            if (!value.isString) return ResultEnvelope()
            val text = value.content
            if (text.length > budget.remainingBytes) { budget.exceeded = true; return ResultEnvelope() }
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (bytes > budget.remainingBytes) { budget.exceeded = true; return ResultEnvelope() }
            budget.remainingBytes -= bytes
            val parsed = try { Json.parseToJsonElement(text) } catch (_: Exception) { return ResultEnvelope() }
            return readResult(parsed, budget, depth + 1)
        }
        is JsonArray -> {
            if (value.size > MAX_CONTENT_BLOCKS) { budget.exceeded = true; return ResultEnvelope() }
            var data: JsonObject? = null
            var failed = false
            for (block in value) {
                val text = (block as? JsonObject)?.get("text")
                val inner = if (text is JsonObject) text["text"]?.takeIf { it !is JsonNull } ?: text else text
                val result = readResult(inner, budget, depth + 1)
                if (data == null) data = result.data
                failed = failed || result.failed
                if (budget.exceeded) break
            }
            return ResultEnvelope(data, failed)
        }
        is JsonObject -> {
            fun present(key: String) = value[key]?.takeIf { it !is JsonNull }
            val failed = (value["isError"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true ||
                (value["is_error"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true ||
                (value["_tag"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "OrchestratorMcpFailure" ||
                present("error") != null
            val content = present("structuredContent") ?: value["content"]
            if (content != null) {
                val nested = readResult(content, budget, depth + 1)
                return ResultEnvelope(nested.data, failed || nested.failed)
            }
            return ResultEnvelope(value, failed)
        }
        else -> return ResultEnvelope()
    }
}

/**
 * Port of T3's `htmlRenderFromToolItem` for a `dynamic_tool` item: null unless [toolName] is `html_render`
 * (or OpenCode's `execute`) and [output] carries a valid reference without an error. [output] is either the
 * compacted wire form `{htmlRender:{...}}` or the raw MCP envelope (`structuredContent.htmlRender`, content
 * blocks, stringified JSON). The caller checks that the item is completed.
 */
fun htmlRenderFromToolOutput(toolName: String?, output: JsonElement?): HtmlRenderReference? {
    if (!isHtmlRenderToolName(toolName)) return null
    val budget = ResultReadBudget()
    val result = readResult(output, budget)
    if (result.failed || budget.exceeded) return null
    return readHtmlRenderReference(result.data?.get("htmlRender"))
}

/** [htmlRenderFromToolOutput] for a thread item: requires a `dynamic_tool` type and `completed` status. */
fun htmlRenderFromToolItem(type: String?, status: String?, toolName: String?, output: JsonElement?): HtmlRenderReference? =
    if (type == "dynamic_tool" && status == "completed") htmlRenderFromToolOutput(toolName, output) else null

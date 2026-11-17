/* Work-log presentation adapted from T3 Code's client-runtime work-log (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.ToolActivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Pure presentation contract for the thread work log. The Compose layer (ConversationParts.kt) only
 * consumes these; grouping and folding live in ConversationTimeline.kt.
 *
 * Trefoil's `ToolActivity.kind` is the T3 turn item `type` (core/session PresentationProjection.toolActivities):
 * `command_execution`, `file_change`, `file_search`, `web_search`, `dynamic_tool`, `reasoning`, `subagent`, `error`, ...
 * Claude's Read/Grep/Glob/LS arrive as `dynamic_tool`; the server titles Read calls `Read <path>` and searches
 * `Searched …`. `title` is the item title, falling back to the raw tool name (e.g. `mcp__t3-code__html_preview`),
 * so T3 MCP tools are recognised from the title. `file_read`/`read` kinds are accepted for older fixtures.
 */

/** Glyph for a work row; mapped to an icon by the UI. Mirrors T3 `toolGroupSummaryIconName`/`workEntryIconName`. */
internal enum class WorkIcon { Read, Edit, Command, CodeSearch, WebSearch, Browser, Device, Agent, Html, Other, Mixed, Reasoning, Error }

/** Header for a multi-entry tool group ("Ran 1 command and read 2 files"). [hasFailure] is for accessibility only. */
internal data class WorkGroupSummary(val label: String, val icon: WorkIcon, val hasFailure: Boolean)

/**
 * T3 `workEntryDisplayIndicatesToolFailure`: error items, failed/declined status, failure text, a failed T3 tool result,
 * then for commands the exit code (which outranks output text), outputIndicatesFailure and the output text heuristic.
 * Cancelled/interrupted/stopped are not failures. Reasoning prose is never scanned.
 */
internal val ToolActivity.displayFailure: Boolean get() {
    if (kind == "error" || status in failedStatuses) return true
    if (kind == "reasoning" || kind in nonToolKinds) return false
    if (!failure.isNullOrBlank()) return true
    if (t3Tool != null && t3ResultIndicatesFailure(output)) return true
    if (kind == "command_execution") {
        exitCode?.let { return it != 0 }
        if (outputIndicatesFailure == true) return true
        return output?.let { toolOutputIndicatesFailure(it.take(maxScannedOutput)) } == true
    }
    return outputIndicatesFailure == true || output?.let { toolOutputIndicatesFailure(it.take(maxScannedOutput)) } == true
}

/** Provider error items (kind `error`) and other severe failures render prominently (destructive colour); everything else stays subtle. */
internal val ToolActivity.severeFailure: Boolean get() = kind == "error"

/**
 * Activities shown inside a group: reasoning only with non-blank text (or omitted output the user can load). Everything else stays
 * visible, including running entries outside the live run (their outcome is unknown and must remain inspectable).
 */
internal fun ToolActivity.visibleInGroup(): Boolean =
    if (kind == "reasoning") reasoningText.isNotBlank() || outputOmitted || detailsOmitted else true

/** Reasoning body text (output, falling back to detail). */
internal val ToolActivity.reasoningText: String get() = output?.takeIf { it.isNotBlank() } ?: detail

/**
 * Port of T3 `summarizeToolGroup`: reasoning is dropped (reasoning-only groups give "Thought" / "Thought (×N)"), the rest is
 * bucketed by action in first-seen order, at most two buckets are named (commands/edits first, generic tools last), the
 * remainder becomes "Performed N other actions", and labels join as a sentence. Icon follows T3 `toolGroupSummaryKind`.
 */
internal fun summarizeWorkGroup(activities: List<ToolActivity>): WorkGroupSummary {
    val tools = activities.filter { it.kind != "reasoning" }
    if (activities.isNotEmpty() && tools.isEmpty())
        return WorkGroupSummary(if (activities.size == 1) "Thought" else "Thought (×${activities.size})", WorkIcon.Reasoning, false)
    val buckets = LinkedHashMap<WorkAction, MutableList<ToolActivity>>()
    tools.forEach { buckets.getOrPut(it.workAction) { mutableListOf() } += it }
    val summaries = buckets.entries.mapIndexed { index, (action, entries) -> Triple(index, action, entries) }
    val selected = summaries.sortedWith(compareBy({ it.second.priority }, { it.first })).take(2).sortedBy { it.first }
    val labels = selected.map { (_, action, entries) -> actionLabel(action, entries) }.toMutableList()
    val remaining = tools.size - selected.sumOf { it.third.size }
    if (remaining > 0) labels += "Performed $remaining other ${if (remaining == 1) "action" else "actions"}"
    val sentence = labels.mapIndexed { index, label -> if (index == 0) label else label.replaceFirstChar { it.lowercaseChar() } }
    val summary = if (sentence.size < 3) sentence.joinToString(" and ") else sentence.dropLast(1).joinToString(", ") + ", and " + sentence.last()
    val actions = buckets.keys
    val icon = when {
        actions.size != 1 -> WorkIcon.Mixed
        actions.first() == WorkAction.Other -> if (tools.all { it.kind == "subagent" }) WorkIcon.Agent else WorkIcon.Other
        else -> actions.first().icon
    }
    return WorkGroupSummary(summary, icon, tools.any { it.displayFailure })
}

/** Label for a group holding exactly one visible activity (no header): T3 `singleToolCallLabel`, with edits summarised ("Changed 1 file"). */
internal fun singleWorkLabel(activity: ToolActivity): String {
    if (activity.kind == "reasoning") return thoughtPreview(activity.reasoningText).ifBlank { "Thought" }
    if (activity.workAction == WorkAction.Edit) return summarizeWorkGroup(listOf(activity)).label
    t3ToolDisplayName(activity, activity.lifecycle)?.let { return it }
    if (activity.kind == "web_search") return activity.meaningfulTitle ?: "Web search"
    return workEntryLabel(activity)
}

/** Label for one row inside an expanded group (T3 `workEntryDisplayLabel`): command text, "Read <path>", file path, search, thought preview, tool title. */
internal fun workEntryLabel(activity: ToolActivity): String {
    if (activity.kind == "system_notice") return activity.title
    if (activity.kind == "reasoning") return thoughtPreview(activity.reasoningText).ifBlank { "Thought" }
    t3ToolDisplayName(activity, activity.lifecycle)?.let { return it }
    val command = activity.input?.takeIf { activity.kind == "command_execution" && it.isNotBlank() }
    if (command != null) return commandDisplayText(command)
    val action = activity.workAction
    if (action == WorkAction.CodeSearch || action == WorkAction.WebSearch) searchLabel(activity)?.let { return it }
    if (action == WorkAction.Read) return activity.readPath?.let { "Read $it" } ?: "Read file"
    if (activity.kind == "file_change") activity.fileName?.takeIf { it.isNotBlank() }?.let { return it }
    activity.meaningfulTitle?.let { title -> return normalizeCompactToolLabel(title).replaceFirstChar { it.uppercaseChar() } }
    return when (activity.kind) {
        "command_execution" -> "Command"
        "file_change" -> "File change"
        "file_search" -> "Searched files"
        "web_search" -> "Web search"
        else -> normalizeCompactToolLabel(activity.title).replaceFirstChar { it.uppercaseChar() }
    }
}

internal fun workEntryIcon(activity: ToolActivity): WorkIcon = when (activity.kind) {
    "error" -> WorkIcon.Error
    "reasoning" -> WorkIcon.Reasoning
    "subagent" -> WorkIcon.Agent
    else -> activity.workAction.icon
}

/**
 * Live row label for the latest in-progress activity (T3 `workLiveLabel`): the current thought line (or "Thinking"),
 * a T3 tool's present tense ("Previewing an HTML page"), "Running gradlew" for commands, else the entry label.
 */
internal fun liveWorkLabel(activity: ToolActivity): String {
    if (activity.kind == "reasoning") return thoughtPreview(activity.reasoningText).ifBlank { "Thinking" }
    val status = activity.lifecycle.let { if (it == Lifecycle.Failed || it == Lifecycle.Declined || it == Lifecycle.Stopped) it else Lifecycle.InProgress }
    t3ToolDisplayName(activity, status)?.let { return it }
    val command = activity.input?.trim()?.takeIf { activity.kind == "command_execution" && it.isNotEmpty() }
    if (command != null) {
        val verb = when (status) { Lifecycle.Failed -> "Failed"; Lifecycle.Declined -> "Declined"; Lifecycle.Stopped -> "Stopped"; else -> "Running" }
        return "$verb ${commandProgramName(command) ?: "command"}"
    }
    return workEntryLabel(activity)
}

/** Port of T3 `liveThoughtLine`: a leading **Heading** line wins, else the first sentence; markdown markers are stripped. */
internal fun thoughtPreview(markdown: String): String {
    val source = markdown.take(maxThoughtSource)
    val heading = thoughtHeading.find(source)?.groupValues?.get(1)
    val text = (heading ?: source)
        .replace(markdownLink, "\$1")
        .replace(markdownListMarker, "")
        .replace(markdownCodeBoldStrike, "")
        .replace(markdownEmphasis, "\$1\$2")
        .replace(whitespace, " ")
        .trim()
    if (heading != null) return text
    val end = sentenceEnd.find(text) ?: return text
    return text.substring(0, end.range.last + 1)
}

/** Distinct images produced by a group's activities, in order, for the thumbnail strip under the group. */
internal fun viewedImages(activities: List<ToolActivity>): List<RemoteFileReference> =
    activities.flatMap { it.images }.distinctBy { it.identity }

/**
 * Simplified port of T3 `commandProgramName`: unwraps `bash -lc '…'`, skips `cd`/`export` segments, env assignments and
 * prefixes such as `sudo`/`env`/`time`, and returns the program basename. `cd /x; FOO=1 ./gradlew test` → `gradlew`.
 */
internal fun commandProgramName(command: String): String? {
    for (segment in shellSegments(withoutShellWrapper(command)) ?: return null) {
        val tokens = shellTokens(segment) ?: return null
        var index = 0
        while (index < tokens.size && (envAssignment.matches(tokens[index]) || tokens[index] in commandPrefixes ||
                (tokens[index].startsWith("-") && index > 0 && tokens[index - 1] in commandPrefixes))) index++
        val token = tokens.getOrNull(index) ?: continue
        if (token in navigationCommands) continue
        val program = token.split('/', '\\').last().removeSuffix(".exe").removeSuffix(".EXE")
        return program.takeIf { it.isNotBlank() }
    }
    return null
}

/** T3 `commandDisplayText`: drops a single-script shell wrapper and shows control characters as Unicode control pictures. */
internal fun commandDisplayText(command: String): String =
    boundedLabel(withoutShellWrapper(command).replace(controlCharacters) { match ->
        val char = match.value[0]
        if (char == '\u007f') "␡" else (0x2400 + char.code).toChar().toString()
    })

/** Row labels are single-line: whitespace (newlines included) collapses to one space and the text is capped at [maxLabelLength] characters. */
internal const val maxLabelLength = 200

internal fun boundedLabel(text: String): String {
    val collapsed = text.replace(whitespace, " ").trim()
    return if (collapsed.length > maxLabelLength) collapsed.take(maxLabelLength - 1).trimEnd() + "…" else collapsed
}

// region T3 tool classification

private enum class WorkAction(val priority: Int, val icon: WorkIcon) {
    Command(0, WorkIcon.Command), Edit(0, WorkIcon.Edit), Read(1, WorkIcon.Read), CodeSearch(1, WorkIcon.CodeSearch),
    WebSearch(1, WorkIcon.WebSearch), HtmlPreview(1, WorkIcon.Html), HtmlRender(1, WorkIcon.Html),
    Browser(1, WorkIcon.Browser), Device(1, WorkIcon.Device), Other(2, WorkIcon.Other),
}

/** T3 tool lifecycle (`inProgress | completed | failed | declined | stopped`) mapped from Trefoil's raw status strings. */
private enum class Lifecycle { InProgress, Completed, Failed, Declined, Stopped, Unknown }

private val failedStatuses = setOf("error", "failed", "failure", "declined")
private val completedStatuses = setOf("completed", "succeeded", "success", "done")
private val stoppedStatuses = setOf("stopped", "interrupted", "cancelled", "canceled")
/** Kinds T3 does not treat as tool calls, so their text is never scanned for failure phrases. */
private val nonToolKinds = setOf("notification", "system_notice", "proposed_plan", "todo_list", "compaction", "checkpoint",
    "handoff", "fork", "thread_created", "run_interrupt_request", "run_interrupt_result")

private val ToolActivity.lifecycle: Lifecycle get() = when {
    status == "declined" -> Lifecycle.Declined
    status in failedStatuses || (t3Tool != null && t3ResultIndicatesFailure(output)) -> Lifecycle.Failed
    isRunning -> Lifecycle.InProgress
    status in completedStatuses -> Lifecycle.Completed
    status in stoppedStatuses -> Lifecycle.Stopped
    else -> Lifecycle.Unknown
}

/** T3 MCP tools (subset of shared/t3McpToolPresentation): labels are action, running, completed, object. */
private class T3Tool(val action: WorkAction, val verb: String, val running: String, val completed: String, val detail: String)

private val t3Tools: Map<String, T3Tool> = buildMap {
    fun tool(name: String, action: WorkAction, labels: String) {
        val (verb, running, completed, detail) = labels.split('|')
        put(name, T3Tool(action, verb, running, completed, detail))
    }
    tool("html_preview", WorkAction.HtmlPreview, "Preview|Previewing|Previewed|an HTML page")
    tool("html_render", WorkAction.HtmlRender, "Render|Rendering|Rendered|an HTML page")
    tool("preview_status", WorkAction.Browser, "Get|Getting|Got|preview browser status")
    tool("preview_open", WorkAction.Browser, "Open|Opening|Opened|a page in the preview browser")
    tool("preview_navigate", WorkAction.Browser, "Navigate|Navigating|Navigated|the preview browser")
    tool("preview_dialog", WorkAction.Browser, "Respond|Responding|Responded|to a preview browser dialog")
    tool("preview_snapshot", WorkAction.Browser, "Take a snapshot of|Taking a snapshot of|Took a snapshot of|the preview page")
    tool("preview_act", WorkAction.Browser, "Use|Using|Used|the preview browser")
    tool("preview_click", WorkAction.Browser, "Click|Clicking|Clicked|in the preview browser")
    tool("preview_press", WorkAction.Browser, "Press|Pressing|Pressed|a key in the preview browser")
    tool("preview_type", WorkAction.Browser, "Type|Typing|Typed|in the preview browser")
    tool("preview_hover", WorkAction.Browser, "Hover|Hovering|Hovered|in the preview browser")
    tool("preview_select", WorkAction.Browser, "Choose|Choosing|Chose|an option in the preview browser")
    tool("preview_drag", WorkAction.Browser, "Drag|Dragging|Dragged|in the preview browser")
    tool("preview_upload", WorkAction.Browser, "Upload|Uploading|Uploaded|files to the preview browser")
    tool("preview_scroll", WorkAction.Browser, "Scroll|Scrolling|Scrolled|the preview browser")
    tool("preview_resize", WorkAction.Browser, "Resize|Resizing|Resized|the preview browser")
    tool("preview_evaluate", WorkAction.Browser, "Evaluate|Evaluating|Evaluated|script in the preview browser")
    tool("preview_wait_for", WorkAction.Browser, "Wait|Waiting|Waited|for the preview page")
    tool("preview_set_appearance", WorkAction.Browser, "Set|Setting|Set|preview browser appearance")
    tool("preview_recording_start", WorkAction.Browser, "Start|Starting|Started|recording the preview browser")
    tool("preview_recording_stop", WorkAction.Browser, "Stop|Stopping|Stopped|recording the preview browser")
    tool("t3_preview_list", WorkAction.Browser, "List|Listing|Listed|preview tabs")
    tool("t3_preview_close", WorkAction.Browser, "Close|Closing|Closed|a preview tab")
    tool("device_list", WorkAction.Device, "List|Listing|Listed|simulators and emulators")
    tool("device_open", WorkAction.Device, "Open|Opening|Opened|a device in the Device panel")
    tool("device_screenshot", WorkAction.Device, "Take a screenshot of|Taking a screenshot of|Took a screenshot of|the device")
    tool("device_close", WorkAction.Device, "Close|Closing|Closed|a device")
}

private val t3ServerAliases = setOf("t3-code", "t3_code", "t3code", "t3-code-direct", "t3_code_direct")
private val mcpToolName = Regex("""^mcp__(.+?)__(.+)$""", RegexOption.IGNORE_CASE)
private val toolNameSeparators = Regex("""[\s-]+""")
private val compactToolLabelSuffix = Regex("""\s+(?:complete|completed)\s*$""", RegexOption.IGNORE_CASE)
private val namespacedToolName = Regex("""^(?:t3[-_]code[-_]direct|t3-code|t3_code|t3code)(?:[.:/]|\s*·\s*)(.+)$""", RegexOption.IGNORE_CASE)

/** T3 `parseT3McpToolName` on the title (Trefoil keeps the raw tool name there when the item has no title). */
private val ToolActivity.t3Tool: T3Tool? get() {
    if (kind != "dynamic_tool" && kind != "mcp_tool_call" && kind != "tool") return null
    val label = normalizeCompactToolLabel(title)
    val name = mcpToolName.find(label)?.let { match -> match.groupValues[2].takeIf { match.groupValues[1].lowercase() in t3ServerAliases } }
        ?: namespacedToolName.find(label)?.groupValues?.get(1)
        ?: label.lowercase().replace(toolNameSeparators, "_")
    return t3Tools[name]
}

/** T3 `resolveT3McpToolPresentation` display name for a status; unknown statuses read as settled. */
private fun t3ToolDisplayName(activity: ToolActivity, status: Lifecycle): String? {
    val tool = activity.t3Tool ?: return null
    val verb = when (status) {
        Lifecycle.InProgress -> tool.running
        Lifecycle.Completed, Lifecycle.Unknown -> tool.completed
        Lifecycle.Failed -> "Failed to ${tool.verb.lowercase()}"
        Lifecycle.Declined -> "Declined to ${tool.verb.lowercase()}"
        Lifecycle.Stopped -> "Stopped ${tool.running.lowercase()}"
    }
    return "$verb ${tool.detail}"
}

private val readTitle = Regex("""^(?:read|read_file|read file)$""", RegexOption.IGNORE_CASE)
private val searchToolTitle = Regex("""^(?:grep|glob|ls|search|search_files|find)$""", RegexOption.IGNORE_CASE)
private val grepWord = Regex("""\bgrep\b""", RegexOption.IGNORE_CASE)

/** Port of T3 `toolGroupAction` for the item types Trefoil receives (no T3 `requestKind`/`toolData` on the client model). */
private val ToolActivity.workAction: WorkAction get() {
    t3Tool?.let { return it.action }
    return when (kind) {
        "command_execution" -> WorkAction.Command
        "file_change" -> WorkAction.Edit
        "file_read", "read" -> WorkAction.Read
        "file_search" -> WorkAction.CodeSearch
        "web_search" -> if (grepWord.containsMatchIn(title)) WorkAction.CodeSearch else WorkAction.WebSearch
        else -> when {
            readTitle.matches(title.trim()) || title.startsWith("Read ") || images.any { it.path != null } -> WorkAction.Read
            searchToolTitle.matches(title.trim()) || title.startsWith("Searched ") -> WorkAction.CodeSearch
            else -> WorkAction.Other
        }
    }
}

private fun actionLabel(action: WorkAction, entries: List<ToolActivity>): String {
    fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    return when (action) {
        WorkAction.Read -> "Read ${count(entries.size, "file", "files")}"
        WorkAction.Edit -> {
            // T3 counts distinct changed files; edits without a file name count once each.
            val named = entries.mapNotNull { it.fileName?.takeIf(String::isNotBlank) }
            "Changed ${count(named.toSet().size + entries.size - named.size, "file", "files")}"
        }
        WorkAction.Command -> "Ran ${count(entries.size, "command", "commands")}"
        WorkAction.CodeSearch -> "Searched code ${count(entries.size, "time", "times")}"
        WorkAction.WebSearch -> "Searched the web ${count(entries.size, "time", "times")}"
        WorkAction.Other -> "Used ${count(entries.size, "tool", "tools")}"
        WorkAction.HtmlPreview, WorkAction.HtmlRender, WorkAction.Browser, WorkAction.Device -> {
            // T3 summarizeT3ToolCalls: count completed calls, or "Tried to …" every call when none completed.
            val completed = entries.filter { it.lifecycle == Lifecycle.Completed }
            val selected = completed.ifEmpty { entries }
            val (past, infinitive, objectText) = when (action) {
                WorkAction.HtmlPreview -> Triple("Previewed", "preview", count(selected.size, "HTML page", "HTML pages"))
                WorkAction.HtmlRender -> Triple("Rendered", "render", count(selected.size, "HTML page", "HTML pages"))
                WorkAction.Browser -> Triple("Used", "use", "browser ${count(selected.size, "time", "times")}")
                else -> Triple("Used", "use", "device controls ${count(selected.size, "time", "times")}")
            }
            "${if (completed.isNotEmpty()) past else "Tried to $infinitive"} $objectText"
        }
    }
}

/** A title the server or provider supplied, not Trefoil's `kind.replace('_', ' ')` fallback. */
private val ToolActivity.meaningfulTitle: String? get() =
    title.trim().takeIf { it.isNotEmpty() && it.replace(' ', '_') != kind }

private fun searchLabel(activity: ToolActivity): String? {
    activity.meaningfulTitle?.takeIf { !searchToolTitle.matches(it) }?.let { return boundedLabel(it) }
    val query = activity.input?.let { input -> jsonStrings(input, listOf("pattern", "query", "glob")) ?: input.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") } }
    return query?.let { boundedLabel("Searched $it") }
}

/** Read target: the server's `Read <path>` title, a `file_path`/`path` input field, or the viewed image path. */
private val ToolActivity.readPath: String? get() =
    title.takeIf { it.startsWith("Read ") }?.removePrefix("Read ")?.trim()?.takeIf { it.isNotEmpty() }
        ?: input?.let { jsonStrings(it, listOf("file_path", "path", "filePath")) }
        ?: images.firstNotNullOfOrNull { it.path }

/** First string under one of [keys] in a JSON object, or the first string of a JSON array (web search patterns). */
private fun jsonStrings(text: String, keys: List<String>): String? {
    val element = text.trim().takeIf { it.startsWith("{") || it.startsWith("[") }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } ?: return null
    return when (element) {
        is JsonObject -> keys.firstNotNullOfOrNull { key -> (element[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
        is JsonArray -> element.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
        else -> null
    }
}

private fun normalizeCompactToolLabel(value: String): String = value.replace(compactToolLabelSuffix, "").trim()

// endregion

// region Failure heuristics

private const val maxScannedOutput = 32_768

private val failureHint = Regex("""enoent|no such|no files found|not found|commandnotfound|not recognized|cannot (?:find path|be found)|exit""", RegexOption.IGNORE_CASE)
private val failurePhrase = Regex("""file not found|no files found|enoent|no such file|commandnotfoundexception|command not found|is not recognized as the name of a cmdlet|a parameter cannot be found that matches parameter name""", RegexOption.IGNORE_CASE)
private val cannotFindPath = Regex("""cannot find path""", RegexOption.IGNORE_CASE)
private val doesNotExist = Regex("""because it does not exist""", RegexOption.IGNORE_CASE)
private val isNotRecognized = Regex("""is not recognized""", RegexOption.IGNORE_CASE)
private val theTerm = Regex("""the term '""", RegexOption.IGNORE_CASE)
private val exitedWithCode = Regex("""exit(?:ed)? with exit code\s+[1-9]\d*""", RegexOption.IGNORE_CASE)
private val exitCodeText = Regex("""exit code\s*[:\s]\s*[1-9]\d*\b""", RegexOption.IGNORE_CASE)

/** Port of T3 shared/toolOutput `toolOutputIndicatesFailure`: providers sometimes report completion for failed output. */
internal fun toolOutputIndicatesFailure(text: String): Boolean =
    failureHint.containsMatchIn(text) && (failurePhrase.containsMatchIn(text) ||
        (cannotFindPath.containsMatchIn(text) && doesNotExist.containsMatchIn(text)) ||
        (isNotRecognized.containsMatchIn(text) && theTerm.containsMatchIn(text)) ||
        exitedWithCode.containsMatchIn(text) || exitCodeText.containsMatchIn(text))

private val errorTag = Regex("""(?:Error|Failure)$""")

/** Port of T3 `t3ToolResultIndicatesFailure`: MCP errors can be returned as data even when the call completed. */
private fun t3ResultIndicatesFailure(output: String?): Boolean {
    if (output == null) return false
    // An oversized result is not parsed. Only its two edges are checked, for a failure flag that provably belongs to the
    // result envelope: the first key of the opening object, or a key closed by the final brace. Flags nested in payload data
    // (a successful result carrying somebody's `_tag: "SyntaxError"`) are ignored, as the structured parser ignores them.
    if (output.length > maxScannedOutput) return t3FailureHead.containsMatchIn(output.take(t3EnvelopeEdge)) || t3FailureTail.containsMatchIn(output.takeLast(t3EnvelopeEdge))
    return output.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }?.let { t3ResultFailed(it, 1) } == true
}

private const val t3EnvelopeEdge = 512
/** A failure flag in raw (possibly string-escaped) JSON text: `"isError":true`, `\"is_error\":true` or an `…Error`/`…Failure` `_tag`. */
private const val t3FailureFlag = """(?:\\*"(?:isError|is_error)\\*"\s*:\s*true\b|\\*"_tag\\*"\s*:\s*\\*"[A-Za-z]*(?:Error|Failure)\\*")"""
/** The flag as the first key of the envelope, which may be JSON embedded in a text content block (`[{"type":"text","text":"{…`). */
private val t3FailureHead = Regex("""^\s*(?:\[\s*\{\s*"type"\s*:\s*"text"\s*,\s*"text"\s*:\s*")?\s*\{\s*""" + t3FailureFlag)
/** The flag as the last key of the envelope: closed by the final brace, or by the embedded envelope's brace before `"}]`. */
private val t3FailureTail = Regex(t3FailureFlag + """\s*\}\s*(?:"\s*\}\s*\])?\s*$""")

private fun t3ResultFailed(value: JsonElement?, depth: Int): Boolean {
    if (value == null || depth > 4) return false
    return when (value) {
        is JsonPrimitive -> value.isString && runCatching { Json.parseToJsonElement(value.content) }.getOrNull()?.let { t3ResultFailed(it, depth + 1) } == true
        is JsonArray -> value.any { block ->
            val record = block as? JsonObject
            val text = record?.get("text") ?: (record?.get("content") as? JsonObject)?.get("text")
            t3ResultFailed((text as? JsonObject)?.get("text") ?: text, depth + 1)
        }
        is JsonObject -> (value["isError"] as? JsonPrimitive)?.booleanOrNull == true ||
            (value["is_error"] as? JsonPrimitive)?.booleanOrNull == true ||
            (value["_tag"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { errorTag.containsMatchIn(it) } == true ||
            (value["error"] != null && value["error"] !is JsonNull) ||
            t3ResultFailed(value["structuredContent"] ?: value["content"], depth + 1)
    }
}

// endregion

// region Thought preview

private const val maxThoughtSource = 4_000
private val thoughtHeading = Regex("""^\s*\*\*([^*\r\n]+)\*\*[ \t]*\r?(?:\n|\z)""")
private val markdownLink = Regex("""!?\[([^\]]*)\]\([^)]*\)""")
private val markdownListMarker = Regex("""^[ \t]*(?:#{1,6}|[-*+]|\d+\.)[ \t]+""", RegexOption.MULTILINE)
private val markdownCodeBoldStrike = Regex("""`+|\*\*|~~""")
private val markdownEmphasis = Regex("""(^|[^\w*])[*_]([^*_\n]+)[*_](?![\w*])""")
private val whitespace = Regex("""\s+""")
private val sentenceEnd = Regex("""[.?!]["'”’)]?(?=\s)""")

// endregion

// region Shell parsing

private val shellPrograms = setOf("bash", "sh", "zsh", "dash", "ksh", "fish")
private val commandPrefixes = setOf("sudo", "env", "time", "exec", "command", "nohup", "nice", "builtin", "xargs")
private val navigationCommands = setOf("cd", "pushd", "popd", "export", "set", "unset", "source", ".", "true", ":", "wait")
private val envAssignment = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
private val controlCharacters = Regex("""[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]""")

/** T3 `withoutShellWrapper`: `/bin/zsh -lc "script"` → `script` when the script is the only remaining argument. */
private fun withoutShellWrapper(command: String): String {
    val trimmed = command.trim()
    if (shellSegments(trimmed)?.size != 1) return trimmed
    val tokens = shellTokens(trimmed) ?: return trimmed
    val program = tokens.firstOrNull()?.split('/', '\\')?.last()?.removeSuffix(".exe") ?: return trimmed
    if (program !in shellPrograms) return trimmed
    var index = 1
    while (index < tokens.size && tokens[index].startsWith("-") && tokens[index] != "--") {
        if (!tokens[index].startsWith("--") && 'c' in tokens[index]) return if (index + 1 == tokens.lastIndex) tokens[index + 1].trim().ifEmpty { trimmed } else trimmed
        index++
    }
    return trimmed
}

/** Splits on unquoted `&&`, `||`, `;`, `|`, `&` and newlines; null when quotes do not balance. */
private fun shellSegments(command: String): List<String>? {
    val segments = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var index = 0
    while (index < command.length) {
        val char = command[index]
        when {
            quote != null -> { current.append(char); if (char == '\\' && quote == '"' && index + 1 < command.length) current.append(command[++index]) else if (char == quote) quote = null }
            char == '\\' && index + 1 < command.length -> { current.append(char).append(command[++index]) }
            char == '\'' || char == '"' -> { quote = char; current.append(char) }
            char == ';' || char == '\n' || char == '|' || char == '&' -> {
                if (index + 1 < command.length && command[index + 1] == char && char != ';' && char != '\n') index++
                current.toString().trim().takeIf { it.isNotEmpty() }?.let(segments::add)
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    if (quote != null) return null
    current.toString().trim().takeIf { it.isNotEmpty() }?.let(segments::add)
    return segments
}

/** Whitespace tokens with quotes removed; null when quotes do not balance. */
private fun shellTokens(segment: String): List<String>? {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var started = false
    var index = 0
    while (index < segment.length) {
        val char = segment[index]
        when {
            quote == '\'' -> if (char == '\'') quote = null else current.append(char)
            quote == '"' -> when {
                char == '"' -> quote = null
                char == '\\' && index + 1 < segment.length && segment[index + 1] in "\"\\$`" -> current.append(segment[++index])
                else -> current.append(char)
            }
            char == '\'' || char == '"' -> { quote = char; started = true }
            char == '\\' && index + 1 < segment.length -> { current.append(segment[++index]); started = true }
            char.isWhitespace() -> { if (started) { tokens += current.toString(); current.clear(); started = false } }
            else -> { current.append(char); started = true }
        }
        index++
    }
    if (quote != null) return null
    if (started) tokens += current.toString()
    return tokens
}

// endregion

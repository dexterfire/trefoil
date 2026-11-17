package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.ToolActivity
import org.junit.Assert.*
import org.junit.Test

class WorkLogPresentationTest {
    private var next = 0
    private fun command(input: String = "./gradlew test", status: String = "completed", exitCode: Int? = 0, output: String? = null) =
        ToolActivity("c${next++}", "Command", kind = "command_execution", status = status, input = input, exitCode = exitCode, output = output)
    private fun read(path: String = "/repo/a.kt") = ToolActivity("r${next++}", "Read $path", kind = "dynamic_tool", status = "completed", input = """{"file_path":"$path"}""")
    private fun edit(file: String? = "a.kt") = ToolActivity("e${next++}", "File change", kind = "file_change", status = "completed", fileName = file)
    private fun thought(text: String = "Checking the build.") = ToolActivity("t${next++}", "reasoning", kind = "reasoning", status = "completed", detail = text)
    private fun preview(status: String = "completed", output: String? = null) =
        ToolActivity("p${next++}", "mcp__t3-code__html_preview", kind = "dynamic_tool", status = status, output = output)
    private fun search(pattern: String = "needle") = ToolActivity("s${next++}", "file search", kind = "file_search", status = "completed", input = pattern)
    private fun tool(title: String = "Skill: deploy") = ToolActivity("o${next++}", title, kind = "dynamic_tool", status = "completed")

    @Test fun summaryNamesBucketsInFirstSeenOrderAndDropsReasoning() {
        assertEquals("Ran 1 command and read 2 files", summarizeWorkGroup(listOf(thought(), command(), read(), read("/repo/b.kt"))).label)
        assertEquals(WorkIcon.Mixed, summarizeWorkGroup(listOf(command(), read())).icon)
        assertEquals("Read 2 files", summarizeWorkGroup(listOf(read(), thought(), read("/b"))).label)
        assertEquals(WorkIcon.Read, summarizeWorkGroup(listOf(read(), read("/b"))).icon)
    }

    @Test fun reasoningOnlyGroupsAreThoughts() {
        assertEquals(WorkGroupSummary("Thought", WorkIcon.Reasoning, false), summarizeWorkGroup(listOf(thought())))
        assertEquals("Thought (×2)", summarizeWorkGroup(listOf(thought(), thought())).label)
    }

    @Test fun summaryKeepsTwoBucketsByPriorityAndCountsLeftovers() {
        // read (1), command (0), search (1), other (2): command plus first priority-1 bucket win, rest are "other actions".
        assertEquals("Read 1 file, ran 1 command, and performed 2 other actions",
            summarizeWorkGroup(listOf(read(), command(), search(), tool())).label)
        assertEquals("Ran 2 commands and changed 1 file", summarizeWorkGroup(listOf(command(), edit(), command(), edit())).label)
        assertEquals("Searched code 1 time and used 1 tool", summarizeWorkGroup(listOf(search(), tool())).label)
        // Read and search share priority 1 and keep first-seen order; the generic tool has the lowest priority.
        assertEquals("Read 1 file, searched code 1 time, and performed 1 other action", summarizeWorkGroup(listOf(read(), tool(), search())).label)
    }

    @Test fun editsCountDistinctFiles() {
        assertEquals("Changed 2 files", summarizeWorkGroup(listOf(edit("a.kt"), edit("a.kt"), edit("b.kt"))).label)
        assertEquals("Changed 2 files", summarizeWorkGroup(listOf(edit("a.kt"), edit(null))).label)
        assertEquals("Changed 1 file", singleWorkLabel(edit("a.kt")))
        assertEquals(WorkIcon.Edit, summarizeWorkGroup(listOf(edit("a.kt"), edit("b.kt"))).icon)
    }

    @Test fun htmlPreviewPhrasingFollowsCompletedCalls() {
        assertEquals("Previewed 2 HTML pages", summarizeWorkGroup(listOf(preview(), preview())).label)
        assertEquals("Previewed 1 HTML page", summarizeWorkGroup(listOf(preview(), preview("failed"))).label)
        assertEquals("Tried to preview 1 HTML page", summarizeWorkGroup(listOf(preview("failed"), thought())).label)
        assertEquals("Tried to preview 1 HTML page", summarizeWorkGroup(listOf(preview(output = """{"isError":true,"content":[]}"""))).label)
        assertEquals(WorkIcon.Html, summarizeWorkGroup(listOf(preview(), preview())).icon)
        assertEquals("Previewed an HTML page", singleWorkLabel(preview()))
        assertEquals("Failed to preview an HTML page", singleWorkLabel(preview("failed")))
        assertEquals("Previewing an HTML page", liveWorkLabel(preview("running")))
        assertEquals("Rendered an HTML page", workEntryLabel(ToolActivity("h", "html_render", kind = "dynamic_tool", status = "completed")))
    }

    @Test fun groupFailureIsReportedForAccessibility() {
        assertTrue(summarizeWorkGroup(listOf(command(exitCode = 2), read())).hasFailure)
        assertFalse(summarizeWorkGroup(listOf(command(), read())).hasFailure)
    }

    @Test fun displayFailureRules() {
        assertTrue(command(status = "failed", exitCode = null).displayFailure)
        assertTrue(command(status = "declined", exitCode = null).displayFailure)
        assertTrue(command(exitCode = 1).displayFailure)
        // The exit code outranks failure-looking output text.
        assertFalse(command(exitCode = 0, output = "grep: x: No such file or directory").displayFailure)
        assertTrue(command(exitCode = null, output = "zsh: command not found: foo").displayFailure)
        assertTrue(command(exitCode = null, output = "Process exited with exit code 3").displayFailure)
        assertFalse(command(exitCode = null, output = "exit code 0").displayFailure)
        assertTrue(ToolActivity("x", "Command", kind = "command_execution", status = "completed", outputIndicatesFailure = true).displayFailure)
        assertTrue(ToolActivity("x", "Tool", kind = "dynamic_tool", status = "completed", failure = "Boom").displayFailure)
        assertTrue(ToolActivity("x", "Error", kind = "error").displayFailure)
        for (status in listOf("cancelled", "interrupted", "stopped", "canceled", "running", "completed"))
            assertFalse(status, command(status = status, exitCode = null).displayFailure)
        // Reasoning prose is never scanned for failure phrases.
        assertFalse(thought("The file not found case is expected, exit code 2 too.").displayFailure)
        assertTrue(preview(output = """[{"type":"text","text":"{\"isError\":true}"}]""").displayFailure)
        assertFalse(preview(output = """{"content":[{"type":"text","text":"ok"}]}""").displayFailure)
    }

    @Test fun oversizedT3FailureResultsAreStillDetected() {
        val filler = "x".repeat(60_000)
        // Trailing flag (typical MCP envelope), leading flag, string-escaped flag, and an error `_tag`.
        assertTrue(preview(output = """{"content":[{"type":"text","text":"$filler"}],"isError":true}""").displayFailure)
        assertTrue(preview(output = """{"isError":true,"content":[{"type":"text","text":"$filler"}]}""").displayFailure)
        assertTrue(preview(output = """[{"type":"text","text":"{\"is_error\":true,\"pad\":\"$filler\"}"}]""").displayFailure)
        assertTrue(preview(output = """{"pad":"$filler","_tag":"PreviewError"}""").displayFailure)
        assertTrue(preview(status = "completed", output = """{"pad":"$filler","isError":true}""").let { singleWorkLabel(it) } == "Failed to preview an HTML page")
        // Large successful results stay successful.
        assertFalse(preview(output = """{"content":[{"type":"text","text":"$filler"}],"isError":false}""").displayFailure)
        assertFalse(preview(output = """{"content":[{"type":"text","text":"$filler"}]}""").displayFailure)
    }

    @Test fun commandProgramNameSkipsNavigationEnvAndWrappers() {
        assertEquals("gradlew", commandProgramName("cd /x; FOO=1 ./gradlew test"))
        assertEquals("npm", commandProgramName("cd app && npm test"))
        assertEquals("git", commandProgramName("/bin/zsh -lc \"cd /repo && git status --short\""))
        assertEquals("python3", commandProgramName("sudo -E env A=b /usr/bin/python3 script.py | tail -5"))
        assertEquals("rg", commandProgramName("rg 'a|b' src"))
        assertNull(commandProgramName("cd /x"))
        assertNull(commandProgramName("echo 'unbalanced"))
        assertEquals("Running gradlew", liveWorkLabel(command(input = "cd /x; FOO=1 ./gradlew test", status = "running", exitCode = null)))
        assertEquals("Running command", liveWorkLabel(command(input = "cd /x", status = "running", exitCode = null)))
    }

    @Test fun commandDisplayTextDropsSingleScriptShellWrapper() {
        assertEquals("cd /repo && git status", commandDisplayText("bash -lc 'cd /repo && git status'"))
        assertEquals("bash -lc 'echo hi' extra", commandDisplayText("bash -lc 'echo hi' extra"))
        assertEquals("echo ␛[0m", commandDisplayText("echo \u001b[0m"))
        assertEquals("cd /repo && git status", workEntryLabel(command(input = "/bin/zsh -lc \"cd /repo && git status\"")))
    }

    @Test fun entryLabelsAndIcons() {
        assertEquals("Read /repo/a.kt", workEntryLabel(read()))
        assertEquals("Read /repo/b.kt", workEntryLabel(ToolActivity("r", "Read", kind = "dynamic_tool", input = """{"file_path":"/repo/b.kt"}""")))
        assertEquals("Read file", workEntryLabel(ToolActivity("r", "read_file", kind = "dynamic_tool")))
        assertEquals("a.kt", workEntryLabel(edit("a.kt")))
        assertEquals("Searched needle", workEntryLabel(search()))
        assertEquals("Skill: deploy", workEntryLabel(tool()))
        assertEquals("Checking the build.", workEntryLabel(thought("Checking the build. Then tests.")))
        assertEquals("Checking the build.", singleWorkLabel(thought("Checking the build. Then tests.")))
        assertEquals(WorkIcon.Command, workEntryIcon(command()))
        assertEquals(WorkIcon.Read, workEntryIcon(read()))
        assertEquals(WorkIcon.Edit, workEntryIcon(edit()))
        assertEquals(WorkIcon.CodeSearch, workEntryIcon(search()))
        assertEquals(WorkIcon.CodeSearch, workEntryIcon(ToolActivity("g", "Searched foo in src", kind = "dynamic_tool")))
        assertEquals(WorkIcon.WebSearch, workEntryIcon(ToolActivity("w", "web search", kind = "web_search")))
        assertEquals(WorkIcon.Html, workEntryIcon(preview()))
        assertEquals(WorkIcon.Reasoning, workEntryIcon(thought()))
        assertEquals(WorkIcon.Error, workEntryIcon(ToolActivity("x", "Error", kind = "error")))
        assertEquals(WorkIcon.Agent, workEntryIcon(ToolActivity("a", "Agent", kind = "subagent")))
        assertEquals(WorkIcon.Other, workEntryIcon(tool()))
    }

    @Test fun liveLabelForReasoningUsesThoughtLineOrThinking() {
        assertEquals("Thinking", liveWorkLabel(ToolActivity("t", "reasoning", kind = "reasoning", status = "running")))
        assertEquals("Planning", liveWorkLabel(ToolActivity("t", "reasoning", kind = "reasoning", status = "running", detail = "**Planning**\nLong text.")))
    }

    @Test fun thoughtPreviewPortsLiveThoughtLine() {
        assertEquals("Inspecting the timeline", thoughtPreview("**Inspecting the timeline**\n\nI will read the file. Then more."))
        assertEquals("I will read ConversationTimeline.kt first.", thoughtPreview("I will read `ConversationTimeline.kt` first. Then the tests."))
        assertEquals("See docs and bold text", thoughtPreview("- See [docs](https://x.test) and **bold** _text_"))
        assertEquals("No sentence end", thoughtPreview("  No   sentence\nend  "))
        assertEquals("", thoughtPreview("   "))
    }

    @Test fun visibilityAndImages() {
        assertFalse(thought("  ").visibleInGroup())
        assertTrue(thought().visibleInGroup())
        assertTrue(command(status = "running", exitCode = null).visibleInGroup())
        assertTrue(command(status = "inProgress", exitCode = null).visibleInGroup())
        assertTrue(ToolActivity("a", "Approve", kind = "approval_request", status = "pending").visibleInGroup())
        assertTrue(ToolActivity("u", "Question", kind = "user_input_request", status = "pending").visibleInGroup())
        assertTrue(thought("  ").copy(outputOmitted = true).visibleInGroup())
        assertTrue(thought("  ").copy(detailsOmitted = true).visibleInGroup())
        val image = RemoteFileReference(path = "/tmp/a.png", fileName = "a.png")
        val viewed = ToolActivity("v", "Read /tmp/a.png", kind = "dynamic_tool", images = listOf(image))
        assertEquals(listOf(image), viewedImages(listOf(viewed, viewed.copy(id = "v2"), command())))
    }
}

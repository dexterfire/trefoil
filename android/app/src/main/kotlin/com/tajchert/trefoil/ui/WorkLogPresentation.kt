/* Work-log presentation adapted from T3 Code's client-runtime work-log (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.RemoteFileReference
import com.tajchert.trefoil.core.model.ToolActivity

/**
 * Pure presentation contract for the thread work log. The Compose layer (ConversationParts.kt) only
 * consumes these; grouping and folding live in ConversationTimeline.kt.
 *
 * CONTRACT STUB: signatures are fixed; bodies are placeholder implementations to be replaced.
 */

/** Glyph for a work row; mapped to an icon by the UI. Mirrors T3 `toolGroupSummaryIconName`/`workEntryIconName`. */
internal enum class WorkIcon { Read, Edit, Command, CodeSearch, WebSearch, Browser, Device, Agent, Html, Other, Mixed, Reasoning, Error }

/** Header for a multi-entry tool group ("Ran 1 command and read 2 files"). [hasFailure] is for accessibility only. */
internal data class WorkGroupSummary(val label: String, val icon: WorkIcon, val hasFailure: Boolean)

/** T3 display failure: failed/declined status, non-zero exit (outranks text), outputIndicatesFailure, failure text, or the output text heuristic. Cancelled/interrupted are not failures. */
internal val ToolActivity.displayFailure: Boolean get() =
    status in setOf("error", "failed", "failure", "declined") || (exitCode != null && exitCode != 0) ||
        outputIndicatesFailure == true || !failure.isNullOrBlank()

/** Provider error items (kind `error`) and other severe failures render prominently (destructive colour); everything else stays subtle. */
internal val ToolActivity.severeFailure: Boolean get() = kind == "error"

/** Activities shown inside a group: reasoning only with non-blank text; neutral in-progress entries only while their run is live. */
internal fun ToolActivity.visibleInGroup(live: Boolean): Boolean =
    if (kind == "reasoning") reasoningText.isNotBlank() else live || !isRunning

/** Reasoning body text (output, falling back to detail). */
internal val ToolActivity.reasoningText: String get() = output?.takeIf { it.isNotBlank() } ?: detail

/** Summary for a group of 2+ visible activities. Reasoning-only groups give "Thought" / "Thought (×N)". */
internal fun summarizeWorkGroup(activities: List<ToolActivity>): WorkGroupSummary =
    WorkGroupSummary("Used ${activities.size} tools", WorkIcon.Mixed, activities.lastOrNull()?.displayFailure == true)

/** Label for a group holding exactly one visible activity (no header): reasoning preview, T3 tool display name, or entry label. */
internal fun singleWorkLabel(activity: ToolActivity): String = workEntryLabel(activity)

/** Label for one row inside an expanded group: command text, "Read <path>", file path (+N more), thought preview, tool title. */
internal fun workEntryLabel(activity: ToolActivity): String =
    activity.input?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: activity.title

internal fun workEntryIcon(activity: ToolActivity): WorkIcon = when (activity.kind) {
    "command_execution" -> WorkIcon.Command
    "file_change" -> WorkIcon.Edit
    "reasoning" -> WorkIcon.Reasoning
    "error" -> WorkIcon.Error
    else -> WorkIcon.Other
}

/** Live row label for the latest in-progress activity: "Running gradlew", "Thinking", "Previewing HTML". */
internal fun liveWorkLabel(activity: ToolActivity): String = if (activity.kind == "reasoning") "Thinking" else activityHeadline(activity)

/** One-line, markdown-stripped preview of reasoning text (heading if it starts with **Heading**, else first sentence). */
internal fun thoughtPreview(markdown: String): String = markdown.trim().lineSequence().firstOrNull().orEmpty()

/** Distinct images produced by a group's activities, in order, for the thumbnail strip under the group. */
internal fun viewedImages(activities: List<ToolActivity>): List<RemoteFileReference> =
    activities.flatMap { it.images }.distinctBy { it.identity }

/* HTML render reference shape adapted from T3 Code shared/htmlRender.ts (MIT).
 * Copyright (c) 2026 T3 Tools Inc. See licenses/T3-Code-MIT.txt.
 */
package com.tajchert.trefoil.core.model

/**
 * A page an agent published with T3's `html_render` tool. The HTML itself is a `text/html` attachment on
 * the environment; the thread only carries this reference.
 */
data class HtmlRenderReference(
    val attachmentId: String,
    /** Trimmed, at most 200 chars, "HTML" when blank. */
    val title: String,
    /** Agent height hint in CSS px, clamped to [HTML_RENDER_MIN_HEIGHT]..[HTML_RENDER_MAX_HEIGHT]. */
    val height: Int,
    /** Measured (width, height) pairs in CSS px, sorted by width; empty when the server could not measure. */
    val heights: List<Pair<Int, Int>> = emptyList(),
) {
    /** Fetched through the existing authenticated attachment download path. */
    val file: RemoteFileReference get() = RemoteFileReference(attachmentId = attachmentId, fileName = htmlRenderFileName(title), mimeType = "text/html")
    override fun toString() = "HtmlRenderReference(content=***)"
}

const val HTML_RENDER_MIN_HEIGHT = 80
const val HTML_RENDER_MAX_HEIGHT = 2000
const val HTML_RENDER_MAX_FIT_HEIGHT = 20_000

/** Mirrors T3's htmlRenderFileName: strip reserved/control chars, cap at 120, append `.html`. */
fun htmlRenderFileName(title: String): String {
    val base = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().take(120).ifBlank { "HTML" }
    return "$base.html"
}

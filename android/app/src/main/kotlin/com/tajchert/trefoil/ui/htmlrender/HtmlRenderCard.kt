package com.tajchert.trefoil.ui.htmlrender

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.HtmlRenderReference

/**
 * Inline thread row for an agent `html_render` page. Reads environment/thread/loader from LocalFileDownloadUi.
 * STUB: the real implementation (WebView, loading/offline states, full-screen) replaces this body.
 */
@Composable
internal fun HtmlRenderCard(reference: HtmlRenderReference, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(reference.height.coerceAtMost(400).dp))
}

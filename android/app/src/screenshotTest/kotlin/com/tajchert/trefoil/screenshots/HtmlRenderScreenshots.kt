package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.HtmlRenderReference
import com.tajchert.trefoil.core.model.ToolActivity
import com.tajchert.trefoil.ui.Conversation
import com.tajchert.trefoil.ui.TrefoilTheme
import com.tajchert.trefoil.ui.htmlrender.HtmlRenderCardContent
import com.tajchert.trefoil.ui.htmlrender.HtmlRenderState

private val reference = HtmlRenderReference(attachmentId = "att-demo", title = "Test results dashboard", height = 360)

@Composable
private fun CardShot(state: HtmlRenderState, dark: Boolean, canRetry: Boolean = false) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            HtmlRenderCardContent(reference, state, Modifier.padding(16.dp), canRetry = canRetry)
        }
    }
}

/** An assistant turn with command activity, then an HTML render row, then the final reply. */
private fun renderState(): ClientState {
    val base = ConversationFixtures.chatState()
    val render = ToolActivity(
        id = "a-html", title = "html_render", kind = "dynamic_tool", status = "completed", ordinal = 4, htmlRender = reference,
    )
    return base.copy(
        messages = base.messages.map { if (it.role == "assistant") it.copy(ordinal = 6) else it },
        activities = base.activities + render,
    )
}

@Composable
private fun ConversationShot(dark: Boolean) {
    TrefoilTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
            Conversation(renderState(), send = {}, interrupt = {}, respond = { _, _, _ -> }, onBack = {}, onOpenAgents = null)
        }
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 460)
@Composable
fun HtmlRenderCard_Ready_Light() = CardShot(HtmlRenderState.Ready("<p>demo</p>"), dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 460, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HtmlRenderCard_Ready_Dark() = CardShot(HtmlRenderState.Ready("<p>demo</p>"), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 460)
@Composable
fun HtmlRenderCard_Loading_Light() = CardShot(HtmlRenderState.Loading, dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 460, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HtmlRenderCard_Loading_Dark() = CardShot(HtmlRenderState.Loading, dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200)
@Composable
fun HtmlRenderCard_Offline_Light() = CardShot(HtmlRenderState.Offline, dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HtmlRenderCard_Offline_Dark() = CardShot(HtmlRenderState.Offline, dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200)
@Composable
fun HtmlRenderCard_Failed_Light() = CardShot(HtmlRenderState.Failed, dark = false, canRetry = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HtmlRenderCard_Failed_Dark() = CardShot(HtmlRenderState.Failed, dark = true, canRetry = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200)
@Composable
fun HtmlRenderCard_TooLarge_Light() = CardShot(HtmlRenderState.TooLarge, dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HtmlRenderCard_TooLarge_Dark() = CardShot(HtmlRenderState.TooLarge, dark = true)

// The row has no download loader here, so it shows its offline state; the placement between activity and reply is what this covers.
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
fun Conversation_HtmlRender_Light() = ConversationShot(dark = false)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Conversation_HtmlRender_Dark() = ConversationShot(dark = true)

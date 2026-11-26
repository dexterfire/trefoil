package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.ui.AgentRowUi
import com.tajchert.trefoil.ui.ModelPickerContent
import com.tajchert.trefoil.ui.NewThreadSheetContent
import com.tajchert.trefoil.ui.SshConnectionDialog
import com.tajchert.trefoil.ui.SshHostKeyDialog
import com.tajchert.trefoil.ui.SubagentTone
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TfIcons
import com.tajchert.trefoil.ui.adaptive.TfIconButton
import com.tajchert.trefoil.ui.ThreadAgentsSheetContent
import com.tajchert.trefoil.ui.TrefoilTheme

/**
 * Previews of the modal surfaces on tablets and foldables. Dialog windows are not captured, so the sheet and dialog bodies are drawn
 * inside a hand-built frame (scrim plus a 28dp-radius surface at the dialog's width) over a page-coloured window. Fake data only.
 * Names: `Dialog_<Surface>_<FormFactor>`.
 */
private const val DARK = Configuration.UI_MODE_NIGHT_YES

/** Scrim over the page, with [content] centred (a dialog) or aligned by [align]. */
@Composable
private fun Backdrop(dark: Boolean, align: Alignment = Alignment.Center, content: @Composable () -> Unit) {
    TrefoilTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize().background(Tf.colors.bg)) {
            Box(Modifier.fillMaxSize().background(Tf.colors.scrim), contentAlignment = align) { content() }
        }
    }
}

@Composable
private fun DialogFrame(width: Dp, height: Dp? = null, dark: Boolean = false, content: @Composable () -> Unit) = Backdrop(dark) {
    Surface(
        // Mirrors TfAdaptiveSheet's dialog: 24dp side margins and at most [width] wide, so a 673dp window keeps a scrim on both sides.
        Modifier.padding(horizontal = 24.dp).widthIn(max = width).fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier),
        shape = RoundedCornerShape(28.dp), color = Tf.colors.sheet, contentColor = Tf.colors.text, tonalElevation = 0.dp,
    ) { Column { content() } }
}

@Composable
private fun SideSheetFrame(width: Dp = 360.dp, dark: Boolean = false, content: @Composable () -> Unit) = Backdrop(dark, Alignment.CenterEnd) {
    Surface(
        Modifier.width(width).fillMaxHeight(),
        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp), color = Tf.colors.sheet, contentColor = Tf.colors.text, tonalElevation = 0.dp,
    ) { Box { content(); Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp)) { TfIconButton(TfIcons.Close, "Close", {}, Modifier.size(48.dp)) } } }
}

// ---- New thread ----

@Composable
private fun NewThread(width: Dp, height: Dp?, dark: Boolean) = DialogFrame(width, height, dark) {
    NewThreadSheetContent(
        ScreenshotFixtures.connectedState().copy(models = ScreenshotFixtures.reasoningModels), dismiss = {}, start = { _, _ -> },
    )
}

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Dialog_NewThread_Tablet1280x800() = NewThread(640.dp, 468.dp, dark = false)

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800, uiMode = DARK)
@Composable fun Dialog_NewThread_Tablet1280x800_Dark() = NewThread(640.dp, 468.dp, dark = true)

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Dialog_NewThread_Foldable673x841() = NewThread(520.dp, null, dark = false)

// ---- Model picker ----

private val pickerModels: List<ModelOption> = ScreenshotFixtures.reasoningModels +
    ModelOption(instanceId = "claudeAgent", model = "claude-sonnet", label = "Sonnet") + ModelOption(instanceId = "codex", model = "gpt-5", label = "GPT-5")
private val pickerProviders = listOf(ProviderInstance("claudeAgent", "claudeAgent", displayName = "Claude"), ProviderInstance("codex", "codex", displayName = "Codex"))

@Composable
private fun Picker(dark: Boolean = false) = DialogFrame(680.dp, 560.dp, dark) {
    ModelPickerContent(
        pickerModels, pickerProviders, pickerModels.first(), mapOf("effort" to "high"), true, {}, {}, { _, _ -> }, Modifier.background(Tf.colors.sheet),
    )
}

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Dialog_ModelPicker_Tablet1280x800() = Picker()

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Dialog_ModelPicker_Foldable673x841() = Picker()

// ---- Agents ----

private val agentRowsFixture = listOf(
    AgentRowUi("a1", "Explore", "Working", SubagentTone.Working, elapsed = "2m 14s", modelLine = "Claude Sonnet", detail = "Reading the session list.", openThreadId = "c1", canStop = true),
    AgentRowUi("a2", "Review", "Completed", SubagentTone.Completed, elapsed = "5m 02s", modelLine = "GPT-5", detail = "No blocking issues found.", openThreadId = "c2"),
    AgentRowUi("a3", "Tests", "Failed", SubagentTone.Failed, elapsed = "1m 40s", detail = "Two cases failed in the fake suite.", openThreadId = "c3"),
)

@Composable
private fun AgentsBody() = ThreadAgentsSheetContent(agentRowsFixture, onOpen = {}, onStop = {}, modifier = Modifier.padding(top = 16.dp))

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Dialog_AgentsSideSheet_Foldable673x841() = SideSheetFrame { AgentsBody() }

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Dialog_AgentsSideSheet_Tablet1280x800() = SideSheetFrame { AgentsBody() }

@PreviewTest @Preview(showBackground = true, widthDp = 880, heightDp = 400)
@Composable fun Dialog_Agents_CompactHeight880x400() = DialogFrame(480.dp, height = 352.dp) { ThreadAgentsSheetContent(agentRowsFixture, onOpen = {}, onStop = {}, modifier = Modifier.padding(top = 4.dp), onClose = {}) }

// ---- SSH ----

@Composable
private fun Window(content: @Composable () -> Unit) = TrefoilTheme { Box(Modifier.fillMaxSize().background(Tf.colors.bg)) { content() } }

@PreviewTest @Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable fun Dialog_SshConnection_Tablet1280x800() = Window { SshConnectionDialog(busy = false, initial = null, dismiss = {}, pickerActive = {}, connect = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 800, heightDp = 1280)
@Composable fun Dialog_SshConnection_TabletPortrait800x1280() = Window { SshConnectionDialog(busy = false, initial = null, dismiss = {}, pickerActive = {}, connect = {}) }

@PreviewTest @Preview(showBackground = true, widthDp = 673, heightDp = 841)
@Composable fun Dialog_SshHostKey_Foldable673x841() = Window { SshHostKeyDialog(ShellFixtures.hostKey, trust = {}, cancel = {}) }

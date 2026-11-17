package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.ui.ModelPickerContent
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme

private object ModelPickerFixtures {
    val effort = ModelOptionDescriptor.Select(
        "effort", "Reasoning", choices = listOf(
            ModelOptionChoice("low", "Low", "Faster, lighter reasoning"),
            ModelOptionChoice("medium", "Medium"),
            ModelOptionChoice("high", "High", "Deeper reasoning for hard problems", isDefault = true),
            ModelOptionChoice("xhigh", "Extra High"),
            ModelOptionChoice("max", "Max"),
        ), isReasoning = true,
    )
    val fast = ModelOptionDescriptor.Toggle("fastMode", "Fast mode", "Faster output at a higher usage rate")
    val opus = ModelOption("claudeAgent", "claude-opus", "Opus", descriptors = listOf(effort, fast))
    val sonnet = ModelOption("claudeAgent", "claude-sonnet", "Sonnet", descriptors = listOf(effort))
    val gpt = ModelOption("codex", "gpt-5", "GPT-5")
    val providers = listOf(ProviderInstance("claudeAgent", "claudeAgent", displayName = "Claude"), ProviderInstance("codex", "codex", displayName = "Codex"))
    val all = listOf(opus, sonnet, gpt)
}

@Composable
private fun Picker(current: ModelOption, explicit: ModelOptionValues = emptyMap(), enabled: Boolean = true, dark: Boolean = false) {
    val fx = ModelPickerFixtures
    TrefoilTheme(darkTheme = dark) {
        ModelPickerContent(fx.all, fx.providers, current, explicit, enabled, {}, {}, { _, _ -> }, Modifier.background(Tf.colors.sheet))
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 860)
@Composable
fun ModelPicker_EffortHigh_Light() = Picker(ModelPickerFixtures.sonnet, mapOf("effort" to "high"))

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 860, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ModelPicker_EffortHigh_Dark() = Picker(ModelPickerFixtures.sonnet, mapOf("effort" to "high"), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 960)
@Composable
fun ModelPicker_FastOn_Light() = Picker(ModelPickerFixtures.opus, mapOf("effort" to "low", "fastMode" to true))

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 520)
@Composable
fun ModelPicker_NoOptions_Light() = Picker(ModelPickerFixtures.gpt)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 960)
@Composable
fun ModelPicker_Disabled_Light() = Picker(ModelPickerFixtures.opus, mapOf("effort" to "high"), enabled = false)

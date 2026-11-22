package com.tajchert.trefoil.screenshots

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.tajchert.trefoil.core.model.ModelKey
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.core.model.ModelPreferences
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.ui.ModelPickerContent
import com.tajchert.trefoil.ui.Tf
import com.tajchert.trefoil.ui.TrefoilTheme

/** Live-catalog style models: Claude, Codex (GPT-6.x families) and Antigravity. */
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
    private fun claude(slug: String, label: String, badge: String? = null, legacy: Boolean = false) =
        ModelOption("claudeAgent", slug, label, descriptors = listOf(effort), isLegacy = legacy, badge = badge)
    private fun codex(slug: String, label: String, fastMode: Boolean = false) =
        ModelOption("codex", slug, label, descriptors = if (fastMode) listOf(effort, fast) else listOf(effort))

    val opus = claude("claude-opus-5-5", "Claude Opus 5.5", badge = "new").copy(descriptors = listOf(effort, fast))
    val opusOld = claude("claude-opus-4-6", "Claude Opus 4.6", legacy = true)
    val sonnet = claude("claude-sonnet-5-5", "Claude Sonnet 5.5")
    val fable = claude("claude-fable-5-2", "Claude Fable 5.2")
    val haiku = claude("claude-haiku-5-5", "Claude Haiku 5.5")
    val sol = codex("gpt-6.2-sol", "GPT-6.2-Sol", fastMode = true)
    val solOld = codex("gpt-6.1-sol", "GPT-6.1-Sol", fastMode = true)
    val astra = codex("gpt-6.2-astra", "GPT-6.2-Astra")
    val astraOld = codex("gpt-6-astra", "GPT-6-Astra")
    val terra = codex("gpt-6.1-terra", "GPT-6.1 Terra")
    val luna = ModelOption("codex", "gpt-6-luna", "GPT-6 Luna")
    val neon = ModelOption("antigravity", "gemini-4-neon", "Gemini 4 Neon", descriptors = listOf(effort))
    val orchestrator = ModelOption("antigravity", "orchestrator-neon", "Orchestrator (Neon)")

    val providers = listOf(
        ProviderInstance("claudeAgent", "claudeAgent", displayName = "Claude"), ProviderInstance("codex", "codex", displayName = "Codex"),
        ProviderInstance("antigravity", "antigravity", displayName = "Antigravity"),
    )
    val all = listOf(opus, opusOld, sonnet, fable, haiku, sol, astra, terra, solOld, astraOld, luna, neon, orchestrator)
    val claudeOnly = listOf(opus, opusOld, sonnet, fable, haiku)
    val favourites = ModelPreferences(listOf(ModelKey.of(opus), ModelKey.of(sol), ModelKey.of(neon), ModelKey.of(luna)), synced = true)
    val synced = ModelPreferences(synced = true)
}

@Composable
private fun Picker(
    current: ModelOption, explicit: ModelOptionValues = emptyMap(), enabled: Boolean = true, dark: Boolean = false, models: List<ModelOption> = ModelPickerFixtures.all,
    prefs: ModelPreferences = ModelPickerFixtures.synced, canWriteSettings: Boolean = true, query: String = "", startOnFavourites: Boolean = false, threadSelection: Boolean = false,
) {
    val fx = ModelPickerFixtures
    TrefoilTheme(darkTheme = dark) {
        ModelPickerContent(
            models, fx.providers, current, explicit, enabled, {}, {}, { _, _ -> }, Modifier.background(Tf.colors.sheet),
            preferences = prefs, canWriteSettings = canWriteSettings, initialQuery = query, startOnFavourites = startOnFavourites, threadSelection = threadSelection,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 760)
@Composable
fun ModelPicker_Favourites_Light() = Picker(ModelPickerFixtures.opus, mapOf("effort" to "high"), prefs = ModelPickerFixtures.favourites)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ModelPicker_Favourites_Dark() = Picker(ModelPickerFixtures.opus, mapOf("effort" to "high"), dark = true, prefs = ModelPickerFixtures.favourites)

/** Codex tab, GPT Sol expanded because the older 6.1 is selected; Fast on. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 780)
@Composable
fun ModelPicker_ProviderExpanded_Light() = Picker(ModelPickerFixtures.solOld, mapOf("effort" to "medium", "fastMode" to true))

/** Claude tab, Claude Opus expanded on its legacy 4.6 with the NEW badge on 5.5. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 780, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ModelPicker_ProviderExpanded_Dark() = Picker(ModelPickerFixtures.opusOld, mapOf("effort" to "xhigh"), dark = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 720)
@Composable
fun ModelPicker_Search_Light() = Picker(ModelPickerFixtures.opus, mapOf("effort" to "high"), query = "opus")

/** A started thread locked to one provider: no tabs, favourites above all models. */
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 680)
@Composable
fun ModelPicker_SingleProvider_Light() = Picker(
    ModelPickerFixtures.sonnet, mapOf("effort" to "high"), models = ModelPickerFixtures.claudeOnly,
    prefs = ModelPreferences(listOf(ModelKey.of(ModelPickerFixtures.opus)), synced = true), threadSelection = true,
)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 600)
@Composable
fun ModelPicker_NoFavourites_Light() = Picker(ModelPickerFixtures.luna, startOnFavourites = true)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 760)
@Composable
fun ModelPicker_Disabled_Light() = Picker(
    ModelPickerFixtures.opus, mapOf("effort" to "high"), enabled = false, prefs = ModelPickerFixtures.favourites, canWriteSettings = true,
)

@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 540)
@Composable
fun ModelPicker_NoOptions_Light() = Picker(ModelPickerFixtures.luna)

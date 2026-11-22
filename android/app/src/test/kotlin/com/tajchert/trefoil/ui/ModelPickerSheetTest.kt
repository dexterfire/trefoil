package com.tajchert.trefoil.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import com.tajchert.trefoil.core.model.ConnectionStatus
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ModelPreferences
import com.tajchert.trefoil.core.model.ProviderInstance
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ModelPickerSheetTest {
    private val effort = ModelOptionDescriptor.Select(
        "effort", "Reasoning", choices = listOf(
            ModelOptionChoice("low", "Low"), ModelOptionChoice("high", "High", isDefault = true), ModelOptionChoice("ultrathink", "Ultrathink"),
        ), promptInjectedValues = listOf("ultrathink"), isReasoning = true,
    )
    private val fast = ModelOptionDescriptor.Toggle("fastMode", "Fast mode")
    private val claude = ModelOption("claude", "opus", "Opus", descriptors = listOf(effort, fast))
    private val plain = ModelOption("codex", "gpt-5", "GPT-5")

    @Test fun pillLabelOmitsEffort() = assertEquals("Opus", modelPillLabel(claude, emptyMap()))
    @Test fun pillLabelShowsFast() = assertEquals("Opus · Fast", modelPillLabel(claude, mapOf("effort" to "low", "fastMode" to true)))
    @Test fun pillLabelWithoutOptions() = assertEquals("GPT-5", modelPillLabel(plain, mapOf("effort" to "low")))
    @Test fun pillLabelWithoutModel() = assertEquals("Model", modelPillLabel(null, emptyMap()))
    @Test fun unknownValueFallsBackToId() = assertEquals("max", reasoningChoice(claude, mapOf("effort" to "max"))?.label)
    @Test fun noReasoningChoiceWithoutDescriptor() = assertNull(reasoningChoice(plain, emptyMap()))
    @Test fun fastOnlyModelHasNoOptionsWhenFastHidden() {
        val fastOnly = ModelOption("codex", "gpt-6", "GPT-6", descriptors = listOf(fast))
        assertTrue(hasModelOptions(fastOnly))
        assertFalse(hasModelOptions(fastOnly, showFast = false))
        assertTrue(hasModelOptions(claude, showFast = false))
    }
    @Test fun fastOffByDefault() = assertFalse(fastModeOn(claude, emptyMap()))

    @Test fun catalogModelPrefersCatalogEntry() {
        val bare = ModelOption("claude", "opus", "Opus")
        assertEquals(listOf(effort, fast), catalogModel(bare, listOf(plain, claude))?.descriptors)
        assertEquals(bare, catalogModel(bare, emptyList()))
    }

    @Test fun lockedThreadStaysOnInstance() {
        val all = listOf(claude, plain)
        assertEquals(listOf(claude), pickerModels(all, claude, providerLocked = true, isDraft = false))
        assertEquals(all, pickerModels(all, claude, providerLocked = true, isDraft = true))
        assertEquals(all, pickerModels(all, claude, providerLocked = false, isDraft = false))
    }

    @Test fun groupsByInstanceInCatalogOrder() {
        val second = ModelOption("claude", "sonnet", "Sonnet")
        assertEquals(listOf("claude" to listOf(claude, second), "codex" to listOf(plain)), groupModelsByInstance(listOf(claude, plain, second)))
    }

    @Test fun headerUsesDisplayNameOrId() {
        val providers = listOf(ProviderInstance("claude", "claudeAgent", displayName = "Claude"))
        assertEquals("Claude", instanceHeader("claude", providers))
        assertEquals("codex", instanceHeader("codex", providers))
    }

    @Test fun sheetStaysOpenOnlyForModelsWithOptions() {
        assertTrue(hasModelOptions(claude))
        assertFalse(hasModelOptions(plain))
    }

    @Test fun loadingOnlyWhileConnectingWithoutModels() {
        assertTrue(modelPickerLoading(emptyList(), ConnectionStatus.Connecting))
        assertTrue(modelPickerLoading(emptyList(), ConnectionStatus.Reconnecting))
        assertFalse(modelPickerLoading(emptyList(), ConnectionStatus.Connected))
        assertFalse(modelPickerLoading(emptyList(), ConnectionStatus.Disconnected))
        assertFalse(modelPickerLoading(listOf(plain), ConnectionStatus.Connecting))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelPickerLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val claude = ModelOption("claude", "opus", "Opus")
    private val codex = ModelOption("codex", "gpt-5", "GPT-5")
    private val providers = listOf(ProviderInstance("claude", "claudeAgent", displayName = "Claude"), ProviderInstance("codex", "codex", displayName = "Codex"))

    @Test fun skeletonShowsNoRowsOrStarsThenModelsReplaceIt() {
        val models = mutableStateOf(emptyList<ModelOption>())
        compose.setContent {
            TrefoilTheme {
                ModelPickerContent(models.value, providers, null, emptyMap(), true, {}, {}, { _, _ -> }, loading = models.value.isEmpty(), preferences = ModelPreferences(synced = true), canWriteSettings = true)
            }
        }
        compose.onNodeWithTag("model-picker-loading").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Model ", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Favourite ", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Remove ", substring = true).assertCountEquals(0)

        compose.runOnIdle { models.value = listOf(claude, codex) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("model-picker-loading").assertCountEquals(0)
        compose.onNodeWithContentDescription("Model Opus").assertIsDisplayed()
        compose.onNodeWithContentDescription("Favourite Opus").assertIsDisplayed()
    }

    @Test fun emptyCatalogWithoutLoadingKeepsEmptyState() {
        compose.setContent { TrefoilTheme { ModelPickerContent(emptyList(), providers, null, emptyMap(), true, {}, {}, { _, _ -> }) } }
        compose.onAllNodesWithTag("model-picker-loading").assertCountEquals(0)
        compose.onNodeWithText("No models available").assertIsDisplayed()
    }
}

package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ProviderInstance
import org.junit.Assert.*
import org.junit.Test

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
}

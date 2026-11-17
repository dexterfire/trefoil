package com.tajchert.trefoil.core.model

import org.junit.Assert.*
import org.junit.Test

class ModelOptionsTest {
    private val effort = ModelOptionDescriptor.Select("effort", "Effort", choices = listOf(
        ModelOptionChoice("low", "Low"), ModelOptionChoice("high", "High", isDefault = true), ModelOptionChoice("ultrathink", "Ultrathink"),
    ), promptInjectedValues = listOf("ultrathink"), isReasoning = true)
    private val fast = ModelOptionDescriptor.Toggle("fastMode", "Fast mode")
    private val model = ModelOption("claude", "opus", "Opus", descriptors = listOf(ModelOptionDescriptor.Select("other", "Other", choices = emptyList()), effort, fast))

    @Test fun helpersFindReasoningAndFastMode() {
        assertEquals(effort, model.reasoning)
        assertEquals(fast, model.fastMode)
        assertEquals(listOf("low", "high"), effort.selectableChoices.map { it.id })
        assertNull(ModelOption("codex", "gpt-5").reasoning)
        assertNull(ModelOption("codex", "gpt-5").fastMode)
    }

    @Test fun resolvedValueFollowsExplicitThenCurrentThenDefault() {
        assertEquals("low", model.resolvedOptionValue(effort, mapOf("effort" to "low")))
        assertEquals("max", model.resolvedOptionValue(effort.copy(currentValue = "max"), emptyMap()))
        assertEquals("low", model.resolvedOptionValue(effort.copy(currentValue = "max"), mapOf("effort" to "low")))
        assertEquals("high", model.resolvedOptionValue(effort, emptyMap()))
        assertEquals("A value of the wrong type is ignored", "high", model.resolvedOptionValue(effort, mapOf("effort" to true)))
        assertNull(model.resolvedOptionValue(effort.copy(choices = listOf(ModelOptionChoice("low", "Low"))), emptyMap()))
        assertNull("An unset toggle is unknown, not off", model.resolvedOptionValue(fast, emptyMap()))
        assertEquals(true, model.resolvedOptionValue(fast.copy(currentValue = true), emptyMap()))
        assertEquals(false, model.resolvedOptionValue(fast.copy(currentValue = true), mapOf("fastMode" to false)))
    }

    @Test fun threadVariantWithoutExplicitValueIsUnknown() {
        val variant = ModelOptionDescriptor.Select("variant", "Variant", choices = listOf(ModelOptionChoice("fast", "Fast", isDefault = true), ModelOptionChoice("deep", "Deep")), currentValue = "deep")
        val m = ModelOption("opencode", "x", descriptors = listOf(variant))
        assertNull(m.resolvedOptionValue(variant, emptyMap(), threadSelection = true))
        assertEquals("fast", m.resolvedOptionValue(variant, mapOf("variant" to "fast"), threadSelection = true))
        assertEquals("Drafts keep the default resolution", "deep", m.resolvedOptionValue(variant, emptyMap()))
        assertEquals("deep", m.resolvedOptionValue(variant.copy(id = "effort"), emptyMap(), threadSelection = true))
    }

    @Test fun offeredValuesAreValidated() {
        assertTrue(model.offersOptionValue("effort", "low"))
        assertFalse(model.offersOptionValue("effort", "extreme"))
        assertFalse(model.offersOptionValue("effort", true))
        assertTrue(model.offersOptionValue("fastMode", true))
        assertFalse(model.offersOptionValue("fastMode", "true"))
        assertFalse(model.offersOptionValue("missing", "x"))
        assertFalse("Prompt-injected values are not options", model.offersOptionValue("effort", "ultrathink"))
        assertEquals(emptyMap<String, Any>(), model.validOptions(mapOf("effort" to "ultrathink")))
        assertThrows(IllegalArgumentException::class.java) { model.requireOptionValue("effort", "extreme") }
        assertThrows(IllegalArgumentException::class.java) { model.requireOptionValue("missing", "x") }
        assertEquals(mapOf("effort" to "low"), model.validOptions(mapOf("effort" to "low", "fastMode" to "yes", "gone" to "x")))
    }

    @Test fun modelsCompareByInstanceAndSlug() {
        assertTrue(model.sameModelAs(ModelOption("claude", "opus")))
        assertFalse(model.sameModelAs(ModelOption("claude", "sonnet")))
        assertFalse(model.sameModelAs(ModelOption("other", "opus")))
        assertFalse(model.sameModelAs(null))
    }
}

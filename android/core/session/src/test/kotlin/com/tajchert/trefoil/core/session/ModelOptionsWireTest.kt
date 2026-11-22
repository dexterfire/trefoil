package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelOptionsWireTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun parsesSelectAndBooleanDescriptorsAndSkipsUnknownTypes() {
        val model = obj("""{"slug":"opus","name":"Opus","capabilities":{"optionDescriptors":[
            {"id":"effort","label":"Effort","description":"How hard to think","type":"select","options":[{"id":"low","label":"Low"},{"id":"high","label":"High","description":"Deeper","isDefault":true},{"id":"ultrathink","label":"Ultrathink"}],"currentValue":"high","promptInjectedValues":["ultrathink"],"futureKey":1},
            {"id":"fastMode","label":"Fast mode","type":"boolean","currentValue":true},
            {"id":"temperature","label":"Temperature","type":"slider","min":0},
            {"id":"mode","label":"Mode","type":"select","role":"reasoning","options":[]},
            {"id":"plain","label":"Plain","type":"select","options":[{"id":"a","label":"A"}]},
            {"label":"No id","type":"boolean"}
        ]}}""".replace("\n", ""))
        val descriptors = modelOptionDescriptors(model)
        assertEquals(listOf("effort", "fastMode", "mode", "plain"), descriptors.map { it.id })
        val effort = descriptors[0] as ModelOptionDescriptor.Select
        assertEquals(ModelOptionDescriptor.Select("effort", "Effort", "How hard to think", listOf(
            ModelOptionChoice("low", "Low"), ModelOptionChoice("high", "High", "Deeper", isDefault = true), ModelOptionChoice("ultrathink", "Ultrathink"),
        ), currentValue = "high", promptInjectedValues = listOf("ultrathink"), isReasoning = true), effort)
        assertEquals(ModelOptionDescriptor.Toggle("fastMode", "Fast mode", currentValue = true), descriptors[1])
        assertTrue("role reasoning marks a select", (descriptors[2] as ModelOptionDescriptor.Select).isReasoning)
        assertFalse((descriptors[3] as ModelOptionDescriptor.Select).isReasoning)
        val option = ModelOption("claude", "opus", descriptors = descriptors)
        assertEquals(effort, option.reasoning)
        assertEquals(descriptors[1], option.fastMode)
    }

    @Test fun missingOrNullCapabilitiesGiveNoDescriptors() {
        assertTrue(modelOptionDescriptors(obj("""{"slug":"gpt-5"}""")).isEmpty())
        assertTrue(modelOptionDescriptors(obj("""{"slug":"gpt-5","capabilities":null}""")).isEmpty())
        assertTrue(modelOptionDescriptors(obj("""{"slug":"gpt-5","capabilities":{}}""")).isEmpty())
    }

    @Test fun readsBothOptionWireForms() {
        val canonical = Json.parseToJsonElement("""[{"id":"effort","value":" high "},{"id":"fastMode","value":true},{"id":"blank","value":"  "},{"id":"num","value":3},{"value":"x"}]""")
        assertEquals(mapOf("effort" to "high", "fastMode" to true), modelOptionValues(canonical))
        val legacy = Json.parseToJsonElement("""{"reasoningEffort":"high","fastMode":false,"nested":{"a":1},"empty":""}""")
        assertEquals(mapOf("reasoningEffort" to "high", "fastMode" to false), modelOptionValues(legacy))
        assertTrue(modelOptionValues(null).isEmpty())
        assertTrue(modelOptionValues(JsonNull).isEmpty())
    }

    @Test fun writesCanonicalArray() {
        assertEquals(Json.parseToJsonElement("""[{"id":"effort","value":"high"},{"id":"fastMode","value":true}]"""),
            modelOptionsJson(mapOf("effort" to "high", "fastMode" to true)))
    }

    @Test fun parsesCatalogMetadataAndDefaultsWhenAbsentOrMalformed() {
        val full = catalogModelOption("codex", listOf(RuntimeMode.Auto), obj("""{"slug":"gpt-6.2-sol","name":"GPT-6.2-Sol","shortName":"Sol","subProvider":"openai","isLegacy":false,"isDefault":true,"badge":"new","future":{}}"""))
        assertEquals(ModelOption("codex", "gpt-6.2-sol", "GPT-6.2-Sol", listOf(RuntimeMode.Auto), shortName = "Sol", subProvider = "openai", isDefault = true, badge = "new"), full)
        val older = catalogModelOption("claudeAgent", emptyList(), obj("""{"slug":"claude-opus-4-6","name":"Claude Opus 4.6","isLegacy":true}"""))
        assertTrue(older.isLegacy); assertFalse(older.isDefault); assertNull(older.shortName); assertNull(older.badge)
        val malformed = catalogModelOption("x", emptyList(), obj("""{"slug":"m","name":"M","shortName":" ","isLegacy":"true","isDefault":1,"badge":null}"""))
        assertEquals(ModelOption("x", "m", "M"), malformed)
    }

    @Test fun parsesModelPreferencesAndKeepsMissingKeys() {
        val settings = obj("""{"favorites":[{"provider":"codex","model":"gpt-6.2-sol"},{"provider":"claudeAgent","model":"claude-opus-5-5"},{"provider":"codex","model":"gpt-6.2-sol"},{"provider":"codex"},"x"],
            "providerModelPreferences":{"codex":{"hiddenModels":["gpt-6-luna",1],"modelOrder":["gpt-6.2-sol","gpt-6.1-terra"]},"cursor":{"hiddenModels":[],"modelOrder":[]},"bad":3}}""".replace("\n", ""))
        val prefs = modelPreferences(settings, ModelPreferences())
        assertEquals(ModelPreferences(
            favorites = listOf(ModelKey("codex", "gpt-6.2-sol"), ModelKey("claudeAgent", "claude-opus-5-5")),
            hiddenModels = mapOf("codex" to setOf("gpt-6-luna")), modelOrder = mapOf("codex" to listOf("gpt-6.2-sol", "gpt-6.1-terra")), synced = true,
        ), prefs)
        // Missing keys keep the last value; an empty favourites list is a reported (synced) empty list.
        assertEquals(prefs, modelPreferences(obj("""{"enablePreviewTunnel":true}"""), prefs))
        assertEquals(prefs.copy(favorites = emptyList()), modelPreferences(obj("""{"favorites":[]}"""), prefs))
        assertEquals(prefs.copy(hiddenModels = emptyMap(), modelOrder = emptyMap()), modelPreferences(obj("""{"providerModelPreferences":{}}"""), prefs))
        // Older servers omit favourites: not synced.
        assertEquals(ModelPreferences(), modelPreferences(obj("""{"defaultRuntimeMode":"auto"}"""), ModelPreferences()))
        assertEquals(ModelPreferences(), modelPreferences(null, ModelPreferences()))
        assertNull(modelFavorites(obj("""{"favorites":"broken"}""")))
        assertEquals(ModelPreferences(synced = true), modelPreferences(obj("""{"favorites":[]}"""), ModelPreferences()))
    }
}

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
}

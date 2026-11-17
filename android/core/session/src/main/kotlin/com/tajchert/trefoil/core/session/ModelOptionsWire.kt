package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.core.model.REASONING_OPTION_IDS
import com.tajchert.trefoil.core.protocol.objects
import com.tajchert.trefoil.core.protocol.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Adapted from T3 Code (MIT): packages/contracts/src/model.ts (ProviderOptionDescriptor,
// ProviderOptionSelections) and packages/client-runtime/src/modelTraits.ts (isReasoningControl).

private fun JsonObject.text(key: String): String? = string(key).trim().takeIf { it.isNotEmpty() }

/**
 * Server `ServerProviderModel.capabilities.optionDescriptors`. Missing or null capabilities give
 * none; descriptors of an unknown `type`, or without an id, are skipped (forward compatible).
 */
internal fun modelOptionDescriptors(model: JsonObject): List<ModelOptionDescriptor> =
    ((model["capabilities"] as? JsonObject)?.objects("optionDescriptors")).orEmpty().mapNotNull { d ->
        val id = d.text("id") ?: return@mapNotNull null
        val label = d.text("label") ?: id
        when (d.string("type")) {
            "select" -> ModelOptionDescriptor.Select(
                id, label, d.text("description"),
                choices = d.objects("options").mapNotNull { option ->
                    val choiceId = option.text("id") ?: return@mapNotNull null
                    ModelOptionChoice(choiceId, option.text("label") ?: choiceId, option.text("description"), (option["isDefault"] as? JsonPrimitive)?.booleanOrNull == true)
                },
                currentValue = (d["currentValue"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() },
                promptInjectedValues = (d["promptInjectedValues"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty(),
                isReasoning = d.string("role") == "reasoning" || id in REASONING_OPTION_IDS,
            )
            "boolean" -> ModelOptionDescriptor.Toggle(id, label, d.text("description"), (d["currentValue"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull)
            else -> null
        }
    }

private fun optionValue(element: JsonElement?): Any? {
    val primitive = element as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content.trim().takeIf { it.isNotEmpty() } else primitive.booleanOrNull
}

/**
 * `modelSelection.options` in either wire form: canonical `[{id, value}]` or legacy `{id: value}`.
 * Values are non-empty trimmed strings or booleans; anything else is skipped.
 */
internal fun modelOptionValues(options: JsonElement?): ModelOptionValues = when (options) {
    is JsonArray -> options.mapNotNull { entry ->
        val option = entry as? JsonObject ?: return@mapNotNull null
        val id = option.text("id") ?: return@mapNotNull null
        optionValue(option["value"])?.let { id to it }
    }.toMap()
    is JsonObject -> options.mapNotNull { (id, value) -> optionValue(value)?.let { id.trim() to it } }.filter { it.first.isNotEmpty() }.toMap()
    else -> emptyMap()
}

/** Canonical wire form `[{"id": ..., "value": ...}]` of String or Boolean [values]. */
internal fun modelOptionsJson(values: ModelOptionValues): JsonArray = JsonArray(values.map { (id, value) ->
    buildJsonObject {
        put("id", id)
        when (value) {
            is Boolean -> put("value", value)
            is String -> put("value", value)
            else -> throw IllegalArgumentException("Option values are strings or booleans")
        }
    }
})

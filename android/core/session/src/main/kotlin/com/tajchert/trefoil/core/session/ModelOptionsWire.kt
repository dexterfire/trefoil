package com.tajchert.trefoil.core.session

import com.tajchert.trefoil.core.model.ModelDefault
import com.tajchert.trefoil.core.model.ModelKey
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.core.model.ModelPreferences
import com.tajchert.trefoil.core.model.NewThreadDefaults
import com.tajchert.trefoil.core.model.REASONING_OPTION_IDS
import com.tajchert.trefoil.core.model.RuntimeMode
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

/** A T3 `ModelSelection` object (`{instanceId, model, options?}`); null when absent, null or missing either id. */
internal fun modelDefault(element: JsonElement?): ModelDefault? {
    val selection = element as? JsonObject ?: return null
    return ModelDefault(selection.text("instanceId") ?: return null, selection.text("model") ?: return null, modelOptionValues(selection["options"]))
}

/**
 * New-thread defaults from `server.getConfig` `settings`: the environment's `defaultModelSelection` and each project's
 * `projectSettingsOverrides` entry (the server already folds legacy project-row defaults into it). Unknown runtime
 * modes and malformed selections are skipped, so the wider scope applies.
 */
internal fun newThreadDefaults(settings: JsonObject?): Pair<ModelDefault?, Map<String, NewThreadDefaults>> {
    val overrides = (settings?.get("projectSettingsOverrides") as? JsonObject).orEmpty().mapNotNull { (projectId, value) ->
        val entry = value as? JsonObject ?: return@mapNotNull null
        NewThreadDefaults(modelDefault(entry["defaultModelSelection"]), RuntimeMode.fromWire((entry["defaultRuntimeMode"] as? JsonPrimitive)?.takeIf { it.isString }?.content))
            .takeIf { it.model != null || it.runtimeMode != null }?.let { projectId to it }
    }.toMap()
    return modelDefault(settings?.get("defaultModelSelection")) to overrides
}

private fun JsonObject.isTrue(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

/**
 * A `ServerProviderModel` of provider instance [instanceId], with the provider's runtime [modes]. Optional catalog
 * metadata (`shortName`, `subProvider`, `isLegacy`, `isDefault`, `badge`) defaults when absent or malformed.
 */
internal fun catalogModelOption(instanceId: String, modes: List<RuntimeMode>, model: JsonObject): ModelOption = ModelOption(
    instanceId, model.string("slug"), model.string("name"), modes, modelOptionDescriptors(model),
    shortName = model.text("shortName"), subProvider = model.text("subProvider"),
    isLegacy = model.isTrue("isLegacy"), isDefault = model.isTrue("isDefault"), badge = model.text("badge"),
)

/**
 * Server `settings.favorites` (`[{provider, model}]`, T3 `ModelFavorite`, `provider` being the instance id) in server
 * order; null when absent or not an array (older servers). Malformed entries are skipped and duplicates dropped.
 */
internal fun modelFavorites(settings: JsonObject?): List<ModelKey>? {
    val favorites = settings?.get("favorites") as? JsonArray ?: return null
    return favorites.mapNotNull { entry ->
        val favorite = entry as? JsonObject ?: return@mapNotNull null
        ModelKey(favorite.text("provider") ?: return@mapNotNull null, favorite.text("model") ?: return@mapNotNull null)
    }.distinct()
}

private fun slugs(element: JsonElement?): List<String> =
    (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty().distinct()

/**
 * [previous] updated from server [settings] (`favorites`, `providerModelPreferences: {instanceId: {hiddenModels,
 * modelOrder}}`). A missing or malformed key keeps the previous value; a reported `favorites` makes it [ModelPreferences.synced].
 */
internal fun modelPreferences(settings: JsonObject?, previous: ModelPreferences): ModelPreferences {
    val favorites = modelFavorites(settings)
    val instances = (settings?.get("providerModelPreferences") as? JsonObject)?.mapNotNull { (id, value) -> (value as? JsonObject)?.let { id to it } }
    return previous.copy(
        favorites = favorites ?: previous.favorites,
        hiddenModels = instances?.mapNotNull { (id, prefs) -> slugs(prefs["hiddenModels"]).takeIf { it.isNotEmpty() }?.let { id to it.toSet() } }?.toMap() ?: previous.hiddenModels,
        modelOrder = instances?.mapNotNull { (id, prefs) -> slugs(prefs["modelOrder"]).takeIf { it.isNotEmpty() }?.let { id to it } }?.toMap() ?: previous.modelOrder,
        synced = previous.synced || favorites != null,
    )
}

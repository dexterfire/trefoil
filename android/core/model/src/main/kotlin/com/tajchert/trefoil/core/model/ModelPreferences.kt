package com.tajchert.trefoil.core.model

/** A catalog model by provider instance and slug (T3 `ModelFavorite`: `provider` is the instance id). */
data class ModelKey(val instanceId: String, val model: String) {
    companion object { fun of(model: ModelOption) = ModelKey(model.instanceId, model.model) }
}

/**
 * The server's model picker preferences, shared with T3 desktop: [favorites] in server order,
 * [hiddenModels] and [modelOrder] by instance id. [synced] is false until a config reported `settings.favorites`.
 */
data class ModelPreferences(
    val favorites: List<ModelKey> = emptyList(),
    val hiddenModels: Map<String, Set<String>> = emptyMap(),
    val modelOrder: Map<String, List<String>> = emptyMap(),
    val synced: Boolean = false,
) {
    fun isFavorite(model: ModelOption): Boolean = favorites.any { it.instanceId == model.instanceId && it.model == model.model }
}

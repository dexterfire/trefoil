package com.tajchert.trefoil.core.model

// Adapted from T3 Code (MIT): packages/shared/src/model.ts (getProviderOptionCurrentValue),
// packages/client-runtime/src/modelTraits.ts and state/threadExecution.ts (REASONING_EFFORT_OPTION_IDS).

/** T3 Fast mode: the boolean descriptor with this id. */
const val FAST_MODE_OPTION_ID = "fastMode"

/** T3 `REASONING_EFFORT_OPTION_IDS` plus `thinking`: a select with one of these ids is the reasoning control. */
val REASONING_OPTION_IDS: Set<String> = setOf("reasoningEffort", "effort", "reasoning", "variant", "reasoning_effort", "thinking_level", "thinking")

/** T3 `variant`: a thread selection without an explicit variant has no known current value. */
const val VARIANT_OPTION_ID = "variant"

/**
 * Current value of [d] for this model (T3 `getProviderOptionCurrentValue`): the explicit value in
 * [explicit] (of the descriptor's type), else the descriptor's `currentValue`, else (select only) the
 * `isDefault` choice. A toggle with neither is null (unknown), not false; a select may also be null.
 * [threadSelection] true resolves a thread's stored selection, where a `variant` without an explicit
 * value is null, like upstream. Drafts keep the default resolution.
 */
fun ModelOption.resolvedOptionValue(d: ModelOptionDescriptor, explicit: ModelOptionValues, threadSelection: Boolean = false): Any? = when (d) {
    is ModelOptionDescriptor.Select -> (explicit[d.id] as? String)
        ?: if (threadSelection && d.id == VARIANT_OPTION_ID) null else d.currentValue ?: d.choices.firstOrNull { it.isDefault }?.id
    is ModelOptionDescriptor.Toggle -> (explicit[d.id] as? Boolean) ?: d.currentValue
}

/**
 * True when this model offers [value] for option [id]: a selectable choice (prompt-injected values
 * such as "ultrathink" are never sent as options), or a Boolean for a toggle.
 */
fun ModelOption.offersOptionValue(id: String, value: Any): Boolean = when (val d = descriptor(id)) {
    is ModelOptionDescriptor.Select -> value is String && d.selectableChoices.any { it.id == value }
    is ModelOptionDescriptor.Toggle -> value is Boolean
    null -> false
}

/**
 * Throws [IllegalArgumentException] with a user-facing message when this model does not offer
 * [value] for option [id]; nothing should be sent then.
 */
fun ModelOption.requireOptionValue(id: String, value: Any) {
    val d = requireNotNull(descriptor(id)) { "$label has no option \"$id\"" }
    require(offersOptionValue(id, value)) { "${d.label} does not offer this value for $label" }
}

/** The entries of [options] this model offers; the rest are dropped (for example after a model change). */
fun ModelOption.validOptions(options: ModelOptionValues): ModelOptionValues = options.filter { (id, value) -> offersOptionValue(id, value) }

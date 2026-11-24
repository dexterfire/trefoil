package com.tajchert.trefoil.core.model

/**
 * T3 `OrchestrationV2PromptSuggestion`: the provider's predicted next message for the run that produced it.
 * Unpersisted on the server and conversation content here: never logged, never stored.
 */
data class PromptSuggestion(val runId: String, val text: String)

/**
 * The suggestion to offer in the empty composer: [ClientState.promptSuggestion] while the run that produced it is the
 * selected thread's latest completed run. Null otherwise. Dismissal and the empty-draft rule belong to the UI.
 */
val ClientState.offeredPromptSuggestion: PromptSuggestion? get() = promptSuggestion

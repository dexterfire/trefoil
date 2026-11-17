package com.tajchert.trefoil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelOptionChoice
import com.tajchert.trefoil.core.model.ModelOptionDescriptor
import com.tajchert.trefoil.core.model.ModelOptionValues
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.resolvedOptionValue

// ---------- Presentation helpers (pure, unit-tested) ----------

/** The catalog entry for [model]: drafts may carry a model without descriptors. */
internal fun catalogModel(model: ModelOption?, catalog: List<ModelOption>): ModelOption? =
    model?.let { m -> catalog.firstOrNull { it.sameModelAs(m) } ?: m }

/** Models the picker offers: a locked selected thread stays on its provider instance. */
internal fun pickerModels(models: List<ModelOption>, current: ModelOption?, providerLocked: Boolean, isDraft: Boolean): List<ModelOption> =
    if (providerLocked && !isDraft && current != null) models.filter { it.instanceId == current.instanceId } else models

/** Models grouped by provider instance, keeping catalog order. */
internal fun groupModelsByInstance(models: List<ModelOption>): List<Pair<String, List<ModelOption>>> =
    models.groupBy { it.instanceId }.map { (id, list) -> id to list }

internal fun instanceHeader(instanceId: String, providers: List<ProviderInstance>): String =
    providers.firstOrNull { it.instanceId == instanceId }?.displayName?.takeIf { it.isNotBlank() } ?: instanceId

/** Resolved reasoning choice of [model], or null when it has no reasoning control or no value resolves. */
internal fun reasoningChoice(model: ModelOption?, explicit: ModelOptionValues, threadSelection: Boolean = false): ModelOptionChoice? {
    val d = model?.reasoning ?: return null
    val value = model.resolvedOptionValue(d, explicit, threadSelection) as? String ?: return null
    return d.choices.firstOrNull { it.id == value } ?: ModelOptionChoice(value, value)
}

internal fun fastModeOn(model: ModelOption?, explicit: ModelOptionValues): Boolean {
    val d = model?.fastMode ?: return false
    return model.resolvedOptionValue(d, explicit) == true
}

/** Composer pill text: `<model> · <effort>` plus ` · Fast` when Fast mode is on. */
internal fun modelPillLabel(model: ModelOption?, explicit: ModelOptionValues, threadSelection: Boolean = false): String {
    if (model == null) return "Model"
    val parts = listOfNotNull(model.label, reasoningChoice(model, explicit, threadSelection)?.label, if (fastModeOn(model, explicit)) "Fast" else null)
    return parts.joinToString(" · ")
}

/** Whether picking [model] leaves the sheet open for effort/Fast choices. */
internal fun hasModelOptions(model: ModelOption?): Boolean = model != null && (model.reasoning != null || model.fastMode != null)

// ---------- Sheet ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    models: List<ModelOption>, providers: List<ProviderInstance>, current: ModelOption?, explicit: ModelOptionValues, enabled: Boolean,
    dismiss: () -> Unit, onSetModel: (ModelOption) -> Unit, onSetModelOption: (String, Any) -> Unit, threadSelection: Boolean = false,
) {
    val c = Tf.colors
    ModalBottomSheet(
        onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.sheet, contentColor = c.text, scrimColor = c.scrim, tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        contentWindowInsets = { WindowInsets(0) },
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.s3, RoundedCornerShape(2.dp))) },
    ) {
        ModelPickerContent(models, providers, current, explicit, enabled, { dismiss() }, onSetModel, onSetModelOption, Modifier.navigationBarsPadding(), threadSelection)
    }
}

/** Sheet body, separate from the [ModalBottomSheet] chrome so it renders standalone. [closeSheet] runs after a model pick without options. */
@Composable
internal fun ModelPickerContent(
    models: List<ModelOption>, providers: List<ProviderInstance>, current: ModelOption?, explicit: ModelOptionValues, enabled: Boolean,
    closeSheet: () -> Unit, onSetModel: (ModelOption) -> Unit, onSetModelOption: (String, Any) -> Unit, modifier: Modifier = Modifier, threadSelection: Boolean = false,
) {
    val c = Tf.colors
    // A pick on a thread lands asynchronously: keep showing the picked model's options until the selection catches up.
    var picked by remember { mutableStateOf<ModelOption?>(null) }
    LaunchedEffect(current?.instanceId, current?.model) { picked = null }
    val highlighted = catalogModel(picked ?: current, models)
    val applied = highlighted != null && highlighted.sameModelAs(current)
    val values = if (applied) explicit else emptyMap()
    val optionsEnabled = enabled && applied
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
        Text("Model", Modifier.padding(start = 4.dp, bottom = 4.dp), style = MaterialTheme.typography.headlineSmall, color = c.text)
        groupModelsByInstance(models).forEach { (instanceId, group) ->
            SectionLabel(instanceHeader(instanceId, providers), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
            group.forEach { option ->
                val selected = option.sameModelAs(highlighted)
                CheckRow(option.label, null, selected, enabled, "Model ${option.label}") {
                    picked = option
                    onSetModel(option)
                    if (!hasModelOptions(option)) closeSheet()
                }
            }
        }
        val reasoning = highlighted?.reasoning
        if (reasoning != null) {
            val choice = reasoningChoice(highlighted, values, threadSelection)
            SectionLabel(reasoning.label.ifBlank { "Reasoning" }, Modifier.padding(start = 4.dp, top = 18.dp, bottom = 2.dp))
            reasoning.selectableChoices.forEach { option ->
                CheckRow(option.label, option.description, option.id == choice?.id, optionsEnabled, "${reasoning.label} ${option.label}") { onSetModelOption(reasoning.id, option.id) }
            }
        }
        val fast = highlighted?.fastMode
        if (fast != null) {
            val on = fastModeOn(highlighted, values)
            SectionLabel("Speed", Modifier.padding(start = 4.dp, top = 18.dp, bottom = 2.dp))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(on, optionsEnabled, Role.Switch) { onSetModelOption(fast.id, !on) }
                    .semantics { contentDescription = fast.label },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(fast.label.ifBlank { "Fast mode" }, style = MaterialTheme.typography.bodyLarge, color = if (optionsEnabled) c.text else c.muted)
                    fast.description?.let { Text(it, style = TrefoilType.preview, color = c.muted) }
                }
                Switch(on, null, enabled = optionsEnabled)
            }
        }
    }
}

@Composable
private fun CheckRow(title: String, supporting: String?, selected: Boolean, enabled: Boolean, description: String, onClick: () -> Unit) {
    val c = Tf.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, enabled, Role.RadioButton, onClick = onClick).semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) c.text else c.muted)
            if (!supporting.isNullOrBlank()) Text(supporting, style = TrefoilType.preview, color = c.muted)
        }
        if (selected) Icon(TfIcons.Check, "Selected", Modifier.padding(horizontal = 8.dp).size(20.dp), tint = c.accent)
    }
}

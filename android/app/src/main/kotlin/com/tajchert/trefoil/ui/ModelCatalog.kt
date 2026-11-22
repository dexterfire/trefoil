package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ModelKey
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelPreferences
import com.tajchert.trefoil.core.model.ProviderInstance

// ---------- Model catalog presentation (pure, unit-tested) ----------

/** Versions of one model line, newest first (non-legacy before legacy); [latest] is the first. Size 1 is a plain model. */
internal data class ModelFamily(val key: String, val name: String, val latest: ModelOption, val versions: List<ModelOption>)

internal data class ProviderSection(val instanceId: String, val driver: String, val title: String, val families: List<ModelFamily>)

internal data class ModelCatalog(val favorites: List<ModelOption>, val sections: List<ProviderSection>)

private val separators = Regex("[\\s\\-_()]+")
private val numeric = Regex("^\\d+(\\.\\d+)*$")
private val driverRank = mapOf("claudeAgent" to 0, "codex" to 1, "antigravity" to 2, "grok" to 3)
private val tierWords = mapOf(
    "claudeAgent" to listOf("opus", "sonnet", "fable", "haiku"),
    "codex" to listOf("sol", "astra", "terra", "luna"),
)

/** [version] components, [versionText] ("6.2"), [key] lowercased remaining tokens, [name] remaining tokens as written. */
private class ParsedName(val version: List<Long>?, val versionText: String?, val key: String, val name: String)

private fun parseName(model: ModelOption): ParsedName {
    val text = model.label.takeIf { it.isNotBlank() && it != model.model } ?: model.model
    val tokens = text.split(separators).filter { it.isNotEmpty() }
    val start = tokens.indexOfFirst { numeric.matches(it) }
    if (start < 0) return ParsedName(null, null, tokens.joinToString(" ") { it.lowercase() }, model.label.ifBlank { model.model })
    var end = start
    while (end < tokens.size && numeric.matches(tokens[end])) end++
    val versionText = tokens.subList(start, end).joinToString(".")
    val rest = tokens.subList(0, start) + tokens.subList(end, tokens.size)
    return ParsedName(
        versionText.split('.').map { it.toLongOrNull() ?: Long.MAX_VALUE }, versionText,
        rest.joinToString(" ") { it.lowercase() }, rest.joinToString(" "),
    )
}

/** Version of [model] as shown on a chip ("6.2", "5.5"), or null when unversioned. */
internal fun versionLabel(model: ModelOption): String? = parseName(model).versionText

/** Component-wise numeric comparison; missing components count as 0. */
private fun compareVersions(a: List<Long>, b: List<Long>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val c = (a.getOrNull(i) ?: 0L).compareTo(b.getOrNull(i) ?: 0L)
        if (c != 0) return c
    }
    return 0
}

private val newestFirst = compareBy<ModelOption> { it.isLegacy }.then { a, b ->
    val va = parseName(a).version
    val vb = parseName(b).version
    if (va != null && vb != null) compareVersions(vb, va) else 0
}

private fun sectionFamilies(driver: String, instanceId: String, models: List<ModelOption>, prefs: ModelPreferences): List<ModelFamily> {
    // LinkedHashMap keeps first appearance; only versioned models share a family, unversioned ones stand alone by slug.
    val groups = LinkedHashMap<String, MutableList<ModelOption>>()
    models.forEach { m ->
        val parsed = parseName(m)
        groups.getOrPut(if (parsed.version != null) "v:${parsed.key}" else "u:${m.model}") { mutableListOf() }.add(m)
    }
    val families = groups.values.map { members ->
        val versions = members.sortedWith(newestFirst)
        val parsed = parseName(versions.first())
        ModelFamily(if (parsed.version != null) parsed.key else versions.first().model, parsed.name, versions.first(), versions)
    }
    val order = prefs.modelOrder[instanceId].orEmpty()
    val tiers = tierWords[driver]
    val unranked = Int.MAX_VALUE
    val rank: (ModelFamily) -> Int = when {
        order.isNotEmpty() -> { f -> f.versions.minOfOrNull { v -> order.indexOf(v.model).takeIf { it >= 0 } ?: unranked } ?: unranked }
        tiers != null -> { f -> parseName(f.latest).key.split(' ').mapNotNull { t -> tiers.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: unranked }
        else -> { _ -> 0 }
    }
    // sortedWith is stable, so equal ranks keep first appearance in the catalog.
    return families.sortedWith(compareBy<ModelFamily>(rank).thenBy { f -> f.versions.all { it.isLegacy } })
}

internal fun buildModelCatalog(models: List<ModelOption>, providers: List<ProviderInstance>, prefs: ModelPreferences, current: ModelOption?): ModelCatalog {
    val visible = models.filter { m -> m.sameModelAs(current) || m.model !in prefs.hiddenModels[m.instanceId].orEmpty() }
    val sections = visible.groupBy { it.instanceId }.map { (instanceId, members) ->
        val driver = providers.firstOrNull { it.instanceId == instanceId }?.driver ?: instanceId
        ProviderSection(instanceId, driver, instanceHeader(instanceId, providers), sectionFamilies(driver, instanceId, members, prefs))
    }.sortedBy { driverRank[it.driver] ?: 4 }
    val favorites = prefs.favorites.distinct().mapNotNull { key -> models.firstOrNull { ModelKey.of(it) == key } }
    return ModelCatalog(favorites, sections)
}

/**
 * Models matching every whitespace token of [query] (case-insensitive substring of label, slug, short name, family name or
 * provider name): favourites first, then catalog order (sections, families, newest version first). Empty query gives none.
 */
internal fun searchModels(models: List<ModelOption>, providers: List<ProviderInstance>, prefs: ModelPreferences, query: String): List<ModelOption> {
    val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    val catalog = buildModelCatalog(models, providers, prefs, null)
    val ordered = catalog.favorites + catalog.sections.flatMap { s -> s.families.flatMap { it.versions } }
    return ordered.distinctBy { ModelKey.of(it) }.filter { m ->
        val fields = listOfNotNull(m.label, m.model, m.shortName, parseName(m).name, instanceHeader(m.instanceId, providers)).map { it.lowercase() }
        tokens.all { t -> fields.any { t in it } }
    }
}

/** The family containing [model] in [catalog]; null when [model] is null or not listed (e.g. a hidden favourite). */
internal fun familyOf(catalog: ModelCatalog, model: ModelOption?): ModelFamily? =
    model?.let { m -> catalog.sections.firstNotNullOfOrNull { s -> s.families.firstOrNull { f -> f.versions.any { it.sameModelAs(m) } } } }

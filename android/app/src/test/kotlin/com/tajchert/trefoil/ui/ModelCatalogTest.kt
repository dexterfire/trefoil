package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ModelKey
import com.tajchert.trefoil.core.model.ModelOption
import com.tajchert.trefoil.core.model.ModelPreferences
import com.tajchert.trefoil.core.model.ProviderInstance
import org.junit.Assert.*
import org.junit.Test

class ModelCatalogTest {
    private fun m(instance: String, slug: String, label: String = slug, legacy: Boolean = false, shortName: String? = null) =
        ModelOption(instance, slug, label, isLegacy = legacy, shortName = shortName)

    // Live catalog, deliberately shuffled so provider and tier ordering has to do the work.
    private val cursor = listOf(m("cursor", "grok-4.7", "Grok 4.7"), m("cursor", "composer-2.5", "Composer 2.5"), m("cursor", "gemini-4-neon", "Gemini 4 Neon"))
    private val codex = listOf(
        m("codex", "gpt-6.1-terra", "GPT-6.1 Terra"), m("codex", "gpt-6.2-astra", "GPT-6.2-Astra"), m("codex", "gpt-6-luna", "GPT-6-Luna"),
        m("codex", "gpt-6.1-sol", "GPT-6.1-Sol"), m("codex", "gpt-6.2-sol", "GPT-6.2-Sol"), m("codex", "gpt-6-astra", "GPT-6-Astra"),
    )
    private val claude = listOf(
        m("claudeAgent", "claude-haiku-5-5", "Claude Haiku 5.5"), m("claudeAgent", "claude-fable-5-2", "Claude Fable 5.2"),
        m("claudeAgent", "claude-sonnet-5-5", "Claude Sonnet 5.5"), m("claudeAgent", "claude-opus-5-5", "Claude Opus 5.5"),
        m("claudeAgent", "claude-opus-4-6", "Claude Opus 4.6", legacy = true), m("claudeAgent", "claude-sonnet-4-6", "Claude Sonnet 4.6", legacy = true),
    )
    private val antigravity = listOf(
        m("antigravity", "gemini-4-neon", "Gemini 4 Neon"), m("antigravity", "gemini-4-argon", "Gemini 4 Argon"), m("antigravity", "gemini-4-hydrogen", "Gemini 4 Hydrogen"),
        m("antigravity", "orchestrator-neon", "Orchestrator (Neon)"), m("antigravity", "orchestrator-argon", "Orchestrator (Argon)"),
        m("antigravity", "gemini-3.8-flash", "Gemini 3.8 Flash"), m("antigravity", "claude-opus-5-5", "Claude Opus 5.5"), m("antigravity", "claude-sonnet-5-5", "Claude Sonnet 5.5"),
    )
    private val models = cursor + codex + claude + antigravity
    private val providers = listOf(
        ProviderInstance("cursor", "cursor", "Cursor"), ProviderInstance("codex", "codex", "OpenAI"),
        ProviderInstance("claudeAgent", "claudeAgent", "Anthropic"), ProviderInstance("antigravity", "antigravity"),
    )
    private val none = ModelPreferences()

    private fun catalog(prefs: ModelPreferences = none, current: ModelOption? = null) = buildModelCatalog(models, providers, prefs, current)
    private fun ModelCatalog.section(id: String) = sections.first { it.instanceId == id }

    @Test fun sectionsFollowDriverRankThenCatalogOrder() {
        assertEquals(listOf("claudeAgent", "codex", "antigravity", "cursor"), catalog().sections.map { it.instanceId })
        val grok = buildModelCatalog(models + m("g", "grok-4", "Grok 4"), providers + ProviderInstance("g", "grok"), none, null)
        assertEquals(listOf("claudeAgent", "codex", "antigravity", "g", "cursor"), grok.sections.map { it.instanceId })
    }

    @Test fun sectionTitleUsesDisplayNameOrInstanceId() {
        val c = catalog()
        assertEquals("OpenAI", c.section("codex").title)
        assertEquals("antigravity", c.section("antigravity").title)
        assertEquals("codex", c.section("codex").driver)
    }

    @Test fun codexFamiliesGroupVersionsAndFollowTierOrder() {
        val families = catalog().section("codex").families
        assertEquals(listOf("GPT Sol", "GPT Astra", "GPT Terra", "GPT Luna"), families.map { it.name })
        assertEquals(listOf("gpt sol", "gpt astra", "gpt terra", "gpt luna"), families.map { it.key })
        assertEquals(listOf("gpt-6.2-sol", "gpt-6.1-sol"), families[0].versions.map { it.model })
        assertEquals("gpt-6.2-sol", families[0].latest.model)
        assertEquals(listOf("gpt-6.2-astra", "gpt-6-astra"), families[1].versions.map { it.model })
        assertEquals(1, families[2].versions.size)
        assertEquals(1, families[3].versions.size)
    }

    @Test fun claudeFamiliesFollowTierAndKeepLegacyVersionsLast() {
        val families = catalog().section("claudeAgent").families
        assertEquals(listOf("Claude Opus", "Claude Sonnet", "Claude Fable", "Claude Haiku"), families.map { it.name })
        assertEquals(listOf("claude-opus-5-5", "claude-opus-4-6"), families[0].versions.map { it.model })
        assertEquals("claude-opus-5-5", families[0].latest.model)
    }

    @Test fun legacyVersionNeverBeatsNonLegacyEvenWhenNumericallyHigher() {
        val list = listOf(m("p", "x-9", "Thing 9", legacy = true), m("p", "x-2", "Thing 2"))
        val f = buildModelCatalog(list, emptyList(), none, null).sections.single().families.single()
        assertEquals(listOf("x-2", "x-9"), f.versions.map { it.model })
    }

    @Test fun antigravityKeepsCatalogOrderWithoutTiering() {
        val families = catalog().section("antigravity").families
        assertEquals(
            listOf("Gemini Neon", "Gemini Argon", "Gemini Hydrogen", "Orchestrator (Neon)", "Orchestrator (Argon)", "Gemini Flash", "Claude Opus", "Claude Sonnet"),
            families.map { it.name },
        )
        assertTrue(families.all { it.versions.size == 1 })
    }

    @Test fun versionsSortNumericallyNotLexically() {
        val list = listOf(m("p", "a-6.9", "Foo 6.9"), m("p", "a-6.10", "Foo 6.10"), m("p", "a-6", "Foo 6"))
        val f = buildModelCatalog(list, emptyList(), none, null).sections.single().families.single()
        assertEquals(listOf("a-6.10", "a-6.9", "a-6"), f.versions.map { it.model })
    }

    @Test fun unversionedModelsAreTheirOwnFamilyKeyedBySlug() {
        val list = listOf(m("p", "o-neon", "Orchestrator (Neon)"), m("p", "o-neon2", "Orchestrator (Neon)"))
        val families = buildModelCatalog(list, emptyList(), none, null).sections.single().families
        assertEquals(listOf("o-neon", "o-neon2"), families.map { it.key })
        assertEquals("Orchestrator (Neon)", families[0].name)
    }

    @Test fun familiesDoNotMergeAcrossInstances() {
        val c = catalog()
        assertEquals(1, c.section("antigravity").families.count { it.name == "Claude Opus" })
        assertEquals(1, c.section("claudeAgent").families.count { it.name == "Claude Opus" })
        assertEquals(1, c.section("antigravity").families.first { it.name == "Claude Opus" }.versions.size)
    }

    @Test fun hiddenModelsAreDroppedExceptTheCurrentOne() {
        val prefs = ModelPreferences(hiddenModels = mapOf("codex" to setOf("gpt-6.1-sol", "gpt-6-luna"), "antigravity" to setOf("gpt-6-luna")))
        val c = catalog(prefs)
        assertEquals(listOf("gpt-6.2-sol"), c.section("codex").families[0].versions.map { it.model })
        assertFalse(c.section("codex").families.any { it.name == "GPT Luna" })
        val kept = catalog(prefs, current = codex.first { it.model == "gpt-6-luna" })
        assertTrue(kept.section("codex").families.any { it.name == "GPT Luna" })
        assertEquals(1, familyOf(kept, codex.first { it.model == "gpt-6-luna" })?.versions?.size)
    }

    @Test fun hiddenSlugAppliesOnlyToItsOwnInstance() {
        val prefs = ModelPreferences(hiddenModels = mapOf("claudeAgent" to setOf("claude-opus-5-5")))
        val c = catalog(prefs)
        assertEquals(listOf("claude-opus-4-6"), c.section("claudeAgent").families.first { it.name == "Claude Opus" }.versions.map { it.model })
        assertEquals("claude-opus-5-5", c.section("antigravity").families.first { it.name == "Claude Opus" }.latest.model)
    }

    @Test fun modelOrderWinsOverTiersAndUnlistedGoLast() {
        val prefs = ModelPreferences(modelOrder = mapOf("codex" to listOf("gpt-6-luna", "gpt-6.1-terra")))
        val names = catalog(prefs).section("codex").families.map { it.name }
        // Listed by index, then unlisted by catalog appearance (Terra's first sighting precedes Astra and Sol; both listed ones lead).
        assertEquals(listOf("GPT Luna", "GPT Terra", "GPT Astra", "GPT Sol"), names)
    }

    @Test fun modelOrderUsesSmallestIndexOfAnyMemberAndIgnoresOtherInstances() {
        val prefs = ModelPreferences(modelOrder = mapOf("codex" to listOf("gpt-6.1-sol", "gpt-6-luna"), "claudeAgent" to emptyList()))
        val c = catalog(prefs)
        assertEquals(listOf("GPT Sol", "GPT Luna"), c.section("codex").families.take(2).map { it.name })
        // Empty order list falls back to tiers.
        assertEquals("Claude Opus", c.section("claudeAgent").families.first().name)
        // No entry for antigravity: catalog order.
        assertEquals("Gemini Neon", c.section("antigravity").families.first().name)
    }

    @Test fun allLegacyFamiliesSortLast() {
        val list = listOf(m("p", "old-1", "Old 1", legacy = true), m("p", "new-1", "New 1"), m("p", "mid-1", "Mid 1"))
        assertEquals(listOf("New", "Mid", "Old"), buildModelCatalog(list, emptyList(), none, null).sections.single().families.map { it.name })
    }

    @Test fun legacyFamiliesSortLastWithinModelOrderTies() {
        val list = listOf(m("p", "old-1", "Old 1", legacy = true), m("p", "new-1", "New 1"))
        val prefs = ModelPreferences(modelOrder = mapOf("p" to listOf("zzz")))
        assertEquals(listOf("New", "Old"), buildModelCatalog(list, emptyList(), prefs, null).sections.single().families.map { it.name })
    }

    @Test fun favoritesResolveInPrefsOrderAndSkipUnknown() {
        val prefs = ModelPreferences(
            favorites = listOf(ModelKey("codex", "gpt-6.1-sol"), ModelKey("nope", "x"), ModelKey("claudeAgent", "claude-haiku-5-5"), ModelKey("codex", "gone")),
            synced = true,
        )
        assertEquals(listOf("gpt-6.1-sol", "claude-haiku-5-5"), catalog(prefs).favorites.map { it.model })
    }

    @Test fun hiddenFavoritesAreStillShown() {
        val prefs = ModelPreferences(favorites = listOf(ModelKey("codex", "gpt-6-luna")), hiddenModels = mapOf("codex" to setOf("gpt-6-luna")))
        val c = catalog(prefs)
        assertEquals(listOf("gpt-6-luna"), c.favorites.map { it.model })
        assertFalse(c.section("codex").families.any { it.name == "GPT Luna" })
    }

    @Test fun searchMatchesAllTokensCaseInsensitively() {
        assertEquals(listOf("gpt-6.2-sol", "gpt-6.1-sol"), searchModels(models, providers, none, "GPT SOL").map { it.model })
        assertEquals(listOf("gpt-6.2-sol", "gpt-6.1-sol"), searchModels(models, providers, none, "  sol   gpt ").map { it.model })
        assertTrue(searchModels(models, providers, none, "sol haiku").isEmpty())
    }

    @Test fun searchMatchesSlugShortNameAndProviderName() {
        assertEquals(listOf("gpt-6.2-astra"), searchModels(models, providers, none, "6.2-astra").map { it.model })
        val short = listOf(m("p", "slug-a", "Alpha 1", shortName = "zippy"))
        assertEquals(listOf("slug-a"), searchModels(short, emptyList(), none, "zip").map { it.model })
        // Provider display name: every OpenAI model.
        assertEquals(codex.map { it.model }.toSet(), searchModels(models, providers, none, "openai").map { it.model }.toSet())
    }

    @Test fun searchOrdersFavoritesThenSectionsThenFamilies() {
        val prefs = ModelPreferences(favorites = listOf(ModelKey("antigravity", "claude-opus-5-5")))
        val hits = searchModels(models, providers, prefs, "opus").map { it.instanceId to it.model }
        assertEquals(
            listOf("antigravity" to "claude-opus-5-5", "claudeAgent" to "claude-opus-5-5", "claudeAgent" to "claude-opus-4-6"),
            hits,
        )
    }

    @Test fun searchFindsHiddenFavoriteButNotOtherHiddenModels() {
        val prefs = ModelPreferences(
            favorites = listOf(ModelKey("codex", "gpt-6-luna")),
            hiddenModels = mapOf("codex" to setOf("gpt-6-luna", "gpt-6-astra")),
        )
        assertEquals(listOf("gpt-6-luna"), searchModels(models, providers, prefs, "luna").map { it.model })
        assertEquals(listOf("gpt-6.2-astra"), searchModels(models, providers, prefs, "astra").map { it.model })
    }

    @Test fun searchEmptyQueryIsEmpty() {
        assertTrue(searchModels(models, providers, none, "").isEmpty())
        assertTrue(searchModels(models, providers, none, "   ").isEmpty())
    }

    @Test fun versionLabelParsesLabelThenSlug() {
        assertEquals("6.2", versionLabel(m("codex", "gpt-6.2-sol", "GPT-6.2-Sol")))
        assertEquals("6", versionLabel(m("codex", "gpt-6-astra", "GPT-6-Astra")))
        assertEquals("5.5", versionLabel(m("c", "claude-opus-5-5", "Claude Opus 5.5")))
        assertEquals("5.5", versionLabel(m("c", "claude-opus-5-5")))
        assertEquals("5.5", versionLabel(m("c", "claude-opus-5-5", "")))
        assertEquals("4", versionLabel(m("a", "gemini-4-neon", "Gemini 4 Neon")))
        assertNull(versionLabel(m("a", "orchestrator-neon", "Orchestrator (Neon)")))
        assertNull(versionLabel(m("a", "auto", "Auto")))
    }

    @Test fun familyOfFindsAnyVersionAndHandlesNull() {
        val c = catalog()
        assertEquals("gpt sol", familyOf(c, codex.first { it.model == "gpt-6.1-sol" })?.key)
        assertEquals("claudeAgent", c.sections.first { s -> s.families.contains(familyOf(c, claude[0])) }.instanceId)
        assertNull(familyOf(c, null))
        assertNull(familyOf(c, m("codex", "unknown")))
    }
}

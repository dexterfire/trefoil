package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ClientState
import com.tajchert.trefoil.core.model.ProviderInstance
import com.tajchert.trefoil.core.model.ProviderSkill
import com.tajchert.trefoil.core.model.ProviderSlashCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `/` and `$` popover as a specification: what it offers, what it ignores, and what a pick writes into the draft. */
class ComposerSuggestionsTest {

    private val state = ClientState(
        providers = listOf(
            ProviderInstance(
                instanceId = "codex", driver = "codex",
                slashCommands = listOf(
                    ProviderSlashCommand("review", "Review the working tree", "<path>"),
                    ProviderSlashCommand("code-review", "Review a pull request", null),
                    ProviderSlashCommand("compact", null, null),
                ),
                skills = listOf(
                    ProviderSkill("frontend-design", "Frontend design", "Distinctive UI"),
                    ProviderSkill("hidden", "Hidden", null, invocable = false),
                ),
            ),
            // A second provider offering the same command: the first one wins, so the list never repeats a name.
            ProviderInstance(
                instanceId = "claude", driver = "claude",
                slashCommands = listOf(ProviderSlashCommand("review", "Same name")),
                skills = listOf(ProviderSkill("godot", "Godot", null)),
            ),
        ),
    )

    private fun suggestions(text: String, caret: Int = text.length) =
        composerSuggestions(state, composerToken(text, caret))

    @Test fun aSlashOffersEveryCommandOnce() {
        assertEquals(listOf("/code-review", "/compact", "/review"), suggestions("/").map { it.label })
    }

    @Test fun theTypedPrefixNarrowsAndSortsFirst() {
        assertEquals(listOf("/review", "/code-review"), suggestions("/re").map { it.label })
    }

    @Test fun aDollarOffersOnlySkillsTheUserMayInvoke() {
        assertEquals(listOf("\$frontend-design", "\$godot"), suggestions("$").map { it.label })
        assertTrue(suggestions("$").none { it.name == "hidden" })
    }

    @Test fun aPathIsNotACommand() {
        assertNull(composerToken("/usr/bin", 8))
        assertTrue(suggestions("/usr/bin").isEmpty())
    }

    @Test fun aPriceIsNotASkill() {
        assertTrue(suggestions("it costs \$5").isEmpty())
        assertTrue(suggestions("\$5").isEmpty())
    }

    @Test fun onlyTheWordAtTheCaretCounts() {
        val text = "please /re"
        assertEquals("/review", suggestions(text, text.length).first().label)
        // The caret is back at the start: the earlier word no longer holds a token.
        assertNull(composerToken(text, 6))
    }

    @Test fun anOrdinaryWordOffersNothing() {
        assertNull(composerToken("hello", 5))
        assertTrue(suggestions("hello").isEmpty())
    }

    @Test fun aPickReplacesTheTokenAndLeavesTheCaretAfterIt() {
        val text = "please /re and then"
        val token = composerToken(text, 10)!!
        val (next, caret) = applyComposerSuggestion(text, token, "review")
        assertEquals("please /review  and then", next)
        assertEquals(15, caret)
        // A skill is written the same way, with its own sigil kept.
        val (withSkill, skillCaret) = applyComposerSuggestion("\$front", composerToken("\$front", 6)!!, "frontend-design")
        assertEquals("\$frontend-design ", withSkill)
        assertEquals(withSkill.length, skillCaret)
    }
}

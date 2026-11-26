package com.tajchert.trefoil.ui

import com.tajchert.trefoil.core.model.ClientState

/** What the composer offers after a `/` (a server slash command) or a `$` (a skill) at the caret. */
internal enum class ComposerSuggestionKind { Command, Skill }

internal data class ComposerSuggestion(
    val kind: ComposerSuggestionKind,
    /** The name inserted after the sigil, without it. */
    val name: String,
    /** What the row shows: the name with its sigil. */
    val label: String,
    val detail: String?,
)

/**
 * The sigil token the caret sits in. [start] is the sigil's index in the text and [end] the caret, so replacing
 * `[start, end)` with a picked name is the whole edit.
 */
internal data class ComposerToken(val start: Int, val end: Int, val sigil: Char, val query: String)

/** Letters, digits and the characters a command or skill name may hold; a slash here means a path, not a command. */
private val NAME_CHARS = Regex("^[\\w.:-]*$")

/**
 * The token being typed at [caret], or null when there is none. Only the word touching the caret counts, so a `/` earlier
 * in the message never reopens the list, and a token with a slash after its sigil (`/usr/bin`) is a path and is ignored.
 */
internal fun composerToken(text: String, caret: Int): ComposerToken? {
    val at = caret.coerceIn(0, text.length)
    val start = text.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), at - 1) + 1
    val token = text.substring(start, at)
    if (token.isEmpty()) return null
    val sigil = token[0]
    if (sigil != '/' && sigil != '$') return null
    val query = token.substring(1)
    if (!NAME_CHARS.matches(query)) return null
    return ComposerToken(start, at, sigil, query)
}

/**
 * The rows for [token], taken from the providers the environment reports. Commands come from the server's
 * `slashCommands`, skills from its `skills` and only those the user may invoke; both are deduplicated by name, because two
 * providers may offer the same one and the first is the one the server will resolve. Names starting with the typed prefix
 * sort first, so `/re` puts `review` above `code-review`.
 */
internal fun composerSuggestions(state: ClientState, token: ComposerToken?, limit: Int = 8): List<ComposerSuggestion> {
    if (token == null) return emptyList()
    val query = token.query.lowercase()
    val all = if (token.sigil == '/') {
        state.providers.flatMap { provider ->
            provider.slashCommands.map { command ->
                val suggestion = ComposerSuggestion(ComposerSuggestionKind.Command, command.name, "/" + command.name, command.description ?: command.inputHint)
                suggestion to suggestion.name.lowercase()
            }
        }
    } else {
        // T3's composerSkillSearch: a skill matches its name, displayName, shortDescription and description, case-insensitively;
        // the projection folds displayName and shortDescription into `label` and both short fields into `description`.
        state.providers.flatMap { provider ->
            provider.skills.filter { it.invocable }.map { skill ->
                val suggestion = ComposerSuggestion(ComposerSuggestionKind.Skill, skill.name, "$" + skill.name, skill.description)
                suggestion to listOfNotNull(skill.name, skill.label, skill.description).joinToString("\n").lowercase()
            }
        }
    }
    return all.distinctBy { it.first.name }
        .filter { query.isEmpty() || it.second.contains(query) }
        .sortedWith(compareBy({ if (it.first.name.lowercase().startsWith(query)) 0 else 1 }, { it.first.name.lowercase() }))
        .take(limit)
        .map { it.first }
}

/** [text] with the token replaced by [name] and a trailing space, and the caret after it, ready for the next word. */
internal fun applyComposerSuggestion(text: String, token: ComposerToken, name: String): Pair<String, Int> {
    val insert = token.sigil + name + " "
    return text.substring(0, token.start) + insert + text.substring(token.end) to token.start + insert.length
}

/* Localization guard: the UI layers declare their user-facing text in string resources
 * (Russian by default, see docs/LOCALIZATION.md). This test scans the Kotlin sources of the
 * UI layers (ui, widget, notifications) for quoted string literals that look like user-facing
 * text and fails on any literal that is not in the allowlist
 * (app/src/test/resources/ui-string-literal-allowlist.txt). The allowlist only shrinks.
 */
package com.tajchert.trefoil

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiStringLiteralGuardTest {

    private val watchedDirs = listOf(
        "com/tajchert/trefoil/ui",
        "com/tajchert/trefoil/widget",
        "com/tajchert/trefoil/notifications",
    )

    @Test fun uiLayersDeclareNoNewUserFacingLiterals() {
        val root = sourceRoot() ?: return
        val allowlist = readAllowlist(root)
        val violations = mutableListOf<String>()
        watchedDirs.forEach { dir ->
            val base = File(root, dir)
            if (!base.isDirectory) return@forEach
            base.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                literals(stripComments(file.readText())).forEach { (literal, line) ->
                    if (!isUserFacing(literal)) return@forEach
                    if ("$relative::$literal" !in allowlist) {
                        violations.add("$relative:$line: \"$literal\"")
                    }
                }
            }
        }
        assertTrue(
            "New user-facing string literals in the UI layers; declare them in values/strings.xml " +
                "(Russian default) and values-en/strings.xml instead, or extend the allowlist only for " +
                "non-translatable text:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /** src/main/kotlin of the app module: Gradle runs unit tests with the module dir as the working directory. */
    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "src/main/kotlin").takeIf { it.isDirectory }?.let { return File(dir, "src/main/kotlin") }
            dir = dir.parentFile
        }
        return null
    }

    private fun readAllowlist(root: File): Set<String> {
        val file = root.toPath().toAbsolutePath().normalize().parent.parent.parent
            .resolve("src/test/resources/ui-string-literal-allowlist.txt").toFile()
        return if (file.isFile) file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.replace("\\n", "\n") }.toSet()
        else emptySet()
    }

    /** Erases comments (as spaces, preserving line count) and char literals so their quotes do not desynchronise the string scanner. */
    private fun stripComments(text: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\' && i + 1 < text.length) { out.append(c).append(text[i + 1]); i += 2; continue }
                if (c == '"') { inString = false; out.append('"'); i++; continue }
                out.append(c); i++; continue
            }
            when {
                c == '"' -> { inString = true; out.append('"'); i++ }
                c == '\'' -> { // char literal
                    out.append(' '); i++
                    if (i < text.length && text[i] == '\\') { out.append(' '); i += 2 } else if (i < text.length) { out.append(' '); i++ }
                    while (i < text.length && text[i] != '\'') { out.append(if (text[i] == '\n') '\n' else ' '); i++ }
                    out.append(' ')
                    if (i < text.length) i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { while (i < text.length && text[i] != '\n') { out.append(if (text[i] == '\n') '\n' else ' '); i++ } }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> { i += 2; while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) { out.append(if (text[i] == '\n') '\n' else ' '); i++ }; i += 2 }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    private fun literals(text: String): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var i = 0
        var line = 1
        var inString = false
        val cur = StringBuilder()
        val startLine = mutableListOf<Int>()
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') line++
            if (inString) {
                if (c == '\\' && i + 1 < text.length) { cur.append(c).append(text[i + 1]); i += 2; continue }
                if (c == '"') { out.add(cur.toString() to startLine.removeAt(startLine.size - 1)); cur.clear(); inString = false; i++; continue }
                cur.append(c); i++; continue
            }
            if (c == '"') { inString = true; startLine.add(line); i++; continue }
            i++
        }
        return out
    }

    /** A literal counts as user-facing when it carries Cyrillic, a multi-word phrase or a trailing ellipsis. */
    private fun isUserFacing(literal: String): Boolean {
        if (literal.none(Char::isLetter)) return false
        val cyrillic = literal.any { it.code in 0x400..0x4FF }
        val phrase = Regex("[A-Za-zА-яЁё] [A-Za-zА-яЁё]").containsMatchIn(literal)
        val ellipsis = Regex("[A-Za-zА-яЁё]…$").containsMatchIn(literal)
        return cyrillic || phrase || ellipsis
    }
}

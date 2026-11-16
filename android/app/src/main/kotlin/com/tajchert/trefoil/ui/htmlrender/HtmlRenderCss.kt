package com.tajchert.trefoil.ui.htmlrender

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import okhttp3.HttpUrl

/**
 * A redirected stylesheet is served to the WebView under the URL it asked for, so its relative references
 * would resolve against the wrong base. This rewrites relative `url(...)` and `@import "..."` targets to
 * absolute URLs resolved against [base] (the final URL). Comments and other strings are copied verbatim;
 * `data:`, absolute (any scheme), `#fragment`, empty, and escaped targets are left alone. The rewritten URLs
 * still pass the page's request policy when the WebView loads them.
 */
internal fun htmlRenderRebaseCss(css: String, base: HttpUrl): String {
    val out = StringBuilder(css.length + 64)
    var i = 0
    val n = css.length
    while (i < n) {
        val c = css[i]
        when {
            c == '/' && i + 1 < n && css[i + 1] == '*' -> {
                val end = css.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                out.append(css, i, end)
                i = end
            }
            c == '"' || c == '\'' -> {
                val end = cssStringEnd(css, i)
                out.append(css, i, end)
                i = end
            }
            c == '@' && css.regionMatches(i, "@import", 0, 7, ignoreCase = true) && !isCssNameChar(css.getOrNull(i + 7)) -> {
                out.append(css, i, i + 7)
                i += 7
                val afterSpace = skipCssSpaceAndComments(css, i)
                if (afterSpace < n && (css[afterSpace] == '"' || css[afterSpace] == '\'')) {
                    out.append(css, i, afterSpace)
                    val end = cssStringEnd(css, afterSpace)
                    out.append(rebasedString(css, afterSpace, end, base))
                    i = end
                }
            }
            (c == 'u' || c == 'U') && css.regionMatches(i, "url(", 0, 4, ignoreCase = true) && !isCssNameChar(css.getOrNull(i - 1)) -> {
                val end = rebaseUrlFunction(css, i, base, out)
                i = end
            }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * Byte-level [htmlRenderRebaseCss]: decodes with the response charset, else a BOM, else a leading `@charset`
 * rule, else UTF-8, and re-encodes with the same charset so the `@charset` rule stays true. Returns [bytes]
 * unchanged when they do not round-trip cleanly (malformed or unmappable input).
 */
internal fun htmlRenderRebaseCssBytes(bytes: ByteArray, headerCharset: Charset?, base: HttpUrl): ByteArray {
    val charset = headerCharset ?: cssBomCharset(bytes) ?: cssCharsetRule(bytes) ?: Charsets.UTF_8
    return try {
        val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val rebased = htmlRenderRebaseCss(text, base)
        if (rebased == text) return bytes
        val encoded = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(rebased))
        ByteArray(encoded.remaining()).also { encoded.get(it) }
    } catch (_: CharacterCodingException) {
        bytes
    } catch (_: UnsupportedOperationException) {
        bytes
    }
}

private fun cssBomCharset(bytes: ByteArray): Charset? = when {
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> Charsets.UTF_8
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
    else -> null
}

/** CSS Syntax: the exact bytes `@charset "<name>";` at the very start. */
private fun cssCharsetRule(bytes: ByteArray): Charset? {
    val prefix = "@charset \""
    if (bytes.size < prefix.length + 2) return null
    val head = String(bytes, 0, minOf(bytes.size, 128), Charsets.ISO_8859_1)
    if (!head.startsWith(prefix)) return null
    val close = head.indexOf("\";", prefix.length)
    if (close < 0) return null
    val name = head.substring(prefix.length, close)
    // UTF-16 labels in an ASCII @charset rule mean UTF-8 per the spec.
    if (name.startsWith("utf-16", ignoreCase = true)) return Charsets.UTF_8
    return runCatching { Charset.forName(name) }.getOrNull()
}

private fun isCssNameChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '-' || c == '_' || c == '\\' || c.code >= 0x80)

private fun isCssSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

private fun skipCssSpaceAndComments(css: String, from: Int): Int {
    var i = from
    while (i < css.length) {
        when {
            isCssSpace(css[i]) -> i++
            css.startsWith("/*", i) -> i = css.indexOf("*/", i + 2).let { if (it < 0) css.length else it + 2 }
            else -> return i
        }
    }
    return i
}

/** End (exclusive) of the string starting at [start], honouring escapes; unterminated strings end at a newline. */
private fun cssStringEnd(css: String, start: Int): Int {
    val quote = css[start]
    var i = start + 1
    while (i < css.length) {
        val c = css[i]
        when {
            c == '\\' -> i += 2
            c == quote -> return i + 1
            c == '\n' -> return i
            else -> i++
        }
    }
    return css.length
}

/** The quoted string [start, end) with its URL rebased, or copied as is. */
private fun rebasedString(css: String, start: Int, end: Int, base: HttpUrl): String {
    val original = css.substring(start, end)
    val quote = css[start]
    if (end - start < 2 || css[end - 1] != quote) return original
    val value = css.substring(start + 1, end - 1)
    if ('\\' in value) return original
    val absolute = rebasedTarget(value, base) ?: return original
    return "$quote$absolute$quote"
}

/** Copies one `url(...)` starting at [start] into [out], rebased when possible; returns the index after it. */
private fun rebaseUrlFunction(css: String, start: Int, base: HttpUrl, out: StringBuilder): Int {
    var i = start + 4
    while (i < css.length && isCssSpace(css[i])) i++
    if (i < css.length && (css[i] == '"' || css[i] == '\'')) {
        // Quoted: url( <string> ) — the string is the target.
        val stringEnd = cssStringEnd(css, i)
        out.append(css, start, i)
        out.append(rebasedString(css, i, stringEnd, base))
        return stringEnd
    }
    // Unquoted: everything up to `)`, no quotes, parens, or escapes (otherwise copy verbatim).
    var j = i
    while (j < css.length && css[j] != ')' && css[j] != '"' && css[j] != '\'' && css[j] != '(' && css[j] != '\\') j++
    if (j >= css.length || css[j] != ')') {
        out.append(css, start, i)
        return i
    }
    val raw = css.substring(i, j).trimEnd { isCssSpace(it) }
    val absolute = raw.takeIf { value -> value.none { isCssSpace(it) } }?.let { rebasedTarget(it, base) }
    if (absolute == null) out.append(css, start, j + 1) else out.append(css, start, i).append(absolute).append(')')
    return j + 1
}

private val CSS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/** The absolute form of a relative [value], or null when it must stay as written. */
private fun rebasedTarget(value: String, base: HttpUrl): String? {
    val target = value.trim()
    if (target.isEmpty() || target.startsWith('#') || CSS_SCHEME.containsMatchIn(target)) return null
    val absolute = base.resolve(target)?.toString() ?: return null
    // The result goes back into CSS unescaped: refuse anything that could end the token.
    if (absolute.any { it == '"' || it == '\'' || it == '(' || it == ')' || it == '\\' || it.code <= 0x20 || it.code == 0x7F }) return null
    return absolute
}

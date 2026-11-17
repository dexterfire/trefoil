package com.tajchert.trefoil.ui.htmlrender

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.ceil
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
    CssRebaser(css, base, BuilderSink(out)).run()
    return out.toString()
}

/** What a counting pass over a stylesheet found: the rebased length in chars and whether anything was rewritten. */
internal class HtmlRenderCssRebaseCount(val length: Long, val rewritten: Boolean)

/**
 * Counting pass of [htmlRenderRebaseCss]: allocates nothing proportional to the output and gives up (null) as
 * soon as the rebased text would pass [limitChars] chars.
 */
internal fun htmlRenderCountRebasedCss(css: String, base: HttpUrl, limitChars: Long = Long.MAX_VALUE): HtmlRenderCssRebaseCount? {
    val sink = CountingSink(limitChars)
    val rebaser = CssRebaser(css, base, sink)
    return try {
        rebaser.run()
        HtmlRenderCssRebaseCount(sink.count, rebaser.rewritten)
    } catch (_: CssOutputLimitExceeded) {
        null
    }
}

/**
 * Build pass: the rebased text in a char array of exactly [length] chars (the counted length), or null when
 * the pass does not write exactly that many (it always does for the input that was counted).
 */
internal fun htmlRenderBuildRebasedCss(css: String, base: HttpUrl, length: Int): CharArray? {
    val chars = CharArray(length)
    val sink = ArraySink(chars)
    return try {
        CssRebaser(css, base, sink).run()
        if (sink.written == length) chars else null
    } catch (_: CssOutputLimitExceeded) {
        null
    }
}

/**
 * Byte-level [htmlRenderRebaseCss]: decodes with the response charset, else a BOM, else a leading `@charset`
 * rule, else UTF-8, and re-encodes with the same charset so the `@charset` rule stays true. Returns [bytes]
 * unchanged when they do not round-trip cleanly (malformed or unmappable input) or nothing needs rewriting.
 *
 * Every allocation proportional to the input or the output is announced to [reserve] (extra bytes about to be
 * held, false when over budget) before it is made, and the rebased output is counted first and refused before
 * it is built when it would pass [maxBytes]. Returns null when over [maxBytes] or over budget.
 */
internal fun htmlRenderRebaseCssBytes(
    bytes: ByteArray,
    headerCharset: Charset?,
    base: HttpUrl,
    maxBytes: Long,
    reserve: (Long) -> Boolean,
): ByteArray? {
    val charset = headerCharset ?: cssBomCharset(bytes) ?: cssCharsetRule(bytes) ?: Charsets.UTF_8
    try {
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        // The decode buffer is presized for the worst case so the decoder never grows (and copies) it.
        val capacity = ceil(bytes.size * decoder.maxCharsPerByte().toDouble()).toLong().coerceAtMost(Int.MAX_VALUE.toLong())
        if (!reserve(capacity * CHAR_BYTES)) return null
        val decoded = CharBuffer.allocate(capacity.toInt())
        var result = decoder.decode(ByteBuffer.wrap(bytes), decoded, true)
        if (result.isUnderflow) result = decoder.flush(decoded)
        if (!result.isUnderflow) return bytes
        decoded.flip()
        if (!reserve(decoded.remaining().toLong() * CHAR_BYTES)) return null
        val text = decoded.toString()
        // Every charset encodes a char to at least one byte, so more than maxBytes chars cannot fit.
        val count = htmlRenderCountRebasedCss(text, base, maxBytes) ?: return null
        if (!count.rewritten) return bytes
        if (!reserve(count.length * CHAR_BYTES)) return null
        // An array-backed source: the encoder reads it in place instead of copying it to an array of its own.
        val rebased = htmlRenderBuildRebasedCss(text, base, count.length.toInt()) ?: return null
        val encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val encodedCapacity = minOf(maxBytes, ceil(rebased.size * encoder.maxBytesPerChar().toDouble()).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
        if (!reserve(encodedCapacity)) return null
        val encoded = ByteBuffer.allocate(encodedCapacity.toInt())
        val chars = CharBuffer.wrap(rebased, 0, rebased.size)
        var encodeResult = encoder.encode(chars, encoded, true)
        if (encodeResult.isUnderflow) encodeResult = encoder.flush(encoded)
        if (encodeResult.isOverflow) return null
        if (!encodeResult.isUnderflow) return bytes
        val size = encoded.position()
        if (size == encoded.capacity()) return encoded.array()
        if (!reserve(size.toLong())) return null
        return encoded.array().copyOf(size)
    } catch (_: CharacterCodingException) {
        return bytes
    } catch (_: UnsupportedOperationException) {
        return bytes
    }
}

private const val CHAR_BYTES = 2L

/** A rebased URL or string target longer than this is copied as written: nothing near it is a real reference. */
private const val MAX_REBASED_TARGET_CHARS = 8_192

private object CssOutputLimitExceeded : RuntimeException() {
    private fun readResolve(): Any = CssOutputLimitExceeded
    override fun fillInStackTrace(): Throwable = this
}

/** Where the scanner writes: a builder, or a counter for the sizing pass. */
private interface CssSink {
    fun append(c: Char)
    fun append(text: CharSequence, start: Int, end: Int)
    fun append(text: CharSequence) = append(text, 0, text.length)
}

private class BuilderSink(private val out: StringBuilder) : CssSink {
    override fun append(c: Char) { out.append(c) }
    override fun append(text: CharSequence, start: Int, end: Int) { out.append(text, start, end) }
}

/** Writes into a fixed array sized by the counting pass; writing past its end fails like an over-limit count. */
private class ArraySink(private val chars: CharArray) : CssSink {
    var written = 0
        private set

    override fun append(c: Char) {
        if (written >= chars.size) throw CssOutputLimitExceeded
        chars[written++] = c
    }

    override fun append(text: CharSequence, start: Int, end: Int) {
        if (end - start > chars.size - written) throw CssOutputLimitExceeded
        for (k in start until end) chars[written++] = text[k]
    }
}

private class CountingSink(private val limit: Long) : CssSink {
    var count = 0L
        private set

    private fun add(chars: Long) {
        count += chars
        if (count > limit) throw CssOutputLimitExceeded
    }

    override fun append(c: Char) = add(1)
    override fun append(text: CharSequence, start: Int, end: Int) = add((end - start).toLong())
}

/** The scanner behind [htmlRenderRebaseCss]; copies everything to [out] and rewrites relative references. */
private class CssRebaser(private val css: String, private val base: HttpUrl, private val out: CssSink) {
    var rewritten = false
        private set

    fun run() {
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
                        appendRebasedString(afterSpace, end)
                        i = end
                    }
                }
                (c == 'u' || c == 'U') && css.regionMatches(i, "url(", 0, 4, ignoreCase = true) && !isCssNameChar(css.getOrNull(i - 1)) ->
                    i = appendUrlFunction(i)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /** The quoted string [start, end) with its URL rebased, or copied as is. */
    private fun appendRebasedString(start: Int, end: Int) {
        val quote = css[start]
        if (end - start < 2 || css[end - 1] != quote || hasBackslash(start + 1, end - 1)) return out.append(css, start, end)
        val absolute = rebasedTarget(start + 1, end - 1) ?: return out.append(css, start, end)
        out.append(quote)
        out.append(absolute)
        out.append(quote)
        rewritten = true
    }

    /** Copies one `url(...)` starting at [start], rebased when possible; returns the index after it. */
    private fun appendUrlFunction(start: Int): Int {
        var i = start + 4
        while (i < css.length && isCssSpace(css[i])) i++
        if (i < css.length && (css[i] == '"' || css[i] == '\'')) {
            // Quoted: url( <string> ) — the string is the target.
            val stringEnd = cssStringEnd(css, i)
            out.append(css, start, i)
            appendRebasedString(i, stringEnd)
            return stringEnd
        }
        // Unquoted: everything up to `)`, no quotes, parens, or escapes (otherwise copy verbatim).
        var j = i
        while (j < css.length && css[j] != ')' && css[j] != '"' && css[j] != '\'' && css[j] != '(' && css[j] != '\\') j++
        if (j >= css.length || css[j] != ')') {
            out.append(css, start, i)
            return i
        }
        var rawEnd = j
        while (rawEnd > i && isCssSpace(css[rawEnd - 1])) rawEnd--
        val absolute = if ((i until rawEnd).none { isCssSpace(css[it]) }) rebasedTarget(i, rawEnd) else null
        if (absolute == null) {
            out.append(css, start, j + 1)
        } else {
            out.append(css, start, i)
            out.append(absolute)
            out.append(')')
            rewritten = true
        }
        return j + 1
    }

    private fun hasBackslash(start: Int, end: Int): Boolean = (start until end).any { css[it] == '\\' }

    /** The absolute form of the relative target in [start, end), or null when it must stay as written. */
    private fun rebasedTarget(start: Int, end: Int): String? {
        var from = start
        var to = end
        while (from < to && css[from].isWhitespace()) from++
        while (to > from && css[to - 1].isWhitespace()) to--
        if (to == from || to - from > MAX_REBASED_TARGET_CHARS || css[from] == '#' || hasScheme(from, to)) return null
        val absolute = base.resolve(css.substring(from, to))?.toString() ?: return null
        // The result goes back into CSS unescaped: refuse anything that could end the token.
        if (absolute.any { it == '"' || it == '\'' || it == '(' || it == ')' || it == '\\' || it.code <= 0x20 || it.code == 0x7F }) return null
        return absolute
    }

    /** `^[A-Za-z][A-Za-z0-9+.-]*:` over [start, end), without copying. */
    private fun hasScheme(start: Int, end: Int): Boolean {
        if (!css[start].let { it in 'a'..'z' || it in 'A'..'Z' }) return false
        for (k in start + 1 until end) {
            val c = css[k]
            if (c == ':') return true
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '.' || c == '-')) return false
        }
        return false
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

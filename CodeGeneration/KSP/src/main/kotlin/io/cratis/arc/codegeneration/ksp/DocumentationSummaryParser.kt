// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.DocumentationSummaries

/**
 * One parsed Kotlin KDoc or Java Javadoc comment.
 *
 * The two tag maps stay separate because the tags mean different things: on a Kotlin class `@property` documents a
 * property while `@param` documents a constructor parameter, and on a Java record or class `@param` documents a
 * component. Only `@param` on a function documents that function's parameters in both languages.
 *
 * @property summary First documentation paragraph folded onto a single line, or `null` when there is none.
 * @property propertyTags Documentation carried by Kotlin `@property` tags, keyed by property name.
 * @property paramTags Documentation carried by `@param` tags, keyed by parameter or record component name.
 */
internal class ParsedDocumentation(
    val summary: String?,
    val propertyTags: Map<String, String>,
    val paramTags: Map<String, String>
) {
    companion object {
        val EMPTY: ParsedDocumentation = ParsedDocumentation(null, emptyMap(), emptyMap())
    }
}

/**
 * Bounded, single-pass reader for the documentation comments KSP exposes through `KSDeclaration.docString`.
 *
 * The reader never backtracks and never evaluates a regular expression, so its cost is linear in the size of the
 * comment, and every string it produces stops at [DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS] Unicode code
 * points. A comment of any size therefore produces metadata of bounded size in bounded time.
 */
internal object DocumentationSummaryParser {
    private const val PROPERTY_TAG = "property"
    private const val PARAM_TAG = "param"

    /** Parses one documentation comment, tolerating `null`, comment delimiters, and asterisk decoration. */
    fun parse(docString: String?): ParsedDocumentation {
        if (docString.isNullOrEmpty()) return ParsedDocumentation.EMPTY
        val summary = BoundedLine()
        val propertyTags = linkedMapOf<String, BoundedLine>()
        val paramTags = linkedMapOf<String, BoundedLine>()
        var current: BoundedLine? = null
        var phase = Phase.LEADING
        var fence: Char = ' '
        var fenceLength = 0
        var index = 0

        while (index < docString.length) {
            val lineEnd = lineEnd(docString, index)
            val line = normalize(docString, index, lineEnd)
            index = nextLineStart(docString, lineEnd)
            when (phase) {
                Phase.FENCED_CODE -> if (closesFence(line, fence, fenceLength)) phase = Phase.LEADING

                Phase.LEADING -> when {
                    line.isBlank() -> Unit
                    isFenceStart(line) -> {
                        phase = Phase.FENCED_CODE
                        fence = line[0]
                        fenceLength = fenceLength(line)
                    }
                    isIndentedCode(line) -> Unit
                    line.startsWith('@') -> {
                        phase = Phase.TAGS
                        current = startTag(line, propertyTags, paramTags)
                    }
                    else -> {
                        phase = Phase.SUMMARY
                        summary.append(line)
                    }
                }

                Phase.SUMMARY -> when {
                    line.isBlank() -> phase = Phase.TRAILING
                    line.startsWith('@') -> {
                        phase = Phase.TAGS
                        current = startTag(line, propertyTags, paramTags)
                    }
                    else -> summary.append(line)
                }

                Phase.TRAILING -> if (line.startsWith('@')) {
                    phase = Phase.TAGS
                    current = startTag(line, propertyTags, paramTags)
                }

                Phase.TAGS -> when {
                    line.isBlank() -> current = null
                    line.startsWith('@') -> current = startTag(line, propertyTags, paramTags)
                    else -> current?.append(line)
                }
            }
        }

        return ParsedDocumentation(summary.value(), propertyTags.resolve(), paramTags.resolve())
    }

    private enum class Phase { LEADING, FENCED_CODE, SUMMARY, TRAILING, TAGS }

    private fun Map<String, BoundedLine>.resolve(): Map<String, String> = entries.mapNotNull { (name, text) ->
        text.value()?.let { value -> name to value }
    }.toMap()

    private fun startTag(
        line: String,
        propertyTags: MutableMap<String, BoundedLine>,
        paramTags: MutableMap<String, BoundedLine>
    ): BoundedLine? {
        val tagEnd = indexOfSpace(line, 1)
        val target = when (line.substring(1, tagEnd)) {
            PROPERTY_TAG -> propertyTags
            PARAM_TAG -> paramTags
            else -> return null
        }
        val nameStart = skipSpaces(line, tagEnd)
        if (nameStart >= line.length) return null
        val nameEnd = indexOfSpace(line, nameStart)
        val name = line.substring(nameStart, nameEnd)
        // The first tag for a name wins, so a repeated tag cannot make the result depend on iteration order.
        if (target.containsKey(name)) return null
        val text = BoundedLine()
        target[name] = text
        text.append(line, nameEnd, line.length)
        return text
    }

    private fun indexOfSpace(line: String, from: Int): Int {
        var index = from
        while (index < line.length && !isSpace(line[index])) index++
        return index
    }

    private fun skipSpaces(line: String, from: Int): Int {
        var index = from
        while (index < line.length && isSpace(line[index])) index++
        return index
    }

    private fun isFenceStart(line: String): Boolean =
        (line.startsWith("```") || line.startsWith("~~~")) && !isIndentedCode(line)

    private fun fenceLength(line: String): Int {
        var length = 0
        while (length < line.length && line[length] == line[0]) length++
        return length
    }

    private fun closesFence(line: String, fence: Char, fenceLength: Int): Boolean {
        val start = skipSpaces(line, 0)
        var length = 0
        while (start + length < line.length && line[start + length] == fence) length++
        if (length < fenceLength) return false
        return skipSpaces(line, start + length) >= line.length
    }

    private fun isIndentedCode(line: String): Boolean =
        line.startsWith('\t') || line.startsWith("    ")

    private fun isSpace(character: Char): Boolean = character == ' ' || character == '\t'

    private fun lineEnd(text: String, from: Int): Int {
        var index = from
        while (index < text.length && !isLineTerminator(text[index])) index++
        return index
    }

    private fun nextLineStart(text: String, lineEnd: Int): Int {
        if (lineEnd >= text.length) return text.length
        if (text[lineEnd] == '\r' && lineEnd + 1 < text.length && text[lineEnd + 1] == '\n') return lineEnd + 2
        return lineEnd + 1
    }

    private fun isLineTerminator(character: Char): Boolean = when (character.code) {
        0x000A, 0x000B, 0x000C, 0x000D, 0x0085, 0x2028, 0x2029 -> true
        else -> false
    }

    /**
     * Strips the comment decoration from one raw line while preserving content indentation.
     *
     * A leading comment opener or asterisk is decoration, as is exactly one space separating it from the content.
     * Everything after that - including further indentation, which marks a code block - is content.
     */
    private fun normalize(text: String, from: Int, to: Int): String {
        var start = from
        var end = to
        var probe = start
        while (probe < end && isSpace(text[probe])) probe++
        if (probe < end && text[probe] == '/') {
            if (text.startsWith("/**", probe)) {
                start = minOf(probe + 3, end)
            } else if (text.startsWith("/*", probe)) {
                start = minOf(probe + 2, end)
            }
        } else if (probe < end && text[probe] == '*' && !text.startsWith("*/", probe)) {
            start = probe + 1
        }
        if (start < end && text[start] == ' ') start++
        while (end > start && isSpace(text[end - 1])) end--
        if (end - start >= 2 && text[end - 2] == '*' && text[end - 1] == '/') {
            end -= 2
            while (end > start && isSpace(text[end - 1])) end--
        }
        return text.substring(start, end)
    }
}

/**
 * A single logical line whose length is capped at [DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS] code points.
 *
 * Whitespace runs collapse to one space, control characters are dropped, and appending stops permanently once the cap
 * is reached, so unbounded input cannot produce unbounded output or unbounded work per appended line.
 */
private class BoundedLine {
    private val builder = StringBuilder()
    private var codePoints = 0
    private var full = false

    fun append(line: String) {
        append(line, 0, line.length)
    }

    fun append(line: String, from: Int, to: Int) {
        if (full) return
        var index = from
        while (index < to) {
            while (index < to && isSpace(line[index])) index++
            if (index >= to) return
            var tokenEnd = index
            while (tokenEnd < to && !isSpace(line[tokenEnd])) tokenEnd++
            appendToken(line, index, tokenEnd)
            if (full) return
            index = tokenEnd
        }
    }

    fun value(): String? = builder.toString().takeIf(String::isNotEmpty)

    private fun appendToken(line: String, from: Int, to: Int) {
        var separatorPending = builder.isNotEmpty()
        var index = from
        while (index < to) {
            val codePoint = line.codePointAt(index)
            val width = Character.charCount(codePoint)
            index += width
            if (isDropped(codePoint)) continue
            if (separatorPending) {
                // A separator is only worth emitting when the code point it separates also fits, so the bounded
                // result can never end with trailing whitespace.
                if (codePoints + 1 >= DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS) {
                    full = true
                    return
                }
                builder.append(' ')
                codePoints++
                separatorPending = false
            }
            if (!emit(codePoint)) return
        }
    }

    private fun emit(codePoint: Int): Boolean {
        if (codePoints >= DocumentationSummaries.MAX_LENGTH_IN_CODE_POINTS) {
            full = true
            return false
        }
        builder.appendCodePoint(codePoint)
        codePoints++
        return true
    }

    private fun isSpace(character: Char): Boolean = character == ' ' || character == '\t'

    private fun isDropped(codePoint: Int): Boolean = when (codePoint) {
        0x0085, 0x2028, 0x2029 -> true
        else -> codePoint < 0x20 || codePoint == 0x7F || codePoint in 0x80..0x9F
    }
}

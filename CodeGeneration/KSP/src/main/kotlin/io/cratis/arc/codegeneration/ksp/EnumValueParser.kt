// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

/**
 * Reads enum-entry constructor integer literals without evaluating arbitrary user code.
 *
 * KSP exposes enum entries but not their constructor arguments. This parser deliberately accepts only a single
 * integer literal per entry. Expressions, constants, overloaded constructors, and entry class bodies remain
 * unproven and therefore require @ArcEnumValue.
 */
internal fun parseEnumConstructorValues(source: String, enumName: String): Map<String, Int> {
    val declaration = sequenceOf(
        Regex("\\benum\\s+class\\s+${Regex.escape(enumName)}\\b"),
        Regex("\\benum\\s+${Regex.escape(enumName)}\\b")
    ).mapNotNull { pattern -> pattern.find(source) }.minByOrNull { match -> match.range.first } ?: return emptyMap()
    val openingBrace = findOpeningBrace(source, declaration.range.last + 1) ?: return emptyMap()
    val closingBrace = findMatchingDelimiter(source, openingBrace, '{', '}') ?: return emptyMap()
    val body = source.substring(openingBrace + 1, closingBrace)
    val entriesSource = body.substring(0, findTopLevelSemicolon(body) ?: body.length)

    return splitEnumEntries(entriesSource).mapNotNull { entry ->
        val parsed = parseEnumEntry(entry) ?: return@mapNotNull null
        val value = parseIntegerLiteral(parsed.second) ?: return@mapNotNull null
        parsed.first to value
    }.toMap()
}

private fun parseEnumEntry(source: String): Pair<String, String>? {
    var index = 0
    while (true) {
        while (index < source.length && source[index].isWhitespace()) index++
        if (index >= source.length || source[index] != '@') break
        index = skipAnnotation(source, index) ?: return null
    }
    val nameStart = index
    if (nameStart >= source.length || !source[nameStart].isJavaIdentifierStart()) return null
    index++
    while (index < source.length && source[index].isJavaIdentifierPart()) index++
    val name = source.substring(nameStart, index)
    while (index < source.length && source[index].isWhitespace()) index++
    if (index >= source.length || source[index] != '(') return null
    val closing = findMatchingDelimiter(source, index, '(', ')') ?: return null
    val argument = source.substring(index + 1, closing).trim()
    if (argument.isEmpty() || hasTopLevelComma(argument)) return null
    var remainder = source.substring(closing + 1).trim()
    if (remainder.endsWith(',')) remainder = remainder.dropLast(1).trim()
    if (remainder.isNotEmpty() && !remainder.startsWith('{')) return null
    return name to argument
}

private fun skipAnnotation(source: String, openingAt: Int): Int? {
    var index = openingAt + 1
    if (index >= source.length || !source[index].isJavaIdentifierStart()) return null
    index++
    while (index < source.length && (source[index].isJavaIdentifierPart() || source[index] == '.')) index++
    while (index < source.length && source[index].isWhitespace()) index++
    if (index < source.length && source[index] == '(') {
        index = (findMatchingDelimiter(source, index, '(', ')') ?: return null) + 1
    }
    return index
}

private fun parseIntegerLiteral(source: String): Int? {
    var literal = source.trim().replace("_", "")
    if (literal.startsWith('(') && literal.endsWith(')') &&
        findMatchingDelimiter(literal, 0, '(', ')') == literal.lastIndex
    ) {
        literal = literal.substring(1, literal.lastIndex).trim()
    }
    literal = literal.removeSuffix("L").removeSuffix("l")
    val negative = literal.startsWith('-')
    val positive = literal.startsWith('+')
    val unsigned = if (negative || positive) literal.substring(1) else literal
    val radix = when {
        unsigned.startsWith("0x", ignoreCase = true) -> 16
        unsigned.startsWith("0b", ignoreCase = true) -> 2
        unsigned.length > 1 && unsigned.startsWith('0') -> 8
        else -> 10
    }
    val digits = when (radix) {
        16, 2 -> unsigned.substring(2)
        8 -> unsigned.substring(1)
        else -> unsigned
    }
    if (digits.isEmpty()) return null
    val magnitude = digits.toLongOrNull(radix) ?: return null
    val signed = if (negative) -magnitude else magnitude
    return signed.takeIf { value -> value in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

private fun findOpeningBrace(source: String, start: Int): Int? {
    var quote: Char? = null
    var escaped = false
    for (index in start until source.length) {
        val character = source[index]
        if (quote != null) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == quote) quote = null
            continue
        }
        when (character) {
            '\'', '"' -> quote = character
            '{' -> return index
        }
    }
    return null
}

private fun findMatchingDelimiter(source: String, openingIndex: Int, opening: Char, closing: Char): Int? {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    var lineComment = false
    var blockComment = false
    var index = openingIndex
    while (index < source.length) {
        val character = source[index]
        val next = source.getOrNull(index + 1)
        if (lineComment) {
            if (character == '\n' || character == '\r') lineComment = false
            index++
            continue
        }
        if (blockComment) {
            if (character == '*' && next == '/') {
                blockComment = false
                index += 2
            } else {
                index++
            }
            continue
        }
        if (quote != null) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == quote) quote = null
            index++
            continue
        }
        if (character == '/' && next == '/') {
            lineComment = true
            index += 2
            continue
        }
        if (character == '/' && next == '*') {
            blockComment = true
            index += 2
            continue
        }
        when (character) {
            '\'', '"' -> quote = character
            opening -> depth++
            closing -> {
                depth--
                if (depth == 0) return index
            }
        }
        index++
    }
    return null
}

private fun findTopLevelSemicolon(source: String): Int? = findTopLevelCharacter(source, ';')

private fun hasTopLevelComma(source: String): Boolean = findTopLevelCharacter(source, ',') != null

private fun findTopLevelCharacter(source: String, expected: Char): Int? {
    var parentheses = 0
    var braces = 0
    var brackets = 0
    var quote: Char? = null
    var escaped = false
    source.forEachIndexed { index, character ->
        if (quote != null) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == quote) quote = null
            return@forEachIndexed
        }
        when (character) {
            '\'', '"' -> quote = character
            '(' -> parentheses++
            ')' -> parentheses--
            '{' -> braces++
            '}' -> braces--
            '[' -> brackets++
            ']' -> brackets--
            expected -> if (parentheses == 0 && braces == 0 && brackets == 0) return index
        }
    }
    return null
}

private fun splitEnumEntries(source: String): List<String> {
    val entries = mutableListOf<String>()
    var start = 0
    var parentheses = 0
    var braces = 0
    var brackets = 0
    var quote: Char? = null
    var escaped = false
    source.forEachIndexed { index, character ->
        if (quote != null) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == quote) quote = null
            return@forEachIndexed
        }
        when (character) {
            '\'', '"' -> quote = character
            '(' -> parentheses++
            ')' -> parentheses--
            '{' -> braces++
            '}' -> braces--
            '[' -> brackets++
            ']' -> brackets--
            ',' -> if (parentheses == 0 && braces == 0 && brackets == 0) {
                entries.add(source.substring(start, index).trim())
                start = index + 1
            }
        }
    }
    entries.add(source.substring(start).trim())
    return entries.filter(String::isNotBlank)
}

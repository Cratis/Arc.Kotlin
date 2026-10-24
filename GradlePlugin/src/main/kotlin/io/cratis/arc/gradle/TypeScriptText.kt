// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.fundamentals.strings.toCamelCase
import java.security.MessageDigest

// Text helpers shared by the TypeScript proxy renderers.

internal fun roles(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]") { "'${escape(it)}'" }

internal fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

internal fun lowerCamel(value: String): String = value.toCamelCase()

internal fun upperCamel(value: String): String = value.replaceFirstChar(Char::uppercaseChar)

internal fun typescriptString(value: String): String = "'" + value
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\r", "\\r")
    .replace("\n", "\\n") + "'"

internal fun sharedAlias(name: String): String = "ArcNested" + MessageDigest.getInstance("SHA-256")
    .digest(name.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

/** Renders a Java regular expression as a TypeScript regular expression literal. */
internal fun regularExpression(pattern: String): String {
    if (pattern.isEmpty()) return "/(?:)/"
    val literal = StringBuilder(pattern.length + 2)
    val escaped = pattern.fold(false) { previousWasEscape, character ->
        if (character == '\\' && !previousWasEscape) {
            literal.append('\\')
            true
        } else {
            literal.append(escapeLiteralCharacter(character, previousWasEscape))
            false
        }
    }
    if (escaped) literal.append('\\')
    return "/$literal/"
}

private fun escapeLiteralCharacter(character: Char, previousWasEscape: Boolean): String = when (character) {
    '/' -> if (previousWasEscape) "/" else "\\/"
    '\r' -> if (previousWasEscape) "r" else "\\r"
    '\n' -> if (previousWasEscape) "n" else "\\n"
    '\u2028' -> if (previousWasEscape) "u2028" else "\\u2028"
    '\u2029' -> if (previousWasEscape) "u2029" else "\\u2029"
    else -> character.toString()
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.json

/** Implements Arc's .NET-compatible first-UTF-16-character camel-case rule. */
public object ArcCamelCase {
    /**
     * Lowercases only the first UTF-16 character when it is an uppercase letter and the second character is not.
     * Names beginning with two uppercase letters are preserved in full.
     */
    @JvmStatic
    public fun convert(name: String?): String? {
        if (name.isNullOrEmpty()) return name
        if (!isUppercaseLetter(name[0])) return name
        if (name.length > 1 && isUppercaseLetter(name[1])) return name

        val first = lowercaseInvariant(name[0])
        return buildString(name.length) {
            append(first)
            append(name, 1, name.length)
        }
    }

    private fun isUppercaseLetter(character: Char): Boolean =
        Character.getType(character) == Character.UPPERCASE_LETTER.toInt()

    private fun lowercaseInvariant(character: Char): Char =
        if (character == '\u0130') character else Character.toLowerCase(character)
}

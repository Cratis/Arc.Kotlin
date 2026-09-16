// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.metadata.ValidationRuleDescriptor
import java.math.BigDecimal
import java.math.BigInteger
import java.lang.reflect.Array as ReflectArray
import java.util.regex.Pattern

/** Bounded shared semantics. Never evaluates annotation descriptors or arbitrary predicates. */
internal object FluentValidationRules {
    private val order = listOf("notNull", "notEmpty", "minLength", "maxLength", "length", "emailAddress", "phone", "url",
        "matches", "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual")
    private val numericRules = order.takeLast(4)
    private val lengthRules = listOf("notEmpty", "minLength", "maxLength", "length")
    private val numericTypes = setOf(Byte::class.java, Short::class.java, Int::class.java, Long::class.java,
        Float::class.java, Double::class.java, Byte::class.javaObjectType, Short::class.javaObjectType,
        Int::class.javaObjectType, Long::class.javaObjectType, Float::class.javaObjectType,
        Double::class.javaObjectType, BigDecimal::class.java, BigInteger::class.java)
    private val maxSafe = BigDecimal("9007199254740991")
    // ECMAScript WhiteSpace + LineTerminator, deliberately not Kotlin isWhitespace / Java \\s.
    private const val whitespace = "\u0009\u000B\u000C\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u202F\u205F\u3000\uFEFF\n\r\u2028\u2029"

    fun snapshot(type: Class<*>, rules: List<ValidationRuleDescriptor>): List<ValidationRuleDescriptor> {
        val normalized = rules.map { rule ->
            val name = rule.ruleName
            require(name in order) { "Unsupported shared fluent rule '$name'; creditCard is server-only and has no client rule." }
            val arity = when (name) { "length" -> 2; "minLength", "maxLength", "matches" -> 1; in numericRules -> 1; else -> 0 }
            require(rule.arguments.size == arity) { "Fluent rule '$name' requires $arity arguments." }
            if (name in lengthRules) require(type == String::class.java || type.isArray || Collection::class.java.isAssignableFrom(type)) {
                "Fluent rule '$name' requires a string, collection or array member, not ${type.name}."
            }
            if (name in listOf("emailAddress", "phone", "url", "matches")) require(type == String::class.java) {
                "Fluent rule '$name' requires a string member, not ${type.name}."
            }
            val arguments: List<Any> = when (name) {
                in numericRules -> {
                    require(type in numericTypes) { "Fluent rule '$name' requires a numeric member, not ${type.name}." }
                    require(type != Float::class.java && type != Float::class.javaObjectType) {
                        "Shared numeric member '${type.name}' rounds at a different precision from JavaScript; use Double or a server-only validator."
                    }
                    listOf(number(rule.arguments.single()))
                }
                "minLength", "maxLength", "length" -> rule.arguments.map {
                    require(it is Int && it >= 0) { "Fluent rule '$name' requires nonnegative Int length bounds." }
                    it
                }
                "matches" -> {
                    val pattern = rule.arguments.single()
                    require(pattern is String) { "Fluent matches requires a literal string pattern." }
                    compilePattern(pattern)
                    listOf(pattern)
                }
                else -> emptyList()
            }
            ValidationRuleDescriptor(name, arguments, rule.message)
        }.distinct().sortedWith(compareBy<ValidationRuleDescriptor> { order.indexOf(it.ruleName) }
            .thenBy { it.arguments.joinToString("\u0000") }.thenBy { it.message != null }.thenBy { it.message.orEmpty() })
        var min = if (normalized.any { it.ruleName == "notEmpty" }) 1 else 0
        var max = Int.MAX_VALUE
        var lower: Pair<Double, Boolean>? = null
        var upper: Pair<Double, Boolean>? = null
        normalized.forEach { rule ->
            when (rule.ruleName) {
                "minLength" -> min = maxOf(min, rule.arguments[0] as Int)
                "maxLength" -> max = minOf(max, rule.arguments[0] as Int)
                "length" -> { min = maxOf(min, rule.arguments[0] as Int); max = minOf(max, rule.arguments[1] as Int) }
                in numericRules -> {
                    val bound = (rule.arguments[0] as Number).toDouble() to rule.ruleName.endsWith("OrEqual")
                    if (rule.ruleName.startsWith("greater")) {
                        val current = lower
                        if (current == null || bound.first > current.first || bound.first == current.first && !bound.second) lower = bound
                    } else {
                        val current = upper
                        if (current == null || bound.first < current.first || bound.first == current.first && !bound.second) upper = bound
                    }
                }
            }
        }
        require(min <= max) { "Fluent rules declare contradictory length bounds." }
        val low = lower
        val high = upper
        require(low == null || high == null || low.first < high.first || low.first == high.first && low.second && high.second) {
            "Fluent rules declare contradictory numeric bounds."
        }
        return java.util.List.copyOf(normalized)
    }

    /** Decimal wire round-trip, finite and inside the safe number domain; never silently rounds a Long/BigDecimal. */
    fun number(value: Any): Number {
        require(value is Number && value.javaClass in numericTypes) { "Fluent numeric values require a supported immutable number." }
        val decimal = value.toString().toBigDecimalOrNull()
        require(decimal != null && decimal.abs() <= maxSafe) { "Fluent numeric value '$value' must be finite and within the JavaScript safe number domain." }
        val double = decimal.toDouble()
        require(double == 0.0 || kotlin.math.abs(double) >= java.lang.Double.MIN_NORMAL) {
            "Fluent numeric value '$value' must not be a subnormal JavaScript number."
        }
        require(BigDecimal.valueOf(double).compareTo(decimal) == 0) { "Fluent numeric value '$value' cannot round-trip through a JavaScript number." }
        return if (decimal.stripTrailingZeros().scale() <= 0) decimal.toLong() else double
    }

    fun valid(rule: ValidationRuleDescriptor, value: Any?): Boolean {
        if (rule.ruleName == "notNull") return value != null
        if (rule.ruleName == "notEmpty") return when (value) {
            null -> false
            is String -> value.any { it !in whitespace }
            else -> length(value) != 0
        }
        if (value == null) return true
        val args = rule.arguments
        return when (rule.ruleName) {
            "minLength" -> length(value) >= args[0] as Int
            "maxLength" -> length(value) <= args[0] as Int
            "length" -> length(value) >= args[0] as Int && length(value) <= args[1] as Int
            "emailAddress" -> {
                val text = value as String
                val at = text.indexOf('@')
                text.isEmpty() || (at > 0 && at == text.lastIndexOf('@') && text.none { it in whitespace } &&
                    text.indexOf('.', at + 2) in (at + 2)..(text.length - 2))
            }
            "phone" -> (value as String).all { it in '0'..'9' || it in whitespace || it in "()+-" }
            "url" -> {
                val text = value as String
                val lower = text.lowercase(java.util.Locale.ROOT)
                val prefix = when { lower.startsWith("http://") -> 7; lower.startsWith("https://") -> 8; else -> -1 }
                text.isEmpty() || prefix >= 0 && text.length > prefix && text[prefix] !in "\n\r\u2028\u2029"
            }
            "matches" -> (value as String).isEmpty() || compilePattern(args[0] as String).matcher(value).find()
            "greaterThan" -> number(value).toDouble() > (args[0] as Number).toDouble()
            "greaterThanOrEqual" -> number(value).toDouble() >= (args[0] as Number).toDouble()
            "lessThan" -> number(value).toDouble() < (args[0] as Number).toDouble()
            "lessThanOrEqual" -> number(value).toDouble() <= (args[0] as Number).toDouble()
            else -> error("Unsupported fluent rule '${rule.ruleName}'.")
        }
    }

    private fun length(value: Any): Int = when (value) {
        is String -> value.length // UTF-16 code units, exactly like the client.
        is Collection<*> -> value.size
        else -> ReflectArray.getLength(value)
    }

    fun message(rule: ValidationRuleDescriptor, member: String): String {
        fun arg(index: Int): String {
            val value = rule.arguments[index]
            if (value !is Double) return value.toString()
            val decimal = BigDecimal.valueOf(value).stripTrailingZeros()
            return if (value == 0.0 || kotlin.math.abs(value) >= 0.000001) decimal.toPlainString()
                else decimal.toString().lowercase(java.util.Locale.ROOT)
        }
        val template = rule.message ?: when (rule.ruleName) {
            "notNull", "notEmpty" -> "'{PropertyName}' must not be empty."
            "minLength" -> "'{PropertyName}' must be at least ${arg(0)} characters."
            "maxLength" -> "'{PropertyName}' must be at most ${arg(0)} characters."
            "length" -> "'{PropertyName}' must be between ${arg(0)} and ${arg(1)} characters."
            "emailAddress" -> "'{PropertyName}' is not a valid email address."
            "phone" -> "'{PropertyName}' is not a valid phone number."
            "url" -> "'{PropertyName}' is not a valid URL."
            "matches" -> "'{PropertyName}' is not in the correct format."
            "greaterThan" -> "'{PropertyName}' must be greater than ${arg(0)}."
            "greaterThanOrEqual" -> "'{PropertyName}' must be greater than or equal to ${arg(0)}."
            "lessThan" -> "'{PropertyName}' must be less than ${arg(0)}."
            "lessThanOrEqual" -> "'{PropertyName}' must be less than or equal to ${arg(0)}."
            else -> error("Unsupported fluent rule '${rule.ruleName}'.")
        }
        // JS String.replace treats $$ in the replacement as one dollar. Other replacement
        // metacharacters cannot occur in our direct identifier grammar.
        return template.replaceFirst("{PropertyName}", member.replace("$$", "$"))
    }

    /** A deliberately smaller subset than legacy annotation screening: no flags, lookarounds,
     * backreferences, dot, negated classes, Unicode escapes or non-ASCII pattern literals.
     * ASCII classes/literals, grouping, alternation, quantifiers, anchors and d/w/s are portable.
     */
    private fun compilePattern(source: String): Pattern {
        val translated = StringBuilder()
        var escaped = false
        var inClass = false
        var classHasAtom = false
        for (character in source) {
            require(character.code in 32..126) { "Fluent matches requires an ASCII portable pattern; unsupported pattern '$source'." }
            if (escaped) {
                require(character in "dws\\.^$|?*+()[]{}-/") { "Fluent matches has an unsupported escape in '$source'." }
                if (character == 's') translated.append(if (inClass) whitespace else "[$whitespace]")
                else translated.append('\\').append(character)
                if (inClass) classHasAtom = true
                escaped = false
            } else when (character) {
                '\\' -> escaped = true
                '[' -> { require(!inClass) { "Fluent matches does not support nested classes." }; inClass = true; classHasAtom = false; translated.append(character) }
                ']' -> {
                    require(inClass && classHasAtom) { "Fluent matches requires nonempty classes and escaped literal closing brackets in '$source'." }
                    inClass = false
                    translated.append(character)
                }
                '.' -> { require(inClass) { "Fluent matches does not support dot; use explicit ASCII classes." }; classHasAtom = true; translated.append(character) }
                '^' -> { require(!inClass) { "Fluent matches does not support negated classes." }; translated.append(character) }
                '$' -> { translated.append(if (inClass) "$" else "\\z"); if (inClass) classHasAtom = true }
                else -> { translated.append(character); if (inClass) classHasAtom = true }
            }
        }
        require(!escaped && !inClass && "&&" !in source && !source.contains("(?") &&
            !Regex("(?:[?*+]\\+|\\{\\d+(?:,\\d*)?}\\+)").containsMatchIn(source)) {
            "Fluent matches uses unsupported JavaScript pattern syntax in '$source'."
        }
        return Pattern.compile(translated.toString())
    }
}

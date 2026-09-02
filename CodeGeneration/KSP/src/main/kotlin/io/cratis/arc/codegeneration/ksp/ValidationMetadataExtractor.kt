// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Modifier
import java.math.BigDecimal
import java.util.regex.PatternSyntaxException

internal data class SourceValidationAnnotation(
    val qualifiedName: String,
    val arguments: Map<String, Any?> = emptyMap()
)

internal data class ValidationMetadata(
    val rules: List<ValidationRuleModel>,
    val validateRecursively: Boolean
)

/** Extracts only Jakarta constraints that have an exact rule in the installed @cratis validator vocabulary. */
internal class ValidationMetadataExtractor(private val logger: ArcDiagnosticReporter) {
    fun extract(
        annotated: Iterable<KSAnnotated>,
        shape: TypeShape,
        identity: String,
        node: KSNode,
        sourceAnnotations: List<SourceValidationAnnotation> = emptyList(),
        inheritedRules: List<ValidationRuleModel> = emptyList()
    ): ValidationMetadata? {
        val kspAnnotations = annotated.flatMap { it.annotations.toList() }
            .mapNotNull { annotation -> annotation.toSourceValidationAnnotation() }
            .distinctBy { annotation -> annotation.identity() }
        val kspNames = kspAnnotations.map(SourceValidationAnnotation::qualifiedName).toSet()
        val annotations = kspAnnotations + sourceAnnotations
            .filterNot { annotation -> annotation.qualifiedName in kspNames }
            .distinctBy { annotation -> annotation.identity() }
        val rules = inheritedRules.toMutableList()
        var recursively = false

        for (annotation in annotations) {
            val name = annotation.qualifiedName
            if (name !in SUPPORTED_ANNOTATIONS) continue
            if (annotation.hasNonDefaultValidationConfiguration()) {
                return unsupported(identity, name, "uses groups or payload metadata that client validation cannot represent", node)
            }
            val message = annotation.message()
            when (name) {
                VALID -> recursively = true
                NOT_NULL -> rules += ValidationRuleModel("notNull", message = message)
                NOT_BLANK, NOT_EMPTY -> {
                    if (!shape.acceptsLength()) return unsupported(identity, name, "requires a string, collection, or array", node)
                    rules += ValidationRuleModel("notEmpty", message = message)
                }
                SIZE -> {
                    if (!shape.acceptsLength()) return unsupported(identity, name, "requires a string, collection, or array", node)
                    val min = annotation.intArgument("min", 0)
                    val max = annotation.intArgument("max", Int.MAX_VALUE)
                    if (min < 0 || max < 0 || min > max) {
                        return invalid(identity, name, "requires 0 <= min <= max", node)
                    }
                    when {
                        min > 0 && max < Int.MAX_VALUE -> rules += ValidationRuleModel("length", listOf(min, max), message)
                        min > 0 -> rules += ValidationRuleModel("minLength", listOf(min), message)
                        max < Int.MAX_VALUE -> rules += ValidationRuleModel("maxLength", listOf(max), message)
                    }
                }
                MIN -> rules += numericRule(annotation, identity, shape, "greaterThanOrEqual", "value", node) ?: return null
                MAX -> rules += numericRule(annotation, identity, shape, "lessThanOrEqual", "value", node) ?: return null
                DECIMAL_MIN -> rules += decimalRule(annotation, identity, shape, true, node) ?: return null
                DECIMAL_MAX -> rules += decimalRule(annotation, identity, shape, false, node) ?: return null
                POSITIVE -> rules += numericConstantRule(identity, shape, "greaterThan", 0, message, node) ?: return null
                POSITIVE_OR_ZERO -> rules += numericConstantRule(identity, shape, "greaterThanOrEqual", 0, message, node) ?: return null
                NEGATIVE -> rules += numericConstantRule(identity, shape, "lessThan", 0, message, node) ?: return null
                NEGATIVE_OR_ZERO -> rules += numericConstantRule(identity, shape, "lessThanOrEqual", 0, message, node) ?: return null
                PATTERN -> {
                    if (!shape.isString()) return unsupported(identity, name, "requires a string", node)
                    val pattern = annotation.stringArgument("regexp") ?: return invalid(identity, name, "requires a regexp", node)
                    if (annotation.hasFlags()) {
                        return unsupported(identity, name, "uses flags that cannot be emitted by the current @cratis matches rule", node)
                    }
                    if (!isRepresentablePattern(pattern)) {
                        return unsupported(identity, name, "uses a regular expression that is not portable to JavaScript", node)
                    }
                    rules += ValidationRuleModel("matches", listOf(pattern), message)
                }
                EMAIL -> {
                    if (!shape.isString()) return unsupported(identity, name, "requires a string", node)
                    rules += ValidationRuleModel("emailAddress", message = message)
                    val pattern = annotation.stringArgument("regexp")
                    if (annotation.hasFlags()) {
                        return unsupported(identity, name, "uses flags that cannot be emitted by the current @cratis matches rule", node)
                    }
                    if (pattern != null && pattern != ".*") {
                        if (!isRepresentablePattern(pattern)) {
                            return unsupported(identity, name, "uses a regular expression that is not portable to JavaScript", node)
                        }
                        rules += ValidationRuleModel("matches", listOf(pattern), message)
                    }
                }
                ARC_PHONE -> {
                    if (!shape.isString()) return unsupported(identity, name, "requires a string", node)
                    rules += ValidationRuleModel("phone", message = message)
                }
                URL, ARC_URL -> {
                    if (!shape.isString()) return unsupported(identity, name, "requires a string", node)
                    rules += ValidationRuleModel("url", message = message)
                }
                CREDIT_CARD_NUMBER, ARC_CREDIT_CARD -> {
                    if (!shape.isString()) return unsupported(identity, name, "requires a string", node)
                    rules += ValidationRuleModel("creditCard", message = message)
                }
            }
        }

        if (recursively && !shape.canValidateRecursively()) {
            return unsupported(identity, VALID, "cannot recursively validate this scalar, enum, abstract, or polymorphic value", node)
        }
        val distinctRules = rules.distinct().sortedWith(
            compareBy<ValidationRuleModel> { rule -> RULE_ORDER.indexOf(rule.ruleName).takeIf { it >= 0 } ?: Int.MAX_VALUE }
                .thenBy(ValidationRuleModel::ruleName)
                .thenBy { rule -> rule.arguments.joinToString("\u0000") }
                .thenBy { rule -> rule.message.orEmpty() }
        )
        if (!validateContradictions(identity, distinctRules, node)) return null
        return ValidationMetadata(distinctRules, recursively)
    }

    private fun numericRule(
        annotation: SourceValidationAnnotation,
        identity: String,
        shape: TypeShape,
        ruleName: String,
        argumentName: String,
        node: KSNode
    ): ValidationRuleModel? {
        if (!shape.isNumeric()) return unsupported(identity, annotation.qualifiedName, "requires a numeric value", node)
        val argument = annotation.argument(argumentName) as? Number
            ?: annotation.argument(argumentName)?.toString()?.toLongOrNull()
            ?: return invalid(identity, annotation.qualifiedName, "requires a numeric '$argumentName'", node)
        val represented = representNumber(argument.toString())
            ?: return unsupported(
                identity,
                annotation.qualifiedName,
                "uses '$argument', which is not exactly representable by a JavaScript number",
                node
            )
        return ValidationRuleModel(ruleName, listOf(represented), annotation.message())
    }

    private fun decimalRule(
        annotation: SourceValidationAnnotation,
        identity: String,
        shape: TypeShape,
        minimum: Boolean,
        node: KSNode
    ): ValidationRuleModel? {
        if (!shape.isNumeric()) return unsupported(identity, annotation.qualifiedName, "requires a numeric value", node)
        val value = annotation.stringArgument("value")
            ?: return invalid(identity, annotation.qualifiedName, "requires a decimal value", node)
        val represented = representNumber(value)
            ?: return unsupported(
                identity,
                annotation.qualifiedName,
                "uses '$value', which is not exactly representable by a JavaScript number",
                node
            )
        val inclusive = annotation.booleanArgument("inclusive", true)
        val rule = when {
            minimum && inclusive -> "greaterThanOrEqual"
            minimum -> "greaterThan"
            inclusive -> "lessThanOrEqual"
            else -> "lessThan"
        }
        return ValidationRuleModel(rule, listOf(represented), annotation.message())
    }

    private fun numericConstantRule(
        identity: String,
        shape: TypeShape,
        ruleName: String,
        value: Number,
        message: String?,
        node: KSNode
    ): ValidationRuleModel? {
        if (!shape.isNumeric()) return unsupported(identity, ruleName, "requires a numeric value", node)
        return ValidationRuleModel(ruleName, listOf(value), message)
    }

    private fun validateContradictions(identity: String, rules: List<ValidationRuleModel>, node: KSNode): Boolean {
        var numericLower: Boundary? = null
        var numericUpper: Boundary? = null
        var lengthLower = if (rules.any { it.ruleName == "notEmpty" }) 1 else 0
        var lengthUpper = Int.MAX_VALUE
        rules.forEach { rule ->
            when (rule.ruleName) {
                "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual" -> {
                    val value = rule.arguments.singleOrNull()?.toString()?.toBigDecimalOrNull() ?: return@forEach
                    when (rule.ruleName) {
                        "greaterThan" -> numericLower = stricterLower(numericLower, Boundary(value, false))
                        "greaterThanOrEqual" -> numericLower = stricterLower(numericLower, Boundary(value, true))
                        "lessThan" -> numericUpper = stricterUpper(numericUpper, Boundary(value, false))
                        "lessThanOrEqual" -> numericUpper = stricterUpper(numericUpper, Boundary(value, true))
                    }
                }
                "minLength" -> lengthLower = maxOf(lengthLower, (rule.arguments.single() as Number).toInt())
                "maxLength" -> lengthUpper = minOf(lengthUpper, (rule.arguments.single() as Number).toInt())
                "length" -> {
                    lengthLower = maxOf(lengthLower, (rule.arguments[0] as Number).toInt())
                    lengthUpper = minOf(lengthUpper, (rule.arguments[1] as Number).toInt())
                }
            }
        }
        if (numericLower != null && numericUpper != null) {
            val comparison = numericLower!!.value.compareTo(numericUpper!!.value)
            if (comparison > 0 || comparison == 0 && (!numericLower!!.inclusive || !numericUpper!!.inclusive)) {
                logger.error(
                    ArcDiagnostic.VALIDATION,
                    "Validation annotations on '$identity' declare contradictory numeric bounds.",
                    node
                )
                return false
            }
        }
        if (lengthLower > lengthUpper) {
            logger.error(
                ArcDiagnostic.VALIDATION,
                "Validation annotations on '$identity' declare contradictory length bounds.",
                node
            )
            return false
        }
        return true
    }

    private fun stricterLower(current: Boundary?, candidate: Boundary): Boundary = when {
        current == null || candidate.value > current.value -> candidate
        candidate.value == current.value && !candidate.inclusive -> candidate
        else -> current
    }

    private fun stricterUpper(current: Boundary?, candidate: Boundary): Boundary = when {
        current == null || candidate.value < current.value -> candidate
        candidate.value == current.value && !candidate.inclusive -> candidate
        else -> current
    }

    private fun unsupported(identity: String, annotation: String, reason: String, node: KSNode): Nothing? {
        logger.error(ArcDiagnostic.VALIDATION, "Validation annotation @$annotation on '$identity' $reason.", node)
        return null
    }

    private fun invalid(identity: String, annotation: String, reason: String, node: KSNode): Nothing? {
        logger.error(ArcDiagnostic.VALIDATION, "Validation annotation @$annotation on '$identity' $reason.", node)
        return null
    }

    private fun KSAnnotation.toSourceValidationAnnotation(): SourceValidationAnnotation? {
        val name = annotationType.resolve().declaration.qualifiedName?.asString() ?: return null
        if (name !in SUPPORTED_ANNOTATIONS) return null
        return SourceValidationAnnotation(
            name,
            arguments.associate { argument -> argument.name?.asString().orEmpty() to argument.value }
        )
    }

    private fun SourceValidationAnnotation.message(): String? = stringArgument("message")
        ?.takeUnless { it in DEFAULT_MESSAGE_KEYS }

    private fun SourceValidationAnnotation.argument(name: String): Any? = arguments[name]
    private fun SourceValidationAnnotation.stringArgument(name: String): String? = argument(name) as? String
    private fun SourceValidationAnnotation.intArgument(name: String, default: Int): Int =
        (argument(name) as? Number)?.toInt() ?: argument(name)?.toString()?.toIntOrNull() ?: default
    private fun SourceValidationAnnotation.booleanArgument(name: String, default: Boolean): Boolean =
        argument(name) as? Boolean ?: default

    private fun SourceValidationAnnotation.hasFlags(): Boolean = when (val value = argument("flags")) {
        null -> false
        is List<*> -> value.isNotEmpty()
        is Array<*> -> value.isNotEmpty()
        else -> value.toString() != "[]" && value.toString().isNotBlank()
    }

    private fun SourceValidationAnnotation.hasNonDefaultValidationConfiguration(): Boolean =
        listOf("groups", "payload").any { name ->
            when (val value = argument(name)) {
                null -> false
                is List<*> -> value.isNotEmpty()
                is Array<*> -> value.isNotEmpty()
                else -> value.toString() != "[]" && value.toString().isNotBlank()
            }
        }

    private fun SourceValidationAnnotation.identity(): String = buildString {
        append(qualifiedName)
        arguments.toSortedMap().forEach { (name, value) -> append('|').append(name).append('=').append(value) }
    }

    private fun TypeShape.acceptsLength(): Boolean = isString() || isEnumerable
    private fun TypeShape.isString(): Boolean = !isEnumerable && (underlyingTypeName ?: typeName) in STRING_TYPES
    private fun TypeShape.isNumeric(): Boolean = !isEnumerable && (underlyingTypeName ?: typeName) in NUMERIC_TYPES
    private fun TypeShape.canValidateRecursively(): Boolean {
        if (underlyingTypeName != null) return true
        val declaration = valueType.declaration as? KSClassDeclaration ?: return false
        val name = declaration.qualifiedName?.asString() ?: return false
        return name !in TERMINAL_TYPES && declaration.classKind == ClassKind.CLASS &&
            Modifier.ABSTRACT !in declaration.modifiers && Modifier.OPEN !in declaration.modifiers &&
            Modifier.SEALED !in declaration.modifiers && declaration.typeParameters.isEmpty()
    }

    private fun representNumber(source: String): Number? {
        val decimal = source.toBigDecimalOrNull() ?: return null
        if (decimal.scale() <= 0) {
            val integer = decimal.toBigIntegerExact()
            if (integer.abs() > MAX_SAFE_INTEGER) return null
            return integer.toLong()
        }
        val value = decimal.toDouble()
        if (!value.isFinite() || BigDecimal.valueOf(value).compareTo(decimal.stripTrailingZeros()) != 0) return null
        return value
    }

    private fun isRepresentablePattern(pattern: String): Boolean {
        try {
            java.util.regex.Pattern.compile(pattern)
        } catch (_: PatternSyntaxException) {
            return false
        }
        if (UNSUPPORTED_PATTERN_FRAGMENTS.any(pattern::contains)) return false
        if (UNSUPPORTED_INLINE_FLAGS.containsMatchIn(pattern)) return false
        if (UNSUPPORTED_POSSESSIVE_QUANTIFIERS.containsMatchIn(pattern)) return false
        return true
    }

    private data class Boundary(val value: BigDecimal, val inclusive: Boolean)

    private companion object {
        const val VALID = "jakarta.validation.Valid"
        const val NOT_NULL = "jakarta.validation.constraints.NotNull"
        const val NOT_BLANK = "jakarta.validation.constraints.NotBlank"
        const val NOT_EMPTY = "jakarta.validation.constraints.NotEmpty"
        const val SIZE = "jakarta.validation.constraints.Size"
        const val MIN = "jakarta.validation.constraints.Min"
        const val MAX = "jakarta.validation.constraints.Max"
        const val DECIMAL_MIN = "jakarta.validation.constraints.DecimalMin"
        const val DECIMAL_MAX = "jakarta.validation.constraints.DecimalMax"
        const val POSITIVE = "jakarta.validation.constraints.Positive"
        const val POSITIVE_OR_ZERO = "jakarta.validation.constraints.PositiveOrZero"
        const val NEGATIVE = "jakarta.validation.constraints.Negative"
        const val NEGATIVE_OR_ZERO = "jakarta.validation.constraints.NegativeOrZero"
        const val PATTERN = "jakarta.validation.constraints.Pattern"
        const val EMAIL = "jakarta.validation.constraints.Email"
        const val ARC_PHONE = "io.cratis.arc.validation.Phone"
        const val ARC_URL = "io.cratis.arc.validation.Url"
        const val ARC_CREDIT_CARD = "io.cratis.arc.validation.CreditCard"
        const val URL = "org.hibernate.validator.constraints.URL"
        const val CREDIT_CARD_NUMBER = "org.hibernate.validator.constraints.CreditCardNumber"
        val SUPPORTED_ANNOTATIONS = setOf(
            VALID, NOT_NULL, NOT_BLANK, NOT_EMPTY, SIZE, MIN, MAX, DECIMAL_MIN, DECIMAL_MAX,
            POSITIVE, POSITIVE_OR_ZERO, NEGATIVE, NEGATIVE_OR_ZERO, PATTERN, EMAIL,
            ARC_PHONE, ARC_URL, ARC_CREDIT_CARD, URL, CREDIT_CARD_NUMBER
        )
        val RULE_ORDER = listOf(
            "notNull", "notEmpty", "minLength", "maxLength", "length", "emailAddress", "phone", "url",
            "creditCard", "matches", "greaterThan", "greaterThanOrEqual", "lessThan", "lessThanOrEqual"
        )
        val DEFAULT_MESSAGE_KEYS = setOf(
            "{jakarta.validation.constraints.NotNull.message}",
            "{jakarta.validation.constraints.NotBlank.message}",
            "{jakarta.validation.constraints.NotEmpty.message}",
            "{jakarta.validation.constraints.Size.message}",
            "{jakarta.validation.constraints.Min.message}",
            "{jakarta.validation.constraints.Max.message}",
            "{jakarta.validation.constraints.DecimalMin.message}",
            "{jakarta.validation.constraints.DecimalMax.message}",
            "{jakarta.validation.constraints.Positive.message}",
            "{jakarta.validation.constraints.PositiveOrZero.message}",
            "{jakarta.validation.constraints.Negative.message}",
            "{jakarta.validation.constraints.NegativeOrZero.message}",
            "{jakarta.validation.constraints.Pattern.message}",
            "{jakarta.validation.constraints.Email.message}",
            "{io.cratis.arc.validation.Phone.message}",
            "{io.cratis.arc.validation.Url.message}",
            "{io.cratis.arc.validation.CreditCard.message}",
            "{org.hibernate.validator.constraints.URL.message}",
            "{org.hibernate.validator.constraints.CreditCardNumber.message}"
        )
        val STRING_TYPES = setOf("kotlin.String", "java.lang.String")
        val NUMERIC_TYPES = setOf(
            "kotlin.Byte", "kotlin.Double", "kotlin.Float", "kotlin.Int", "kotlin.Long", "kotlin.Short",
            "java.lang.Byte", "java.lang.Double", "java.lang.Float", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "byte", "double", "float", "int", "long", "short", "java.math.BigDecimal",
            "java.math.BigInteger"
        )
        val TERMINAL_TYPES = STRING_TYPES + NUMERIC_TYPES + setOf(
            "kotlin.Boolean", "kotlin.Char", "java.lang.Boolean", "java.lang.Character", "boolean", "char",
            "java.util.UUID", "java.util.Date", "java.time.Duration", "java.time.Instant", "java.time.LocalDate",
            "java.time.LocalDateTime", "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.time.Period", "java.time.Year", "java.time.YearMonth", "java.time.ZonedDateTime"
        )
        val MAX_SAFE_INTEGER = BigDecimal("9007199254740991").toBigIntegerExact()
        val UNSUPPORTED_PATTERN_FRAGMENTS = listOf(
            "\\A", "\\Z", "\\z", "\\G", "\\Q", "\\E", "\\R", "\\X", "(?>", "&&"
        )
        val UNSUPPORTED_INLINE_FLAGS = Regex("\\(\\?[idmsuxU-]+(?::|\\))")
        val UNSUPPORTED_POSSESSIVE_QUANTIFIERS = Regex("(?:[?*+]\\+|\\{\\d+(?:,\\d*)?}\\+)")
    }
}

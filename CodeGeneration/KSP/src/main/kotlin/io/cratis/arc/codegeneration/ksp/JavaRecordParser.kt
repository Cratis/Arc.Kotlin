// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

internal data class JavaRecordProperty(
    val name: String,
    val typeName: String,
    val isNullable: Boolean,
    val isCommandKey: Boolean,
    val validationAnnotations: List<SourceValidationAnnotation>
)

internal fun parseJavaRecordProperties(source: String, recordName: String): List<JavaRecordProperty>? {
    val withoutComments = source
        .replace(Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)), " ")
        .replace(Regex("//[^\\r\\n]*"), " ")
    val declaration = Regex("\\brecord\\s+${Regex.escape(recordName)}\\s*\\(").find(withoutComments) ?: return null
    val openingParenthesis = declaration.range.last
    val closingParenthesis = findMatchingParenthesis(withoutComments, openingParenthesis) ?: return emptyList()
    val header = withoutComments.substring(openingParenthesis + 1, closingParenthesis)
    if (header.isBlank()) return emptyList()
    return splitTopLevel(header).mapNotNull(::parseRecordComponent)
}

private fun parseRecordComponent(component: String): JavaRecordProperty? {
    val annotations = mutableListOf<JavaSourceAnnotation>()
    val declaration = removeAnnotations(component, annotations).trim()
    val nameMatch = Regex("([A-Za-z_$][A-Za-z0-9_$]*)\\s*(\\[\\s*])?$").find(declaration) ?: return null
    val name = nameMatch.groupValues[1]
    val trailingArray = nameMatch.groupValues[2].isNotEmpty()
    var typeName = declaration.substring(0, nameMatch.range.first).trim()
    if (trailingArray) typeName += "[]"
    if (typeName.isEmpty()) return null
    typeName = normalizeJavaTypeName(typeName)
    val simpleAnnotations = annotations.map { annotation -> annotation.name.substringAfterLast('.') }
    return JavaRecordProperty(
        name = name,
        typeName = typeName,
        isNullable = simpleAnnotations.any { annotation -> annotation == "Nullable" || annotation == "CheckForNull" },
        isCommandKey = simpleAnnotations.any { annotation -> annotation == "CommandKey" },
        validationAnnotations = annotations.mapNotNull(JavaSourceAnnotation::toValidationAnnotation)
    )
}

private fun removeAnnotations(value: String, annotations: MutableList<JavaSourceAnnotation>): String = buildString(value.length) {
    var index = 0
    var genericDepth = 0
    while (index < value.length) {
        when (value[index]) {
            '<' -> genericDepth++
            '>' -> genericDepth--
        }
        if (value[index] != '@') {
            append(value[index++])
            continue
        }
        val annotationStart = index
        index++
        val nameStart = index
        while (index < value.length && (value[index].isJavaIdentifierPart() || value[index] == '.')) index++
        if (nameStart == index) {
            append('@')
            continue
        }
        val name = value.substring(nameStart, index)
        while (index < value.length && value[index].isWhitespace()) index++
        val arguments = if (index < value.length && value[index] == '(') {
            val closing = findMatchingParenthesis(value, index) ?: (value.length - 1)
            val source = value.substring(index + 1, closing)
            index = closing + 1
            parseAnnotationArguments(source)
        } else {
            emptyMap()
        }
        if (genericDepth > 0) {
            append(value.substring(annotationStart, index))
        } else {
            annotations.add(JavaSourceAnnotation(name, arguments))
            append(' ')
        }
    }
}

private fun parseAnnotationArguments(source: String): Map<String, Any?> {
    if (source.isBlank()) return emptyMap()
    return splitTopLevel(source).mapIndexed { index, argument ->
        val assignment = findTopLevelAssignment(argument)
        val name = if (assignment < 0) {
            if (index == 0) "value" else "argument$index"
        } else {
            argument.substring(0, assignment).trim()
        }
        val value = argument.substring(if (assignment < 0) 0 else assignment + 1).trim()
        name to parseAnnotationValue(value)
    }.toMap()
}

private fun findTopLevelAssignment(value: String): Int {
    var parentheses = 0
    var braces = 0
    var quote: Char? = null
    var escaped = false
    value.forEachIndexed { index, character ->
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
            '=' -> if (parentheses == 0 && braces == 0) return index
        }
    }
    return -1
}

private fun parseAnnotationValue(value: String): Any? {
    val trimmed = value.trim()
    if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
        return splitTopLevel(trimmed.substring(1, trimmed.length - 1)).map(::parseAnnotationValue)
    }
    if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
        return unescapeJavaString(trimmed.substring(1, trimmed.length - 1))
    }
    if (trimmed == "true") return true
    if (trimmed == "false") return false
    val numeric = trimmed.removeSuffix("L").removeSuffix("l")
    numeric.toLongOrNull()?.let { return it }
    numeric.toDoubleOrNull()?.let { return it }
    return trimmed
}

private fun unescapeJavaString(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (character != '\\' || index == value.length) {
            append(character)
            continue
        }
        when (val escaped = value[index++]) {
            'b' -> append('\b')
            'f' -> append('\u000c')
            'n' -> append('\n')
            'r' -> append('\r')
            't' -> append('\t')
            '\\', '\'', '"' -> append(escaped)
            'u' -> {
                while (index < value.length && value[index] == 'u') index++
                val end = (index + 4).coerceAtMost(value.length)
                val codePoint = value.substring(index, end).toIntOrNull(16)
                if (codePoint == null || end - index != 4) {
                    append("\\u").append(value.substring(index, end))
                } else {
                    append(codePoint.toChar())
                }
                index = end
            }
            else -> append(escaped)
        }
    }
}

private fun JavaSourceAnnotation.toValidationAnnotation(): SourceValidationAnnotation? {
    val simpleName = name.substringAfterLast('.')
    val qualifiedName = when {
        name.startsWith("jakarta.validation.") || name.startsWith("io.cratis.arc.validation.") ||
            name.startsWith("org.hibernate.validator.constraints.") -> name
        name == "Valid" -> "jakarta.validation.Valid"
        simpleName in VALIDATION_CONSTRAINT_NAMES -> "jakarta.validation.constraints.$simpleName"
        simpleName in ARC_VALIDATION_CONSTRAINT_NAMES -> "io.cratis.arc.validation.$simpleName"
        simpleName in HIBERNATE_VALIDATION_CONSTRAINT_NAMES ->
            "org.hibernate.validator.constraints.$simpleName"
        else -> return null
    }
    return SourceValidationAnnotation(qualifiedName, arguments)
}

private fun normalizeJavaTypeName(typeName: String): String {
    val normalized = typeName.replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*<\\s*"), "<")
        .replace(Regex("\\s*>\\s*"), ">")
        .replace(Regex("\\s*,\\s*"), ", ")
        .replace(Regex("\\s*\\[\\s*]"), "[]")
    return when (normalized) {
        "Boolean" -> "java.lang.Boolean"
        "Byte" -> "java.lang.Byte"
        "Character" -> "java.lang.Character"
        "Double" -> "java.lang.Double"
        "Float" -> "java.lang.Float"
        "Integer" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Short" -> "java.lang.Short"
        "String" -> "java.lang.String"
        else -> normalized
    }
}

private fun splitTopLevel(value: String): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    var parentheses = 0
    var angles = 0
    var brackets = 0
    var braces = 0
    var quote: Char? = null
    var escaped = false
    value.forEachIndexed { index, character ->
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == quote) {
                quote = null
            }
            return@forEachIndexed
        }
        when (character) {
            '\'', '"' -> quote = character
            '(' -> parentheses++
            ')' -> parentheses--
            '<' -> angles++
            '>' -> angles--
            '[' -> brackets++
            ']' -> brackets--
            '{' -> braces++
            '}' -> braces--
            ',' -> if (parentheses == 0 && angles == 0 && brackets == 0 && braces == 0) {
                parts.add(value.substring(start, index).trim())
                start = index + 1
            }
        }
    }
    parts.add(value.substring(start).trim())
    return parts.filter(String::isNotBlank)
}

private fun findMatchingParenthesis(value: String, openingIndex: Int): Int? {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    for (index in openingIndex until value.length) {
        val character = value[index]
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else if (character == quote) {
                quote = null
            }
            continue
        }
        when (character) {
            '\'', '"' -> quote = character
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return index
            }
        }
    }
    return null
}

private data class JavaSourceAnnotation(val name: String, val arguments: Map<String, Any?>)

private val VALIDATION_CONSTRAINT_NAMES = setOf(
    "NotNull", "NotBlank", "NotEmpty", "Size", "Min", "Max", "DecimalMin", "DecimalMax",
    "Positive", "PositiveOrZero", "Negative", "NegativeOrZero", "Pattern", "Email"
)
private val ARC_VALIDATION_CONSTRAINT_NAMES = setOf("Phone", "Url", "CreditCard")
private val HIBERNATE_VALIDATION_CONSTRAINT_NAMES = setOf("URL", "CreditCardNumber")

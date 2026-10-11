// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

/** A Java source type name with its type-use nullability annotations stripped. */
internal data class JavaTypeUse(val typeName: String, val isNullable: Boolean)

/** A parameterized Java source type name split into its base name and arguments. */
internal data class JavaGenericType(val baseName: String, val arguments: List<JavaTypeUse>)

private val annotationName = Regex("^@([A-Za-z_$][A-Za-z0-9_$.]*)")

/** Parses `Base<Arg, Arg>` text, or returns null when [sourceTypeName] is not a parameterized type. */
internal fun parseJavaGenericType(sourceTypeName: String): JavaGenericType? {
    val typeUse = parseJavaTypeUse(sourceTypeName)
    val opening = typeUse.typeName.indexOf('<')
    if (opening < 0 || !typeUse.typeName.endsWith('>')) return null
    val baseName = typeUse.typeName.substring(0, opening).trim()
    val body = typeUse.typeName.substring(opening + 1, typeUse.typeName.length - 1)
    return JavaGenericType(baseName, splitTypeArguments(body))
}

private fun splitTypeArguments(body: String): List<JavaTypeUse> {
    val arguments = mutableListOf<JavaTypeUse>()
    var depth = 0
    var start = 0
    body.forEachIndexed { index, character ->
        when (character) {
            '<' -> depth++
            '>' -> depth--
            ',' -> if (depth == 0) {
                arguments += parseJavaTypeUse(body.substring(start, index))
                start = index + 1
            }
        }
    }
    if (body.substring(start).isNotBlank()) arguments += parseJavaTypeUse(body.substring(start))
    return arguments
}

/** Strips leading type-use annotations from [sourceTypeName], remembering whether one marked it nullable. */
internal fun parseJavaTypeUse(sourceTypeName: String): JavaTypeUse {
    var remaining = sourceTypeName.trim()
    var isNullable = false
    while (remaining.startsWith('@')) {
        val name = annotationName.find(remaining) ?: break
        val simpleName = name.groupValues[1].substringAfterLast('.')
        isNullable = isNullable || simpleName == "Nullable" || simpleName == "CheckForNull"
        remaining = remaining.substring(annotationEnd(remaining, name.range.last + 1)).trimStart()
    }
    return JavaTypeUse(remaining.trim(), isNullable)
}

private fun annotationEnd(text: String, nameEnd: Int): Int {
    var end = nameEnd
    while (end < text.length && text[end].isWhitespace()) end++
    if (end >= text.length || text[end] != '(') return end
    return skipAnnotationArguments(text, end + 1)
}

private fun skipAnnotationArguments(text: String, start: Int): Int {
    var depth = 1
    var end = start
    while (end < text.length && depth > 0) {
        when (text[end++]) {
            '(' -> depth++
            ')' -> depth--
        }
    }
    return end
}

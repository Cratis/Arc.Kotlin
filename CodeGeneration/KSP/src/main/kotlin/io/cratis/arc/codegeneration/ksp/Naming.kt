// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import java.security.MessageDigest

internal const val GENERATED_PACKAGE: String = "io.cratis.arc.generated"
internal const val GENERATED_COMMANDS_PACKAGE: String = "$GENERATED_PACKAGE.commands"
internal const val GENERATED_QUERIES_PACKAGE: String = "$GENERATED_PACKAGE.queries"

private val kotlinKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface",
    "is", "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias",
    "typeof", "val", "var", "when", "while", "by", "catch", "constructor", "delegate", "dynamic", "field",
    "file", "finally", "get", "import", "init", "param", "property", "receiver", "set", "setparam", "where",
    "actual", "abstract", "annotation", "companion", "const", "crossinline", "data", "enum", "expect",
    "external", "final", "infix", "inline", "inner", "internal", "lateinit", "noinline", "open", "operator",
    "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg"
)

internal fun validateModuleName(value: String?): String? = value?.takeIf { name ->
    name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) && name !in kotlinKeywords
}

internal fun moduleClassName(moduleName: String): String = "${moduleName}ArcArtifactModule"

internal fun commandHandlerClassName(qualifiedName: String): String =
    "${generatedSimpleName(qualifiedName)}ArcCommandHandler_${identityDigest(qualifiedName)}"

internal fun queryPerformerClassName(fullyQualifiedQueryName: String): String =
    "${generatedSimpleName(fullyQualifiedQueryName)}ArcQueryPerformer_${identityDigest(fullyQualifiedQueryName)}"

private fun generatedSimpleName(qualifiedName: String): String =
    qualifiedName.substringAfterLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")

private fun identityDigest(qualifiedName: String): String = MessageDigest.getInstance("SHA-256")
    .digest(qualifiedName.toByteArray(Charsets.UTF_8))
    .take(6)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun renderQualifiedName(qualifiedName: String): String = qualifiedName
    .split('.')
    .joinToString(".") { segment -> if (segment in kotlinKeywords) "`$segment`" else segment }

internal fun renderClassLiteralType(qualifiedName: String): String = when (qualifiedName) {
    "boolean" -> "kotlin.Boolean"
    "byte" -> "kotlin.Byte"
    "char" -> "kotlin.Char"
    "double" -> "kotlin.Double"
    "float" -> "kotlin.Float"
    "int" -> "kotlin.Int"
    "long" -> "kotlin.Long"
    "short" -> "kotlin.Short"
    else -> renderQualifiedName(qualifiedName)
}

internal fun quote(value: String): String = buildString(value.length + 2) {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

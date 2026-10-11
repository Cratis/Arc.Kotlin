// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.validate

// Shared KSP symbol helpers used by the Arc symbol processing collaborators.

internal fun containsMapType(type: KSType, visiting: MutableSet<String> = mutableSetOf()): Boolean {
    val declarationName = type.declaration.qualifiedName?.asString() ?: return false
    if (declarationName in MAP_TYPE_NAMES) return true
    val identity = "$declarationName:${type.arguments.size}"
    if (!visiting.add(identity)) return false
    return type.arguments.any { argument ->
        argument.type?.resolve()?.let { nested -> containsMapType(nested, visiting) } == true
    }
}

internal fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
    annotationsNamed(qualifiedName).any()

internal fun KSAnnotated.hasNullableAnnotation(): Boolean = annotations.any { annotation ->
    annotation.shortName.asString() == "Nullable"
}

internal fun KSAnnotated.annotationsNamed(qualifiedName: String): Sequence<KSAnnotation> = annotations.filter {
    annotation -> annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
}

internal fun KSAnnotated.roleAnnotations(): List<String> = annotations.flatMap { annotation ->
    when (annotation.annotationType.resolve().declaration.qualifiedName?.asString()) {
        ROLES_ANNOTATION -> sequenceOf(annotation).flatMap { it.stringListArgument("value").asSequence() }
        ROLES_CONTAINER_ANNOTATION -> annotation.annotationListArgument("value").asSequence()
            .flatMap { nested -> nested.stringListArgument("value").asSequence() }
        else -> emptySequence()
    }
}.toList()

internal fun KSAnnotation.stringArgument(name: String): String? =
    arguments.firstOrNull { argument -> argument.name?.asString() == name }?.value as? String

internal fun KSAnnotation.stringListArgument(name: String): List<String> =
    argumentValues(name).filterIsInstance<String>()

internal fun KSAnnotation.annotationListArgument(name: String): List<KSAnnotation> =
    argumentValues(name).filterIsInstance<KSAnnotation>()

internal fun KSAnnotation.argumentValues(name: String): List<*> = when (
    val value = arguments.firstOrNull { argument -> argument.name?.asString() == name }?.value
) {
    is List<*> -> value
    is Array<*> -> value.toList()
    is KSType -> listOf(value) // Java's single-element annotation array shorthand.
    else -> emptyList<Any>()
}

internal fun KSDeclaration.hasCommandKey(): Boolean {
    if (hasAnnotation(COMMAND_KEY_ANNOTATION)) return true
    return (this as? com.google.devtools.ksp.symbol.KSPropertyDeclaration)
        ?.getter
        ?.hasAnnotation(COMMAND_KEY_ANNOTATION) == true
}

internal fun KSAnnotated.enumArgument(annotationName: String, argumentName: String): String? =
    annotationsNamed(annotationName).firstOrNull()
        ?.arguments
        ?.firstOrNull { argument -> argument.name?.asString() == argumentName }
        ?.value
        ?.toString()
        ?.substringAfterLast('.')

internal fun renderKotlinType(type: KSType): String? {
    val qualifiedName = type.declaration.qualifiedName?.asString() ?: return null
    val base = renderRuntimeType(qualifiedName)
    if (type.arguments.isEmpty()) return base + if (type.nullability == Nullability.NULLABLE) "?" else ""
    val arguments = type.arguments.map { argument ->
        val reference = argument.type ?: return null
        renderKotlinType(reference.resolve()) ?: return null
    }
    return "$base<${arguments.joinToString(", ")}>" + if (type.nullability == Nullability.NULLABLE) "?" else ""
}

internal fun renderRuntimeType(qualifiedName: String): String = when (qualifiedName) {
    "java.lang.Boolean", "boolean" -> "kotlin.Boolean"
    "java.lang.Byte", "byte" -> "kotlin.Byte"
    "java.lang.Character", "char" -> "kotlin.Char"
    "java.lang.Double", "double" -> "kotlin.Double"
    "java.lang.Float", "float" -> "kotlin.Float"
    "java.lang.Integer", "int" -> "kotlin.Int"
    "java.lang.Long", "long" -> "kotlin.Long"
    "java.lang.Short", "short" -> "kotlin.Short"
    "java.lang.String" -> "kotlin.String"
    else -> renderQualifiedName(qualifiedName)
}

internal fun isCompletionStage(type: KSType, resolver: Resolver): Boolean {
    val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(COMPLETION_STAGE_TYPE))
        ?: return false
    return declaration.asStarProjectedType().isAssignableFrom(type)
}

/**
 * Validates a symbol the way the deprecated single-predicate [KSNode.validate] overload did, using the new
 * overload that makes the choice explicit: the default always-true predicate and no new validation features.
 * KSP 2.3.12 deprecated the single-predicate form and this repository compiles with warnings as errors.
 */
internal fun KSAnnotated.validateForProcessing(): Boolean = validate({ _, _ -> true }, enableNewFeatures = false)

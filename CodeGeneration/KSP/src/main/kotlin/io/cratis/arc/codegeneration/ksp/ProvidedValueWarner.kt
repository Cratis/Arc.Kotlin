// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance

/** Warns when a value produced by a command's provide method is not consumed by any handle parameter. */
internal class ProvidedValueWarner(private val logger: ArcDiagnosticReporter) {
    fun warn(commandName: String, provide: KSFunctionDeclaration, handler: KSFunctionDeclaration, resolver: Resolver) {
        val producedTypes = producedTypes(provide, resolver) ?: return
        val handlerTypes = handler.parameters.map { parameter -> consumedType(handler, parameter) }
        producedTypes.filter(::isTrackedType).forEach { type ->
            if (handlerTypes.any { handlerType -> handlerType.isAssignableFrom(type) }) return@forEach
            val name = type.declaration.qualifiedName?.asString() ?: type.toString()
            logger.warn(
                ArcDiagnostic.UNUSED_PROVIDED_VALUE,
                "Value of type '$name' produced by '$commandName.$PROVIDE_NAME' is not consumed by any handle parameter.",
                provide
            )
        }
    }

    private fun producedTypes(provide: KSFunctionDeclaration, resolver: Resolver): List<KSType>? {
        var providedType = provide.returnType?.resolve() ?: return null
        if (providedType.isError) return null
        if (isCompletionStage(providedType, resolver)) {
            providedType = providedType.arguments.singleOrNull()?.type?.resolve() ?: return null
        }
        val providedName = providedType.declaration.qualifiedName?.asString() ?: return null
        return if (providedName == "kotlin.Pair" || providedName == "kotlin.Triple") {
            providedType.arguments.mapNotNull { argument -> argument.type?.resolve() }
        } else {
            listOf(providedType)
        }
    }

    private fun consumedType(handler: KSFunctionDeclaration, parameter: KSValueParameter): KSType {
        val handlerType = parameter.type.resolve()
        val isJavaOptional = handler.origin == Origin.JAVA &&
            handlerType.declaration.qualifiedName?.asString() == JAVA_OPTIONAL_TYPE
        if (!isJavaOptional) return handlerType
        return handlerType.arguments.singleOrNull()
            ?.takeIf { argument -> argument.variance == Variance.INVARIANT }
            ?.type
            ?.resolve()
            ?: handlerType
    }

    private fun isTrackedType(type: KSType): Boolean {
        val name = type.declaration.qualifiedName?.asString()
        return name !in PROVIDE_CONTROL_TYPES && name !in PROVIDE_DYNAMIC_TYPES && name !in VOID_TYPE_NAMES
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter

/** The outcome of resolving the value type that a `ConceptAs<T>` concept wraps. */
internal data class ConceptValueResolution(val isValid: Boolean, val value: KSType?)

/** True when this class has [baseName] among its transitive supertypes. */
internal fun KSClassDeclaration.isAssignableTo(baseName: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
    val ownName = qualifiedName?.asString() ?: return false
    if (!seen.add(ownName)) return false
    return superTypes.map { reference -> reference.resolve().declaration as? KSClassDeclaration }
        .filterNotNull()
        .any { superDeclaration ->
            superDeclaration.qualifiedName?.asString() == baseName || superDeclaration.isAssignableTo(baseName, seen)
        }
}

/** Resolves the scalar value type behind concept declarations. */
internal class ConceptResolver(private val logger: ArcDiagnosticReporter) {
    fun resolve(declaration: KSClassDeclaration, identity: String, node: KSNode): ConceptValueResolution {
        if (!declaration.isAssignableTo(CONCEPT_AS_TYPE)) return ConceptValueResolution(true, null)
        val value = findValue(declaration)
        val valueDeclaration = value?.declaration as? KSClassDeclaration
        val valueName = valueDeclaration?.qualifiedName?.asString()
        if (value == null || value.isError || value.declaration is KSTypeParameter || valueName == null) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Concept '$identity' implements ConceptAs<T>, but its underlying generic type cannot be resolved.",
                node
            )
            return ConceptValueResolution(false, null)
        }
        if (value.arguments.isNotEmpty() || isNonScalar(valueName)) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Concept artifact/property '$identity' value path 'conceptValue' has unsupported non-scalar underlying type '$valueName'.",
                node
            )
            return ConceptValueResolution(false, null)
        }
        if (valueDeclaration.isAssignableTo(CONCEPT_AS_TYPE)) return resolve(valueDeclaration, identity, node)
        return ConceptValueResolution(true, value)
    }

    private fun isNonScalar(typeName: String): Boolean =
        typeName in MAP_TYPE_NAMES || typeName in COLLECTION_TYPE_NAMES || typeName == ARRAY_TYPE

    fun findValue(
        declaration: KSClassDeclaration,
        substitutions: Map<String, KSType> = emptyMap(),
        seen: MutableSet<String> = mutableSetOf()
    ): KSType? {
        val declarationName = declaration.qualifiedName?.asString() ?: return null
        if (!seen.add(declarationName)) return null
        for (reference in declaration.superTypes) {
            val superType = reference.resolve()
            val superDeclaration = superType.declaration as? KSClassDeclaration ?: continue
            if (superDeclaration.qualifiedName?.asString() == CONCEPT_AS_TYPE) {
                val argument = superType.arguments.singleOrNull()?.type?.resolve() ?: return null
                return substitute(argument, substitutions)
            }
            val superSubstitutions = substitutionsFor(superDeclaration, superType, substitutions)
            findValue(superDeclaration, superSubstitutions, seen)?.let { return it }
        }
        return null
    }

    private fun substitutionsFor(
        superDeclaration: KSClassDeclaration,
        superType: KSType,
        substitutions: Map<String, KSType>
    ): Map<String, KSType> = superDeclaration.typeParameters.mapIndexedNotNull { index, parameter ->
        val argument = superType.arguments.getOrNull(index)?.type?.resolve() ?: return@mapIndexedNotNull null
        parameter.name.asString() to substitute(argument, substitutions)
    }.toMap()

    private fun substitute(type: KSType, substitutions: Map<String, KSType>): KSType =
        (type.declaration as? KSTypeParameter)?.name?.asString()?.let(substitutions::get) ?: type
}

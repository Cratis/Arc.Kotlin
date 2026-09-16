// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate

/** Declaration-only discovery. Instances, method bodies and dependency classpaths are never scanned. */
internal class IdentityDetailsDiscovery(
    private val logger: ArcDiagnosticReporter,
    private val collect: (KSClassDeclaration) -> Boolean
) {
    private val deferred = mutableListOf<KSAnnotated>()

    fun discover(declarations: Sequence<KSDeclaration>): List<KSAnnotated> {
        declarations.forEach(::visit)
        return deferred.distinct()
    }

    private fun visit(declaration: KSDeclaration) {
        if (!declaration.isPublic()) return
        when (declaration) {
            is KSClassDeclaration -> {
                if (declaration.classKind in setOf(ClassKind.CLASS, ClassKind.OBJECT) &&
                    !declaration.isAbstractClass()) {
                    inspect(declaration.asStarProjectedType(), declaration)
                }
                declaration.declarations.forEach(::visit)
            }
            is KSFunctionDeclaration -> {
                if (declaration.simpleName.asString() != "<init>") {
                    declaration.returnType?.resolve()?.let { inspect(it, declaration) }
                }
            }
        }
    }

    private fun inspect(type: KSType, site: KSDeclaration) {
        fun walk(current: KSType, bindings: Map<KSTypeParameter, KSType?>, path: Set<String>, unboundReturn: Boolean = false) {
            if (current.isError) {
                deferred += site
                return
            }
            val parameter = current.declaration as? KSTypeParameter
            if (parameter != null) {
                val key = "parameter:${parameter.qualifiedName?.asString() ?: parameter.name.asString()}"
                if (key !in path) parameter.bounds.forEach { walk(it.resolve(), bindings, path + key, unboundReturn = true) }
                return
            }
            val declaration = current.declaration as? KSClassDeclaration ?: return
            val name = declaration.qualifiedName?.asString() ?: return
            if (name in path) return
            val arguments = declaration.typeParameters.mapIndexed { index, parameter ->
                val argument = current.arguments.getOrNull(index)
                val resolved = argument?.takeIf { it.variance == Variance.INVARIANT }?.type?.resolve()
                parameter to if (resolved?.declaration is KSTypeParameter) bindings[resolved.declaration] else resolved
            }.toMap()
            if (name in PROVIDERS) {
                val details = arguments.values.singleOrNull()
                if (details?.isError == true) {
                    deferred += site
                    return
                }
                val dto = details?.declaration as? KSClassDeclaration
                val dtoName = dto?.qualifiedName?.asString()
                if (unboundReturn || dto == null || dtoName in setOf("kotlin.Any", "java.lang.Object") ||
                    details.nullability == Nullability.NULLABLE || dto.typeParameters.isNotEmpty() ||
                    dto.parentDeclaration != null || !dto.isPublic() ||
                    dto.classKind != ClassKind.CLASS || dto.isAbstractClass() ||
                    dtoName?.startsWith("kotlin.") == true || dtoName?.startsWith("java.") == true) {
                    invalid(site, "has no supported concrete public top-level details class")
                } else if (!dto.validate({ _, _ -> true }, enableNewFeatures = false)) {
                    deferred += site
                } else if (!collect(dto)) {
                    invalid(site, "has an unsupported details graph '$dtoName'")
                }
                return
            }
            declaration.superTypes.forEach { walk(it.resolve(), arguments, path + name, unboundReturn) }
        }
        walk(type, emptyMap(), emptySet())
    }

    // Kotlin sealed classes are implicitly abstract, but KSP2 omits ABSTRACT; Java sealed classes may be concrete.
    private fun KSClassDeclaration.isAbstractClass(): Boolean =
        Modifier.ABSTRACT in modifiers ||
            (Modifier.SEALED in modifiers && (origin == Origin.KOTLIN || origin == Origin.KOTLIN_LIB))

    private fun invalid(site: KSDeclaration, reason: String) {
        logger.error(
            ArcDiagnostic.IDENTITY_DETAILS,
            "Identity provider declaration '${site.qualifiedName?.asString() ?: site.simpleName.asString()}' $reason; " +
                "bind IdentityDetailsProvider<T> or AsyncIdentityDetailsProvider<T> to a supported details DTO " +
                "instead of an erased, star-projected or unspecialized type.",
            site
        )
    }

    private companion object {
        val PROVIDERS = setOf("io.cratis.arc.identity.IdentityDetailsProvider", "io.cratis.arc.identity.AsyncIdentityDetailsProvider")
    }
}

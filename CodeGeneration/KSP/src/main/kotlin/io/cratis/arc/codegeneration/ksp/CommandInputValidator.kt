// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Origin
import java.io.File

/** True for scalar and platform value types that need no further model collection. */
internal fun isTerminal(qualifiedName: String): Boolean = qualifiedName in TERMINAL_TYPE_NAMES

/** Validates that every type reachable from a command's input is writable on the wire. */
internal class CommandInputValidator(
    private val logger: ArcDiagnosticReporter,
    resolver: Resolver?,
    private val derivativesOf: (KSClassDeclaration) -> List<KSClassDeclaration>
) {
    private val wireValidator = WireAnnotationValidator(logger)
    private val javaTypes = JavaSourceTypeDescriber(logger, resolver)

    /** Output-only computed getters remain valid; a command's reachable input graph must be writable. */
    fun validate(declaration: KSClassDeclaration, visited: MutableSet<String> = mutableSetOf()): Boolean {
        val name = declaration.qualifiedName?.asString() ?: return true
        if (!visited.add(name) || isExempt(declaration, name)) return true
        var valid = validateProperties(declaration, name, visited)
        if (declaration.origin == Origin.JAVA && !validateJava(declaration, name, visited)) valid = false
        if (!validateRelated(declaration, visited)) valid = false
        return valid
    }

    private fun validateProperties(declaration: KSClassDeclaration, name: String, visited: MutableSet<String>): Boolean {
        var valid = true
        for (property in orderedDeclaredProperties(declaration)) {
            if (isSkipped(declaration, property)) continue
            if (!validateProperty(declaration, name, property, visited)) valid = false
        }
        return valid
    }

    private fun validateRelated(declaration: KSClassDeclaration, visited: MutableSet<String>): Boolean {
        var valid = true
        for (related in declaration.superTypes.mapNotNull { it.resolve().declaration as? KSClassDeclaration } +
            derivativesOf(declaration).asSequence()) {
            if (!validate(related, visited)) valid = false
        }
        return valid
    }

    private fun isExempt(declaration: KSClassDeclaration, name: String): Boolean =
        isTerminal(name) || name in ROOT_TYPE_NAMES || name in MAP_TYPE_NAMES ||
            declaration.classKind == ClassKind.ENUM_CLASS || declaration.isAssignableTo(CONCEPT_AS_TYPE)

    private fun isSkipped(declaration: KSClassDeclaration, property: KSPropertyDeclaration): Boolean =
        (!property.isPublic() && declaration.origin != Origin.JAVA) || property.isWireIgnored()

    private fun validateProperty(
        declaration: KSClassDeclaration,
        name: String,
        property: KSPropertyDeclaration,
        visited: MutableSet<String>
    ): Boolean {
        var valid = wireValidator.validateWritableInput(declaration, name, property)
        if (!validateType(property.type.resolve(), visited)) valid = false
        return valid
    }

    private fun validateJava(declaration: KSClassDeclaration, name: String, visited: MutableSet<String>): Boolean {
        // Resolved members retain imported type identities. Parse source types only when KSP exposes no usable member.
        val source = declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
        val record = source?.let { parseJavaRecordProperties(it, declaration.simpleName.asString()) }
        if (record != null) return validateRecordComponents(declaration, name, record, visited)
        if (declaration.classKind == ClassKind.INTERFACE) return validateInterfaceReturnTypes(declaration, visited)
        return true
    }

    private fun validateRecordComponents(
        declaration: KSClassDeclaration,
        name: String,
        record: List<JavaRecordProperty>,
        visited: MutableSet<String>
    ): Boolean {
        var valid = true
        val properties = declaration.getDeclaredProperties().associateBy { it.simpleName.asString() }
        val accessors = declaration.getDeclaredFunctions().filter { it.parameters.isEmpty() }
            .associateBy { it.simpleName.asString() }
        for (component in record) {
            val type = properties[component.name]?.type?.resolve()?.takeUnless { it.isError }
                ?: accessors[component.name]?.returnType?.resolve()?.takeUnless { it.isError }
                ?: javaTypes.describe(
                    component.typeName,
                    declaration,
                    "$name.${component.name}",
                    propertyUseNode(declaration, component.name)
                )?.valueType
            if (type == null || !validateType(type, visited)) valid = false
        }
        return valid
    }

    private fun validateInterfaceReturnTypes(declaration: KSClassDeclaration, visited: MutableSet<String>): Boolean {
        var valid = true
        for (function in declaration.getDeclaredFunctions().filter { it.parameters.isEmpty() }) {
            val type = function.returnType?.resolve() ?: continue
            if (!validateType(type, visited)) valid = false
        }
        return valid
    }

    private fun validateType(type: KSType, visited: MutableSet<String>): Boolean {
        val valueType = if (type.declaration.qualifiedName?.asString() in COLLECTION_TYPE_NAMES + ARRAY_TYPE) {
            type.arguments.singleOrNull()?.type?.resolve()
        } else type
        val child = valueType?.declaration as? KSClassDeclaration ?: return true
        return validate(child, visited)
    }
}

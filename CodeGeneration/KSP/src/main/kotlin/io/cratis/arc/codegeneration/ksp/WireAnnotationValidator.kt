// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import io.cratis.arc.json.ArcCamelCase

private val symmetricAccess = setOf("AUTO", "READ_ONLY", "READ_WRITE")

/** The annotations of a property and its accessors that influence its wire representation. */
internal fun KSPropertyDeclaration.wireAnnotations(): List<KSAnnotation> =
    listOfNotNull(this, getter, setter).flatMap { it.annotations.toList() }

internal fun KSPropertyDeclaration.isWireIgnored(): Boolean = wireAnnotations().any { annotation ->
    annotation.annotationType.resolve().declaration.qualifiedName?.asString() == JSON_IGNORE_ANNOTATION &&
        annotation.arguments.firstOrNull { it.name?.asString() == "value" }?.value != false
}

private fun KSAnnotation.argumentNamed(name: String): Any? = arguments.firstOrNull { it.name?.asString() == name }?.value

private fun KSAnnotation.isNamed(qualifiedName: String): Boolean =
    annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName

/** Validates Jackson wire annotations that metadata cannot represent faithfully. */
internal class WireAnnotationValidator(private val logger: ArcDiagnosticReporter) {
    /** The descriptor has a source name, not independent read/write names or access flags. */
    fun validateBodyWireAnnotations(
        property: KSPropertyDeclaration,
        constructorParameter: KSValueParameter?,
        identity: String
    ): Boolean {
        if (property.origin != Origin.KOTLIN) return true
        val isBodyProperty = constructorParameter?.let { it.isVal || it.isVar } != true
        val annotations = property.wireAnnotations().filter { it.isNamed(JSON_PROPERTY_ANNOTATION) }
        for (annotation in annotations) {
            if (isRepresentable(property, annotation, isBodyProperty)) continue
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Artifact/property '$identity' has body-property Jackson names or access that metadata cannot represent; " +
                    "use the default Arc wire name and symmetric access, or a separate wire model.",
                property
            )
            return false
        }
        return true
    }

    private fun isRepresentable(property: KSPropertyDeclaration, annotation: KSAnnotation, isBodyProperty: Boolean): Boolean {
        if (property.isWireIgnored()) return false
        if (!isBodyProperty) return true
        val name = annotation.argumentNamed("value") as? String
        val access = annotation.argumentNamed("access")?.toString()
        val renamed = !name.isNullOrEmpty() && name != ArcCamelCase.convert(property.simpleName.asString())
        val asymmetric = access != null && access.substringAfterLast('.') !in symmetricAccess
        return !renamed && !asymmetric
    }

    /** Output-only computed getters remain valid; a command's reachable input graph must be writable. */
    fun validateWritableInput(declaration: KSClassDeclaration, name: String, property: KSPropertyDeclaration): Boolean {
        var valid = true
        if (property.origin == Origin.KOTLIN && isReadOnly(property)) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Artifact/property '$name.${property.simpleName.asString()}' is read-only command input; " +
                    "use symmetric Jackson access, @JsonIgnore, or a separate output model.",
                property
            )
            valid = false
        }
        if (property.origin == Origin.KOTLIN && isComputed(declaration, property)) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Artifact/property '$name.${property.simpleName.asString()}' is computed command input; " +
                    "use a backed property, @JsonIgnore, or a separate output model.",
                property
            )
            valid = false
        }
        return valid
    }

    private fun isReadOnly(property: KSPropertyDeclaration): Boolean = property.wireAnnotations().any { annotation ->
        annotation.isNamed(JSON_PROPERTY_ANNOTATION) &&
            annotation.argumentNamed("access")?.toString()?.substringAfterLast('.') == "READ_ONLY"
    }

    private fun isComputed(declaration: KSClassDeclaration, property: KSPropertyDeclaration): Boolean =
        !property.hasBackingField && Modifier.ABSTRACT !in property.modifiers &&
            declaration.classKind != ClassKind.INTERFACE
}

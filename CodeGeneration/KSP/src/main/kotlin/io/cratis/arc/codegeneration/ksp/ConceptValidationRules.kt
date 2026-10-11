// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.Origin
import io.cratis.arc.metadata.TypeShapeDescriptor
import java.io.File

/** Collects the validation rules a concept carries on its own and inherited `ConceptAs` declarations. */
internal class ConceptValidationRules(
    private val logger: ArcDiagnosticReporter,
    private val validationExtractor: ValidationMetadataExtractor,
    private val resolver: Resolver?,
    private val concepts: ConceptResolver
) {
    fun extract(shape: TypeShape, identity: String, node: KSNode): List<ValidationRuleModel>? {
        val underlying = shape.underlyingValueType ?: return emptyList()
        val declaration = shape.valueType.declaration as? KSClassDeclaration ?: return emptyList()
        val underlyingName = requireNotNull(underlying.declaration.qualifiedName).asString()
        val effectiveShape = TypeShape(
            typeName = underlyingName,
            descriptor = TypeShapeDescriptor.value(underlyingName),
            valueType = underlying
        )
        return collect(declaration, effectiveShape, identity, node, mutableSetOf())
    }

    private fun collect(
        declaration: KSClassDeclaration,
        effectiveShape: TypeShape,
        identity: String,
        node: KSNode,
        visited: MutableSet<String>
    ): List<ValidationRuleModel>? {
        val declarationName = declaration.qualifiedName?.asString() ?: return emptyList()
        if (!visited.add(declarationName)) return emptyList()
        val rules = rulesFor(declaration, effectiveShape, identity, node)?.toMutableList() ?: return null
        for (superDeclaration in conceptSupertypes(declaration)) {
            rules += collect(superDeclaration, effectiveShape, identity, node, visited) ?: return null
        }
        val carriedConcept = concepts.findValue(declaration)?.declaration as? KSClassDeclaration
        if (carriedConcept != null && carriedConcept.isAssignableTo(CONCEPT_AS_TYPE)) {
            rules += collect(carriedConcept, effectiveShape, identity, node, visited) ?: return null
        }
        return rules
    }

    private fun conceptSupertypes(declaration: KSClassDeclaration): List<KSClassDeclaration> =
        declaration.superTypes.map { reference -> reference.resolve().declaration }
            .filterIsInstance<KSClassDeclaration>()
            .filter { candidate ->
                candidate.qualifiedName?.asString() != CONCEPT_AS_TYPE && candidate.isAssignableTo(CONCEPT_AS_TYPE)
            }
            .sortedBy { candidate -> candidate.qualifiedName?.asString() }
            .toList()

    private fun rulesFor(
        declaration: KSClassDeclaration,
        effectiveShape: TypeShape,
        identity: String,
        node: KSNode
    ): List<ValidationRuleModel>? {
        val constructorParameter = declaration.primaryConstructor?.parameters?.singleOrNull()
        val constructorName = constructorParameter?.name?.asString()
        val properties = declaration.getDeclaredProperties().filter { property ->
            constructorName == null || property.simpleName.asString() == constructorName
        }.toList()
        val accessors = declaration.getDeclaredFunctions().filter { function ->
            function.parameters.isEmpty() && function.simpleName.asString() in accessorNames(constructorName)
        }.toList()
        val sourceAnnotations = javaSourceAnnotations(declaration)
        val ignored = sourceAnnotations.any { it.qualifiedName == IgnoreValidationPolicy.ANNOTATION } ||
            properties.any { IgnoreValidationPolicy.isIgnored(listOfNotNull(it, it.getter), it, resolver, logger) }
        if (ignored) return emptyList()
        return validationExtractor.extract(
            listOfNotNull(constructorParameter) + properties + accessors,
            effectiveShape,
            "$identity concept value",
            node,
            sourceAnnotations
        )?.rules
    }

    private fun accessorNames(constructorName: String?): Set<String> = setOf(
        "value",
        "getValue",
        constructorName.orEmpty(),
        constructorName?.let { name -> "get${name.replaceFirstChar(Char::uppercase)}" }.orEmpty()
    )

    private fun javaSourceAnnotations(declaration: KSClassDeclaration): List<SourceValidationAnnotation> {
        if (declaration.origin != Origin.JAVA) return emptyList()
        return declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
            ?.let { source -> parseJavaRecordProperties(source, declaration.simpleName.asString()) }
            ?.singleOrNull()
            ?.validationAnnotations
            .orEmpty()
    }
}

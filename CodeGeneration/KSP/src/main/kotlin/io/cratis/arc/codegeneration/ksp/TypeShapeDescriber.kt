// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import io.cratis.arc.metadata.TypeShapeDescriptor

/** Describes the shape of a Kotlin or Java type used by an artifact property, parameter or return value. */
internal class TypeShapeDescriber(
    private val logger: ArcDiagnosticReporter,
    private val concepts: ConceptResolver,
    private val registerConcept: (KSClassDeclaration, KSType) -> Unit
) {
    private val mapShapes = MapShapeDescriber(logger)

    fun describe(type: KSType, identity: String, node: KSNode, allowMaps: Boolean): TypeShape? {
        val declaration = resolveDeclaration(type, identity, node) ?: return null
        val qualifiedName = requireNotNull(declaration.qualifiedName).asString()
        if (qualifiedName in MAP_TYPE_NAMES) return describeMap(type, qualifiedName, ShapeSite(identity, "value", node), allowMaps)
        if (qualifiedName in COLLECTION_TYPE_NAMES || qualifiedName == ARRAY_TYPE) {
            return describeCollection(type, qualifiedName, identity, node)
        }
        if (type.arguments.isNotEmpty()) {
            logger.error("'$identity' uses unsupported generic type '$qualifiedName'.", node)
            return null
        }
        val conceptValue = resolveConcept(declaration, identity, node) ?: return null
        return TypeShape(
            typeName = qualifiedName,
            descriptor = TypeShapeDescriptor.value(qualifiedName, type.nullability == Nullability.NULLABLE),
            valueType = type,
            underlyingTypeName = conceptValue.value?.declaration?.qualifiedName?.asString(),
            underlyingValueType = conceptValue.value
        )
    }

    private fun resolveDeclaration(type: KSType, identity: String, node: KSNode): KSClassDeclaration? {
        if (type.isError || type.declaration is KSTypeParameter) {
            logger.error("'$identity' has an unresolvable or generic type.", node)
            return null
        }
        val declaration = type.declaration as? KSClassDeclaration
        if (declaration == null || declaration.qualifiedName == null) {
            logger.error("'$identity' must use a resolvable class type.", node)
            return null
        }
        return declaration
    }

    /** Resolves and registers the concept behind [declaration], or returns null when the concept is invalid. */
    private fun resolveConcept(declaration: KSClassDeclaration, identity: String, node: KSNode): ConceptValueResolution? {
        val resolution = concepts.resolve(declaration, identity, node)
        if (!resolution.isValid) return null
        resolution.value?.let { underlying -> registerConcept(declaration, underlying) }
        return resolution
    }

    private fun describeMap(type: KSType, qualifiedName: String, site: ShapeSite, allowMaps: Boolean): TypeShape? {
        if (!allowMaps) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Artifact/property '${site.identity}' value path 'value' uses map type '$qualifiedName'; maps are supported only for command, model, interface, and read-model properties.",
                site.node
            )
            return null
        }
        val descriptor = mapShapes.describeMap(type, site) ?: return null
        return TypeShape(typeName = descriptorTypeName(descriptor), descriptor = descriptor, valueType = type)
    }

    private fun describeCollection(type: KSType, qualifiedName: String, identity: String, node: KSNode): TypeShape? {
        val element = mapShapes.concreteTypeArguments(type, 1, ShapeSite(identity, "element", node))?.singleOrNull()
            ?: return null
        if (!validateElementNullability(type, element, identity, node)) return null
        val elementDeclaration = element.declaration as? KSClassDeclaration
        val elementName = elementDeclaration?.qualifiedName?.asString()
        if (elementDeclaration == null || elementName == null) {
            logger.error("'$identity' has an unnamed collection element type.", node)
            return null
        }
        if (isNestedElement(element, elementName)) {
            logger.error("'$identity' uses an unsupported nested or generic collection element shape.", node)
            return null
        }
        val conceptValue = resolveConcept(elementDeclaration, "$identity element", node) ?: return null
        val descriptor = TypeShapeDescriptor.sequence(
            sequenceKind(qualifiedName),
            TypeShapeDescriptor.value(elementName, element.nullability == Nullability.NULLABLE),
            type.nullability == Nullability.NULLABLE
        )
        return TypeShape(
            typeName = "$qualifiedName<$elementName>",
            descriptor = descriptor,
            valueType = element,
            underlyingTypeName = conceptValue.value?.declaration?.qualifiedName?.asString(),
            underlyingValueType = conceptValue.value
        )
    }

    private fun validateElementNullability(type: KSType, element: KSType, identity: String, node: KSNode): Boolean {
        if (node is KSValueParameter && element.nullability == Nullability.NULLABLE) {
            mapShapes.unsupported(ShapeSite(identity, "element", node), "nullable sequence elements are unsupported")
            return false
        }
        if (isNullableElement(type, element)) {
            mapShapes.unsupportedSequenceElement(identity, node)
            return false
        }
        return true
    }

    private fun isNullableElement(type: KSType, element: KSType): Boolean =
        element.nullability == Nullability.NULLABLE || element.annotations.hasNullableTypeUse() ||
            type.arguments.single().type?.annotations?.hasNullableTypeUse() == true

    private fun isNestedElement(element: KSType, elementName: String): Boolean =
        elementName in MAP_TYPE_NAMES || elementName in COLLECTION_TYPE_NAMES || elementName == ARRAY_TYPE ||
            element.arguments.isNotEmpty()
}

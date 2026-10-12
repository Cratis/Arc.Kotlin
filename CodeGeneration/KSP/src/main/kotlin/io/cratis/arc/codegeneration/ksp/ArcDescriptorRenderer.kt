// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind

/** Renders Kotlin source fragments that construct descriptors for generated artifact modules. */
internal object ArcDescriptorRenderer {
    private const val METADATA_PACKAGE = "io.cratis.arc.metadata"

    private val boxedPrimitiveTypes = mapOf(
        "java.lang.Boolean" to "kotlin.Boolean",
        "java.lang.Byte" to "kotlin.Byte",
        "java.lang.Character" to "kotlin.Char",
        "java.lang.Short" to "kotlin.Short",
        "java.lang.Integer" to "kotlin.Int",
        "java.lang.Long" to "kotlin.Long",
        "java.lang.Float" to "kotlin.Float",
        "java.lang.Double" to "kotlin.Double"
    )

    private val primitiveArrayElements = mapOf(
        "Z" to "boolean",
        "B" to "byte",
        "C" to "char",
        "S" to "short",
        "I" to "int",
        "J" to "long",
        "F" to "float",
        "D" to "double"
    )

    private val collectionTypeMappings = mapOf(
        "java.lang.String" to "kotlin.String",
        "java.util.List" to "kotlin.collections.List",
        "java.util.Set" to "kotlin.collections.Set",
        "java.util.Collection" to "kotlin.collections.Collection",
        "java.util.Map" to "kotlin.collections.Map"
    )

    fun renderTypeDescriptor(type: TypeModel): String {
        val properties = renderProperties(type.properties)
        val location = renderLocationSegments(type.location)
        val baseTypeName = quoteOrNull(type.baseTypeName)
        val derivedTypeId = quoteOrNull(type.derivedTypeId)
        return "$METADATA_PACKAGE.TypeDescriptor(" +
            "name = ${quote(type.name)}, fullyQualifiedName = ${quote(type.fullyQualifiedName)}, " +
            "location = listOf($location), properties = $properties, baseTypeName = $baseTypeName, " +
            "derivedTypeId = $derivedTypeId, summary = ${quoteOrNull(type.summary)})"
    }

    fun renderDerivedTypeRegistration(registration: DerivedTypeRegistrationModel): String =
        "io.cratis.arc.polymorphism.DerivedTypeRegistration(" +
            "${registration.baseTypeName}::class.java, ${registration.derivedTypeName}::class.java)"

    fun renderInterfaceDescriptor(interfaceModel: InterfaceModel): String {
        val properties = renderProperties(interfaceModel.properties)
        val location = renderLocationSegments(interfaceModel.location)
        return "$METADATA_PACKAGE.InterfaceDescriptor(" +
            "name = ${quote(interfaceModel.name)}, fullyQualifiedName = ${quote(interfaceModel.fullyQualifiedName)}, " +
            "location = listOf($location), properties = $properties, " +
            "summary = ${quoteOrNull(interfaceModel.summary)})"
    }

    fun renderConceptDescriptor(concept: ConceptModel): String =
        "$METADATA_PACKAGE.ConceptDescriptor(${quote(concept.name)}, ${quote(concept.fullyQualifiedName)}, " +
            "${quote(concept.underlyingTypeName)})"

    fun renderEnumDescriptor(enum: EnumModel): String {
        val location = renderLocationSegments(enum.location)
        val members = if (enum.members.isEmpty()) {
            "emptyList()"
        } else {
            enum.members.joinToString(", ", "listOf(", ")") { member ->
                "$METADATA_PACKAGE.EnumMemberDescriptor(${quote(member.name)}, ${member.value})"
            }
        }
        return "$METADATA_PACKAGE.EnumDescriptor(" +
            "name = ${quote(enum.name)}, fullyQualifiedName = ${quote(enum.fullyQualifiedName)}, " +
            "location = listOf($location), members = $members, isFlags = ${enum.isFlags}, " +
            "summary = ${quoteOrNull(enum.summary)})"
    }

    fun renderProperties(properties: List<PropertyModel>): String = if (properties.isEmpty()) {
        "emptyList()"
    } else {
        properties.joinToString(", ", "listOf(", ")", transform = ::renderProperty)
    }

    private fun renderProperty(property: PropertyModel): String {
        val derivatives = property.derivatives.joinToString(", ") { derivative -> quote(derivative) }
        return "$METADATA_PACKAGE.PropertyDescriptor(" +
            "name = ${quote(property.name)}, shape = ${renderTypeShape(property.shape)}, " +
            "isCommandKey = ${property.isCommandKey}, " +
            "validationRules = ${renderValidationRules(property.validationRules)}, " +
            "validateRecursively = ${property.validateRecursively}, derivatives = listOf($derivatives), " +
            "summary = ${quoteOrNull(property.summary)}, ignoreValidation = ${property.ignoreValidation})"
    }

    fun renderTypeShape(shape: TypeShapeDescriptor): String = when (shape.kind) {
        TypeShapeKind.VALUE -> "$METADATA_PACKAGE.TypeShapeDescriptor.value(" +
            "typeName = ${quote(requireNotNull(shape.typeName))}, nullable = ${shape.nullable})"
        TypeShapeKind.SEQUENCE -> renderSequenceShape(shape)
        TypeShapeKind.MAP -> renderMapShape(shape)
    }

    private fun renderSequenceShape(shape: TypeShapeDescriptor): String =
        "$METADATA_PACKAGE.TypeShapeDescriptor.sequence(" +
            "sequenceKind = $METADATA_PACKAGE.SequenceKind.${requireNotNull(shape.sequenceKind).name}, " +
            "elementShape = ${renderTypeShape(requireNotNull(shape.elementShape))}, nullable = ${shape.nullable})"

    private fun renderMapShape(shape: TypeShapeDescriptor): String =
        "$METADATA_PACKAGE.TypeShapeDescriptor.map(" +
            "keyShape = ${renderTypeShape(requireNotNull(shape.keyShape))}, " +
            "valueShape = ${renderTypeShape(requireNotNull(shape.valueShape))}, " +
            "keyCodec = $METADATA_PACKAGE.MapKeyCodec.${requireNotNull(shape.keyCodec).name}, " +
            "nullable = ${shape.nullable})"

    fun renderValidationRules(rules: List<ValidationRuleModel>): String = if (rules.isEmpty()) {
        "emptyList()"
    } else {
        rules.joinToString(", ", "listOf(", ")", transform = ::renderValidationRule)
    }

    private fun renderValidationRule(rule: ValidationRuleModel): String {
        val arguments = if (rule.arguments.isEmpty()) {
            "emptyList()"
        } else {
            rule.arguments.joinToString(", ", "listOf(", ")") { argument -> renderRuleArgument(argument) }
        }
        val message = quoteOrNull(rule.message)
        return "$METADATA_PACKAGE.ValidationRuleDescriptor(${quote(rule.ruleName)}, $arguments, $message)"
    }

    private fun renderRuleArgument(argument: Any?): String = when (argument) {
        is String -> quote(argument)
        is Number, is Boolean -> argument.toString()
        else -> error("Unsupported validation rule argument '$argument'.")
    }

    fun renderModuleArtifacts(artifacts: List<String>): String = if (artifacts.isEmpty()) {
        "emptyList()"
    } else {
        artifacts.joinToString(",\n", "java.util.List.copyOf(\n        listOf(\n", "\n        )\n    )") { artifact ->
            "            $artifact"
        }
    }

    fun renderFluentContributions(declarations: List<FluentDeclaration>): String {
        if (declarations.isEmpty()) return ""
        val registrations = declarations.sortedBy { it.validator }.joinToString(",\n        ", transform = ::renderFluentRegistration)
        return " {\n    override val fluentValidators: List<io.cratis.arc.validation.FluentValidatorRegistration> = listOf(\n        $registrations\n    )\n}"
    }

    private fun renderFluentRegistration(declaration: FluentDeclaration): String {
        val members = declaration.members.joinToString(", ") { member ->
            val rules = member.rules.map { ValidationRuleModel(it.ruleName, it.arguments, it.message) }
            "io.cratis.arc.validation.FluentValidationMember(${quote(member.name)}, ${renderFluentMemberType(member.runtimeType)}, ${renderValidationRules(rules)})"
        }
        return "io.cratis.arc.validation.FluentValidatorRegistration(${renderQualifiedName(declaration.validator)}(), " +
            "${renderQualifiedName(declaration.model)}::class.java, listOf($members))"
    }

    private fun renderFluentMemberType(name: String): String {
        boxedPrimitiveTypes[name]?.let { return "$it::class.javaObjectType" }
        if (name.startsWith('[')) return renderFluentArrayType(name.substring(1))
        val mapped = collectionTypeMappings[name] ?: name.replace('$', '.')
        return "${renderClassLiteralType(mapped)}::class.java"
    }

    private fun renderFluentArrayType(element: String): String {
        val type = if (element.startsWith('L')) {
            element.substring(1, element.length - 1)
        } else {
            primitiveArrayElements[element] ?: element
        }
        return "java.lang.reflect.Array.newInstance(${renderFluentMemberType(type)}, 0).javaClass"
    }

    private fun renderLocationSegments(segments: List<String>): String = segments.joinToString(", ") { segment -> quote(segment) }
}

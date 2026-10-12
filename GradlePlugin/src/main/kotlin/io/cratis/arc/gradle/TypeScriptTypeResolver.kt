// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.gradle

import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import org.gradle.api.GradleException

/** Resolves JVM type names, concepts, mapped types and map shapes to their TypeScript representation. */
internal class TypeScriptTypeResolver(private val model: TypeScriptGenerationModel) {
    private val artifacts = model.artifacts
    private val options = model.options
    private val conceptsBySource = model.conceptsBySource
    private val targetBySource = model.targetBySource

    fun resolveMapShape(shape: TypeShapeDescriptor, propertyName: String): TypeScriptType =
        resolveMapShape(shape, MapEntry(propertyName, propertyName), true)

    /** The property and entry path a map shape is being resolved for, used in diagnostics. */
    private class MapEntry(val propertyName: String, val path: String) {
        fun at(childPath: String): MapEntry = MapEntry(propertyName, childPath)

        val label: String get() = "Map property '$propertyName' entry path"
    }

    private fun resolveMapShape(shape: TypeShapeDescriptor, entry: MapEntry, root: Boolean): TypeScriptType {
        if (!root && shape.nullable) {
            throw GradleException("${entry.label} '${entry.path}' cannot be nullable.")
        }
        return when (shape.kind) {
            TypeShapeKind.VALUE -> resolveMapValueLeaf(shape, entry)
            TypeShapeKind.SEQUENCE -> {
                val element = resolveMapShape(requireNotNull(shape.elementShape), entry.at("${entry.path}[]"), false)
                TypeScriptType("${element.name}[]", element.constructor, nestedTypes = listOf(element))
            }
            TypeShapeKind.MAP -> {
                requireStringMapKey(shape, entry)
                val value = resolveMapShape(requireNotNull(shape.valueShape), entry.at("${entry.path}.value"), false)
                TypeScriptType("Record<string, ${value.name}>", "Object", nestedTypes = listOf(value))
            }
        }
    }

    private fun requireStringMapKey(shape: TypeShapeDescriptor, entry: MapEntry) {
        val key = requireNotNull(shape.keyShape)
        val validKey = shape.keyCodec == MapKeyCodec.STRING && !key.nullable &&
            key.kind == TypeShapeKind.VALUE && key.typeName in MAP_STRING_TYPE_NAMES
        if (!validKey) {
            throw GradleException("${entry.label} '${entry.path}.key' must use nonnullable String keys.")
        }
    }

    private fun resolveMapValueLeaf(shape: TypeShapeDescriptor, entry: MapEntry): TypeScriptType {
        val typeName = requireNotNull(shape.typeName)
        if (typeName !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
            throw GradleException("${entry.label} '${entry.path}' has unsupported value leaf '$typeName'.")
        }
        return mapScalarOverride(typeName, entry) ?: PRIMITIVE_TYPES[typeName] ?: throw GradleException(
            "${entry.label} '${entry.path}' has no TypeScript primitive mapping for '$typeName'."
        )
    }

    private fun mapScalarOverride(typeName: String, entry: MapEntry): TypeScriptType? {
        val mapping = options.typeMappings[typeName] ?: return null
        if (mapping.npmPackage != null || mapping.typeScriptType !in setOf("string", "number", "boolean")) {
            throw GradleException("${entry.label} '${entry.path}' cannot hydrate an external or non-scalar override for '$typeName'.")
        }
        return mappedType(typeName, mapping.typeScriptType, null)
    }

    fun resolveType(rawTypeName: String, current: ArtifactTarget): TypeScriptType =
        resolveType(rawTypeName, current, mutableSetOf())

    private fun resolveType(
        rawTypeName: String,
        current: ArtifactTarget,
        concepts: MutableSet<String>
    ): TypeScriptType {
        val typeName = rawTypeName.removeSuffix("?")
        if (isGenericOrMap(typeName)) {
            throw GradleException("Unsupported generic or map type '$rawTypeName' in '${current.sourceName}'.")
        }
        // Concepts unwrap first, then configuration, then the built-in map, matching the order Arc .NET's
        // TypeExtensions.GetTargetType uses. Concepts and primitives never share a key, so moving the concept
        // branch above the primitive lookup changes nothing on its own.
        conceptsBySource[typeName]?.let { concept ->
            if (!concepts.add(typeName)) {
                throw GradleException("Cyclic Arc concept metadata for '$typeName' in '${current.sourceName}'.")
            }
            return resolveType(concept.underlyingTypeName, current, concepts)
        }
        // Ahead of the built-in map, which is what lets a mapping correct an existing type rather than only
        // declare one the generator has never seen.
        options.typeMappings[typeName]?.let { mapping ->
            return mappedType(typeName, mapping.typeScriptType, mapping.npmPackage)
        }
        PRIMITIVE_TYPES[typeName]?.let { return it }
        ProxyTypeMappings.npmPackageFor(typeName, options.packageMappings)?.let { npmPackage ->
            return mappedType(typeName, typeName.substringAfterLast('.'), npmPackage)
        }
        return generatedType(rawTypeName, typeName, current)
    }

    private fun isGenericOrMap(typeName: String): Boolean =
        typeName.contains('<') || typeName.contains('>') || isMapType(typeName)

    private fun generatedType(rawTypeName: String, typeName: String, current: ArtifactTarget): TypeScriptType {
        val target = targetBySource[typeName]
            ?: throw GradleException("Unsupported Arc proxy type '$rawTypeName' in '${current.sourceName}'.")
        val constructor = when (target.kind) {
            ArtifactKind.ENUM -> "Number"
            ArtifactKind.INTERFACE -> "Object"
            else -> target.typeScriptName
        }
        return TypeScriptType(target.typeScriptName, constructor, target)
    }

    fun mappedType(sourceName: String, name: String, npmPackage: String?): TypeScriptType {
        if (npmPackage == null) {
            val constructor = MAPPED_GLOBAL_TYPES[name] ?: throw GradleException(
                "Arc proxy mapping '$sourceName=$name' has no supported runtime constructor; " +
                    "use string, number, boolean, object or Date, or supply an npm package and exported name."
            )
            return TypeScriptType(name, constructor)
        }
        if (!isSafeMapping(name, npmPackage)) {
            throw GradleException("Unsafe Arc proxy mapping '$sourceName=$name=$npmPackage'; use an exported identifier and npm package path.")
        }
        val isEnum = artifacts.enums.any { it.fullyQualifiedName == sourceName }
        val isInterface = artifacts.interfaces.any { it.fullyQualifiedName == sourceName }
        val constructor = when {
            isEnum -> "Number"
            isInterface -> "Object"
            else -> name
        }
        return TypeScriptType(name, constructor, valueImport = TypeScriptValueImport(npmPackage, name, isEnum || isInterface))
    }

    private fun isSafeMapping(name: String, npmPackage: String): Boolean =
        TYPESCRIPT_IDENTIFIER.matches(name) && name !in RESERVED_MAPPING_NAMES &&
            NPM_IMPORT_PATH.matches(npmPackage) && npmPackage.split('/').none { it == "." || it == ".." }

    private fun isMapType(typeName: String): Boolean =
        typeName == "kotlin.collections.Map" || typeName == "kotlin.collections.MutableMap" || typeName == "java.util.Map"

    fun resolvePropertyType(property: PropertyDescriptor, current: ArtifactTarget): TypeScriptType =
        if (property.shape.kind == TypeShapeKind.MAP) {
            resolveMapShape(property.shape, property.name)
        } else {
            resolveType(if (property.isEnumerable) requireElementType(property.name, property.elementTypeName) else property.typeName, current)
        }

    fun propertyTypeName(property: PropertyDescriptor, current: ArtifactTarget): String =
        resolvePropertyType(property, current).name + (if (property.isEnumerable) "[]" else "") +
            if (model.shared.contains(current.sourceName) && property.isNullable) " | null" else ""

    private fun isNullableShared(parameter: ParameterDescriptor): Boolean =
        parameter.isNullable && model.shared.reaches(parameter.shape)

    fun parameterArraySuffix(parameter: ParameterDescriptor): String =
        if (!parameter.isEnumerable) "" else "[]" + if (isNullableShared(parameter)) " | null" else ""

    fun resolveParameterType(parameter: ParameterDescriptor, current: ArtifactTarget): TypeScriptType {
        val type = resolveType(if (parameter.isEnumerable) requireElementType(parameter.name, parameter.elementTypeName) else parameter.typeName, current)
        return if (isNullableShared(parameter) && !parameter.isEnumerable) type.copy(name = type.name + " | null") else type
    }

    fun resolveDerivatives(property: PropertyDescriptor, current: ArtifactTarget): List<TypeScriptType> =
        property.derivatives.map { derivativeName ->
            val derivative = resolveType(derivativeName, current)
            if (derivative.target?.kind != ArtifactKind.TYPE) {
                throw GradleException(
                    "Arc derivative '$derivativeName' on '${current.sourceName}.${property.name}' must be a generated concrete model type."
                )
            }
            derivative
        }

    fun resolveBaseType(baseTypeName: String, current: ArtifactTarget): TypeScriptType {
        val baseType = resolveType(baseTypeName, current)
        if (baseType.target?.kind != ArtifactKind.TYPE) {
            throw GradleException("Arc base type '$baseTypeName' on '${current.sourceName}' must be a generated model type.")
        }
        return baseType
    }

    private fun requireElementType(name: String, elementTypeName: String?): String =
        elementTypeName ?: throw GradleException("Enumerable '$name' does not declare an elementTypeName.")
}

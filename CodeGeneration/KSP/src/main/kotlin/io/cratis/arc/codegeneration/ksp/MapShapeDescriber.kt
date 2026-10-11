// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind

/** The artifact property and value path of a type shape being described, with the node that diagnostics point at. */
internal class ShapeSite(val identity: String, val path: String, val node: KSNode) {
    fun at(childPath: String): ShapeSite = ShapeSite(identity, childPath, node)
}

/** Reports an unsupported value shape and returns null so callers can `return` the result directly. */
internal fun reportUnsupportedShape(logger: ArcDiagnosticReporter, site: ShapeSite, detail: String): Nothing? {
    logger.error(
        ArcDiagnostic.PROXY_SHAPE,
        "Artifact/property '${site.identity}' value path '${site.path}': $detail.",
        site.node
    )
    return null
}

internal fun sequenceKind(qualifiedName: String): SequenceKind = when {
    qualifiedName == ARRAY_TYPE -> SequenceKind.ARRAY
    qualifiedName.endsWith("List") -> SequenceKind.LIST
    else -> SequenceKind.COLLECTION
}

internal fun descriptorTypeName(shape: TypeShapeDescriptor): String = when (shape.kind) {
    TypeShapeKind.VALUE -> requireNotNull(shape.typeName)
    TypeShapeKind.SEQUENCE -> {
        val container = when (requireNotNull(shape.sequenceKind)) {
            SequenceKind.ARRAY -> ARRAY_TYPE
            SequenceKind.LIST -> "kotlin.collections.List"
            SequenceKind.COLLECTION -> "kotlin.collections.Collection"
        }
        "$container<${descriptorTypeName(requireNotNull(shape.elementShape))}>"
    }
    TypeShapeKind.MAP -> "kotlin.collections.Map<${descriptorTypeName(requireNotNull(shape.keyShape))}, " +
        "${descriptorTypeName(requireNotNull(shape.valueShape))}>"
}

internal fun Sequence<KSAnnotation>.hasNullableTypeUse(): Boolean = any { annotation ->
    annotation.annotationType.resolve().declaration.simpleName.asString() in setOf("Nullable", "CheckForNull")
}

/** Describes the shape of Kotlin map types, whose keys must be strings and whose values must be runtime-safe. */
internal class MapShapeDescriber(private val logger: ArcDiagnosticReporter) {
    fun describeMap(type: KSType, site: ShapeSite): TypeShapeDescriptor? {
        val arguments = concreteTypeArguments(type, 2, site) ?: return null
        val key = arguments[0]
        val keyName = key.declaration.qualifiedName?.asString()
        if (key.nullability == Nullability.NULLABLE || keyName !in STRING_TYPE_NAMES || key.arguments.isNotEmpty()) {
            return unsupported(site.at("${site.path}.key"), "map keys must be nonnullable String")
        }
        val valueShape = describeMapValue(arguments[1], site) ?: return null
        return TypeShapeDescriptor.map(
            TypeShapeDescriptor.value(requireNotNull(keyName), false),
            valueShape,
            MapKeyCodec.STRING,
            type.nullability == Nullability.NULLABLE
        )
    }

    fun unsupportedSequenceElement(identity: String, node: KSNode) {
        logger.error(
            ArcDiagnostic.PROXY_SHAPE,
            "Artifact/property '$identity' value path 'value[]': nullable sequence elements are unsupported; " +
                "declare nonnullable elements, for example List<T> or List<T>?.",
            node
        )
    }

    fun unsupported(site: ShapeSite, detail: String): Nothing? = reportUnsupportedShape(logger, site, detail)

    /** Resolves exactly [count] invariant, concrete type arguments, or reports why the type is unsupported. */
    fun concreteTypeArguments(type: KSType, count: Int, site: ShapeSite): List<KSType>? {
        // KSP models a source Java array parameter as Array<out E>, unlike an explicit
        // wildcard generic or Kotlin Array<out E>. Only this parameter extraction is
        // widened; existing property/return and map boundaries remain unchanged.
        val javaArrayParameter = isJavaArrayParameter(type, site.node)
        if (type.arguments.size != count || type.arguments.any { argument -> isUnsupportedArgument(argument, javaArrayParameter) }) {
            return unsupported(
                site,
                "raw and wildcard arguments are unsupported; star projections are unsupported; variant generic arguments are unsupported"
            )
        }
        val arguments = type.arguments.map { argument -> requireNotNull(argument.type).resolve() }
        if (arguments.any { argument -> argument.isError || argument.declaration is KSTypeParameter }) {
            return unsupported(site, "unresolvable and type-parameter arguments are unsupported")
        }
        return arguments
    }

    private fun isJavaArrayParameter(type: KSType, node: KSNode): Boolean =
        type.declaration.qualifiedName?.asString() == ARRAY_TYPE &&
            node is KSValueParameter && node.origin == Origin.JAVA && node.type.origin == Origin.JAVA

    private fun isUnsupportedArgument(argument: KSTypeArgument, javaArrayParameter: Boolean): Boolean {
        if (argument.type == null) return true
        if (argument.variance == Variance.INVARIANT) return false
        val syntheticJavaArray = javaArrayParameter && argument.variance == Variance.COVARIANT &&
            argument.origin == Origin.SYNTHETIC && argument.type?.origin == Origin.SYNTHETIC
        return !syntheticJavaArray
    }

    private fun describeMapValue(type: KSType, site: ShapeSite): TypeShapeDescriptor? {
        val declaration = resolveValueDeclaration(type, site) ?: return null
        val name = declaration.qualifiedName?.asString()
            ?: return unsupported(site, "map values must use named class types")
        if (name in MAP_TYPE_NAMES) return describeMap(type, site.at("${site.path}.value"))
        if (name in COLLECTION_TYPE_NAMES || name == ARRAY_TYPE) return describeSequenceValue(type, name, site)
        if (type.arguments.isNotEmpty()) {
            return unsupported(site, "generic, wildcard, and star-projected map value leaves are unsupported")
        }
        if (name !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
            return unsupported(
                site,
                "map value leaf '$name' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, temporal, and derived leaves are unsupported"
            )
        }
        return TypeShapeDescriptor.value(name, false)
    }

    private fun resolveValueDeclaration(type: KSType, site: ShapeSite): KSClassDeclaration? {
        if (type.isError || type.declaration is KSTypeParameter) {
            return unsupported(site, "map values must use concrete types; type parameters are unsupported")
        }
        if (type.nullability == Nullability.NULLABLE) {
            return unsupported(site, "nullable map values and sequence elements are unsupported")
        }
        return type.declaration as? KSClassDeclaration ?: unsupported(site, "map values must use resolvable class types")
    }

    private fun describeSequenceValue(type: KSType, name: String, site: ShapeSite): TypeShapeDescriptor? {
        val elementSite = site.at("${site.path}[]")
        val element = concreteTypeArguments(type, 1, elementSite)?.singleOrNull() ?: return null
        val elementShape = describeMapValue(element, elementSite) ?: return null
        return TypeShapeDescriptor.sequence(sequenceKind(name), elementShape, false)
    }
}

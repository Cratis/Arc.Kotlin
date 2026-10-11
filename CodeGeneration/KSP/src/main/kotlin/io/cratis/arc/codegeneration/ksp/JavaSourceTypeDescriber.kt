// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor

/** A Java source type name decomposed into its base name, argument and array-ness. */
private class JavaDeclaredType(val sourceTypeName: String) {
    val rawType = sourceTypeName.removeSuffix("[]")
    private val genericStart = rawType.indexOf('<')
    val baseName = if (genericStart >= 0) rawType.substring(0, genericStart) else rawType
    val argumentName = if (genericStart >= 0) rawType.substring(genericStart + 1, rawType.lastIndexOf('>')).trim() else null
    val isArray = sourceTypeName.endsWith("[]")
    val isEnumerable = isArray || baseName in JAVA_COLLECTION_NAMES
    val lacksElementType = isEnumerable && argumentName == null && !isArray
}

/** Describes member types that KSP only exposes as Java source text, such as Java record components. */
internal class JavaSourceTypeDescriber(
    private val logger: ArcDiagnosticReporter,
    private val resolver: Resolver?
) {
    private val mapShapes = MapShapeDescriber(logger)

    fun describe(sourceTypeName: String, owner: KSClassDeclaration, identity: String, node: KSNode): TypeShape? {
        val declared = JavaDeclaredType(sourceTypeName)
        if (declared.baseName in JAVA_MAP_NAMES) return describeMapShape(owner, identity, sourceTypeName)
        val valueSourceName = valueSourceName(declared, owner, identity, node) ?: return null
        val declaration = resolveDeclaration(valueSourceName, owner)
        if (declaration == null) {
            logger.error("'$identity' has unresolvable Java type '$sourceTypeName'.", owner)
            return null
        }
        return valueShape(declared, declaration)
    }

    private fun valueSourceName(declared: JavaDeclaredType, owner: KSClassDeclaration, identity: String, node: KSNode): String? {
        if (declared.lacksElementType) {
            logger.error("'$identity' must use a concrete collection element type.", owner)
            return null
        }
        val valueUse = parseJavaTypeUse(declared.argumentName ?: declared.rawType)
        if (declared.isEnumerable && valueUse.isNullable) {
            mapShapes.unsupportedSequenceElement(identity, node)
            return null
        }
        if (hasNestedShape(valueUse.typeName)) {
            logger.error("'$identity' uses an unsupported nested, wildcard, or generic shape.", owner)
            return null
        }
        return valueUse.typeName
    }

    private fun hasNestedShape(typeName: String): Boolean = '<' in typeName || '?' in typeName || '*' in typeName

    private fun describeMapShape(owner: KSClassDeclaration, identity: String, sourceTypeName: String): TypeShape? {
        val site = ShapeSite(identity, "value", owner)
        val descriptor = describeMap(sourceTypeName, owner, site) ?: return null
        val declaration = resolveDeclaration("java.util.Map", owner)
            ?: return mapShapes.unsupported(site, "java.util.Map cannot be resolved")
        return TypeShape(descriptorTypeName(descriptor), descriptor, declaration.asStarProjectedType())
    }

    private fun valueShape(declared: JavaDeclaredType, declaration: KSClassDeclaration): TypeShape {
        val valueName = requireNotNull(declaration.qualifiedName).asString()
        val collectionName = if (declared.isArray) ARRAY_TYPE else normalizeCollectionName(declared.baseName)
        val descriptor = if (declared.isEnumerable) {
            TypeShapeDescriptor.sequence(sequenceKind(collectionName), TypeShapeDescriptor.value(valueName))
        } else {
            TypeShapeDescriptor.value(valueName)
        }
        return TypeShape(
            typeName = if (declared.isEnumerable) "$collectionName<$valueName>" else valueName,
            descriptor = descriptor,
            valueType = declaration.asStarProjectedType()
        )
    }

    private fun describeMap(sourceTypeName: String, owner: KSClassDeclaration, site: ShapeSite): TypeShapeDescriptor? {
        val parsed = parseJavaGenericType(sourceTypeName)
            ?: return mapShapes.unsupported(
                site,
                "raw, wildcard, and type-parameter map arguments are unsupported; star projections are unsupported"
            )
        if (parsed.baseName !in JAVA_MAP_NAMES || parsed.arguments.size != 2) {
            return mapShapes.unsupported(site, "map values must declare exactly two concrete arguments")
        }
        if (!isStringKey(parsed.arguments[0])) {
            return mapShapes.unsupported(site.at("${site.path}.key"), "map keys must be nonnullable String")
        }
        val valueShape = describeMapValue(parsed.arguments[1], owner, site) ?: return null
        return TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("java.lang.String"),
            valueShape,
            MapKeyCodec.STRING
        )
    }

    private fun isStringKey(key: JavaTypeUse): Boolean {
        val keyName = JAVA_TYPE_ALIASES[key.typeName] ?: key.typeName
        return !key.isNullable && (keyName == "java.lang.String" || keyName == "String")
    }

    private fun describeMapValue(typeUse: JavaTypeUse, owner: KSClassDeclaration, site: ShapeSite): TypeShapeDescriptor? {
        if (typeUse.isNullable) {
            return mapShapes.unsupported(site, "nullable map values and sequence elements are unsupported")
        }
        val sourceTypeName = typeUse.typeName
        if ('?' in sourceTypeName || '*' in sourceTypeName) {
            return mapShapes.unsupported(site, "raw and wildcard arguments are unsupported; star projections are unsupported")
        }
        val parsed = parseJavaGenericType(sourceTypeName)
        if (parsed != null) return describeGenericMapValue(parsed, sourceTypeName, owner, site)
        if (sourceTypeName.endsWith("[]")) {
            val elementSite = site.at("${site.path}[]")
            val element = describeMapValue(parseJavaTypeUse(sourceTypeName.removeSuffix("[]")), owner, elementSite)
                ?: return null
            return TypeShapeDescriptor.sequence(SequenceKind.ARRAY, element)
        }
        return describeMapLeaf(sourceTypeName, owner, site)
    }

    private fun describeGenericMapValue(
        parsed: JavaGenericType,
        sourceTypeName: String,
        owner: KSClassDeclaration,
        site: ShapeSite
    ): TypeShapeDescriptor? {
        if (parsed.baseName in JAVA_MAP_NAMES) return describeMap(sourceTypeName, owner, site.at("${site.path}.value"))
        if (parsed.baseName !in JAVA_COLLECTION_NAMES) {
            return mapShapes.unsupported(site, "generic map value leaves are unsupported")
        }
        val elementSite = site.at("${site.path}[]")
        if (parsed.arguments.size != 1) {
            return mapShapes.unsupported(elementSite, "sequences require one concrete element type")
        }
        val element = describeMapValue(parsed.arguments.single(), owner, elementSite) ?: return null
        return TypeShapeDescriptor.sequence(sequenceKind(normalizeCollectionName(parsed.baseName)), element)
    }

    private fun describeMapLeaf(sourceTypeName: String, owner: KSClassDeclaration, site: ShapeSite): TypeShapeDescriptor? {
        val declaration = resolveDeclaration(sourceTypeName, owner)
            ?: return mapShapes.unsupported(site, "map value type '$sourceTypeName' cannot be resolved")
        val name = requireNotNull(declaration.qualifiedName).asString()
        if (name !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
            return mapShapes.unsupported(
                site,
                "map value leaf '$name' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, temporal, and derived leaves are unsupported"
            )
        }
        return TypeShapeDescriptor.value(name)
    }

    private fun resolveDeclaration(typeName: String, owner: KSClassDeclaration): KSClassDeclaration? {
        val currentResolver = resolver ?: return null
        val normalized = JAVA_TYPE_ALIASES[typeName] ?: typeName
        val candidates = if ('.' in normalized) {
            listOf(normalized)
        } else {
            val packageName = owner.packageName.asString()
            listOf("$packageName.$normalized", "java.lang.$normalized", "java.util.$normalized")
        }
        return candidates.firstNotNullOfOrNull { candidate ->
            currentResolver.getClassDeclarationByName(currentResolver.getKSNameFromString(candidate))
        }
    }

    private fun normalizeCollectionName(name: String): String = when (name) {
        "List", "java.util.List" -> "java.util.List"
        "Collection", "java.util.Collection" -> "java.util.Collection"
        else -> name
    }
}

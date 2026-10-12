// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Origin
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import java.io.File

/** Describes type shapes and their validation, as provided by the metadata collector. */
internal interface ModelShapeSource {
    fun describe(type: KSType, identity: String, node: KSNode, allowMaps: Boolean = false): TypeShape?

    fun extractValidation(
        annotated: Iterable<KSAnnotated>,
        shape: TypeShape,
        identity: String,
        node: KSNode,
        sourceAnnotations: List<SourceValidationAnnotation> = emptyList()
    ): ValidationMetadata?
}

/** Constructor order is contractual; only the remaining declared state is name-sorted. */
internal fun orderedDeclaredProperties(declaration: KSClassDeclaration): List<KSPropertyDeclaration> {
    val properties = declaration.getDeclaredProperties().associateBy { it.simpleName.asString() }
    if (declaration.origin == Origin.JAVA) return properties.values.sortedBy { it.simpleName.asString() }
    if (declaration.classKind == ClassKind.INTERFACE) return properties.values.toList()
    val constructorNames = declaration.primaryConstructor?.parameters.orEmpty()
        .filter { it.isVal || it.isVar }.mapNotNull { it.name?.asString() }
    return constructorNames.mapNotNull(properties::get) + properties.values
        .filter { it.simpleName.asString() !in constructorNames }.sortedBy { it.simpleName.asString() }
}

/** Finds the node a property-level diagnostic should point at, falling back to the owning type. */
internal fun propertyUseNode(owner: KSClassDeclaration, propertyName: String): KSNode {
    owner.getDeclaredProperties().firstOrNull { property -> property.simpleName.asString() == propertyName }
        ?.let { return it }
    if (owner.origin == Origin.JAVA) {
        val capitalized = propertyName.replaceFirstChar(Char::uppercase)
        val names = setOf(propertyName, "get$capitalized", "is$capitalized")
        owner.getDeclaredFunctions().firstOrNull { function ->
            function.parameters.isEmpty() && function.simpleName.asString() in names
        }?.let { return it }
    }
    // Some Java record components have source metadata but no KSP property or accessor node.
    return owner
}

internal fun TypeShapeDescriptor.withNullability(nullable: Boolean): TypeShapeDescriptor = when (kind) {
    TypeShapeKind.VALUE -> TypeShapeDescriptor.value(requireNotNull(typeName), nullable)
    TypeShapeKind.SEQUENCE -> TypeShapeDescriptor.sequence(
        requireNotNull(sequenceKind),
        requireNotNull(elementShape),
        nullable
    )
    TypeShapeKind.MAP -> TypeShapeDescriptor.map(
        requireNotNull(keyShape),
        requireNotNull(valueShape),
        requireNotNull(keyCodec),
        nullable
    )
}

/** The type whose properties are being described. */
private class PropertyOwner(val declaration: KSClassDeclaration, val identity: String, val memberTags: Map<String, String>) {
    val properties: Map<String, KSPropertyDeclaration> =
        declaration.getDeclaredProperties().associateBy { property -> property.simpleName.asString() }
}

/** Describes the wire properties of a command, model, record or interface declaration. */
internal class ModelPropertyDescriber(
    private val logger: ArcDiagnosticReporter,
    private val binaryRecords: BinaryRecordMetadata,
    private val resolver: Resolver?,
    private val shapes: ModelShapeSource
) {
    private val wireValidator = WireAnnotationValidator(logger)
    private val mapShapes = MapShapeDescriber(logger)
    private val javaTypes = JavaSourceTypeDescriber(logger, resolver)

    fun describe(declaration: KSClassDeclaration, identity: String): List<PropertyModel>? {
        val documentation = DocumentationSummaryParser.parse(declaration.docString)
        // A Kotlin class documents its properties with `@property`; a Java record or class uses `@param`.
        val declaredMemberTags = if (declaration.origin == Origin.JAVA) {
            documentation.paramTags
        } else {
            documentation.propertyTags
        }
        val owner = PropertyOwner(declaration, identity, declaredMemberTags)
        if (declaration.origin == Origin.JAVA) {
            val record = javaRecordComponents(declaration)
            if (record != null) return describeJavaSourceRecord(owner, record)
        }
        if (isBinaryRecord(declaration)) return describeBinaryRecord(owner)
        if (declaration.origin == Origin.JAVA && declaration.classKind == ClassKind.INTERFACE) {
            return describeJavaInterface(owner)
        }
        return describeBody(owner)
    }

    private fun javaRecordComponents(declaration: KSClassDeclaration): List<JavaRecordProperty>? =
        declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
            ?.let { source -> parseJavaRecordProperties(source, declaration.simpleName.asString()) }

    private fun isBinaryRecord(declaration: KSClassDeclaration): Boolean =
        declaration.origin == Origin.JAVA_LIB && declaration.superTypes.any {
            it.resolve().declaration.qualifiedName?.asString() == "java.lang.Record"
        }

    /** Prefers the member's own documentation and falls back to the declaring type's member tag. */
    private fun memberSummary(own: String?, declaredMemberTags: Map<String, String>, name: String): String? =
        DocumentationSummaryParser.parse(own).summary ?: declaredMemberTags[name]

    private fun describeJavaSourceRecord(owner: PropertyOwner, record: List<JavaRecordProperty>): List<PropertyModel>? {
        val accessors = owner.declaration.getDeclaredFunctions()
            .filter { function -> function.parameters.isEmpty() }
            .associateBy { function -> function.simpleName.asString() }
        val properties = mutableListOf<PropertyModel>()
        for (component in record) {
            properties.add(describeRecordComponent(owner, component, accessors[component.name]) ?: return null)
        }
        return properties
    }

    private fun describeRecordComponent(
        owner: PropertyOwner,
        component: JavaRecordProperty,
        accessor: KSFunctionDeclaration?
    ): PropertyModel? {
        val identity = "${owner.identity}.${component.name}"
        val memberNode = propertyUseNode(owner.declaration, component.name)
        // KSP can expose a platform accessor even when the record source explicitly annotates its element.
        val sourceElement = parseJavaGenericType(component.typeName)
            ?.takeIf { it.baseName in JAVA_COLLECTION_NAMES }?.arguments?.singleOrNull()
        if (sourceElement?.isNullable == true) {
            mapShapes.unsupportedSequenceElement(identity, memberNode)
            return null
        }
        val shape = recordComponentShape(owner, component, accessor, memberNode) ?: return null
        val validation = shapes.extractValidation(
            listOfNotNull(owner.properties[component.name], accessor),
            shape,
            identity,
            accessor ?: owner.declaration,
            component.validationAnnotations
        ) ?: return null
        val nullable = component.isNullable || shape.isNullable
        return PropertyModel(
            name = component.name,
            typeName = shape.typeName,
            isNullable = nullable,
            isCommandKey = component.isCommandKey,
            isEnumerable = shape.isEnumerable,
            elementTypeName = shape.elementTypeName,
            shape = shape.descriptor.withNullability(nullable),
            validationRules = validation.rules,
            validateRecursively = validation.validateRecursively,
            ignoreValidation = validation.ignoreValidation,
            summary = memberSummary(accessor?.docString, owner.memberTags, component.name)
        )
    }

    private fun recordComponentShape(
        owner: PropertyOwner,
        component: JavaRecordProperty,
        accessor: KSFunctionDeclaration?,
        memberNode: KSNode
    ): TypeShape? {
        val identity = "${owner.identity}.${component.name}"
        val sourceBaseName = component.typeName.substringBefore('<').trim()
        val returnType = accessor?.returnType?.resolve()
        if (sourceBaseName !in JAVA_MAP_NAMES && accessor != null && returnType != null) {
            return shapes.describe(returnType, identity, accessor, allowMaps = true)
        }
        return javaTypes.describe(component.typeName, owner.declaration, identity, memberNode)
    }

    private fun describeBinaryRecord(owner: PropertyOwner): List<PropertyModel>? {
        val declaration = owner.declaration
        val binaryName = binaryName(declaration)
        val binaryFields = try {
            binaryRecords.fields(binaryName)
        } catch (exception: Exception) {
            logger.error(
                ArcDiagnostic.IGNORE_VALIDATION,
                "Cannot inspect binary record '${owner.identity}': ${exception.message}; supply its exact compile classpath with '${BinaryRecordMetadata.OPTION}'.",
                declaration
            )
            return null
        }
        val functions = declaration.getDeclaredFunctions().filter { it.parameters.isEmpty() }.associateBy { it.simpleName.asString() }
        val components = declaration.getConstructors().map { it.parameters }.filter { parameters ->
            parameters.all { it.name?.asString() in functions }
        }.maxByOrNull { it.size }.orEmpty()
        return components.map { component ->
            describeBinaryComponent(owner, component, functions, binaryFields) ?: return null
        }
    }

    private fun binaryName(declaration: KSClassDeclaration): String {
        val nestedName = generateSequence(declaration) { it.parentDeclaration as? KSClassDeclaration }
            .toList().asReversed().joinToString("$") { it.simpleName.asString() }
        return listOf(declaration.packageName.asString(), nestedName).filter(String::isNotBlank).joinToString(".")
    }

    private fun describeBinaryComponent(
        owner: PropertyOwner,
        component: KSValueParameter,
        functions: Map<String, KSFunctionDeclaration>,
        binaryFields: Map<String, List<SourceValidationAnnotation>>?
    ): PropertyModel? {
        val name = requireNotNull(component.name).asString()
        val identity = "${owner.identity}.$name"
        val accessor = functions.getValue(name)
        val shape = shapes.describe(requireNotNull(accessor.returnType).resolve(), identity, accessor, allowMaps = true)
            ?: return null
        val componentField = owner.properties[name]
            ?: resolver?.let { it.getPropertyDeclarationByName(it.getKSNameFromString(identity)) }
        val fieldAnnotations = binaryFields?.get(name)
        // Class-file metadata contains both sites. Do not treat field constraints as a
        // fallback for accessor annotations: the same constraint type may have different bounds.
        val sites = if (fieldAnnotations == null) listOfNotNull(componentField, accessor) else emptyList()
        val validation = shapes.extractValidation(sites, shape, identity, accessor, fieldAnnotations.orEmpty()) ?: return null
        if (componentField == null && fieldAnnotations == null && !validation.ignoreValidation) {
            logger.error(
                ArcDiagnostic.IGNORE_VALIDATION,
                "Binary record component '$identity' has no visible field annotation metadata; supply its exact compile classpath with '${BinaryRecordMetadata.OPTION}' or compile the model from source.",
                accessor
            )
            return null
        }
        return PropertyModel(
            name, shape.typeName, shape.isNullable, false, shape.isEnumerable, shape.elementTypeName,
            shape.descriptor, validation.rules, validation.validateRecursively, ignoreValidation = validation.ignoreValidation
        )
    }

    private fun describeBody(owner: PropertyOwner): List<PropertyModel>? {
        val declaration = owner.declaration
        val constructorParameters = declaration.primaryConstructor?.parameters.orEmpty()
            .mapNotNull { parameter -> parameter.name?.asString()?.let { name -> name to parameter } }
            .toMap()
        val properties = mutableListOf<PropertyModel>()
        for (property in orderedDeclaredProperties(declaration)) {
            if (!property.isPublic() && declaration.origin != Origin.JAVA) continue
            val name = property.simpleName.asString()
            val parameter = constructorParameters[name]
            if (!wireValidator.validateBodyWireAnnotations(property, parameter, "${owner.identity}.$name")) return null
            if (property.isWireIgnored()) continue
            properties.add(describeBodyProperty(owner, property, parameter) ?: return null)
        }
        return properties
    }

    private fun describeBodyProperty(
        owner: PropertyOwner,
        property: KSPropertyDeclaration,
        constructorParameter: KSValueParameter?
    ): PropertyModel? {
        val name = property.simpleName.asString()
        val identity = "${owner.identity}.$name"
        val shape = shapes.describe(property.type.resolve(), identity, property, allowMaps = true) ?: return null
        val annotated = listOfNotNull(property, property.getter, constructorParameter)
        val validation = shapes.extractValidation(annotated, shape, identity, property) ?: return null
        return PropertyModel(
            name = name,
            typeName = shape.typeName,
            isNullable = shape.isNullable,
            isCommandKey = property.hasAnnotation(COMMAND_KEY_ANNOTATION) ||
                property.getter?.hasAnnotation(COMMAND_KEY_ANNOTATION) == true ||
                constructorParameter?.hasAnnotation(COMMAND_KEY_ANNOTATION) == true,
            isEnumerable = shape.isEnumerable,
            elementTypeName = shape.elementTypeName,
            shape = shape.descriptor,
            validationRules = validation.rules,
            validateRecursively = validation.validateRecursively,
            ignoreValidation = validation.ignoreValidation,
            summary = memberSummary(property.docString, owner.memberTags, name)
        )
    }

    private fun describeJavaInterface(owner: PropertyOwner): List<PropertyModel>? {
        val properties = mutableListOf<PropertyModel>()
        val members = owner.declaration.getDeclaredFunctions()
            .filter { candidate -> candidate.parameters.isEmpty() }
            .mapNotNull { function -> function.returnType?.resolve()?.let { type -> function to type } }
            .filterNot { (_, type) -> type.declaration.qualifiedName?.asString() in voidReturnTypes }
        for ((function, returnType) in members) {
            properties.add(describeInterfaceMember(owner, function, returnType) ?: return null)
        }
        return properties.sortedBy(PropertyModel::name)
    }

    private fun describeInterfaceMember(owner: PropertyOwner, function: KSFunctionDeclaration, returnType: KSType): PropertyModel? {
        val name = accessorPropertyName(function.simpleName.asString())
        val identity = "${owner.identity}.$name"
        val shape = shapes.describe(returnType, identity, function, allowMaps = true) ?: return null
        val validation = shapes.extractValidation(listOf(function), shape, identity, function) ?: return null
        return PropertyModel(
            name = name,
            typeName = shape.typeName,
            isNullable = shape.isNullable,
            isCommandKey = false,
            isEnumerable = shape.isEnumerable,
            elementTypeName = shape.elementTypeName,
            shape = shape.descriptor,
            validationRules = validation.rules,
            validateRecursively = validation.validateRecursively,
            ignoreValidation = validation.ignoreValidation,
            summary = memberSummary(function.docString, owner.memberTags, name)
        )
    }

    private fun accessorPropertyName(sourceName: String): String = when {
        sourceName.startsWith("get") && sourceName.length > 3 -> sourceName.substring(3).replaceFirstChar(Char::lowercase)
        sourceName.startsWith("is") && sourceName.length > 2 -> sourceName.substring(2).replaceFirstChar(Char::lowercase)
        else -> sourceName
    }

    private companion object {
        val voidReturnTypes = setOf("kotlin.Unit", "java.lang.Void")
    }
}

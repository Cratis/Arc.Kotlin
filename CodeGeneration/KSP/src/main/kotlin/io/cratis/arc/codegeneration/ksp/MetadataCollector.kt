// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import java.io.File

/** Collects the closed, language-neutral model graph used by generated modules and manifests. */
internal class MetadataCollector(private val logger: ArcDiagnosticReporter) {
    private val validationExtractor = ValidationMetadataExtractor(logger)
    private val collectedTypes = linkedMapOf<String, TypeModel>()
    private val collectedInterfaces = linkedMapOf<String, InterfaceModel>()
    private val collectedEnums = linkedMapOf<String, EnumModel>()
    private val collectedConcepts = linkedMapOf<String, ConceptModel>()
    private val visiting = mutableSetOf<String>()
    private var resolver: Resolver? = null
    private var derivedDeclarations: List<KSClassDeclaration> = emptyList()
    private val reportedDuplicateDerivedIds = mutableSetOf<String>()

    val types: List<TypeModel> get() = collectedTypes.values.sortedBy(TypeModel::fullyQualifiedName)
    val interfaces: List<InterfaceModel> get() = collectedInterfaces.values.sortedBy(InterfaceModel::fullyQualifiedName)
    val enums: List<EnumModel> get() = collectedEnums.values.sortedBy(EnumModel::fullyQualifiedName)
    val concepts: List<ConceptModel> get() = collectedConcepts.values.sortedBy(ConceptModel::fullyQualifiedName)

    fun useResolver(resolver: Resolver) {
        this.resolver = resolver
        derivedDeclarations = resolver.getSymbolsWithAnnotation(DERIVED_TYPE_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .filter { declaration -> declaration.qualifiedName != null }
            .sortedBy { declaration -> declaration.qualifiedName!!.asString() }
            .toList()
    }

    fun describeProperties(declaration: KSClassDeclaration, identity: String): List<PropertyModel>? {
        val declarations = declaration.getDeclaredProperties().associateBy { property -> property.simpleName.asString() }
        val constructorParameters = declaration.primaryConstructor?.parameters.orEmpty()
            .mapNotNull { parameter -> parameter.name?.asString()?.let { name -> name to parameter } }
            .toMap()
        if (declaration.origin == Origin.JAVA) {
            val source = declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
            val record = source?.let { parseJavaRecordProperties(it, declaration.simpleName.asString()) }
            if (record != null) {
                val accessors = declaration.getDeclaredFunctions()
                    .filter { function -> function.parameters.isEmpty() }
                    .associateBy { function -> function.simpleName.asString() }
                val properties = mutableListOf<PropertyModel>()
                for (component in record) {
                    val accessor = accessors[component.name]
                    val returnType = accessor?.returnType?.resolve()
                    val sourceBaseName = component.typeName.substringBefore('<').trim()
                    val shape = (if (sourceBaseName in JAVA_MAP_NAMES) {
                        describeJavaType(component.typeName, declaration, "$identity.${component.name}")
                    } else if (accessor != null && returnType != null) {
                        describe(returnType, "$identity.${component.name}", accessor, allowMaps = true)
                    } else {
                        describeJavaType(component.typeName, declaration, "$identity.${component.name}")
                    }) ?: return null
                    val annotated = listOfNotNull(declarations[component.name], accessor)
                    val validation = extractValidation(
                        annotated,
                        shape,
                        "$identity.${component.name}",
                        accessor ?: declaration,
                        component.validationAnnotations
                    ) ?: return null
                    properties.add(
                        PropertyModel(
                            name = component.name,
                            typeName = shape.typeName,
                            isNullable = component.isNullable || shape.isNullable,
                            isCommandKey = component.isCommandKey,
                            isEnumerable = shape.isEnumerable,
                            elementTypeName = shape.elementTypeName,
                            shape = shape.descriptor.withNullability(component.isNullable || shape.isNullable),
                            validationRules = validation.rules,
                            validateRecursively = validation.validateRecursively
                        )
                    )
                }
                return properties
            }
        }
        if (declaration.origin == Origin.JAVA && declaration.classKind == ClassKind.INTERFACE) {
            return describeJavaInterfaceProperties(declaration, identity)
        }
        val ordered = if (declaration.origin == Origin.JAVA) {
            declarations.values.sortedBy { it.simpleName.asString() }
        } else if (declaration.classKind == ClassKind.INTERFACE) {
            declarations.values.toList()
        } else {
            val constructorNames = declaration.primaryConstructor?.parameters.orEmpty()
                .filter { parameter -> parameter.isVal || parameter.isVar }
                .mapNotNull { parameter -> parameter.name?.asString() }
            if (constructorNames.isNotEmpty()) {
                constructorNames.mapNotNull(declarations::get)
            } else {
                declarations.values.filter { property -> Modifier.PUBLIC in property.modifiers }
                    .sortedBy { property -> property.simpleName.asString() }
            }
        }

        val properties = mutableListOf<PropertyModel>()
        for (property in ordered) {
            if (Modifier.PUBLIC !in property.modifiers && declaration.origin != Origin.JAVA) continue
            val name = property.simpleName.asString()
            val shape = describe(property.type.resolve(), "$identity.$name", property, allowMaps = true) ?: return null
            val annotated = listOfNotNull(property, property.getter, constructorParameters[name])
            val validation = extractValidation(annotated, shape, "$identity.$name", property) ?: return null
            properties.add(
                PropertyModel(
                    name = name,
                    typeName = shape.typeName,
                    isNullable = shape.isNullable,
                    isCommandKey = property.hasAnnotation(COMMAND_KEY_ANNOTATION) ||
                        property.getter?.hasAnnotation(COMMAND_KEY_ANNOTATION) == true ||
                        constructorParameters[name]?.hasAnnotation(COMMAND_KEY_ANNOTATION) == true,
                    isEnumerable = shape.isEnumerable,
                    elementTypeName = shape.elementTypeName,
                    shape = shape.descriptor,
                    validationRules = validation.rules,
                    validateRecursively = validation.validateRecursively
                )
            )
        }
        return properties
    }

    private fun describeJavaInterfaceProperties(
        declaration: KSClassDeclaration,
        identity: String
    ): List<PropertyModel>? {
        val properties = mutableListOf<PropertyModel>()
        for (function in declaration.getDeclaredFunctions().filter { candidate -> candidate.parameters.isEmpty() }) {
            val returnType = function.returnType?.resolve() ?: continue
            val returnName = returnType.declaration.qualifiedName?.asString()
            if (returnName == "kotlin.Unit" || returnName == "java.lang.Void") continue
            val sourceName = function.simpleName.asString()
            val name = when {
                sourceName.startsWith("get") && sourceName.length > 3 -> sourceName.substring(3).replaceFirstChar(Char::lowercase)
                sourceName.startsWith("is") && sourceName.length > 2 -> sourceName.substring(2).replaceFirstChar(Char::lowercase)
                else -> sourceName
            }
            val shape = describe(returnType, "$identity.$name", function, allowMaps = true) ?: return null
            val validation = extractValidation(listOf(function), shape, "$identity.$name", function) ?: return null
            properties.add(
                PropertyModel(
                    name = name,
                    typeName = shape.typeName,
                    isNullable = shape.isNullable,
                    isCommandKey = false,
                    isEnumerable = shape.isEnumerable,
                    elementTypeName = shape.elementTypeName,
                    shape = shape.descriptor,
                    validationRules = validation.rules,
                    validateRecursively = validation.validateRecursively
                )
            )
        }
        return properties.sortedBy(PropertyModel::name)
    }

    fun describe(type: KSType, identity: String, node: KSNode, allowMaps: Boolean = false): TypeShape? {
        if (type.isError || type.declaration is KSTypeParameter) {
            logger.error("'$identity' has an unresolvable or generic type.", node)
            return null
        }
        val declaration = type.declaration as? KSClassDeclaration
        val qualifiedName = declaration?.qualifiedName?.asString()
        if (declaration == null || qualifiedName == null) {
            logger.error("'$identity' must use a resolvable class type.", node)
            return null
        }
        if (qualifiedName in MAP_TYPE_NAMES) {
            if (!allowMaps) {
                logger.error(
                    ArcDiagnostic.PROXY_SHAPE,
                    "Artifact/property '$identity' value path 'value' uses map type '$qualifiedName'; maps are supported only for command, model, interface, and read-model properties.",
                    node
                )
                return null
            }
            val descriptor = describeMap(type, identity, "value", node) ?: return null
            return TypeShape(typeName = descriptorTypeName(descriptor), descriptor = descriptor, valueType = type)
        }
        if (qualifiedName in COLLECTION_TYPE_NAMES || qualifiedName == ARRAY_TYPE) {
            val element = concreteTypeArgument(type, 1, identity, "element", node)?.singleOrNull() ?: return null
            val elementDeclaration = element.declaration as? KSClassDeclaration
            val elementName = elementDeclaration?.qualifiedName?.asString()
            if (elementName == null) {
                logger.error("'$identity' has an unnamed collection element type.", node)
                return null
            }
            if (elementName in MAP_TYPE_NAMES || elementName in COLLECTION_TYPE_NAMES || elementName == ARRAY_TYPE ||
                element.arguments.isNotEmpty()
            ) {
                logger.error("'$identity' uses an unsupported nested or generic collection element shape.", node)
                return null
            }
            val conceptResolution = resolveConceptValue(elementDeclaration, "$identity element", node)
            if (!conceptResolution.isValid) return null
            val conceptValue = conceptResolution.value
            conceptValue?.let { underlying -> registerConcept(elementDeclaration, underlying) }
            val descriptor = TypeShapeDescriptor.sequence(
                sequenceKind(qualifiedName),
                TypeShapeDescriptor.value(elementName, element.nullability == Nullability.NULLABLE),
                type.nullability == Nullability.NULLABLE
            )
            return TypeShape(
                typeName = "$qualifiedName<$elementName>",
                descriptor = descriptor,
                valueType = element,
                underlyingTypeName = conceptValue?.declaration?.qualifiedName?.asString(),
                underlyingValueType = conceptValue
            )
        }
        if (type.arguments.isNotEmpty()) {
            logger.error("'$identity' uses unsupported generic type '$qualifiedName'.", node)
            return null
        }
        val conceptResolution = resolveConceptValue(declaration, identity, node)
        if (!conceptResolution.isValid) return null
        val conceptValue = conceptResolution.value
        conceptValue?.let { underlying -> registerConcept(declaration, underlying) }
        return TypeShape(
            typeName = qualifiedName,
            descriptor = TypeShapeDescriptor.value(qualifiedName, type.nullability == Nullability.NULLABLE),
            valueType = type,
            underlyingTypeName = conceptValue?.declaration?.qualifiedName?.asString(),
            underlyingValueType = conceptValue
        )
    }

    private fun describeMap(type: KSType, identity: String, path: String, node: KSNode): TypeShapeDescriptor? {
        val arguments = concreteTypeArgument(type, 2, identity, path, node) ?: return null
        val key = arguments[0]
        val keyName = key.declaration.qualifiedName?.asString()
        if (key.nullability == Nullability.NULLABLE || keyName !in STRING_TYPE_NAMES || key.arguments.isNotEmpty()) {
            return unsupportedMapShape(identity, "$path.key", "map keys must be nonnullable String", node)
        }
        val valueShape = describeMapValue(arguments[1], identity, path, node) ?: return null
        return TypeShapeDescriptor.map(
            TypeShapeDescriptor.value(requireNotNull(keyName), false),
            valueShape,
            MapKeyCodec.STRING,
            type.nullability == Nullability.NULLABLE
        )
    }

    private fun describeMapValue(type: KSType, identity: String, path: String, node: KSNode): TypeShapeDescriptor? {
        if (type.isError || type.declaration is KSTypeParameter) {
            return unsupportedMapShape(identity, path, "map values must use concrete types; type parameters are unsupported", node)
        }
        if (type.nullability == Nullability.NULLABLE) {
            return unsupportedMapShape(identity, path, "nullable map values and sequence elements are unsupported", node)
        }
        val declaration = type.declaration as? KSClassDeclaration
            ?: return unsupportedMapShape(identity, path, "map values must use resolvable class types", node)
        val name = declaration.qualifiedName?.asString()
            ?: return unsupportedMapShape(identity, path, "map values must use named class types", node)
        if (name in MAP_TYPE_NAMES) return describeMap(type, identity, "$path.value", node)
        if (name in COLLECTION_TYPE_NAMES || name == ARRAY_TYPE) {
            val element = concreteTypeArgument(type, 1, identity, "$path[]", node)?.singleOrNull() ?: return null
            val elementShape = describeMapValue(element, identity, "$path[]", node) ?: return null
            return TypeShapeDescriptor.sequence(sequenceKind(name), elementShape, false)
        }
        if (type.arguments.isNotEmpty()) {
            return unsupportedMapShape(identity, path, "generic, wildcard, and star-projected map value leaves are unsupported", node)
        }
        if (name !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
            return unsupportedMapShape(
                identity,
                path,
                "map value leaf '$name' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, temporal, and derived leaves are unsupported",
                node
            )
        }
        return TypeShapeDescriptor.value(name, false)
    }

    private fun concreteTypeArgument(
        type: KSType,
        count: Int,
        identity: String,
        path: String,
        node: KSNode
    ): List<KSType>? {
        if (type.arguments.size != count || type.arguments.any { argument ->
                argument.type == null || argument.variance != Variance.INVARIANT
            }
        ) {
            return unsupportedMapShape(
                identity,
                path,
                "raw and wildcard arguments are unsupported; star projections are unsupported; variant generic arguments are unsupported",
                node
            )?.let { emptyList() }
        }
        val arguments = type.arguments.map { argument -> requireNotNull(argument.type).resolve() }
        if (arguments.any { argument -> argument.isError || argument.declaration is KSTypeParameter }) {
            return unsupportedMapShape(identity, path, "unresolvable and type-parameter arguments are unsupported", node)
                ?.let { emptyList() }
        }
        return arguments
    }

    private fun unsupportedMapShape(identity: String, path: String, detail: String, node: KSNode): TypeShapeDescriptor? {
        logger.error(ArcDiagnostic.PROXY_SHAPE, "Artifact/property '$identity' value path '$path': $detail.", node)
        return null
    }

    private fun sequenceKind(qualifiedName: String): SequenceKind = when {
        qualifiedName == ARRAY_TYPE -> SequenceKind.ARRAY
        qualifiedName.endsWith("List") -> SequenceKind.LIST
        else -> SequenceKind.COLLECTION
    }

    private fun descriptorTypeName(shape: TypeShapeDescriptor): String = when (shape.kind) {
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

    fun collect(shape: TypeShape, identity: String, node: KSNode): Boolean =
        shape.descriptor.kind == TypeShapeKind.MAP || collectType(shape.valueType, identity, node)

    fun collectDeclaration(
        declaration: KSClassDeclaration,
        identity: String,
        node: KSNode = declaration,
        knownProperties: List<PropertyModel>? = null
    ): Boolean {
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null || declaration.parentDeclaration != null) {
            logger.error("'$identity' uses an unsupported local or nested model type.", node)
            return false
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error("'$identity' uses unsupported generic model '$qualifiedName'.", node)
            return false
        }
        val conceptResolution = resolveConceptValue(declaration, identity, node)
        if (!conceptResolution.isValid) return false
        conceptResolution.value?.let { underlying ->
            registerConcept(declaration, underlying)
            return collectType(underlying, "$identity concept value", node)
        }
        if (declaration.classKind == ClassKind.ENUM_CLASS) return collectEnum(declaration)
        if (isTerminal(qualifiedName)) return true
        if (declaration.classKind != ClassKind.CLASS && declaration.classKind != ClassKind.INTERFACE) {
            logger.error("'$identity' uses unsupported model declaration '$qualifiedName'.", node)
            return false
        }
        if (qualifiedName in collectedTypes || qualifiedName in collectedInterfaces || !visiting.add(qualifiedName)) {
            return true
        }

        val superDeclarations = declaration.superTypes.map { reference -> reference.resolve() }
            .filterNot(KSType::isError)
            .mapNotNull { type -> type.declaration as? KSClassDeclaration }
            .filter { candidate -> candidate.qualifiedName?.asString() !in ROOT_TYPE_NAMES }
            .toList()
        if (superDeclarations.any { candidate -> candidate.typeParameters.isNotEmpty() }) {
            logger.error("'$identity' uses an unsupported generic base type from '$qualifiedName'.", node)
            visiting.remove(qualifiedName)
            return false
        }
        val baseTypeName = superDeclarations.firstOrNull { candidate -> candidate.classKind == ClassKind.CLASS }
            ?.qualifiedName?.asString()
        val derivedTypeId = declaration.derivedTypeId()
        if (declaration.hasAnnotation(DERIVED_TYPE_ANNOTATION) && derivedTypeId.isNullOrBlank()) {
            logger.error("Derived type '$qualifiedName' must declare a nonblank @DerivedType id.", declaration)
            visiting.remove(qualifiedName)
            return false
        }
        if (declaration.classKind == ClassKind.INTERFACE && derivedTypeId != null) {
            logger.error("Interface '$qualifiedName' cannot carry @DerivedType; annotate concrete implementations.", declaration)
            visiting.remove(qualifiedName)
            return false
        }

        val describedProperties = knownProperties ?: describeProperties(declaration, qualifiedName)
        if (describedProperties == null) {
            visiting.remove(qualifiedName)
            return false
        }
        val properties = describedProperties.map { property ->
            val propertyDeclaration = if (property.shape.kind == TypeShapeKind.MAP) null else
                resolveNamedDeclaration(property.elementTypeName ?: property.typeName)
            val derivatives = propertyDeclaration
                ?.takeIf { candidate -> candidate.classKind == ClassKind.INTERFACE }
                ?.let(::derivativesFor)
                .orEmpty()
                .mapNotNull { candidate -> candidate.qualifiedName?.asString() }
            property.copy(derivatives = derivatives)
        }
        val location = qualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank)
        if (declaration.classKind == ClassKind.INTERFACE) {
            collectedInterfaces[qualifiedName] = InterfaceModel(
                name = declaration.simpleName.asString(),
                fullyQualifiedName = qualifiedName,
                location = location,
                properties = properties
            )
        } else {
            collectedTypes[qualifiedName] = TypeModel(
                name = declaration.simpleName.asString(),
                fullyQualifiedName = qualifiedName,
                location = location,
                properties = properties,
                baseTypeName = baseTypeName,
                derivedTypeId = derivedTypeId
            )
        }

        var valid = true
        for (superDeclaration in superDeclarations) {
            val superName = superDeclaration.qualifiedName?.asString() ?: continue
            if (!collectDeclaration(superDeclaration, "$qualifiedName base $superName", declaration)) valid = false
        }

        val declarations = declaration.getDeclaredProperties().associateBy { property -> property.simpleName.asString() }
        val accessors = if (declaration.origin == Origin.JAVA) {
            declaration.getDeclaredFunctions().filter { function -> function.parameters.isEmpty() }
                .associateBy { function -> function.simpleName.asString() }
        } else {
            emptyMap()
        }
        for (property in properties) {
            val propertyDeclaration = declarations[property.name]
            val accessor = accessors[property.name]
                ?: accessors["get${property.name.replaceFirstChar(Char::uppercase)}"]
                ?: accessors["is${property.name.replaceFirstChar(Char::uppercase)}"]
            val propertyType = propertyDeclaration?.type?.resolve() ?: accessor?.returnType?.resolve()
            val propertyNode = propertyDeclaration ?: accessor
            if (property.shape.kind == TypeShapeKind.MAP) {
                // Runtime-safe map leaves never contribute model declarations to the closed graph.
            } else if (propertyType == null || propertyNode == null) {
                val reachableName = property.elementTypeName ?: property.typeName
                if (!collectNamedType(reachableName, "$qualifiedName.${property.name}", declaration)) valid = false
            } else {
                val shape = describe(propertyType, "$qualifiedName.${property.name}", propertyNode, allowMaps = true)
                if (shape == null || !collect(shape, "$qualifiedName.${property.name}", propertyNode)) valid = false
            }
            for (derivativeName in property.derivatives) {
                if (!collectNamedType(derivativeName, "$qualifiedName.${property.name} derivative", declaration)) valid = false
            }
        }
        for (derivative in derivativesFor(declaration)) {
            val derivativeName = derivative.qualifiedName?.asString() ?: continue
            if (!collectDeclaration(derivative, "$qualifiedName derivative $derivativeName", derivative)) valid = false
        }

        visiting.remove(qualifiedName)
        if (!valid) {
            collectedTypes.remove(qualifiedName)
            collectedInterfaces.remove(qualifiedName)
        }
        return valid
    }

    fun extractValidation(
        annotated: Iterable<KSAnnotated>,
        shape: TypeShape,
        identity: String,
        node: KSNode,
        sourceAnnotations: List<SourceValidationAnnotation> = emptyList()
    ): ValidationMetadata? {
        val conceptRules = extractConceptValidationRules(shape, identity, node) ?: return null
        return validationExtractor.extract(
            annotated,
            shape,
            identity,
            node,
            sourceAnnotations,
            conceptRules
        )
    }

    private fun extractConceptValidationRules(
        shape: TypeShape,
        identity: String,
        node: KSNode
    ): List<ValidationRuleModel>? {
        val underlying = shape.underlyingValueType ?: return emptyList()
        val declaration = shape.valueType.declaration as? KSClassDeclaration ?: return emptyList()
        val underlyingName = requireNotNull(underlying.declaration.qualifiedName).asString()
        val effectiveShape = TypeShape(
            typeName = underlyingName,
            descriptor = TypeShapeDescriptor.value(underlyingName),
            valueType = underlying
        )
        return collectConceptValidationRules(declaration, effectiveShape, identity, node, mutableSetOf())
    }

    private fun collectConceptValidationRules(
        declaration: KSClassDeclaration,
        effectiveShape: TypeShape,
        identity: String,
        node: KSNode,
        visited: MutableSet<String>
    ): List<ValidationRuleModel>? {
        val declarationName = declaration.qualifiedName?.asString() ?: return emptyList()
        if (!visited.add(declarationName)) return emptyList()
        val rules = validationRulesForConceptDeclaration(declaration, effectiveShape, identity, node)?.toMutableList()
            ?: return null
        val conceptSuperDeclarations = declaration.superTypes.map { reference -> reference.resolve().declaration }
            .filterIsInstance<KSClassDeclaration>()
            .filter { candidate ->
                candidate.qualifiedName?.asString() != CONCEPT_AS_TYPE && candidate.isAssignableTo(CONCEPT_AS_TYPE)
            }
            .sortedBy { candidate -> candidate.qualifiedName?.asString() }
            .toList()
        for (superDeclaration in conceptSuperDeclarations) {
            rules += collectConceptValidationRules(superDeclaration, effectiveShape, identity, node, visited) ?: return null
        }
        val carriedConcept = findConceptValue(declaration, emptyMap(), mutableSetOf())
            ?.declaration as? KSClassDeclaration
        if (carriedConcept != null && carriedConcept.isAssignableTo(CONCEPT_AS_TYPE)) {
            rules += collectConceptValidationRules(carriedConcept, effectiveShape, identity, node, visited) ?: return null
        }
        return rules
    }

    private fun validationRulesForConceptDeclaration(
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
            function.parameters.isEmpty() && function.simpleName.asString() in setOf(
                "value",
                "getValue",
                constructorName.orEmpty(),
                constructorName?.let { name -> "get${name.replaceFirstChar(Char::uppercase)}" }.orEmpty()
            )
        }.toList()
        val sourceAnnotations = if (declaration.origin == Origin.JAVA) {
            declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
                ?.let { source -> parseJavaRecordProperties(source, declaration.simpleName.asString()) }
                ?.singleOrNull()
                ?.validationAnnotations
                .orEmpty()
        } else {
            emptyList()
        }
        return validationExtractor.extract(
            listOfNotNull(constructorParameter) + properties + accessors,
            effectiveShape,
            "$identity concept value",
            node,
            sourceAnnotations
        )?.rules
    }

    private fun resolveConceptValue(
        declaration: KSClassDeclaration,
        identity: String,
        node: KSNode
    ): ConceptValueResolution {
        if (!declaration.isAssignableTo(CONCEPT_AS_TYPE)) return ConceptValueResolution(true, null)
        val value = findConceptValue(declaration, emptyMap(), mutableSetOf())
        val valueDeclaration = value?.declaration as? KSClassDeclaration
        val valueName = valueDeclaration?.qualifiedName?.asString()
        if (value == null || value.isError || value.declaration is KSTypeParameter || valueName == null) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Concept '$identity' implements ConceptAs<T>, but its underlying generic type cannot be resolved.",
                node
            )
            return ConceptValueResolution(false, null)
        }
        if (value.arguments.isNotEmpty() || valueName in MAP_TYPE_NAMES || valueName in COLLECTION_TYPE_NAMES || valueName == ARRAY_TYPE) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Concept artifact/property '$identity' value path 'conceptValue' has unsupported non-scalar underlying type '$valueName'.",
                node
            )
            return ConceptValueResolution(false, null)
        }
        if (valueDeclaration.isAssignableTo(CONCEPT_AS_TYPE)) {
            return resolveConceptValue(valueDeclaration, identity, node)
        }
        return ConceptValueResolution(true, value)
    }

    private fun findConceptValue(
        declaration: KSClassDeclaration,
        substitutions: Map<String, KSType>,
        seen: MutableSet<String>
    ): KSType? {
        val declarationName = declaration.qualifiedName?.asString() ?: return null
        if (!seen.add(declarationName)) return null
        for (reference in declaration.superTypes) {
            val superType = reference.resolve()
            val superDeclaration = superType.declaration as? KSClassDeclaration ?: continue
            val superName = superDeclaration.qualifiedName?.asString()
            if (superName == CONCEPT_AS_TYPE) {
                val argument = superType.arguments.singleOrNull()?.type?.resolve() ?: return null
                return substitute(argument, substitutions)
            }
            val superSubstitutions = superDeclaration.typeParameters.mapIndexedNotNull { index, parameter ->
                val argument = superType.arguments.getOrNull(index)?.type?.resolve() ?: return@mapIndexedNotNull null
                parameter.name.asString() to substitute(argument, substitutions)
            }.toMap()
            findConceptValue(superDeclaration, superSubstitutions, seen)?.let { return it }
        }
        return null
    }

    private fun substitute(type: KSType, substitutions: Map<String, KSType>): KSType =
        (type.declaration as? KSTypeParameter)?.name?.asString()?.let(substitutions::get) ?: type

    private fun registerConcept(declaration: KSClassDeclaration, underlying: KSType) {
        val name = requireNotNull(declaration.qualifiedName).asString()
        val resolvedUnderlyingName = requireNotNull(underlying.declaration.qualifiedName).asString()
        val underlyingName = if (declaration.origin == Origin.JAVA) {
            KOTLIN_TO_JAVA_BOXED_TYPE_NAMES[resolvedUnderlyingName] ?: resolvedUnderlyingName
        } else {
            resolvedUnderlyingName
        }
        collectedConcepts.putIfAbsent(
            name,
            ConceptModel(declaration.simpleName.asString(), name, underlyingName)
        )
    }

    private data class ConceptValueResolution(val isValid: Boolean, val value: KSType?)

    private fun describeJavaType(
        sourceTypeName: String,
        owner: KSClassDeclaration,
        identity: String
    ): TypeShape? {
        val rawType = sourceTypeName.removeSuffix("[]")
        val genericStart = rawType.indexOf('<')
        val baseName = if (genericStart >= 0) rawType.substring(0, genericStart) else rawType
        val argumentName = if (genericStart >= 0) rawType.substring(genericStart + 1, rawType.lastIndexOf('>')).trim() else null
        val isEnumerable = sourceTypeName.endsWith("[]") || baseName in JAVA_COLLECTION_NAMES
        if (baseName in JAVA_MAP_NAMES) {
            val descriptor = describeJavaMapType(sourceTypeName, owner, identity, "value") ?: return null
            val declaration = resolveJavaDeclaration("java.util.Map", owner)
                ?: return unsupportedJavaMapShape(identity, "value", "java.util.Map cannot be resolved", owner)
            return TypeShape(descriptorTypeName(descriptor), descriptor, declaration.asStarProjectedType())
        }
        if (isEnumerable && (argumentName == null && !sourceTypeName.endsWith("[]"))) {
            logger.error("'$identity' must use a concrete collection element type.", owner)
            return null
        }
        val valueSourceName = argumentName ?: rawType
        if ('<' in valueSourceName || '?' in valueSourceName || '*' in valueSourceName) {
            logger.error("'$identity' uses an unsupported nested, wildcard, or generic shape.", owner)
            return null
        }
        val declaration = resolveJavaDeclaration(valueSourceName, owner)
        if (declaration == null) {
            logger.error("'$identity' has unresolvable Java type '$sourceTypeName'.", owner)
            return null
        }
        val valueType = declaration.asStarProjectedType()
        val valueName = requireNotNull(declaration.qualifiedName).asString()
        val collectionName = if (sourceTypeName.endsWith("[]")) ARRAY_TYPE else normalizeJavaCollectionName(baseName)
        val descriptor = if (isEnumerable) {
            TypeShapeDescriptor.sequence(sequenceKind(collectionName), TypeShapeDescriptor.value(valueName))
        } else {
            TypeShapeDescriptor.value(valueName)
        }
        return TypeShape(
            typeName = if (isEnumerable) "$collectionName<$valueName>" else valueName,
            descriptor = descriptor,
            valueType = valueType
        )
    }

    private data class JavaTypeUse(val typeName: String, val isNullable: Boolean)

    private data class JavaGenericType(val baseName: String, val arguments: List<JavaTypeUse>)

    private fun describeJavaMapType(
        sourceTypeName: String,
        owner: KSClassDeclaration,
        identity: String,
        path: String
    ): TypeShapeDescriptor? {
        val parsed = parseJavaGenericType(sourceTypeName)
            ?: return unsupportedJavaMapShape(
                identity,
                path,
                "raw, wildcard, and type-parameter map arguments are unsupported; star projections are unsupported",
                owner
            )
        if (parsed.baseName !in JAVA_MAP_NAMES || parsed.arguments.size != 2) {
            return unsupportedJavaMapShape(identity, path, "map values must declare exactly two concrete arguments", owner)
        }
        val key = parsed.arguments[0]
        val keyName = JAVA_TYPE_ALIASES[key.typeName] ?: key.typeName
        if (key.isNullable || keyName != "java.lang.String" && keyName != "String") {
            return unsupportedJavaMapShape(identity, "$path.key", "map keys must be nonnullable String", owner)
        }
        val valueShape = describeJavaMapValue(parsed.arguments[1], owner, identity, path) ?: return null
        return TypeShapeDescriptor.map(
            TypeShapeDescriptor.value("java.lang.String"),
            valueShape,
            MapKeyCodec.STRING
        )
    }

    private fun describeJavaMapValue(
        typeUse: JavaTypeUse,
        owner: KSClassDeclaration,
        identity: String,
        path: String
    ): TypeShapeDescriptor? {
        if (typeUse.isNullable) {
            return unsupportedJavaMapShape(
                identity,
                path,
                "nullable map values and sequence elements are unsupported",
                owner
            )
        }
        val sourceTypeName = typeUse.typeName
        if ('?' in sourceTypeName || '*' in sourceTypeName) {
            return unsupportedJavaMapShape(
                identity,
                path,
                "raw and wildcard arguments are unsupported; star projections are unsupported",
                owner
            )
        }
        val parsed = parseJavaGenericType(sourceTypeName)
        if (parsed != null) {
            if (parsed.baseName in JAVA_MAP_NAMES) {
                return describeJavaMapType(sourceTypeName, owner, identity, "$path.value")
            }
            if (parsed.baseName in JAVA_COLLECTION_NAMES) {
                if (parsed.arguments.size != 1) {
                    return unsupportedJavaMapShape(identity, "$path[]", "sequences require one concrete element type", owner)
                }
                val element = describeJavaMapValue(parsed.arguments.single(), owner, identity, "$path[]") ?: return null
                return TypeShapeDescriptor.sequence(
                    sequenceKind(normalizeJavaCollectionName(parsed.baseName)),
                    element
                )
            }
            return unsupportedJavaMapShape(identity, path, "generic map value leaves are unsupported", owner)
        }
        if (sourceTypeName.endsWith("[]")) {
            val element = describeJavaMapValue(
                parseJavaTypeUse(sourceTypeName.removeSuffix("[]")),
                owner,
                identity,
                "$path[]"
            ) ?: return null
            return TypeShapeDescriptor.sequence(SequenceKind.ARRAY, element)
        }
        val declaration = resolveJavaDeclaration(sourceTypeName, owner)
            ?: return unsupportedJavaMapShape(identity, path, "map value type '$sourceTypeName' cannot be resolved", owner)
        val name = requireNotNull(declaration.qualifiedName).asString()
        if (name !in MAP_SAFE_PRIMITIVE_TYPE_NAMES) {
            return unsupportedJavaMapShape(
                identity,
                path,
                "map value leaf '$name' is not a runtime-safe JavaScript primitive; model, concept, enum, UUID, temporal, and derived leaves are unsupported",
                owner
            )
        }
        return TypeShapeDescriptor.value(name)
    }

    private fun parseJavaGenericType(sourceTypeName: String): JavaGenericType? {
        val typeUse = parseJavaTypeUse(sourceTypeName)
        val opening = typeUse.typeName.indexOf('<')
        if (opening < 0 || !typeUse.typeName.endsWith('>')) return null
        val baseName = typeUse.typeName.substring(0, opening).trim()
        val body = typeUse.typeName.substring(opening + 1, typeUse.typeName.length - 1)
        val arguments = mutableListOf<JavaTypeUse>()
        var depth = 0
        var start = 0
        body.forEachIndexed { index, character ->
            when (character) {
                '<' -> depth++
                '>' -> depth--
                ',' -> if (depth == 0) {
                    arguments += parseJavaTypeUse(body.substring(start, index))
                    start = index + 1
                }
            }
        }
        if (body.substring(start).isNotBlank()) arguments += parseJavaTypeUse(body.substring(start))
        return JavaGenericType(baseName, arguments)
    }

    private fun parseJavaTypeUse(sourceTypeName: String): JavaTypeUse {
        var remaining = sourceTypeName.trim()
        var isNullable = false
        while (remaining.startsWith('@')) {
            val name = Regex("^@([A-Za-z_$][A-Za-z0-9_$.]*)").find(remaining) ?: break
            val simpleName = name.groupValues[1].substringAfterLast('.')
            isNullable = isNullable || simpleName == "Nullable" || simpleName == "CheckForNull"
            var end = name.range.last + 1
            while (end < remaining.length && remaining[end].isWhitespace()) end++
            if (end < remaining.length && remaining[end] == '(') {
                var depth = 1
                end++
                while (end < remaining.length && depth > 0) {
                    when (remaining[end++]) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                }
            }
            remaining = remaining.substring(end).trimStart()
        }
        return JavaTypeUse(remaining.trim(), isNullable)
    }

    private fun unsupportedJavaMapShape(
        identity: String,
        path: String,
        detail: String,
        owner: KSClassDeclaration
    ): Nothing? {
        logger.error(ArcDiagnostic.PROXY_SHAPE, "Artifact/property '$identity' value path '$path': $detail.", owner)
        return null
    }

    private fun TypeShapeDescriptor.withNullability(nullable: Boolean): TypeShapeDescriptor = when (kind) {
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

    private fun resolveNamedDeclaration(typeName: String): KSClassDeclaration? {
        val currentResolver = resolver ?: return null
        return currentResolver.getClassDeclarationByName(currentResolver.getKSNameFromString(typeName))
    }

    private fun collectNamedType(typeName: String, identity: String, node: KSNode): Boolean {
        if (isTerminal(typeName)) return true
        val currentResolver = resolver ?: return false
        val declaration = currentResolver.getClassDeclarationByName(currentResolver.getKSNameFromString(typeName))
        if (declaration == null) {
            logger.error("'$identity' has unresolvable type '$typeName'.", node)
            return false
        }
        return collectDeclaration(declaration, identity, node)
    }

    private fun resolveJavaDeclaration(typeName: String, owner: KSClassDeclaration): KSClassDeclaration? {
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

    private fun normalizeJavaCollectionName(name: String): String = when (name) {
        "List", "java.util.List" -> "java.util.List"
        "Collection", "java.util.Collection" -> "java.util.Collection"
        else -> name
    }

    private fun collectType(type: KSType, identity: String, node: KSNode): Boolean {
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        val qualifiedName = declaration.qualifiedName?.asString() ?: return false
        if (isTerminal(qualifiedName)) return true
        val conceptResolution = resolveConceptValue(declaration, identity, node)
        if (!conceptResolution.isValid) return false
        conceptResolution.value?.let { underlying ->
            registerConcept(declaration, underlying)
            return collectType(underlying, "$identity concept value", node)
        }
        return collectDeclaration(declaration, identity, node)
    }

    private fun derivativesFor(base: KSClassDeclaration): List<KSClassDeclaration> {
        val baseName = base.qualifiedName?.asString() ?: return emptyList()
        val derivatives = derivedDeclarations.filter { candidate ->
            candidate.classKind == ClassKind.CLASS && Modifier.ABSTRACT !in candidate.modifiers &&
                candidate.qualifiedName?.asString() != baseName && candidate.isAssignableTo(baseName)
        }
        derivatives.groupBy { candidate -> candidate.derivedTypeId() }
            .filterKeys { id -> !id.isNullOrBlank() }
            .filterValues { declarations -> declarations.size > 1 }
            .forEach { (id, declarations) ->
                val key = "$baseName:$id"
                if (reportedDuplicateDerivedIds.add(key)) {
                    val names = declarations.mapNotNull { declaration -> declaration.qualifiedName?.asString() }.sorted()
                    logger.error(
                        ArcDiagnostic.PROXY_SHAPE,
                        "Base type '$baseName' has ambiguous @DerivedType id '$id' on ${names.joinToString()}.",
                        declarations.first()
                    )
                }
            }
        return derivatives.sortedBy { candidate -> candidate.qualifiedName?.asString() }
    }

    private fun KSClassDeclaration.isAssignableTo(baseName: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
        val ownName = qualifiedName?.asString() ?: return false
        if (!seen.add(ownName)) return false
        return superTypes.map { reference -> reference.resolve().declaration as? KSClassDeclaration }
            .filterNotNull()
            .any { superDeclaration ->
                superDeclaration.qualifiedName?.asString() == baseName || superDeclaration.isAssignableTo(baseName, seen)
            }
    }

    private fun collectEnum(declaration: KSClassDeclaration): Boolean {
        val qualifiedName = requireNotNull(declaration.qualifiedName).asString()
        if (qualifiedName in collectedEnums) return true
        val entries = declaration.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { candidate -> candidate.classKind == ClassKind.ENUM_ENTRY }
            .toList()
        val implementsArcEnum = declaration.superTypes.any { reference ->
            reference.resolve().declaration.qualifiedName?.asString() == ARC_ENUM_TYPE
        }
        val constructorCanSupplyValue = declaration.primaryConstructor?.parameters?.singleOrNull()
            ?.type?.resolve()?.declaration?.qualifiedName?.asString() in INTEGER_TYPE_NAMES
        val constructorValues = if (implementsArcEnum && constructorCanSupplyValue) {
            declaration.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText()
                ?.let { source -> parseEnumConstructorValues(source, declaration.simpleName.asString()) }
                .orEmpty()
        } else {
            emptyMap()
        }
        var valid = true
        val members = entries.mapIndexed { ordinal, entry ->
            val name = entry.simpleName.asString()
            val annotatedValue = entry.intAnnotationArgument(ARC_ENUM_VALUE_ANNOTATION)
            val constructorValue = constructorValues[name]
            val value = if (!implementsArcEnum) {
                ordinal
            } else if (annotatedValue != null && constructorValue != null && annotatedValue != constructorValue) {
                logger.error(
                    ArcDiagnostic.ENUM_VALUE,
                    "Enum '$qualifiedName.$name' has ambiguous Arc values: constructor $constructorValue and " +
                        "@ArcEnumValue $annotatedValue.",
                    entry
                )
                valid = false
                annotatedValue
            } else {
                annotatedValue ?: constructorValue ?: run {
                    logger.error(
                        ArcDiagnostic.ENUM_VALUE,
                        "Enum '$qualifiedName.$name' implements ArcEnum, but its value cannot be proven from a " +
                            "single integer-literal constructor argument; add @ArcEnumValue with the wire value.",
                        entry
                    )
                    valid = false
                    0
                }
            }
            EnumMemberModel(name, value)
        }
        if (!valid) return false
        collectedEnums[qualifiedName] = EnumModel(
            name = declaration.simpleName.asString(),
            fullyQualifiedName = qualifiedName,
            location = qualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
            members = members,
            isFlags = declaration.hasAnnotation(FLAGS_ANNOTATION)
        )
        return true
    }

    private fun KSClassDeclaration.derivedTypeId(): String? = annotations
        .firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == DERIVED_TYPE_ANNOTATION
        }
        ?.arguments?.firstOrNull { argument -> argument.name?.asString() == "id" || argument.name == null }
        ?.value as? String

    private fun KSAnnotated.intAnnotationArgument(qualifiedName: String): Int? = annotations
        .firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
        }
        ?.arguments?.firstOrNull { argument -> argument.name?.asString() == "value" || argument.name == null }
        ?.value
        ?.let { value -> (value as? Number)?.toInt() }

    private fun isTerminal(qualifiedName: String): Boolean = qualifiedName in TERMINAL_TYPE_NAMES

    private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean = annotations.any { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

    private companion object {
        const val ARRAY_TYPE = "kotlin.Array"
        const val CONCEPT_AS_TYPE = "io.cratis.arc.concepts.ConceptAs"
        const val ARC_ENUM_TYPE = "io.cratis.arc.concepts.ArcEnum"
        const val ARC_ENUM_VALUE_ANNOTATION = "io.cratis.arc.concepts.ArcEnumValue"
        const val FLAGS_ANNOTATION = "io.cratis.arc.concepts.Flags"
        const val COMMAND_KEY_ANNOTATION = "io.cratis.arc.artifacts.CommandKey"
        const val DERIVED_TYPE_ANNOTATION = "io.cratis.arc.polymorphism.DerivedType"
        val COLLECTION_TYPE_NAMES = setOf(
            "kotlin.collections.List",
            "kotlin.collections.MutableList",
            "kotlin.collections.Collection",
            "kotlin.collections.MutableCollection",
            "java.util.List",
            "java.util.Collection"
        )
        val MAP_TYPE_NAMES = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap", "java.util.Map")
        val JAVA_COLLECTION_NAMES = setOf("List", "Collection", "java.util.List", "java.util.Collection")
        val JAVA_MAP_NAMES = setOf("Map", "java.util.Map")
        val JAVA_TYPE_ALIASES = mapOf(
            "boolean" to "java.lang.Boolean",
            "byte" to "java.lang.Byte",
            "char" to "java.lang.Character",
            "double" to "java.lang.Double",
            "float" to "java.lang.Float",
            "int" to "java.lang.Integer",
            "long" to "java.lang.Long",
            "short" to "java.lang.Short"
        )
        val KOTLIN_TO_JAVA_BOXED_TYPE_NAMES = mapOf(
            "kotlin.Boolean" to "java.lang.Boolean",
            "kotlin.Byte" to "java.lang.Byte",
            "kotlin.Char" to "java.lang.Character",
            "kotlin.Double" to "java.lang.Double",
            "kotlin.Float" to "java.lang.Float",
            "kotlin.Int" to "java.lang.Integer",
            "kotlin.Long" to "java.lang.Long",
            "kotlin.Short" to "java.lang.Short",
            "kotlin.String" to "java.lang.String"
        )
        val ROOT_TYPE_NAMES = setOf(
            "kotlin.Any",
            "java.lang.Object",
            "java.lang.Record",
            "java.io.Serializable",
            "java.lang.Cloneable"
        )
        val INTEGER_TYPE_NAMES = setOf("kotlin.Int", "java.lang.Integer", "int")
        val STRING_TYPE_NAMES = setOf("kotlin.String", "java.lang.String")
        val MAP_SAFE_PRIMITIVE_TYPE_NAMES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Int", "kotlin.Short", "kotlin.String",
            "java.lang.Boolean", "java.lang.Byte", "java.lang.Character", "java.lang.Integer", "java.lang.Short",
            "java.lang.String", "boolean", "byte", "char", "int", "short"
        )
        val TERMINAL_TYPE_NAMES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Char", "kotlin.Double", "kotlin.Float", "kotlin.Int",
            "kotlin.Long", "kotlin.Short", "kotlin.String", "java.lang.Boolean", "java.lang.Byte",
            "java.lang.Character", "java.lang.Double", "java.lang.Float", "java.lang.Integer", "java.lang.Long",
            "java.lang.Short", "java.lang.String", "boolean", "byte", "char", "double", "float", "int",
            "long", "short", "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID", "java.util.Date",
            "java.time.Duration", "java.time.Instant", "java.time.LocalDate", "java.time.LocalDateTime",
            "java.time.LocalTime", "java.time.OffsetDateTime", "java.time.OffsetTime", "java.time.Period",
            "java.time.Year", "java.time.YearMonth", "java.time.ZonedDateTime"
        )
    }
}

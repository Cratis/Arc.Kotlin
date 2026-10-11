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
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import io.cratis.arc.json.ArcCamelCase
import io.cratis.arc.metadata.MapKeyCodec
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import java.io.File

/** Collects the closed, language-neutral model graph used by generated modules and manifests. */
internal class MetadataCollector(
    private val logger: ArcDiagnosticReporter,
    private val fluentRules: Map<String, List<ValidationRuleModel>> = emptyMap(),
    private val binaryRecords: BinaryRecordMetadata = BinaryRecordMetadata(null)
) : ModelShapeSource {
    private val validationExtractor = ValidationMetadataExtractor(logger)
    private val conceptResolver = ConceptResolver(logger)
    private val mapShapes = MapShapeDescriber(logger)
    private val collectedTypes = linkedMapOf<String, TypeModel>()
    private val collectedInterfaces = linkedMapOf<String, InterfaceModel>()
    private val collectedEnums = linkedMapOf<String, EnumModel>()
    private val collectedConcepts = linkedMapOf<String, ConceptModel>()
    private enum class Visit { ACTIVE, COMPLETED, INVALID }
    private val visits = mutableMapOf<String, Visit>()
    private var resolver: Resolver? = null
    private var derivedDeclarations: List<KSClassDeclaration> = emptyList()
    private val reportedDuplicateDerivedIds = mutableSetOf<String>()
    // Validation spans rounds even when command/model metadata has already been collected. Retain names, not symbols.
    private val propertyDerivedTypeNames = mutableSetOf<String>()
    private val concretePropertyUses = mutableSetOf<ConcretePropertyUse>()
    private val reportedConcretePropertyUses = mutableSetOf<ConcretePropertyUse>()
    private val collectedDerivedTypes = linkedSetOf<DerivedTypeRegistrationModel>()

    val types: List<TypeModel> get() = collectedTypes.values.sortedBy(TypeModel::fullyQualifiedName)
    val interfaces: List<InterfaceModel> get() = collectedInterfaces.values.sortedBy(InterfaceModel::fullyQualifiedName)
    val enums: List<EnumModel> get() = collectedEnums.values.sortedBy(EnumModel::fullyQualifiedName)
    val concepts: List<ConceptModel> get() = collectedConcepts.values.sortedBy(ConceptModel::fullyQualifiedName)

    /**
     * Base-to-derivative mappings for every collected model type that has `@DerivedType` derivatives.
     *
     * Only mappings whose base and derivative both survived collection are reported, so a module never carries a
     * registration for a type it did not also describe.
     */
    val derivedTypes: List<DerivedTypeRegistrationModel>
        get() = collectedDerivedTypes
            .filter { registration ->
                (registration.baseTypeName in collectedTypes || registration.baseTypeName in collectedInterfaces) &&
                    collectedTypes[registration.derivedTypeName]?.derivedTypeId != null
            }
            .sortedWith(
                compareBy(
                    DerivedTypeRegistrationModel::baseTypeName,
                    DerivedTypeRegistrationModel::derivedTypeName
                )
            )

    private fun javaTypes() = JavaSourceTypeDescriber(logger, resolver)

    fun useResolver(resolver: Resolver, derivedTypeNames: Set<String> = emptySet()) {
        this.resolver = resolver
        collectedTypes.clear()
        collectedInterfaces.clear()
        collectedEnums.clear()
        collectedConcepts.clear()
        visits.clear()
        propertyDerivedTypeNames += derivedTypeNames
        propertyDerivedTypeNames += resolver.getSymbolsWithAnnotation(DERIVED_TYPE_ANNOTATION)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { declaration -> declaration.qualifiedName?.asString() }
        derivedDeclarations = propertyDerivedTypeNames.sorted().mapNotNull(::resolveNamedDeclaration)
        for (use in concretePropertyUses) {
            val owner = resolveNamedDeclaration(use.ownerName) ?: continue
            val base = resolveNamedDeclaration(use.baseName) ?: continue
            validateConcretePropertyUse(use, base, propertyUseNode(owner, use.propertyName))
        }
    }

    /** Drop semantic round state before returning to KSP; only plain graph values survive for rendering. */
    fun releaseSymbols() {
        resolver = null
        derivedDeclarations = emptyList()
    }

    fun propertiesFor(typeName: String): List<PropertyModel>? = collectedTypes[typeName]?.properties

    fun isConcept(typeName: String): Boolean = collectedConcepts.containsKey(typeName)

    fun describeProperties(declaration: KSClassDeclaration, identity: String): List<PropertyModel>? {
        IgnoreValidationPolicy.validateWireOwner(declaration, logger)
        // Validate roots before the Java record/interface fast paths as well as Kotlin property collection.
        if (declaration.hasAnnotation(COMMAND_ANNOTATION) && !CommandInputValidator(logger, resolver, ::derivativesFor).validate(declaration)) return null
        return ModelPropertyDescriber(logger, binaryRecords, resolver, this).describe(declaration, identity)
    }

    override fun describe(type: KSType, identity: String, node: KSNode, allowMaps: Boolean): TypeShape? {
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
        if (qualifiedName in MAP_TYPE_NAMES) return describeMapShape(type, qualifiedName, identity, node, allowMaps)
        if (qualifiedName in COLLECTION_TYPE_NAMES || qualifiedName == ARRAY_TYPE) {
            return describeCollectionShape(type, qualifiedName, identity, node)
        }
        if (type.arguments.isNotEmpty()) {
            logger.error("'$identity' uses unsupported generic type '$qualifiedName'.", node)
            return null
        }
        val conceptResolution = conceptResolver.resolve(declaration, identity, node)
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

    private fun describeMapShape(
        type: KSType,
        qualifiedName: String,
        identity: String,
        node: KSNode,
        allowMaps: Boolean
    ): TypeShape? {
        if (!allowMaps) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Artifact/property '$identity' value path 'value' uses map type '$qualifiedName'; maps are supported only for command, model, interface, and read-model properties.",
                node
            )
            return null
        }
        val descriptor = mapShapes.describeMap(type, ShapeSite(identity, "value", node)) ?: return null
        return TypeShape(typeName = descriptorTypeName(descriptor), descriptor = descriptor, valueType = type)
    }

    private fun describeCollectionShape(type: KSType, qualifiedName: String, identity: String, node: KSNode): TypeShape? {
        val element = mapShapes.concreteTypeArguments(type, 1, ShapeSite(identity, "element", node))?.singleOrNull()
            ?: return null
        if (node is KSValueParameter && element.nullability == Nullability.NULLABLE) {
            mapShapes.unsupported(ShapeSite(identity, "element", node), "nullable sequence elements are unsupported")
            return null
        }
        if (element.nullability == Nullability.NULLABLE || element.annotations.hasNullableTypeUse() ||
            type.arguments.single().type?.annotations?.hasNullableTypeUse() == true
        ) {
            mapShapes.unsupportedSequenceElement(identity, node)
            return null
        }
        val elementDeclaration = element.declaration as? KSClassDeclaration
        val elementName = elementDeclaration?.qualifiedName?.asString()
        if (elementDeclaration == null || elementName == null) {
            logger.error("'$identity' has an unnamed collection element type.", node)
            return null
        }
        if (elementName in MAP_TYPE_NAMES || elementName in COLLECTION_TYPE_NAMES || elementName == ARRAY_TYPE ||
            element.arguments.isNotEmpty()
        ) {
            logger.error("'$identity' uses an unsupported nested or generic collection element shape.", node)
            return null
        }
        val conceptResolution = conceptResolver.resolve(elementDeclaration, "$identity element", node)
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

    fun collect(shape: TypeShape, identity: String, node: KSNode): Boolean =
        shape.descriptor.kind == TypeShapeKind.MAP || collectType(shape.valueType, identity, node)

    fun collectDeclaration(
        declaration: KSClassDeclaration,
        identity: String,
        node: KSNode = declaration,
        knownProperties: List<PropertyModel>? = null
    ): Boolean {
        val name = declaration.qualifiedName?.asString()
        when (visits[name]) {
            Visit.ACTIVE, Visit.COMPLETED -> return true
            Visit.INVALID -> return false
            null -> Unit
        }
        if (name != null) visits[name] = Visit.ACTIVE
        val valid = collectUnvisitedDeclaration(declaration, identity, node, knownProperties)
        if (name != null) visits[name] = if (valid) Visit.COMPLETED else Visit.INVALID
        return valid
    }

    private fun collectUnvisitedDeclaration(
        declaration: KSClassDeclaration,
        identity: String,
        node: KSNode,
        knownProperties: List<PropertyModel>?
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
        val conceptResolution = conceptResolver.resolve(declaration, identity, node)
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
        val header = modelHeader(declaration, qualifiedName, identity, node) ?: return false
        val describedProperties = knownProperties ?: describeProperties(declaration, qualifiedName)
        if (describedProperties == null) return false
        val properties = withInterfaceDerivatives(describedProperties)
        registerModel(declaration, qualifiedName, properties, header)
        val valid = collectModelGraph(declaration, qualifiedName, properties, header)

        if (!valid) {
            collectedTypes.remove(qualifiedName)
            collectedInterfaces.remove(qualifiedName)
        }
        return valid
    }

    private fun collectModelGraph(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        properties: List<PropertyModel>,
        header: ModelHeader
    ): Boolean {
        var valid = true
        for (superDeclaration in header.superDeclarations) {
            val superName = superDeclaration.qualifiedName?.asString() ?: continue
            if (!collectDeclaration(superDeclaration, "$qualifiedName base $superName", declaration)) valid = false
        }
        if (!collectPropertyGraph(declaration, qualifiedName, properties)) valid = false
        for (derivative in derivativesFor(declaration)) {
            val derivativeName = derivative.qualifiedName?.asString() ?: continue
            collectedDerivedTypes.add(DerivedTypeRegistrationModel(qualifiedName, derivativeName))
            if (!collectDeclaration(derivative, "$qualifiedName derivative $derivativeName", derivative)) valid = false
        }
        return valid
    }

    private class ModelHeader(
        val superDeclarations: List<KSClassDeclaration>,
        val baseTypeName: String?,
        val derivedTypeId: String?
    )

    private fun modelHeader(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        identity: String,
        node: KSNode
    ): ModelHeader? {
        val superDeclarations = declaration.superTypes.map { reference -> reference.resolve() }
            .filterNot(KSType::isError)
            .mapNotNull { type -> type.declaration as? KSClassDeclaration }
            .filter { candidate -> candidate.qualifiedName?.asString() !in ROOT_TYPE_NAMES }
            .toList()
        if (superDeclarations.any { candidate -> candidate.typeParameters.isNotEmpty() }) {
            logger.error("'$identity' uses an unsupported generic base type from '$qualifiedName'.", node)
            return null
        }
        val baseTypeName = superDeclarations.firstOrNull { candidate -> candidate.classKind == ClassKind.CLASS }
            ?.qualifiedName?.asString()
        val derivedTypeId = declaration.derivedTypeId()
        if (declaration.hasAnnotation(DERIVED_TYPE_ANNOTATION) && derivedTypeId.isNullOrBlank()) {
            logger.error(
                ArcDiagnostic.DERIVED_TYPE_ID,
                "Derived type '$qualifiedName' must declare a nonblank @DerivedType id.",
                declaration
            )
            return null
        }
        if (declaration.classKind == ClassKind.INTERFACE && derivedTypeId != null) {
            logger.error(
                ArcDiagnostic.DERIVED_TYPE_TARGET,
                "Interface '$qualifiedName' cannot carry @DerivedType; annotate concrete implementations.",
                declaration
            )
            return null
        }
        return ModelHeader(superDeclarations, baseTypeName, derivedTypeId)
    }

    private fun withInterfaceDerivatives(describedProperties: List<PropertyModel>): List<PropertyModel> =
        describedProperties.map { property ->
            val propertyDeclaration = if (property.shape.kind == TypeShapeKind.MAP) null else
                resolveNamedDeclaration(property.elementTypeName ?: property.typeName)
            val derivatives = propertyDeclaration
                ?.takeIf { candidate -> candidate.classKind == ClassKind.INTERFACE }
                ?.let(::derivativesFor)
                .orEmpty()
                .mapNotNull { candidate -> candidate.qualifiedName?.asString() }
            property.copy(derivatives = derivatives)
        }

    private fun registerModel(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        properties: List<PropertyModel>,
        header: ModelHeader
    ) {
        val location = qualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank)
        val summary = DocumentationSummaryParser.parse(declaration.docString).summary
        if (declaration.classKind == ClassKind.INTERFACE) {
            collectedInterfaces[qualifiedName] = InterfaceModel(
                name = declaration.simpleName.asString(),
                fullyQualifiedName = qualifiedName,
                location = location,
                properties = properties,
                summary = summary
            )
        } else {
            collectedTypes[qualifiedName] = TypeModel(
                name = declaration.simpleName.asString(),
                fullyQualifiedName = qualifiedName,
                location = location,
                properties = properties,
                baseTypeName = header.baseTypeName,
                derivedTypeId = header.derivedTypeId,
                summary = summary
            )
        }
    }

    private fun collectPropertyGraph(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        properties: List<PropertyModel>
    ): Boolean {
        var valid = true
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
            if (!validatePropertyUse(qualifiedName, property, propertyNode ?: declaration)) valid = false
            if (!collectPropertyType(declaration, qualifiedName, property, propertyType, propertyNode)) valid = false
            for (derivativeName in property.derivatives) {
                if (!collectNamedType(derivativeName, "$qualifiedName.${property.name} derivative", declaration)) valid = false
            }
        }
        return valid
    }

    private fun collectPropertyType(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        property: PropertyModel,
        propertyType: KSType?,
        propertyNode: KSNode?
    ): Boolean {
        // Runtime-safe map leaves never contribute model declarations to the closed graph.
        if (property.shape.kind == TypeShapeKind.MAP) return true
        if (propertyType == null || propertyNode == null) {
            val reachableName = property.elementTypeName ?: property.typeName
            return collectNamedType(reachableName, "$qualifiedName.${property.name}", declaration)
        }
        val shape = describe(propertyType, "$qualifiedName.${property.name}", propertyNode, allowMaps = true)
        return shape != null && collect(shape, "$qualifiedName.${property.name}", propertyNode)
    }

    private data class ConcretePropertyUse(val ownerName: String, val propertyName: String, val baseName: String)

    // KSP2 omits ABSTRACT for implicitly abstract Kotlin sealed classes; Java sealed classes can be concrete.
    private fun KSClassDeclaration.isConcreteClassForPropertyUse(): Boolean =
        classKind == ClassKind.CLASS && Modifier.ABSTRACT !in modifiers &&
            !(Modifier.SEALED in modifiers && (origin == Origin.KOTLIN || origin == Origin.KOTLIN_LIB))

    private fun validatePropertyUse(ownerName: String, property: PropertyModel, node: KSNode): Boolean {
        if (property.shape.kind == TypeShapeKind.MAP) return true
        val baseName = property.elementTypeName ?: property.typeName
        val base = resolveNamedDeclaration(baseName) ?: return true
        if (!base.isConcreteClassForPropertyUse() || isTerminal(baseName) || base.isAssignableTo(CONCEPT_AS_TYPE)
        ) return true
        val use = ConcretePropertyUse(ownerName, property.name, baseName)
        concretePropertyUses += use
        return validateConcretePropertyUse(use, base, node)
    }

    private fun validateConcretePropertyUse(use: ConcretePropertyUse, base: KSClassDeclaration, node: KSNode): Boolean {
        if (!base.isConcreteClassForPropertyUse()) return true
        val hasDescendant = propertyDerivedTypeNames.any { name ->
            if (name == use.baseName) return@any false
            val candidate = resolveNamedDeclaration(name) ?: return@any false
            candidate.isConcreteClassForPropertyUse() &&
                !candidate.derivedTypeId().isNullOrBlank() && candidate.isAssignableTo(use.baseName)
        }
        if (!hasDescendant) return true
        if (reportedConcretePropertyUses.add(use)) {
            logger.error(
                ArcDiagnostic.CONCRETE_POLYMORPHIC_PROPERTY,
                "Artifact/property '${use.ownerName}.${use.propertyName}' declares concrete polymorphic base " +
                    "'${use.baseName}' with visible @DerivedType descendants; declare an interface or abstract base instead.",
                node
            )
        }
        return false
    }

    override fun extractValidation(
        annotated: Iterable<KSAnnotated>,
        shape: TypeShape,
        identity: String,
        node: KSNode,
        sourceAnnotations: List<SourceValidationAnnotation>
    ): ValidationMetadata? {
        if (IgnoreValidationPolicy.isIgnored(annotated, node, resolver, logger) ||
            sourceAnnotations.any { it.qualifiedName == IgnoreValidationPolicy.ANNOTATION }) {
            // Declaration parsing/fingerprints remain unchanged; only effective member rules are cut.
            return ValidationMetadata(emptyList(), false, true)
        }
        val conceptRules = ConceptValidationRules(logger, validationExtractor, resolver, conceptResolver)
            .extract(shape, identity, node) ?: return null
        return validationExtractor.extract(
            annotated,
            shape,
            identity,
            node,
            sourceAnnotations,
            conceptRules,
            fluentRules[identity].orEmpty()
        )
    }

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

    private fun collectType(type: KSType, identity: String, node: KSNode): Boolean {
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        val qualifiedName = declaration.qualifiedName?.asString() ?: return false
        if (isTerminal(qualifiedName)) return true
        val conceptResolution = conceptResolver.resolve(declaration, identity, node)
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

    private fun collectEnum(declaration: KSClassDeclaration): Boolean {
        val qualifiedName = requireNotNull(declaration.qualifiedName).asString()
        if (qualifiedName in collectedEnums) return true
        collectedEnums[qualifiedName] = EnumModelReader(logger).read(declaration) ?: return false
        return true
    }

    private fun KSClassDeclaration.derivedTypeId(): String? = annotations
        .firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == DERIVED_TYPE_ANNOTATION
        }
        ?.arguments?.firstOrNull { argument -> argument.name?.asString() == "id" || argument.name == null }
        ?.value as? String

}

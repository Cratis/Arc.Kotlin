// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.FunctionKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
import io.cratis.arc.metadata.CommandEventMetadata
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.metadata.ConceptDescriptor
import io.cratis.arc.metadata.EnumDescriptor
import io.cratis.arc.metadata.EnumMemberDescriptor
import io.cratis.arc.metadata.InterfaceDescriptor
import io.cratis.arc.metadata.ParameterDescriptor
import io.cratis.arc.metadata.PropertyDescriptor
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.QueryParameterSource
import io.cratis.arc.metadata.RouteOptions
import io.cratis.arc.metadata.TypeDescriptor
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.metadata.TypeShapeKind
import io.cratis.arc.metadata.ValidationRuleDescriptor
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryTransportType
import java.io.File

internal class ArcSymbolProcessor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val codeGenerator: CodeGenerator = environment.codeGenerator
    private val logger = ArcDiagnosticReporter(environment.logger)
    private val configuredModuleName = environment.options[MODULE_NAME_OPTION]
    private val moduleName = validateModuleName(configuredModuleName)
    private val graphLogger = ArcDiagnosticReporter(environment.logger, buffered = true)
    private val fluentTrace: ((String) -> Unit)? = if (environment.options["arc.fluentValidationTrace"] == "true")
        { message -> environment.logger.info(message) } else null
    private var fluentRound = 0
    private val binaryRecords = try { BinaryRecordMetadata(environment.options[BinaryRecordMetadata.OPTION]) }
        catch (exception: Exception) {
            logger.error(ArcDiagnostic.CONFIGURATION, "KSP option '${BinaryRecordMetadata.OPTION}' is invalid: ${exception.message}; supply compile classpath file URIs joined by '|'.")
            BinaryRecordMetadata(null)
        }
    private var metadataCollector = MetadataCollector(graphLogger, binaryRecords = binaryRecords)
    private val methodReader = CommandMethodReader(logger)
    private val commandNames = sortedSetOf<String>()
    private val readModelNames = sortedSetOf<String>()
    private val exportedTypeNames = sortedSetOf<String>()
    private val derivedTypeNames = sortedSetOf<String>()
    private val responseHandlerNames = sortedSetOf<String>()
    private val importedHandlers = environment.options[ResponseHandlerMetadata.OPTION]?.let { option ->
        try {
            ResponseHandlerMetadata.read(option)
        } catch (exception: Exception) {
            logger.error(ArcDiagnostic.CONFIGURATION,
                "KSP option '${ResponseHandlerMetadata.OPTION}' cannot read '$option': ${exception.message}; " +
                    "supply a valid format-1 dependency handler index as an absolute file URI.")
            emptyList()
        }
    }.orEmpty()
    private val fluentNames = sortedSetOf<String>()
    private val fluentIndexOption = environment.options[FluentValidationMetadata.OPTION]
    private val fluentRootCompilation = environment.options["arc.fluentValidationRoot"] == "true"
    private val importedFluent = fluentIndexOption?.let { option ->
        try { FluentValidationMetadata.readIndex(option) } catch (exception: Exception) {
            logger.error(ArcDiagnostic.FLUENT_METADATA,
                "KSP option '${FluentValidationMetadata.OPTION}' cannot read '$option': ${exception.message}; supply a verified format-1 dependency index.")
            emptyList()
        }
    }.orEmpty()
    private var fluentSnapshot = FluentValidationDiscovery.Snapshot(emptyList(), emptyList(), emptyList())
    private val exportedHandlers = sortedMapOf<String, List<String>>()
    private val emittedCommands = mutableSetOf<String>()
    private val emittedQueries = mutableSetOf<String>()
    private val inspectedCommandLikeTypes = mutableSetOf<String>()
    private val ignoreValidationOwners = sortedSetOf<String>()
    private val ignoreValidationTopLevelProperties = sortedSetOf<String>()
    private val queryNames = mutableSetOf<String>()
    private val explicitQueryRoutes = mutableSetOf<String>()
    private val declarativeHandledResponseTypes = sortedSetOf<String>()
    private val handledTypeContributions = mutableMapOf<String, List<String>>()
    private val commands = mutableListOf<CommandModel>()
    private val queries = mutableListOf<QueryModel>()
    private val exportRoots = sortedSetOf<String>()
    private var configurationReported = false
    private var moduleGenerated = false
    private var latestRoundFiles: List<KSFile> = emptyList()
    private var hasDeferredInputs = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // KSP 2.3.9/2.3.11 call finish/onError before clearing terminal-round caches. Discard all previous sites
        // at entry, including on error rounds. Only this round's real diagnostic nodes and dependency files survive.
        graphLogger.beginRound()
        latestRoundFiles = emptyList()
        commands.clear()
        queries.clear()
        exportRoots.clear()
        queryNames.clear()
        explicitQueryRoutes.clear()
        metadataCollector = MetadataCollector(graphLogger, binaryRecords = binaryRecords)
        hasDeferredInputs = false
        latestRoundFiles = resolver.getAllFiles().toList()
        val deferred = mutableListOf<KSAnnotated>()
        try {
            reportInvalidConfiguration()
            IgnoreValidationPolicy.validateTargets(resolver, graphLogger, ignoreValidationOwners, ignoreValidationTopLevelProperties)
            fluentTrace?.invoke("[ARC-FLUENT-ROUND] ${++fluentRound}")
            val fluent = FluentValidationDiscovery(graphLogger, fluentTrace).discover(resolver, fluentNames, importedFluent, fluentIndexOption != null)
            deferred += fluent.deferred
            fluentSnapshot = fluent.copy(deferred = emptyList())
            val fluentRules = fluentSnapshot.all.flatMap { declaration -> declaration.members.map { member ->
                "${declaration.model}.${member.name}" to member.rules.map { ValidationRuleModel(it.ruleName, it.arguments, it.message) }
            } }.groupBy({ it.first }, { it.second }).mapValues { (_, rules) -> rules.flatten() }
            metadataCollector = MetadataCollector(graphLogger, fluentRules, binaryRecords)
            deferred += discoverDeclarativeHandledResponseTypes(resolver)
            inspectCommandLikeTypes(resolver)
            val commandSymbols = discoverRoots(resolver, COMMAND_ANNOTATION, commandNames)
            val readModelSymbols = discoverRoots(resolver, READ_MODEL_ANNOTATION, readModelNames)
            val exportedTypeSymbols = discoverRoots(resolver, EXPORTED_TYPE_ANNOTATION, exportedTypeNames)
            derivedTypeNames += resolver.getSymbolsWithAnnotation("io.cratis.arc.polymorphism.DerivedType")
                .filterIsInstance<KSClassDeclaration>().mapNotNull { it.qualifiedName?.asString() }
            metadataCollector.useResolver(resolver, derivedTypeNames)
            deferred += (commandSymbols + readModelSymbols + exportedTypeSymbols).filterNot(KSAnnotated::validateForProcessing)
            commandSymbols.filter(KSAnnotated::validateForProcessing).forEach { processCommand(it, resolver) }
            readModelSymbols.filter(KSAnnotated::validateForProcessing).forEach { processReadModel(it, resolver) }
            exportedTypeSymbols.filter(KSAnnotated::validateForProcessing).forEach { processExportedType(it) }
            fluentSnapshot.local.map { it.model }.distinct().forEach { name ->
                val model = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
                if (model == null) hasDeferredInputs = true
                else if (!model.validate({ _, _ -> true }, enableNewFeatures = false)) deferred += model
                else if (metadataCollector.collectDeclaration(model, name)) {
                    if (metadataCollector.propertiesFor(name) == null) graphLogger.error(ArcDiagnostic.FLUENT_DECLARATION,
                        "Fluent model '$name' has no concrete model-property metadata; use a plain model or a server-only ModelValidator for scalar/concept targets.", model)
                    else exportRoots += name
                }
            }
            deferred += IdentityDetailsDiscovery(graphLogger) { declaration ->
                val name = requireNotNull(declaration.qualifiedName).asString()
                metadataCollector.collectDeclaration(declaration, name).also { collected ->
                    if (collected) exportRoots += name
                }
            }.discover(latestRoundFiles.asSequence().flatMap { it.declarations })
            validateSharedWireEdges(resolver)
            fluentSnapshot.all.forEach { declaration ->
                val properties = metadataCollector.propertiesFor(declaration.model)
                if (properties != null) declaration.members.filter { member -> properties.none { it.name == member.name } }.forEach { member ->
                    graphLogger.error(ArcDiagnostic.FLUENT_RULE,
                        "Fluent validator '${declaration.validator}', member '${member.name}' is not a readable generated wire member; remove the ignored or unsupported selector.",
                        resolver.getClassDeclarationByName(resolver.getKSNameFromString(declaration.validator)))
                }
            }
            hasDeferredInputs = hasDeferredInputs || deferred.isNotEmpty()
            return deferred.distinct()
        } finally {
            metadataCollector.releaseSymbols()
        }
    }

    private fun validateSharedWireEdges(resolver: Resolver) {
        if (fluentSnapshot.all.isEmpty()) return
        val declarations = mutableMapOf<String, KSClassDeclaration>()
        val edges = mutableMapOf<String, List<Pair<String, String>>>()
        fun visit(type: KSType) {
            val declaration = type.declaration as? KSClassDeclaration ?: return
            val name = declaration.qualifiedName?.asString() ?: return
            if (name.startsWith("kotlin.") || name.startsWith("java.")) { type.arguments.mapNotNull { it.type?.resolve() }.forEach(::visit); return }
            if (declarations.putIfAbsent(name, declaration) != null) return
            val members = sharedWireMembers(declaration, resolver)
            edges[name] = sharedWireEdgesOf(name, members)
            members.forEach { visit(sharedWireLeaf(it.type.resolve())) }
            edges[name].orEmpty().forEach { (_, child) -> resolver.getClassDeclarationByName(resolver.getKSNameFromString(child))?.let { visit(it.asStarProjectedType()) } }
        }
        (metadataCollector.types.map { it.fullyQualifiedName } + fluentSnapshot.all.map { it.model }).distinct().forEach { name ->
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))?.let { visit(it.asStarProjectedType()) }
        }
        val active = activeSharedModels(edges)
        active.forEach { name ->
            val declaration = declarations[name] ?: return@forEach
            reportSharedWireEdgeErrors(name, declaration, edges[name].orEmpty().filter { it.second in active })
        }
    }

    private fun sharedWireMembers(declaration: KSClassDeclaration, resolver: Resolver): List<KSPropertyDeclaration> =
        declaration.getAllProperties().filter { it.isPublic() && Modifier.JAVA_STATIC !in it.modifiers }
            .filterNot { IgnoreValidationPolicy.isIgnored(listOfNotNull(it, it.getter), it, resolver, graphLogger) }.toList()

    private fun sharedWireLeaf(type: KSType): KSType =
        if (type.declaration.qualifiedName?.asString() in SHARED_WIRE_COLLECTION_TYPES) {
            type.arguments.singleOrNull()?.type?.resolve() ?: type
        } else {
            type
        }

    private fun sharedWireEdgesOf(name: String, members: List<KSPropertyDeclaration>): List<Pair<String, String>> =
        (members.mapNotNull { member ->
            sharedWireLeaf(member.type.resolve()).declaration.qualifiedName?.asString()?.let { member.simpleName.asString() to it }
        } + metadataCollector.propertiesFor(name).orEmpty().filterNot { it.ignoreValidation }
            .map { it.name to (it.elementTypeName ?: it.typeName) }).distinct()

    private fun activeSharedModels(edges: Map<String, List<Pair<String, String>>>): Set<String> {
        val active = fluentSnapshot.all.map { it.model }.toMutableSet()
        var changed: Boolean
        do {
            changed = false
            edges.forEach { (name, members) -> if (name !in active && members.any { it.second in active }) { active += name; changed = true } }
        } while (changed)
        return active
    }

    private fun reportSharedWireEdgeErrors(
        name: String,
        declaration: KSClassDeclaration,
        activeEdges: List<Pair<String, String>>
    ) {
        if (Modifier.OPEN in declaration.modifiers || Modifier.SEALED in declaration.modifiers || Modifier.ABSTRACT in declaration.modifiers)
            graphLogger.error(ArcDiagnostic.FLUENT_RULE, "Shared validation graph '$name' is open, abstract or polymorphic; use concrete final shared input models or server-only validators.", declaration)
        activeEdges.forEach { (member, _) ->
            try { FluentValidationDiscovery.requireWireMember(declaration, member) }
            catch (exception: IllegalArgumentException) { graphLogger.error(ArcDiagnostic.FLUENT_RULE,
                "Shared validation edge '$name.$member': ${exception.message}.", declaration) }
        }
        val wire = metadataCollector.propertiesFor(name).orEmpty().map { it.name }.toSet()
        activeEdges.filter { it.first !in wire }.forEach { (member, _) ->
            graphLogger.error(ArcDiagnostic.FLUENT_RULE, "Shared validation edge '$name.$member' is not a generated wire member; remove the ignored/inherited edge or use a server-only validator.", declaration)
        }
    }

    private fun discoverRoots(resolver: Resolver, annotation: String, names: MutableSet<String>): List<KSAnnotated> {
        val discovered = resolver.getSymbolsWithAnnotation(annotation).toList()
        names += discovered.filterIsInstance<KSClassDeclaration>().mapNotNull { it.qualifiedName?.asString() }
        val resolved = names.mapNotNull { name ->
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(name)).also {
                if (it == null) hasDeferredInputs = true
            }
        }
        return resolved + discovered.filter { it !is KSClassDeclaration || it.qualifiedName == null }
    }

    override fun finish() {
        graphLogger.flush()
        val configuredName = moduleName
        if (!logger.hasErrors && !graphLogger.hasErrors && !hasDeferredInputs && !moduleGenerated &&
            configuredName != null) {
            if (fluentSnapshot.local.isNotEmpty()) {
                codeGenerator.createNewFileByPath(Dependencies(true, *latestRoundFiles.toTypedArray()),
                    "${FluentValidationMetadata.PREFIX}$configuredName.json", "").bufferedWriter(Charsets.UTF_8).use {
                    it.write(FluentValidationMetadata.document(configuredName, fluentSnapshot.local))
                }
            }
            if (exportedHandlers.isNotEmpty()) {
                codeGenerator.createNewFileByPath(
                    Dependencies(true, *latestRoundFiles.toTypedArray()),
                    "${ResponseHandlerMetadata.PREFIX}$configuredName.json", ""
                ).bufferedWriter(Charsets.UTF_8).use {
                    it.write(ResponseHandlerMetadata.document(configuredName, exportedHandlers))
                }
            }
            if (commands.isNotEmpty() || queries.isNotEmpty() || exportRoots.isNotEmpty() || fluentRootCompilation && fluentSnapshot.all.isNotEmpty()) {
                generateModule(configuredName)
            }
            moduleGenerated = true
        }
        latestRoundFiles = emptyList()
    }

    override fun onError() {
        graphLogger.flush()
        latestRoundFiles = emptyList()
    }

    private fun inspectCommandLikeTypes(resolver: Resolver) {
        resolver.getAllFiles().flatMap { file -> file.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration ->
                val qualifiedName = declaration.qualifiedName?.asString() ?: return@forEach
                if (!inspectedCommandLikeTypes.add(qualifiedName) || declaration.hasAnnotation(COMMAND_ANNOTATION) ||
                    declaration.classKind != ClassKind.CLASS || declaration.parentDeclaration != null ||
                    declaration.simpleName.asString().endsWith("Extensions") ||
                    declaration.simpleName.asString().endsWith("Helper") ||
                    declaration.simpleName.asString().endsWith("Helpers")) {
                    return@forEach
                }
                val handles = declaration.getDeclaredFunctions()
                    .filter { function -> function.simpleName.asString() == HANDLER_NAME }
                    .toList()
                val commandLikeHandle = handles.firstOrNull { function ->
                    function.functionKind == FunctionKind.MEMBER && Modifier.JAVA_STATIC !in function.modifiers &&
                        function.isPublic()
                }
                val hasPublicState = declaration.getAllProperties().any { property -> property.isPublic() }
                if (commandLikeHandle != null && hasPublicState) {
                    logger.warn(
                        ArcDiagnostic.MISSING_COMMAND,
                        "Type '$qualifiedName' has a handle function and public state but is missing @Command; " +
                            "add @Command or rename the function if this is not a command.",
                        declaration
                    )
                }
                handles.filter { function ->
                    function.functionKind == FunctionKind.MEMBER && Modifier.JAVA_STATIC !in function.modifiers &&
                        function.isPublic() && function.parameters.any { parameter ->
                            (parameter.type.resolve().declaration as? KSClassDeclaration)?.hasAnnotation(COMMAND_ANNOTATION) == true
                        }
                }.forEach { function ->
                    logger.error(
                        ArcDiagnostic.COMMAND_HANDLER,
                        "External handler '$qualifiedName.${function.simpleName.asString()}' accepts an @Command type; " +
                            "move handling to a public instance handle function on the command.",
                        function
                    )
                }
            }
    }

    private fun processCommand(symbol: KSAnnotated, resolver: Resolver) {
        val command = symbol as? KSClassDeclaration
        if (command == null) {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "@$COMMAND_SIMPLE_NAME can only be applied to a class.", symbol)
            return
        }
        val qualifiedName = command.qualifiedName?.asString()
        if (qualifiedName != null) {
            buildCommandModel(command, resolver)?.let { model ->
                if (metadataCollector.collectDeclaration(command, qualifiedName, command, model.properties)) {
                    // Concepts contribute scalar metadata, not a TypeModel with enriched properties.
                    commands.add(if (metadataCollector.isConcept(qualifiedName)) model else
                        model.copy(properties = requireNotNull(metadataCollector.propertiesFor(qualifiedName))))
                }
            }
        } else {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "@$COMMAND_SIMPLE_NAME classes must not be local.", command)
        }
    }

    private fun processReadModel(symbol: KSAnnotated, resolver: Resolver) {
        val readModel = symbol as? KSClassDeclaration
        if (readModel == null) {
            logger.error(ArcDiagnostic.READ_MODEL_SHAPE, "@$READ_MODEL_SIMPLE_NAME can only be applied to a class.", symbol)
            return
        }
        val qualifiedName = readModel.qualifiedName?.asString()
        if (qualifiedName != null) {
            if (validateReadModelShape(readModel, qualifiedName)) {
                queries += buildQueryModels(readModel, resolver)
                metadataCollector.collectDeclaration(readModel, qualifiedName)
            }
        } else {
            logger.error(ArcDiagnostic.READ_MODEL_SHAPE, "@$READ_MODEL_SIMPLE_NAME classes must not be local.", readModel)
        }
    }

    private fun processExportedType(symbol: KSAnnotated) {
        val declaration = symbol as? KSClassDeclaration
        if (declaration == null) {
            logger.error(
                ArcDiagnostic.EXPORTED_TYPE_TARGET,
                "@$EXPORTED_TYPE_SIMPLE_NAME can only be applied to a class, enum class or interface.",
                symbol
            )
            return
        }
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null || declaration.parentDeclaration != null) {
            logger.error(
                ArcDiagnostic.EXPORTED_TYPE_TARGET,
                "@$EXPORTED_TYPE_SIMPLE_NAME types must be top-level; nested and local types are not supported.",
                declaration
            )
            return
        }
        if (!validateExportedTypeShape(declaration, qualifiedName)) return
        if (metadataCollector.collectDeclaration(declaration, qualifiedName)) exportRoots += qualifiedName
    }

    private fun validateExportedTypeShape(declaration: KSClassDeclaration, qualifiedName: String): Boolean {
        if (!declaration.isPublic()) {
            logger.error(
                ArcDiagnostic.EXPORTED_TYPE_TARGET,
                "Exported type '$qualifiedName' must be public so generated clients can use it.",
                declaration
            )
            return false
        }
        if (declaration.typeParameters.isNotEmpty()) {
            logger.error(
                ArcDiagnostic.EXPORTED_TYPE_TARGET,
                "Exported type '$qualifiedName' must not declare type parameters; " +
                    "a generic definition has no single shape to generate.",
                declaration
            )
            return false
        }
        val kind = declaration.classKind
        if (kind != ClassKind.CLASS && kind != ClassKind.ENUM_CLASS && kind != ClassKind.INTERFACE) {
            logger.error(
                ArcDiagnostic.EXPORTED_TYPE_TARGET,
                "Exported type '$qualifiedName' must be a class, enum class or interface.",
                declaration
            )
            return false
        }
        if (kind == ClassKind.CLASS && Modifier.ABSTRACT in declaration.modifiers) {
            logger.error(
                ArcDiagnostic.EXPORTED_TYPE_TARGET,
                "Exported type '$qualifiedName' must not be abstract; " +
                    "export the concrete derived types instead.",
                declaration
            )
            return false
        }
        return true
    }

    private fun registerQuery(model: QueryModel, source: KSFunctionDeclaration): Boolean {
        if (!queryNames.add(model.fullyQualifiedName)) {
            logger.error(
                ArcDiagnostic.DUPLICATE_QUERY,
                "Duplicate fully qualified query name '${model.fullyQualifiedName}' is generated by more than one function.",
                source
            )
            return false
        }
        val explicitPath = model.explicitPath ?: return true
        val normalizedPath = "/" + explicitPath.trim().trim('/').replace(Regex("/+"), "/")
        if (!explicitQueryRoutes.add(normalizedPath)) {
            logger.error(
                ArcDiagnostic.ROUTE,
                "Explicit query route '$normalizedPath' is generated by more than one query; @Path values must be unique.",
                source
            )
            return false
        }
        return true
    }

    private fun reportInvalidConfiguration() {
        if (configurationReported || moduleName != null) return
        val detail = if (configuredModuleName == null) {
            "is required"
        } else {
            "must be a safe Kotlin identifier matching [A-Za-z_][A-Za-z0-9_]* and must not be a keyword"
        }
        logger.error("KSP option '$MODULE_NAME_OPTION' $detail.")
        configurationReported = true
    }

    private fun validateCommandClass(command: KSClassDeclaration, qualifiedName: String): Boolean {
        if (command.parentDeclaration != null) {
            logger.error("Command '$qualifiedName' must be a top-level class; nested commands are not supported.", command)
            return false
        }
        if (command.classKind != ClassKind.CLASS) {
            logger.error("Command '$qualifiedName' must be a concrete class.", command)
            return false
        }
        if (!command.isPublic()) {
            logger.error("Command '$qualifiedName' must be public so its generated handler can invoke it.", command)
            return false
        }
        if (Modifier.ABSTRACT in command.modifiers) {
            logger.error("Command '$qualifiedName' must not be abstract.", command)
            return false
        }
        if (command.typeParameters.isNotEmpty()) {
            logger.error("Command '$qualifiedName' must not declare type parameters.", command)
            return false
        }
        return true
    }

    private fun findCommandHandler(command: KSClassDeclaration, qualifiedName: String): KSFunctionDeclaration? {
        val namedHandlers = command.getDeclaredFunctions().filter { it.simpleName.asString() == HANDLER_NAME }.toList()
        if (namedHandlers.isEmpty()) {
            logger.error("Command '$qualifiedName' must declare exactly one public instance function named '$HANDLER_NAME'.", command)
            return null
        }
        if (namedHandlers.size > 1) {
            logger.error("Command '$qualifiedName' has overloaded '$HANDLER_NAME' functions; exactly one is supported.", command)
            return null
        }
        val handler = namedHandlers.single()
        if (!handler.isPublic()) {
            logger.error("Handler '$qualifiedName.$HANDLER_NAME' must be public.", handler)
            return null
        }
        if (handler.functionKind != FunctionKind.MEMBER || Modifier.JAVA_STATIC in handler.modifiers) {
            logger.error("Handler '$qualifiedName.$HANDLER_NAME' must be a non-static instance function.", handler)
            return null
        }
        if (Modifier.ABSTRACT in handler.modifiers) {
            logger.error("Handler '$qualifiedName.$HANDLER_NAME' must not be abstract.", handler)
            return null
        }
        if (handler.extensionReceiver != null) {
            logger.error("Handler '$qualifiedName.$HANDLER_NAME' must not be an extension function.", handler)
            return null
        }
        if (handler.typeParameters.isNotEmpty()) {
            logger.error("Handler '$qualifiedName.$HANDLER_NAME' must not declare type parameters.", handler)
            return null
        }
        return handler
    }

    private fun validateProvideMethod(method: KSFunctionDeclaration, qualifiedName: String): Boolean {
        if (!method.isPublic()) {
            logger.error("Provide method '$qualifiedName.$PROVIDE_NAME' must be public.", method)
            return false
        }
        if (method.functionKind != FunctionKind.MEMBER || Modifier.JAVA_STATIC in method.modifiers) {
            logger.error("Provide method '$qualifiedName.$PROVIDE_NAME' must be a non-static instance function.", method)
            return false
        }
        if (Modifier.ABSTRACT in method.modifiers || method.extensionReceiver != null || method.typeParameters.isNotEmpty()) {
            logger.error("Provide method '$qualifiedName.$PROVIDE_NAME' must be concrete, non-generic, and not an extension function.", method)
            return false
        }
        return true
    }

    private fun buildCommandModel(command: KSClassDeclaration, resolver: Resolver): CommandModel? {
        val qualifiedName = command.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "@$COMMAND_SIMPLE_NAME classes must not be local.", command)
            return null
        }
        if (!validateCommandClass(command, qualifiedName)) return null
        val handler = findCommandHandler(command, qualifiedName) ?: return null

        val handlerSite = CommandMethodSite(qualifiedName, handler, HANDLER_NAME)
        val parameters = methodReader.parameters(handlerSite) ?: return null
        val invocationKind = methodReader.invocationKind(handlerSite, resolver) ?: return null
        val namedProvides = command.getDeclaredFunctions().filter { it.simpleName.asString() == PROVIDE_NAME }.toList()
        if (namedProvides.size > 1) {
            logger.error("Command '$qualifiedName' has overloaded '$PROVIDE_NAME' functions; at most one is supported.", command)
            return null
        }
        val provideMethod = namedProvides.singleOrNull()
        if (provideMethod != null && !validateProvideMethod(provideMethod, qualifiedName)) return null
        val provide = provideMethod?.let { method ->
            val provideSite = CommandMethodSite(qualifiedName, method, PROVIDE_NAME)
            ProvideModel(
                methodReader.parameters(provideSite) ?: return null,
                methodReader.invocationKind(provideSite, resolver, allowNullableReturn = true) ?: return null
            )
        }
        if (provideMethod != null) warnForUnusedProvidedValues(qualifiedName, provideMethod, handler, resolver)
        val properties = buildProperties(command, qualifiedName) ?: return null
        val commandKeys = properties.filter(PropertyModel::isCommandKey)
        if (commandKeys.size > 1) {
            logger.error(
                "Command '$qualifiedName' declares multiple @CommandKey properties: " +
                    commandKeys.joinToString { property -> property.name } + ". Exactly one is supported.",
                command
            )
            return null
        }
        val commandKey = commandKeys.singleOrNull()
        val eventMetadata = buildCommandEventMetadata(command, qualifiedName)
        if (eventMetadata?.isValid == false) return null
        val authorization = buildAuthorization(command, handler, qualifiedName, "Command") ?: return null
        val containingFile = command.containingFile
        if (containingFile == null) {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "Command '$qualifiedName' does not have a resolvable source file.", command)
            return null
        }

        // Validate unconditional response restrictions without traversing a provisional CLIENT-only graph.
        CommandResponseClassifier(responseServices(), qualifiedName, handler, collectClientGraph = false)
            .determine(resolver) ?: return null
        if (moduleName != null && emittedCommands.add(qualifiedName)) {
            generateHandler(
                CommandInvocationModel(
                    qualifiedName, commandHandlerClassName(qualifiedName), parameters, provide,
                    commandKey?.name, commandKey != null && isParsedJavaRecord(command), invocationKind
                ),
                containingFile
            )
        }
        val response = CommandResponseClassifier(responseServices(), qualifiedName, handler).determine(resolver)
            ?: return null
        return CommandModel(
            qualifiedName = qualifiedName,
            simpleName = command.simpleName.asString(),
            handlerClassName = commandHandlerClassName(qualifiedName),
            parameters = parameters,
            provide = provide,
            properties = properties,
            commandKeyPropertyName = commandKey?.name,
            commandKeyUsesFunction = commandKey != null && isParsedJavaRecord(command),
            authorization = authorization,
            treatWarningsAsErrors = command.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION) ||
                handler.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION),
            responseTypeName = response.typeName,
            responseIsEnumerable = response.isEnumerable,
            responseValues = response.values,
            invocationKind = invocationKind,
            summary = DocumentationSummaryParser.parse(command.docString).summary,
            eventMetadata = eventMetadata
        )
    }

    private fun responseServices() =
        CommandResponseServices(logger, graphLogger, metadataCollector, declarativeHandledResponseTypes)

    private fun warnForUnusedProvidedValues(
        commandName: String,
        provide: KSFunctionDeclaration,
        handler: KSFunctionDeclaration,
        resolver: Resolver
    ) {
        var providedType = provide.returnType?.resolve() ?: return
        if (providedType.isError) return
        if (isCompletionStage(providedType, resolver)) {
            providedType = providedType.arguments.singleOrNull()?.type?.resolve() ?: return
        }
        val providedName = providedType.declaration.qualifiedName?.asString() ?: return
        val producedTypes = if (providedName == "kotlin.Pair" || providedName == "kotlin.Triple") {
            providedType.arguments.mapNotNull { argument -> argument.type?.resolve() }
        } else {
            listOf(providedType)
        }
        val handlerTypes = handler.parameters.map { parameter ->
            val handlerType = parameter.type.resolve()
            if (handler.origin == Origin.JAVA &&
                handlerType.declaration.qualifiedName?.asString() == JAVA_OPTIONAL_TYPE
            ) {
                handlerType.arguments.singleOrNull()
                    ?.takeIf { argument -> argument.variance == Variance.INVARIANT }
                    ?.type
                    ?.resolve()
                    ?: handlerType
            } else {
                handlerType
            }
        }
        producedTypes.filter { type ->
            val name = type.declaration.qualifiedName?.asString()
            name !in PROVIDE_CONTROL_TYPES && name !in PROVIDE_DYNAMIC_TYPES && name !in VOID_TYPE_NAMES
        }.forEach { type ->
            val consumed = handlerTypes.any { handlerType -> handlerType.isAssignableFrom(type) }
            if (!consumed) {
                val name = type.declaration.qualifiedName?.asString() ?: type.toString()
                logger.warn(
                    ArcDiagnostic.UNUSED_PROVIDED_VALUE,
                    "Value of type '$name' produced by '$commandName.$PROVIDE_NAME' is not consumed by any handle parameter.",
                    provide
                )
            }
        }
    }

    private fun discoverDeclarativeHandledResponseTypes(resolver: Resolver): List<KSAnnotated> {
        // Only stable names cross rounds. Revalidate binary annotations and SPI through this resolver.
        handledTypeContributions.clear()
        exportedHandlers.clear()
        importedHandlers.forEach { imported -> revalidateImportedHandler(resolver, imported) }
        val discovered = resolver.getSymbolsWithAnnotation(HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION, inDepth = true)
            .toList()
        responseHandlerNames += discovered.filterIsInstance<KSClassDeclaration>()
            .filter { it.containingFile != null }.mapNotNull { it.qualifiedName?.asString() }
        val symbols = responseHandlerNames.mapNotNull { name ->
            resolver.getClassDeclarationByName(resolver.getKSNameFromString(name)).also {
                if (it == null) hasDeferredInputs = true
            }
        } + discovered.filter { it !is KSClassDeclaration || it.qualifiedName == null }
        val deferred = symbols.filterNot(KSAnnotated::validateForProcessing).toMutableList()
        symbols.filter(KSAnnotated::validateForProcessing)
            .filterIsInstance<KSClassDeclaration>()
            .sortedBy { declaration -> declaration.qualifiedName?.asString().orEmpty() }
            .forEach { declaration -> registerResponseHandler(declaration, deferred) }
        declarativeHandledResponseTypes.clear()
        declarativeHandledResponseTypes += handledTypeContributions.values.flatten()
        return deferred
    }

    private fun handledResponseValueTypes(declaration: KSClassDeclaration): List<KSType> =
        declaration.annotationsNamed(HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION)
            .flatMap { annotation -> annotation.argumentValues("value").asSequence() }
            .mapNotNull { value -> value as? KSType }
            .toList()

    private fun revalidateImportedHandler(resolver: Resolver, imported: ResponseHandlerMetadata.Declaration) {
        val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(imported.handler))
        fun invalid(reason: String) {
            graphLogger.error(ArcDiagnostic.COMMAND_HANDLER,
                "Dependency handler '${imported.handler}' in ${imported.resource} $reason; rebuild the producer " +
                    "and dependency handler index.")
        }
        if (declaration == null) {
            invalid("cannot be resolved on the compilation classpath")
            return
        }
        val values = handledResponseValueTypes(declaration)
        if (!declaration.validateForProcessing() || values.any(KSType::isError)) {
            hasDeferredInputs = true
            invalid("has unresolved annotation or SPI types")
            return
        }
        if (!importedHandlerAgrees(declaration, values, imported)) {
            invalid("does not agree with its public top-level annotation and supported handler SPI")
            return
        }
        handledTypeContributions[imported.handler] = imported.values
    }

    private fun importedHandlerAgrees(
        declaration: KSClassDeclaration,
        values: List<KSType>,
        imported: ResponseHandlerMetadata.Declaration
    ): Boolean = declaration.isPublic() && declaration.parentDeclaration == null &&
        isSupportedResponseValueHandler(declaration) && values.all(::isExportableResponseType) &&
        values.mapNotNull { it.declaration.qualifiedName?.asString() }.distinct().sorted() == imported.values

    private fun registerResponseHandler(declaration: KSClassDeclaration, deferred: MutableList<KSAnnotated>) {
        val qualifiedName = declaration.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error(
                ArcDiagnostic.COMMAND_HANDLER,
                "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME handlers must not be local.",
                declaration
            )
            return
        }
        val handledTypeSymbols = handledResponseValueTypes(declaration)
        if (handledTypeSymbols.any(KSType::isError)) {
            deferred.add(declaration)
            return
        }
        if (!isSupportedResponseValueHandler(declaration)) {
            logger.error(
                ArcDiagnostic.COMMAND_HANDLER,
                "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME declaration '$qualifiedName' must implement " +
                    "CommandResponseValueHandler, BlockingCommandResponseValueHandler, or " +
                    "AsyncCommandResponseValueHandler.",
                declaration
            )
            return
        }

        val handledTypes = handledTypeSymbols
            .mapNotNull { type -> type.declaration.qualifiedName?.asString() }
        if (handledTypes.isEmpty()) {
            logger.error(
                ArcDiagnostic.COMMAND_HANDLER,
                "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME declaration '$qualifiedName' must declare " +
                    "at least one handled response value type.",
                declaration
            )
            return
        }
        val imported = importedHandlers.firstOrNull { it.handler == qualifiedName }
        if (imported != null && imported.values != handledTypes.distinct().sorted()) {
            graphLogger.error(ArcDiagnostic.COMMAND_HANDLER,
                "Handler '$qualifiedName' conflicts with ${imported.resource}; remove the conflicting declaration.", declaration)
            return
        }
        handledTypeContributions[qualifiedName] = handledTypes
        if (isExportableHandler(declaration, qualifiedName, handledTypeSymbols)) {
            exportedHandlers[qualifiedName] = handledTypes.distinct().sorted()
        }
    }

    private fun isExportableHandler(
        declaration: KSClassDeclaration,
        qualifiedName: String,
        handledTypeSymbols: List<KSType>
    ): Boolean = declaration.containingFile != null && declaration.isPublic() && declaration.parentDeclaration == null &&
        ResponseHandlerMetadata.exportableName(qualifiedName) && handledTypeSymbols.all(::isExportableResponseType)

    private fun isExportableResponseType(value: KSType): Boolean {
        val type = value.declaration as? KSClassDeclaration ?: return false
        return type.isPublic() && type.parentDeclaration == null &&
            type.qualifiedName?.asString()?.let(ResponseHandlerMetadata::exportableName) == true
    }

    private fun isSupportedResponseValueHandler(declaration: KSClassDeclaration): Boolean =
        declaration.getAllSuperTypes()
            .mapNotNull { superType -> superType.declaration.qualifiedName?.asString() }
            .any(SUPPORTED_RESPONSE_VALUE_HANDLER_TYPES::contains)

    private fun isParsedJavaRecord(command: KSClassDeclaration): Boolean {
        if (command.origin != Origin.JAVA) return false
        val source = command.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText() ?: return false
        return parseJavaRecordProperties(source, command.simpleName.asString()) != null
    }

    private fun buildProperties(command: KSClassDeclaration, commandName: String): List<PropertyModel>? =
        metadataCollector.describeProperties(command, commandName)

    private fun buildCommandEventMetadata(
        command: KSClassDeclaration,
        commandName: String
    ): CommandEventMetadataModel? {
        var isValid = true
        fun value(annotationName: String): String? {
            val annotation = command.annotationsNamed(annotationName).singleOrNull() ?: return null
            val raw = annotation.stringArgument("value")
            if (raw.isNullOrBlank() || raw.any(Char::isISOControl)) {
                logger.error(
                    ArcDiagnostic.COMMAND_EVENT_METADATA,
                    "Command '$commandName' declares @${annotation.shortName.asString()} with a blank or " +
                        "control-character value; omit the annotation or supply a safe nonblank value.",
                    annotation
                )
                isValid = false
                return null
            }
            return raw
        }

        fun provides(interfaceName: String): Boolean = command.getAllSuperTypes().any { superType ->
            superType.declaration.qualifiedName?.asString() == interfaceName
        }
        if (
            command.hasAnnotation(COMMAND_EVENT_STREAM_ID_ANNOTATION) &&
            provides(COMMAND_EVENT_STREAM_ID_PROVIDER)
        ) {
            logger.error(
                ArcDiagnostic.COMMAND_EVENT_METADATA,
                "Command '$commandName' cannot declare @CommandEventStreamId and implement " +
                    "CommandEventStreamIdProvider; choose one event-stream ID source.",
                command
            )
            isValid = false
        }
        if (
            command.hasAnnotation(COMMAND_EVENT_SUBJECT_ANNOTATION) &&
            provides(COMMAND_EVENT_SUBJECT_PROVIDER)
        ) {
            logger.error(
                ArcDiagnostic.COMMAND_EVENT_METADATA,
                "Command '$commandName' cannot declare @CommandEventSubject and implement " +
                    "CommandEventSubjectProvider; choose one subject source.",
                command
            )
            isValid = false
        }

        val metadata = CommandEventMetadataModel(
            eventSourceType = value(COMMAND_EVENT_SOURCE_TYPE_ANNOTATION),
            eventStreamType = value(COMMAND_EVENT_STREAM_TYPE_ANNOTATION),
            eventStreamId = value(COMMAND_EVENT_STREAM_ID_ANNOTATION),
            subject = value(COMMAND_EVENT_SUBJECT_ANNOTATION),
            isValid = isValid
        )
        return if (
            !metadata.isValid || listOf(
                metadata.eventSourceType,
                metadata.eventStreamType,
                metadata.eventStreamId,
                metadata.subject
            ).any { it != null }
        ) metadata else null
    }

    private fun buildAuthorization(
        declaration: KSClassDeclaration,
        operation: KSFunctionDeclaration,
        identity: String,
        artifactKind: String
    ): AuthorizationModel? {
        val classAllowAnonymous = declaration.hasAnnotation(ALLOW_ANONYMOUS_ANNOTATION)
        val operationAllowAnonymous = operation.hasAnnotation(ALLOW_ANONYMOUS_ANNOTATION)
        if (classAllowAnonymous && operationAllowAnonymous) {
            logger.error(
                ArcDiagnostic.AUTHORIZATION,
                "$artifactKind '$identity' declares @AllowAnonymous on both the class and operation; declare it once.",
                operation
            )
            return null
        }

        val classAuthorize = declaration.annotationsNamed(AUTHORIZE_ANNOTATION).toList()
        val operationAuthorize = operation.annotationsNamed(AUTHORIZE_ANNOTATION).toList()
        val classRoles = declaration.roleAnnotations()
        val operationRoles = operation.roleAnnotations()
        // One declaration that both opens and restricts access contradicts itself and has no defensible reading,
        // so it stays an error. Metadata split across the class and its operation is a different thing entirely:
        // that is the ordinary way to state a default and then override it for one operation.
        if (classAllowAnonymous && (classAuthorize.isNotEmpty() || classRoles.isNotEmpty())) {
            logger.error(
                ArcDiagnostic.AUTHORIZATION,
                "$artifactKind '$identity' cannot combine @AllowAnonymous with @Authorize or @Roles on the same declaration.",
                declaration
            )
            return null
        }
        if (operationAllowAnonymous && (operationAuthorize.isNotEmpty() || operationRoles.isNotEmpty())) {
            logger.error(
                ArcDiagnostic.AUTHORIZATION,
                "$artifactKind '$identity' cannot combine @AllowAnonymous with @Authorize or @Roles on the same declaration.",
                operation
            )
            return null
        }

        // Operation-level authorization replaces class-level authorization wholesale rather than merging with it,
        // matching Arc .NET's AuthorizationEvaluator.IsAuthorized(MethodInfo): it resolves the method's own metadata
        // first and only falls back to the declaring type when the method declares none. The class therefore states
        // the default for a read model, and any one operation - open or restricted - overrides it.
        val operationDeclaresAuthorization =
            operationAllowAnonymous || operationAuthorize.isNotEmpty() || operationRoles.isNotEmpty()
        val authorize = if (operationDeclaresAuthorization) operationAuthorize else classAuthorize
        val declaredRoles = if (operationDeclaresAuthorization) operationRoles else classRoles
        val roles = (
            authorize.flatMap { annotation -> annotation.stringListArgument("roles") } + declaredRoles
            ).distinct()
        val schemes = authorize.flatMap { annotation -> annotation.stringListArgument("schemes") }.distinct()
        return AuthorizationModel(
            allowAnonymous = if (operationDeclaresAuthorization) operationAllowAnonymous else classAllowAnonymous,
            policy = authorize.firstNotNullOfOrNull { annotation ->
                annotation.stringArgument("policy")?.takeIf(String::isNotBlank)
            },
            roles = roles,
            schemes = schemes
        )
    }

    private fun validateReadModelShape(readModel: KSClassDeclaration, qualifiedName: String): Boolean {
        if (readModel.parentDeclaration != null) {
            logger.error("Read model '$qualifiedName' must be a top-level class; nested read models are not supported.", readModel)
            return false
        }
        if (readModel.classKind != ClassKind.CLASS) {
            logger.error("Read model '$qualifiedName' must be a concrete class.", readModel)
            return false
        }
        if (!readModel.isPublic()) {
            logger.error("Read model '$qualifiedName' must be public so generated performers can use it.", readModel)
            return false
        }
        if (Modifier.ABSTRACT in readModel.modifiers) {
            logger.error("Read model '$qualifiedName' must not be abstract.", readModel)
            return false
        }
        if (readModel.typeParameters.isNotEmpty()) {
            logger.error("Read model '$qualifiedName' must not declare type parameters.", readModel)
            return false
        }
        return true
    }

    private fun buildQueryModels(readModel: KSClassDeclaration, resolver: Resolver): List<QueryModel> {
        val qualifiedName = readModel.qualifiedName?.asString() ?: return emptyList()
        val containingFile = readModel.containingFile
        if (containingFile == null) {
            logger.error("Read model '$qualifiedName' does not have a resolvable source file.", readModel)
            return emptyList()
        }

        val functions = if (readModel.origin == Origin.JAVA) {
            javaQueryFunctions(readModel, qualifiedName, resolver)
        } else {
            kotlinQueryFunctions(readModel, qualifiedName, resolver)
        } ?: return emptyList()

        val overloadedNames = functions.groupBy { function -> function.simpleName.asString() }
            .filterValues { overloads -> overloads.size > 1 }
            .keys
        overloadedNames.forEach { name ->
            logger.error("Read model '$qualifiedName' has overloaded query name '$name'; query names must be unique.", readModel)
        }

        return functions.filter { function -> function.simpleName.asString() !in overloadedNames }
            .mapNotNull { function ->
                buildQueryModel(readModel, function, resolver)?.takeIf { registerQuery(it, function) }?.also { model ->
                    if (moduleName != null && emittedQueries.add(model.fullyQualifiedName)) generatePerformer(model, containingFile)
                }
            }
    }

    private fun kotlinQueryFunctions(
        readModel: KSClassDeclaration,
        qualifiedName: String,
        resolver: Resolver
    ): List<KSFunctionDeclaration>? {
        val unsupportedInstanceFunctions = readModel.getDeclaredFunctions()
            .filter { function -> function.origin == Origin.KOTLIN && !function.isGeneratedModelFunction() }
            .toList()
        if (unsupportedInstanceFunctions.isNotEmpty()) {
            unsupportedInstanceFunctions.forEach { function ->
                logger.error(
                    "Query '${qualifiedName}.${function.simpleName.asString()}' must be declared in the read model companion object.",
                    function
                )
            }
            return null
        }

        val companions = readModel.declarations.filterIsInstance<KSClassDeclaration>()
            .filter(KSClassDeclaration::isCompanionObject)
            .toList()
        if (companions.size > 1) {
            logger.error("Read model '$qualifiedName' has ambiguous companion object declarations.", readModel)
            return null
        }
        return companions.singleOrNull()?.getDeclaredFunctions()
            ?.filterNot { function -> function.isGeneratedModelFunction() }
            ?.filter { function -> function.isQueryCandidate(readModel, resolver) }
            ?.toList()
            .orEmpty()
    }

    private fun javaQueryFunctions(
        readModel: KSClassDeclaration,
        qualifiedName: String,
        resolver: Resolver
    ): List<KSFunctionDeclaration>? {
        val recordComponents = readModel.containingFile?.filePath
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.readText()
            ?.let { source -> parseJavaRecordProperties(source, readModel.simpleName.asString()) }
            ?.map(JavaRecordProperty::name)
            .orEmpty()
            .toSet()
        var valid = true
        val queries = mutableListOf<KSFunctionDeclaration>()
        readModel.getDeclaredFunctions().filterNot { function ->
            function.isGeneratedModelFunction() || function.simpleName.asString() in recordComponents
        }.filter { function ->
            Modifier.JAVA_STATIC !in function.modifiers || function.isQueryCandidate(readModel, resolver)
        }.forEach { function ->
            val methodName = function.simpleName.asString()
            if (Modifier.JAVA_STATIC !in function.modifiers) {
                logger.error("Java query '$qualifiedName.$methodName' must be static.", function)
                valid = false
            } else if (!function.isPublic()) {
                logger.error("Java query '$qualifiedName.$methodName' must be public.", function)
                valid = false
            } else {
                queries.add(function)
            }
        }
        return queries.takeIf { valid }
    }

    private fun KSFunctionDeclaration.isGeneratedModelFunction(): Boolean {
        if (origin == Origin.SYNTHETIC) return true
        val name = simpleName.asString()
        return name.startsWith('<') || name == "equals" || name == "hashCode" || name == "toString" ||
            name == "copy" || name.matches(Regex("component[0-9]+"))
    }

    private fun KSFunctionDeclaration.isQueryCandidate(
        readModel: KSClassDeclaration,
        resolver: Resolver
    ): Boolean = hasQuerySpecificAnnotation() || returnsReadModelShape(readModel, resolver)

    private fun KSAnnotated.hasQuerySpecificAnnotation(): Boolean =
        hasAnnotation(PATH_ANNOTATION) ||
            hasAnnotation(QUERY_HTTP_METHOD_ANNOTATION) ||
            hasAnnotation(QUERY_TRANSPORT_ANNOTATION)

    private fun KSFunctionDeclaration.returnsReadModelShape(
        readModel: KSClassDeclaration,
        resolver: Resolver
    ): Boolean {
        val readModelName = readModel.qualifiedName?.asString() ?: return false
        var type = returnType?.resolve()?.takeUnless { resolved -> resolved.isError } ?: return false
        val outerName = type.declaration.qualifiedName?.asString()
        if (outerName == KOTLIN_FLOW_TYPE || outerName in JDK_PUBLISHER_TYPES || outerName in RXJAVA3_OBSERVABLE_TYPES || isCompletionStage(type, resolver)) {
            type = type.arguments.singleOrNull()?.type?.resolve() ?: return false
        }
        val returnName = type.declaration.qualifiedName?.asString() ?: return false
        if (returnName == readModelName) return true
        if (returnName !in COLLECTION_TYPE_NAMES && returnName != ARRAY_TYPE &&
            returnName != QUERY_PAGE_TYPE && returnName != SPRING_PAGE_TYPE) return false
        return type.arguments.singleOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == readModelName
    }

    private fun logGenericQueryFunction(identity: String, function: KSFunctionDeclaration) {
        val typeParameterBounds = function.typeParameters.asSequence()
            .flatMap { typeParameter -> typeParameter.bounds }
            .map { bound -> bound.resolve() }
            .toList()
        val infrastructureBound = typeParameterBounds.firstNotNullOfOrNull { bound ->
            matchingQueryBound(bound, QUERY_INFRASTRUCTURE_TYPES.keys, QUERY_INFRASTRUCTURE_SIMPLE_NAMES)
        }
        val hostAdapterBound = typeParameterBounds.firstNotNullOfOrNull { bound ->
            matchingQueryBound(bound, QUERY_HOST_ADAPTER_TYPES.keys, QUERY_HOST_ADAPTER_SIMPLE_NAMES)
        }
        when {
            infrastructureBound != null -> logger.error(
                ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER,
                "Query '$identity' must not use a generic infrastructure parameter bounded by " +
                    "'$infrastructureBound'; only exact non-generic query infrastructure parameter types are supported.",
                function
            )
            hostAdapterBound != null -> logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '$identity' must not use a generic host adapter parameter bounded by '$hostAdapterBound'; " +
                    "only exact non-generic '$SPRING_PAGEABLE_TYPE' and '$SPRING_SORT_TYPE' parameters are supported.",
                function
            )
            else -> logger.error("Query '$identity' must not declare type parameters.", function)
        }
    }

    private fun matchingQueryBound(bound: KSType, exactTypes: Set<String>, simpleNames: Set<String>): String? {
        val declaration = bound.declaration as? KSClassDeclaration ?: return null
        val qualifiedName = declaration.qualifiedName?.asString() ?: return null
        return qualifiedName.takeIf {
            qualifiedName in exactTypes ||
                declaration.simpleName.asString() in simpleNames ||
                declaration.getAllSuperTypes().any { supertype ->
                    supertype.declaration.qualifiedName?.asString() in exactTypes
                }
        }
    }

    private fun buildQueryModel(
        readModel: KSClassDeclaration,
        function: KSFunctionDeclaration,
        resolver: Resolver
    ): QueryModel? {
        val declaringTypeName = requireNotNull(readModel.qualifiedName).asString()
        val methodName = function.simpleName.asString()
        val identity = "$declaringTypeName.$methodName"
        if (!function.isPublic()) {
            logger.error("Query '$identity' must be public.", function)
            return null
        }
        // Only actual Kotlin query candidates reach this point; unrelated private companions are not artifacts.
        if (readModel.origin != Origin.JAVA && function.parentDeclaration?.isPublic() != true) {
            logger.error(
                ArcDiagnostic.QUERY_DECLARATION,
                "Query '$identity' must be declared in a public companion object; make the companion public.",
                function
            )
            return null
        }
        if (Modifier.ABSTRACT in function.modifiers) {
            logger.error("Query '$identity' must not be abstract.", function)
            return null
        }
        if (function.extensionReceiver != null) {
            logger.error("Query '$identity' must not be an extension function.", function)
            return null
        }
        if (function.typeParameters.isNotEmpty()) {
            logGenericQueryFunction(identity, function)
            return null
        }

        val parameters = QueryParameterBuilder(logger, metadataCollector, identity, function).build() ?: return null
        val defaultedClientParameters = parameters.filter(QueryParameterModel::hasDefault)
        if (defaultedClientParameters.size > MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS) {
            logger.error(
                ArcDiagnostic.QUERY_DEFAULT,
                "Kotlin query parameter defaults on '$identity' are unsupported because " +
                    "${defaultedClientParameters.size} defaulted client parameters require more than the maximum " +
                    "$MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS (${1 shl MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS} invocation branches).",
                function
            )
            return null
        }
        val returnShape = QueryReturnShapeAnalyzer(logger, resolver, readModel, function).analyze() ?: return null
        val hostAdapterParameters = parameters.filter { it.source == QueryParameterSource.HOST_ADAPTER }
        if (hostAdapterParameters.isNotEmpty() && !returnShape.adaptsSpringDataPage) {
            logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '$identity' uses Pageable or Sort host adapters but does not return exact " +
                    "'$SPRING_PAGE_TYPE<T>'; provider-owned adapters require a Page return to prevent Arc from " +
                    "sorting or paging the result again.",
                function
            )
            return null
        }
        val authorization = buildAuthorization(readModel, function, identity, "Query") ?: return null
        val pathResolution = resolveExplicitPath(readModel, function, identity)
        if (!pathResolution.first) return null
        val explicitPath = pathResolution.second
        val queryHttpMethod = function.enumArgument(QUERY_HTTP_METHOD_ANNOTATION, "value")
            ?: readModel.enumArgument(QUERY_HTTP_METHOD_ANNOTATION, "value")
            ?: "AUTO"
        val declaredTransport = function.enumArgument(QUERY_TRANSPORT_ANNOTATION, "value")
        if (declaredTransport == "OBSERVABLE" && !returnShape.isObservable) {
            logger.error("Query '$identity' declares observable transport but does not return Flow<T>, Flow.Publisher<T>, or an RxJava 3 ObservableSource<T>.", function)
            return null
        }
        if (declaredTransport == "REQUEST_RESPONSE" && returnShape.isObservable) {
            logger.error("Query '$identity' declares request-response transport but returns an observable type.", function)
            return null
        }
        val transport = if (returnShape.isObservable) "OBSERVABLE" else "REQUEST_RESPONSE"

        return QueryModel(
            declaringTypeName = declaringTypeName,
            methodName = methodName,
            performerClassName = queryPerformerClassName(identity),
            parameters = parameters,
            returnTypeName = declaringTypeName,
            authorization = authorization,
            explicitPath = explicitPath,
            queryHttpMethod = queryHttpMethod,
            transport = transport,
            isEnumerable = returnShape.isEnumerable,
            supportsPaging = returnShape.supportsPaging ||
                parameters.any { parameter -> parameter.hostAdapterKind == QueryHostAdapterKind.PAGEABLE },
            supportsSorting = parameters.any { parameter ->
                parameter.hostAdapterKind == QueryHostAdapterKind.PAGEABLE ||
                    parameter.hostAdapterKind == QueryHostAdapterKind.SORT
            },
            treatWarningsAsErrors = readModel.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION) ||
                function.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION),
            invocationKind = returnShape.invocationKind,
            adaptsSpringDataPage = returnShape.adaptsSpringDataPage,
            summary = DocumentationSummaryParser.parse(function.docString).summary
        )
    }

    private fun resolveExplicitPath(
        readModel: KSClassDeclaration,
        function: KSFunctionDeclaration,
        identity: String
    ): Pair<Boolean, String?> {
        val classPaths = readModel.annotationsNamed(PATH_ANNOTATION).mapNotNull { it.stringArgument("value") }.distinct().toList()
        val methodPaths = function.annotationsNamed(PATH_ANNOTATION).mapNotNull { it.stringArgument("value") }.distinct().toList()
        if (classPaths.size > 1 || methodPaths.size > 1) {
            logger.error("Query '$identity' has conflicting ambiguous @Path declarations.", function)
            return false to null
        }
        return true to (methodPaths.singleOrNull() ?: classPaths.singleOrNull())
    }

    private fun generateHandler(command: CommandInvocationModel, containingFile: KSFile) {
        val dependencies = Dependencies(aggregating = false, containingFile)
        codeGenerator.createNewFile(dependencies, GENERATED_COMMANDS_PACKAGE, command.handlerClassName).bufferedWriter().use {
            writer -> writer.write(ArcCommandRenderer.renderHandler(command, moduleName))
        }
    }

    private fun generatePerformer(query: QueryModel, containingFile: KSFile) {
        val dependencies = Dependencies(aggregating = false, containingFile)
        codeGenerator.createNewFile(dependencies, GENERATED_QUERIES_PACKAGE, query.performerClassName).bufferedWriter().use {
            writer -> writer.write(ArcQueryRenderer.renderPerformer(query, moduleName))
        }
    }

    private fun generateMetadataFactories(
        moduleName: String,
        commands: List<CommandModel>,
        queries: List<QueryModel>,
        dependencies: Dependencies
    ) {
        val factories = commands.map { command ->
            "    fun create${command.handlerClassName}(): io.cratis.arc.metadata.CommandDescriptor = " +
                ArcCommandRenderer.renderDescriptor(command)
        } + queries.map { query ->
            "    fun create${query.performerClassName}(): io.cratis.arc.metadata.QueryDescriptor = " +
                ArcQueryRenderer.renderDescriptor(query)
        }
        val className = "${moduleName}ArcArtifactMetadata"
        val source = """// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package $GENERATED_PACKAGE

/** Creates fresh descriptors without constructing a module or an invoker. */
internal object $className {
${factories.joinToString("\n\n")}
}
"""
        codeGenerator.createNewFile(dependencies, GENERATED_PACKAGE, className).bufferedWriter().use { writer ->
            writer.write(source)
        }
    }

    private fun generateModule(moduleName: String) {
        val sortedCommands = commands.sortedBy(CommandModel::qualifiedName)
        val sortedQueries = queries.sortedBy(QueryModel::fullyQualifiedName)
        val sortedTypes = metadataCollector.types
        val sortedInterfaces = metadataCollector.interfaces
        val sortedEnums = metadataCollector.enums
        val sortedConcepts = metadataCollector.concepts
        // ALL_FILES can retain invalid first-round KSFiles in KSP2. The terminal round's files remain valid in finish.
        // Preserve all current source associations and the aggregating wildcard, including later generated artifacts.
        val dependencies = Dependencies(aggregating = true, *latestRoundFiles.toTypedArray())
        generateMetadataFactories(moduleName, sortedCommands, sortedQueries, dependencies)
        // A separate scope receipt distinguishes verified compile-time contributions from runtime-only
        // discovery, without adding origin fields to the artifact manifest or re-exporting declarations.
        val scope = FluentValidationMetadata.scopeDocument(moduleName, fluentIndexOption != null, fluentSnapshot.all.map { it.validator })
        codeGenerator.createNewFileByPath(dependencies, "META-INF/cratis/arc-fluent-validation-scope/$moduleName.json", "")
            .bufferedWriter(Charsets.UTF_8).use { it.write(scope) }
        val className = moduleClassName(moduleName)
        val handlers = ArcDescriptorRenderer.renderModuleArtifacts(
            sortedCommands.map { command -> "$GENERATED_COMMANDS_PACKAGE.${command.handlerClassName}()" }
        )
        val performers = ArcDescriptorRenderer.renderModuleArtifacts(
            sortedQueries.map { query -> "$GENERATED_QUERIES_PACKAGE.${query.performerClassName}()" }
        )
        val types = ArcDescriptorRenderer.renderModuleArtifacts(sortedTypes.map(ArcDescriptorRenderer::renderTypeDescriptor))
        val interfaces = ArcDescriptorRenderer.renderModuleArtifacts(sortedInterfaces.map(ArcDescriptorRenderer::renderInterfaceDescriptor))
        val enums = ArcDescriptorRenderer.renderModuleArtifacts(sortedEnums.map(ArcDescriptorRenderer::renderEnumDescriptor))
        val concepts = ArcDescriptorRenderer.renderModuleArtifacts(sortedConcepts.map(ArcDescriptorRenderer::renderConceptDescriptor))
        val derivedTypes = ArcDescriptorRenderer.renderModuleArtifacts(
            metadataCollector.derivedTypes.map(ArcDescriptorRenderer::renderDerivedTypeRegistration)
        )
        val source = """// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package $GENERATED_PACKAGE

/** Generated Arc artifact module for the '$moduleName' compilation. */
public class $className : io.cratis.arc.artifacts.ArcArtifactModule(
    commandHandlers = $handlers,
    queryPerformers = $performers,
    types = $types,
    enums = $enums,
    interfaces = $interfaces,
    concepts = $concepts,
    derivedTypes = $derivedTypes
)${ArcDescriptorRenderer.renderFluentContributions(fluentSnapshot.all)}
"""
        codeGenerator.createNewFile(dependencies, GENERATED_PACKAGE, className).bufferedWriter().use { writer ->
            writer.write(source)
        }
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/services/io.cratis.arc.artifacts.ArcArtifactModule",
            ""
        ).bufferedWriter().use { writer ->
            writer.write("$GENERATED_PACKAGE.$className\n")
        }
        val manifest = ArcManifestBuilder.build(
            moduleName,
            ManifestContent(sortedCommands, sortedQueries, sortedTypes, sortedInterfaces, sortedEnums, sortedConcepts)
        )
        val manifestJson = ArcManifestJson.serialize(manifest)
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/cratis/arc/$moduleName.json",
            ""
        ).bufferedWriter().use { writer -> writer.write(manifestJson) }
    }
}

/**
 * Validates a symbol the way the deprecated single-predicate [KSNode.validate] overload did, using the new
 * overload that makes the choice explicit: the default always-true predicate and no new validation features.
 * KSP 2.3.12 deprecated the single-predicate form and this repository compiles with warnings as errors.
 */
private fun KSAnnotated.validateForProcessing(): Boolean = validate({ _, _ -> true }, enableNewFeatures = false)

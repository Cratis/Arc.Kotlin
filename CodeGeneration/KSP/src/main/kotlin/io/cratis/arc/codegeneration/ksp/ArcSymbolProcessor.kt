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
    private val commandNames = sortedSetOf<String>()
    private val readModelNames = sortedSetOf<String>()
    private val exportedTypeNames = sortedSetOf<String>()
    private val derivedTypeNames = sortedSetOf<String>()
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
    private val responseHandlers = ResponseHandlerRegistry(logger, graphLogger, importedHandlers)
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
    private val emittedCommands = mutableSetOf<String>()
    private val emittedQueries = mutableSetOf<String>()
    private val inspectedCommandLikeTypes = mutableSetOf<String>()
    private val shapeValidator = ArtifactShapeValidator(logger)
    private val authorizationReader = AuthorizationReader(logger)
    private val commandLikeTypeInspector = CommandLikeTypeInspector(logger, inspectedCommandLikeTypes)
    private val ignoreValidationOwners = sortedSetOf<String>()
    private val ignoreValidationTopLevelProperties = sortedSetOf<String>()
    private val queryNames = mutableSetOf<String>()
    private val explicitQueryRoutes = mutableSetOf<String>()
    private val commands = mutableListOf<CommandModel>()
    private val queries = mutableListOf<QueryModel>()
    private val exportRoots = sortedSetOf<String>()
    private var configurationReported = false
    private var moduleGenerated = false
    private var latestRoundFiles: List<KSFile> = emptyList()
    private var hasDeferredInputs = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        beginRound(resolver)
        val deferred = mutableListOf<KSAnnotated>()
        try {
            reportInvalidConfiguration()
            IgnoreValidationPolicy.validateTargets(resolver, graphLogger, ignoreValidationOwners, ignoreValidationTopLevelProperties)
            discoverFluentValidation(resolver, deferred)
            deferred += responseHandlers.discover(resolver)
            hasDeferredInputs = hasDeferredInputs || responseHandlers.hasDeferredInputs
            commandLikeTypeInspector.inspect(resolver)
            processArtifactRoots(resolver, deferred)
            collectFluentModels(resolver, deferred)
            deferred += IdentityDetailsDiscovery(graphLogger) { declaration ->
                val name = requireNotNull(declaration.qualifiedName).asString()
                metadataCollector.collectDeclaration(declaration, name).also { collected ->
                    if (collected) exportRoots += name
                }
            }.discover(latestRoundFiles.asSequence().flatMap { it.declarations })
            SharedWireEdgeValidator(resolver, graphLogger, metadataCollector, fluentSnapshot.all).validate()
            reportUnreadableFluentMembers(resolver)
            hasDeferredInputs = hasDeferredInputs || deferred.isNotEmpty()
            return deferred.distinct()
        } finally {
            metadataCollector.releaseSymbols()
        }
    }

    private fun beginRound(resolver: Resolver) {
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
    }

    private fun discoverFluentValidation(resolver: Resolver, deferred: MutableList<KSAnnotated>) {
        fluentTrace?.invoke("[ARC-FLUENT-ROUND] ${++fluentRound}")
        val fluent = FluentValidationDiscovery(graphLogger, fluentTrace).discover(resolver, fluentNames, importedFluent, fluentIndexOption != null)
        deferred += fluent.deferred
        fluentSnapshot = fluent.copy(deferred = emptyList())
        val fluentRules = fluentSnapshot.all.flatMap { declaration -> declaration.members.map { member ->
            "${declaration.model}.${member.name}" to member.rules.map { ValidationRuleModel(it.ruleName, it.arguments, it.message) }
        } }.groupBy({ it.first }, { it.second }).mapValues { (_, rules) -> rules.flatten() }
        metadataCollector = MetadataCollector(graphLogger, fluentRules, binaryRecords)
    }

    private fun processArtifactRoots(resolver: Resolver, deferred: MutableList<KSAnnotated>) {
        val commandSymbols = discoverRoots(resolver, COMMAND_ANNOTATION, commandNames)
        val readModelSymbols = discoverRoots(resolver, READ_MODEL_ANNOTATION, readModelNames)
        val exportedTypeSymbols = discoverRoots(resolver, EXPORTED_TYPE_ANNOTATION, exportedTypeNames)
        derivedTypeNames += derivedTypeAnnotations().flatMap { resolver.getSymbolsWithAnnotation(it) }
            .filterIsInstance<KSClassDeclaration>().mapNotNull { it.qualifiedName?.asString() }
        metadataCollector.useResolver(resolver, derivedTypeNames)
        deferred += (commandSymbols + readModelSymbols + exportedTypeSymbols).filterNot(KSAnnotated::validateForProcessing)
        commandSymbols.filter(KSAnnotated::validateForProcessing).forEach { processCommand(it, resolver) }
        readModelSymbols.filter(KSAnnotated::validateForProcessing).forEach { processReadModel(it, resolver) }
        exportedTypeSymbols.filter(KSAnnotated::validateForProcessing).forEach { processExportedType(it) }
    }

    private fun collectFluentModels(resolver: Resolver, deferred: MutableList<KSAnnotated>) {
        fluentSnapshot.local.map { it.model }.distinct().forEach { name ->
            val model = resolver.getClassDeclarationByName(resolver.getKSNameFromString(name))
            if (model == null) {
                hasDeferredInputs = true
            } else if (!model.validateForProcessing()) {
                deferred += model
            } else if (metadataCollector.collectDeclaration(model, name)) {
                registerFluentModel(model, name)
            }
        }
    }

    private fun registerFluentModel(model: KSClassDeclaration, name: String) {
        if (metadataCollector.propertiesFor(name) == null) {
            graphLogger.error(
                ArcDiagnostic.FLUENT_DECLARATION,
                "Fluent model '$name' has no concrete model-property metadata; use a plain model or a server-only ModelValidator for scalar/concept targets.",
                model
            )
        } else {
            exportRoots += name
        }
    }

    private fun reportUnreadableFluentMembers(resolver: Resolver) {
        fluentSnapshot.all.forEach { declaration ->
            val properties = metadataCollector.propertiesFor(declaration.model) ?: return@forEach
            declaration.members.filter { member -> properties.none { it.name == member.name } }.forEach { member ->
                graphLogger.error(
                    ArcDiagnostic.FLUENT_RULE,
                    "Fluent validator '${declaration.validator}', member '${member.name}' is not a readable generated wire member; remove the ignored or unsupported selector.",
                    resolver.getClassDeclarationByName(resolver.getKSNameFromString(declaration.validator))
                )
            }
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
        if (configuredName != null && canGenerateModule()) {
            writeFluentValidationIndex(configuredName)
            writeResponseHandlerIndex(configuredName)
            if (hasArtifactsToGenerate()) generateModule(configuredName)
            moduleGenerated = true
        }
        latestRoundFiles = emptyList()
    }

    private fun canGenerateModule(): Boolean =
        !logger.hasErrors && !graphLogger.hasErrors && !hasDeferredInputs && !moduleGenerated

    private fun hasArtifactsToGenerate(): Boolean =
        commands.isNotEmpty() || queries.isNotEmpty() || exportRoots.isNotEmpty() ||
            fluentRootCompilation && fluentSnapshot.all.isNotEmpty()

    private fun writeFluentValidationIndex(configuredName: String) {
        if (fluentSnapshot.local.isEmpty()) return
        codeGenerator.createNewFileByPath(Dependencies(true, *latestRoundFiles.toTypedArray()),
            "${FluentValidationMetadata.PREFIX}$configuredName.json", "").bufferedWriter(Charsets.UTF_8).use {
            it.write(FluentValidationMetadata.document(configuredName, fluentSnapshot.local))
        }
    }

    private fun writeResponseHandlerIndex(configuredName: String) {
        if (responseHandlers.exportedHandlers.isEmpty()) return
        codeGenerator.createNewFileByPath(
            Dependencies(true, *latestRoundFiles.toTypedArray()),
            "${ResponseHandlerMetadata.PREFIX}$configuredName.json", ""
        ).bufferedWriter(Charsets.UTF_8).use {
            it.write(ResponseHandlerMetadata.document(configuredName, responseHandlers.exportedHandlers))
        }
    }

    override fun onError() {
        graphLogger.flush()
        latestRoundFiles = emptyList()
    }

    private fun processCommand(symbol: KSAnnotated, resolver: Resolver) {
        val command = symbol as? KSClassDeclaration
        if (command == null) {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "@$COMMAND_SIMPLE_NAME can only be applied to a class.", symbol)
            return
        }
        val qualifiedName = command.qualifiedName?.asString()
        if (qualifiedName != null) {
            commandModelBuilder(resolver).build(command)?.let { model ->
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
            if (shapeValidator.validateReadModelShape(readModel, qualifiedName)) {
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
        if (!shapeValidator.validateExportedTypeShape(declaration, qualifiedName)) return
        if (metadataCollector.collectDeclaration(declaration, qualifiedName)) exportRoots += qualifiedName
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

    private fun commandModelBuilder(resolver: Resolver) = CommandModelBuilder(responseServices(), resolver) { name, invocation, file ->
        if (moduleName != null && emittedCommands.add(name)) generateHandler(invocation(), file)
    }

    private fun responseServices() =
        CommandResponseServices(logger, graphLogger, metadataCollector, responseHandlers.declarativeHandledTypes)

    private fun buildQueryModels(readModel: KSClassDeclaration, resolver: Resolver): List<QueryModel> {
        val qualifiedName = readModel.qualifiedName?.asString() ?: return emptyList()
        val containingFile = readModel.containingFile
        if (containingFile == null) {
            logger.error("Read model '$qualifiedName' does not have a resolvable source file.", readModel)
            return emptyList()
        }

        val functions = QueryFunctionDiscovery(logger, resolver, readModel, qualifiedName).discover() ?: return emptyList()

        val overloadedNames = functions.groupBy { function -> function.simpleName.asString() }
            .filterValues { overloads -> overloads.size > 1 }
            .keys
        overloadedNames.forEach { name ->
            logger.error("Read model '$qualifiedName' has overloaded query name '$name'; query names must be unique.", readModel)
        }

        return functions.filter { function -> function.simpleName.asString() !in overloadedNames }
            .mapNotNull { function ->
                QueryModelBuilder(logger, metadataCollector, resolver, authorizationReader).build(readModel, function)?.takeIf { registerQuery(it, function) }?.also { model ->
                    if (moduleName != null && emittedQueries.add(model.fullyQualifiedName)) generatePerformer(model, containingFile)
                }
            }
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

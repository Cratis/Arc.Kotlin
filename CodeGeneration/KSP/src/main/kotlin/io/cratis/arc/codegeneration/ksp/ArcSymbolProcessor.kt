// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
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
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Nullability
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.validate
import io.cratis.arc.artifacts.ArcArtifactManifest
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.AuthorizationMetadata
import io.cratis.arc.metadata.CommandDescriptor
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
    private val metadataCollector = MetadataCollector(logger)
    private val processedCommands = mutableSetOf<String>()
    private val processedReadModels = mutableSetOf<String>()
    private val inspectedCommandLikeTypes = mutableSetOf<String>()
    private val queryNames = mutableMapOf<String, KSFunctionDeclaration>()
    private val explicitQueryRoutes = mutableMapOf<String, KSFunctionDeclaration>()
    private val declarativeHandledResponseTypes = sortedSetOf<String>()
    private val processedDeclarativeResponseHandlers = mutableSetOf<String>()
    private val commands = mutableListOf<CommandModel>()
    private val queries = mutableListOf<QueryModel>()
    private var configurationReported = false
    private var moduleGenerated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        metadataCollector.useResolver(resolver)
        reportInvalidConfiguration()
        val responseHandlerDeferred = discoverDeclarativeHandledResponseTypes(resolver)
        inspectCommandLikeTypes(resolver)
        val commandSymbols = resolver.getSymbolsWithAnnotation(COMMAND_ANNOTATION).toList()
        val readModelSymbols = resolver.getSymbolsWithAnnotation(READ_MODEL_ANNOTATION).toList()
        val deferred = (commandSymbols + readModelSymbols + responseHandlerDeferred)
            .filterNot(KSAnnotated::validate)
            .distinct()

        commandSymbols.filter(KSAnnotated::validate).forEach { symbol -> processCommand(symbol, resolver) }
        readModelSymbols.filter(KSAnnotated::validate).forEach { symbol -> processReadModel(symbol, resolver) }
        return deferred
    }

    override fun finish() {
        val configuredName = moduleName
        if (!moduleGenerated && configuredName != null && (commands.isNotEmpty() || queries.isNotEmpty())) {
            generateModule(configuredName)
            moduleGenerated = true
        }
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
                        Modifier.PUBLIC in function.modifiers
                }
                val hasPublicState = declaration.getAllProperties().any { property -> Modifier.PUBLIC in property.modifiers }
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
                        Modifier.PUBLIC in function.modifiers && function.parameters.any { parameter ->
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
            logger.error("@$COMMAND_SIMPLE_NAME can only be applied to a class.", symbol)
            return
        }
        val qualifiedName = command.qualifiedName?.asString()
        if (qualifiedName != null && processedCommands.add(qualifiedName)) {
            buildCommandModel(command, resolver)?.let { model ->
                if (metadataCollector.collectDeclaration(command, qualifiedName, command, model.properties)) {
                    commands.add(model)
                    generateHandler(model)
                }
            }
        } else if (qualifiedName == null) {
            logger.error("@$COMMAND_SIMPLE_NAME classes must not be local.", command)
        }
    }

    private fun processReadModel(symbol: KSAnnotated, resolver: Resolver) {
        val readModel = symbol as? KSClassDeclaration
        if (readModel == null) {
            logger.error("@$READ_MODEL_SIMPLE_NAME can only be applied to a class.", symbol)
            return
        }
        val qualifiedName = readModel.qualifiedName?.asString()
        if (qualifiedName != null && processedReadModels.add(qualifiedName)) {
            if (validateReadModelShape(readModel, qualifiedName) &&
                metadataCollector.collectDeclaration(readModel, qualifiedName)) {
                buildQueryModels(readModel, resolver).forEach { model ->
                    if (registerQuery(model)) {
                        queries.add(model)
                        generatePerformer(model)
                    }
                }
            }
        } else if (qualifiedName == null) {
            logger.error("@$READ_MODEL_SIMPLE_NAME classes must not be local.", readModel)
        }
    }

    private fun registerQuery(model: QueryModel): Boolean {
        val previousName = queryNames.putIfAbsent(model.fullyQualifiedName, model.source)
        if (previousName != null) {
            logger.error(
                ArcDiagnostic.DUPLICATE_QUERY,
                "Duplicate fully qualified query name '${model.fullyQualifiedName}' is generated by more than one function.",
                model.source
            )
            return false
        }
        val explicitPath = model.explicitPath ?: return true
        val normalizedPath = "/" + explicitPath.trim().trim('/').replace(Regex("/+"), "/")
        val previousRoute = explicitQueryRoutes.putIfAbsent(normalizedPath, model.source)
        if (previousRoute != null) {
            logger.error(
                ArcDiagnostic.ROUTE,
                "Explicit query route '$normalizedPath' is generated by more than one query; @Path values must be unique.",
                model.source
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

    private fun buildCommandModel(command: KSClassDeclaration, resolver: Resolver): CommandModel? {
        val qualifiedName = command.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error("@$COMMAND_SIMPLE_NAME classes must not be local.", command)
            return null
        }
        if (command.parentDeclaration != null) {
            logger.error("Command '$qualifiedName' must be a top-level class; nested commands are not supported.", command)
            return null
        }
        if (command.classKind != ClassKind.CLASS) {
            logger.error("Command '$qualifiedName' must be a concrete class.", command)
            return null
        }
        if (Modifier.PUBLIC !in command.modifiers) {
            logger.error("Command '$qualifiedName' must be public so its generated handler can invoke it.", command)
            return null
        }
        if (Modifier.ABSTRACT in command.modifiers) {
            logger.error("Command '$qualifiedName' must not be abstract.", command)
            return null
        }
        if (command.typeParameters.isNotEmpty()) {
            logger.error("Command '$qualifiedName' must not declare type parameters.", command)
            return null
        }

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
        if (Modifier.PUBLIC !in handler.modifiers) {
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

        val parameters = buildMethodParameters(qualifiedName, handler, HANDLER_NAME) ?: return null
        val invocationKind = determineInvocationKind(qualifiedName, handler, resolver, HANDLER_NAME) ?: return null
        val response = determineCommandResponse(qualifiedName, handler, resolver) ?: return null
        val namedProvides = command.getDeclaredFunctions().filter { it.simpleName.asString() == PROVIDE_NAME }.toList()
        if (namedProvides.size > 1) {
            logger.error("Command '$qualifiedName' has overloaded '$PROVIDE_NAME' functions; at most one is supported.", command)
            return null
        }
        val provideMethod = namedProvides.singleOrNull()
        val provide = provideMethod?.let { method ->
            if (Modifier.PUBLIC !in method.modifiers) {
                logger.error("Provide method '$qualifiedName.$PROVIDE_NAME' must be public.", method)
                return null
            }
            if (method.functionKind != FunctionKind.MEMBER || Modifier.JAVA_STATIC in method.modifiers) {
                logger.error("Provide method '$qualifiedName.$PROVIDE_NAME' must be a non-static instance function.", method)
                return null
            }
            if (Modifier.ABSTRACT in method.modifiers || method.extensionReceiver != null || method.typeParameters.isNotEmpty()) {
                logger.error("Provide method '$qualifiedName.$PROVIDE_NAME' must be concrete, non-generic, and not an extension function.", method)
                return null
            }
            ProvideModel(
                buildMethodParameters(qualifiedName, method, PROVIDE_NAME) ?: return null,
                determineInvocationKind(qualifiedName, method, resolver, PROVIDE_NAME, allowNullableReturn = true)
                    ?: return null
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
        val authorization = buildAuthorization(command, handler, qualifiedName, "Command") ?: return null
        val containingFile = command.containingFile
        if (containingFile == null) {
            logger.error("Command '$qualifiedName' does not have a resolvable source file.", command)
            return null
        }

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
            containingFile = containingFile
        )
    }

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

    private fun buildMethodParameters(
        commandName: String,
        method: KSFunctionDeclaration,
        methodName: String
    ): List<HandlerParameterModel>? {
        val parameters = mutableListOf<HandlerParameterModel>()
        method.parameters.forEach { parameter ->
            val type = parameter.type.resolve()
            val parameterName = parameter.name?.asString() ?: "argument${parameters.size}"
            if (containsMapType(type)) {
                logger.error(
                    ArcDiagnostic.PROXY_SHAPE,
                    "Service parameter '$commandName.$methodName.$parameterName' value path 'parameter' uses a map; maps are supported only for artifact properties.",
                    parameter
                )
                return null
            }
            val declaration = type.declaration
            val qualifiedName = declaration.qualifiedName?.asString()
            if (type.isError || declaration is KSTypeParameter || declaration !is KSClassDeclaration || qualifiedName == null) {
                logger.error(
                    "Parameter '$parameterName' on '$commandName.$methodName' must have a resolvable class or interface type.",
                    parameter
                )
                return null
            }
            val isOptionalType = qualifiedName == JAVA_OPTIONAL_TYPE
            if (isOptionalType && method.origin != Origin.JAVA) {
                logger.error(
                    "Parameter '$parameterName' on '$commandName.$methodName' must not use java.util.Optional from Kotlin; " +
                        "use a nullable @ReadModel parameter or an ordinary non-null service parameter.",
                    parameter
                )
                return null
            }
            val isJavaOptional = isOptionalType && method.origin == Origin.JAVA
            val resolvedType = if (isJavaOptional) {
                val typeArgument = type.arguments.singleOrNull()
                val argument = typeArgument?.type?.resolve()
                val argumentDeclaration = argument?.declaration as? KSClassDeclaration
                val argumentName = argumentDeclaration?.qualifiedName?.asString()
                if (type.nullability == Nullability.NULLABLE || typeArgument?.variance != Variance.INVARIANT ||
                    argument == null || argument.isError || argumentDeclaration == null || argumentName == null ||
                    argument.nullability == Nullability.NULLABLE || argument.arguments.isNotEmpty()) {
                    logger.error(
                        "Java Optional parameter '$parameterName' on '$commandName.$methodName' must have one concrete, " +
                            "invariant, non-null, non-parameterized class or interface type.",
                        parameter
                    )
                    return null
                }
                argumentName to CommandParameterResolution.OPTIONAL
            } else {
                if (type.nullability == Nullability.NULLABLE && !declaration.hasAnnotation(READ_MODEL_ANNOTATION)) {
                    logger.error(
                        "Parameter '$parameterName' on '$commandName.$methodName' may be nullable only when its type is an @ReadModel command dependency.",
                        parameter
                    )
                    return null
                }
                qualifiedName to if (type.nullability == Nullability.NULLABLE) {
                    CommandParameterResolution.NULLABLE
                } else {
                    CommandParameterResolution.REQUIRED
                }
            }
            if (parameter.hasDefault) {
                logger.warn(
                    ArcDiagnostic.INTEROP,
                    "Parameter '$parameterName' on '$commandName.$methodName' has a default value, but generated " +
                        "Java/Kotlin invocation always resolves and supplies the argument.",
                    parameter
                )
            }
            if (type.arguments.isNotEmpty() && !isJavaOptional) {
                logger.error(
                    "Parameter '$parameterName' on '$commandName.$methodName' must not use a parameterized service type.",
                    parameter
                )
                return null
            }
            if (resolvedType.first == "kotlin.Array") {
                logger.error(
                    "Parameter '$parameterName' on '$commandName.$methodName' must not use an array service type.",
                    parameter
                )
                return null
            }
            parameters.add(
                HandlerParameterModel(
                    parameterName,
                    renderClassLiteralType(resolvedType.first),
                    resolvedType.second
                )
            )
        }
        return parameters
    }

    private fun determineInvocationKind(
        commandName: String,
        method: KSFunctionDeclaration,
        resolver: Resolver,
        methodName: String,
        allowNullableReturn: Boolean = true
    ): InvocationKind? {
        val returnReference = method.returnType
        if (returnReference == null) {
            logger.error("Command method '$commandName.$methodName' has no resolvable return type.", method)
            return null
        }
        val returnType = returnReference.resolve()
        val returnDeclaration = returnType.declaration
        val returnName = returnDeclaration.qualifiedName?.asString()
        if (returnType.isError || returnDeclaration is KSTypeParameter || returnName == null) {
            logger.error("Command method '$commandName.$methodName' has an unsupported or unresolvable return type.", method)
            return null
        }
        if (returnName == "kotlin.Nothing") {
            logger.error("Command method '$commandName.$methodName' must return a value, Unit, void, or CompletionStage<T>.", method)
            return null
        }
        if (!allowNullableReturn && returnType.nullability == Nullability.NULLABLE) {
            logger.error("Command method '$commandName.$methodName' must not declare a nullable return type.", method)
            return null
        }

        if (isCompletionStage(returnType, resolver)) {
            if (returnType.arguments.size != 1 || returnType.arguments.single().type == null) {
                logger.error("Command method '$commandName.$methodName' must return CompletionStage<T> with a concrete T.", method)
                return null
            }
            val resultType = returnType.arguments.single().type!!.resolve()
            val resultName = resultType.declaration.qualifiedName?.asString()
            if (resultType.isError || resultType.declaration is KSTypeParameter || resultName == null) {
                logger.error("Command method '$commandName.$methodName' has an unresolvable CompletionStage result type.", method)
                return null
            }
            if (!allowNullableReturn && resultType.nullability == Nullability.NULLABLE) {
                logger.error("Command method '$commandName.$methodName' must not return CompletionStage<T?>.", method)
                return null
            }
            return if (resultName in VOID_TYPE_NAMES) {
                InvocationKind.COMPLETION_STAGE_VOID
            } else {
                InvocationKind.COMPLETION_STAGE
            }
        }
        return if (returnName in VOID_TYPE_NAMES) InvocationKind.UNIT else InvocationKind.VALUE
    }

    private fun determineCommandResponse(
        commandName: String,
        handler: KSFunctionDeclaration,
        resolver: Resolver
    ): CommandResponseModel? {
        var responseType = handler.returnType?.resolve()
        if (responseType == null || responseType.isError) {
            logger.error("Handler '$commandName.$HANDLER_NAME' has no resolvable response type.", handler)
            return null
        }
        if (rejectExplicitlyNullableResponseType(responseType, commandName, handler)) return null
        if (isCompletionStage(responseType, resolver)) {
            responseType = unwrapSingleTypeArgument(responseType, commandName, handler, "CompletionStage") ?: return null
        }
        if (responseType.declaration.qualifiedName?.asString() == COMMAND_RESULT_TYPE) {
            responseType = unwrapSingleTypeArgument(responseType, commandName, handler, "CommandResult") ?: return null
        }
        if (responseType.declaration.qualifiedName?.asString() in MAP_TYPE_NAMES) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Command '$commandName' response value path 'response' uses a top-level map; command response maps are unsupported.",
                handler
            )
            return null
        }

        val values = classifyCommandResponseType(responseType, commandName, handler) ?: return null
        val clientValues = values.filter { value -> value.disposition == CommandResponseValueDisposition.CLIENT }
        if (clientValues.size > 1) {
            val conflictingTypes = clientValues.joinToString(", ") { value -> "'${value.typeName}'" }
            logger.error(
                ArcDiagnostic.AMBIGUOUS_COMMAND_RESPONSE,
                "Handler '$commandName.$HANDLER_NAME' has ambiguous command response values in declaration order: " +
                    "$conflictingTypes.",
                handler
            )
            return null
        }
        return CommandResponseModel(values)
    }

    private fun classifyCommandResponseType(
        responseType: KSType,
        commandName: String,
        handler: KSFunctionDeclaration
    ): List<CommandResponseValueModel>? {
        if (responseType.isError || responseType.declaration is KSTypeParameter) {
            logger.error("Handler '$commandName.$HANDLER_NAME' has an unresolvable or generic response type.", handler)
            return null
        }
        if (rejectExplicitlyNullableResponseType(responseType, commandName, handler)) return null
        val responseName = responseType.declaration.qualifiedName?.asString()
        if (responseName == null) {
            logger.error("Handler '$commandName.$HANDLER_NAME' has an unnamed response type.", handler)
            return null
        }
        if (responseName in VOID_TYPE_NAMES || responseName == COMMAND_RESPONSE_VALUES_TYPE) return emptyList()
        if (responseName == COMMAND_RESULT_TYPE) {
            val response = resolveResponseTypeArguments(responseType, 1, commandName, handler)?.singleOrNull()
                ?: return null
            return classifyCommandResponseType(response, commandName, handler)
        }

        val aggregateSize = AGGREGATE_TYPE_ARITIES[responseName]
        if (aggregateSize != null) {
            val members = resolveResponseTypeArguments(responseType, aggregateSize, commandName, handler) ?: return null
            return members.flatMap { member ->
                classifyCommandResponseType(member, commandName, handler) ?: return null
            }
        }
        if (responseName == ARC_ONE_OF_TYPE) {
            val member = resolveResponseTypeArguments(responseType, 1, commandName, handler)?.singleOrNull() ?: return null
            return classifyCommandResponseType(member, commandName, handler)
        }
        if (responseName in COLLECTION_TYPE_NAMES || responseName == ARRAY_TYPE) {
            return classifyCollectionResponseLeaf(responseType, commandName, handler)?.let(::listOf)
        }

        val disposition = if (isHandledResponseLeaf(responseType)) {
            CommandResponseValueDisposition.HANDLED
        } else {
            CommandResponseValueDisposition.CLIENT
        }
        if (disposition == CommandResponseValueDisposition.CLIENT) {
            val identity = "$commandName.$HANDLER_NAME response"
            val shape = metadataCollector.describe(responseType, identity, handler) ?: return null
            if (!metadataCollector.collect(shape, identity, handler)) return null
        }
        return listOf(CommandResponseValueModel(responseName, false, disposition))
    }

    private fun rejectExplicitlyNullableResponseType(
        responseType: KSType,
        commandName: String,
        handler: KSFunctionDeclaration
    ): Boolean {
        if (responseType.nullability != Nullability.NULLABLE) return false
        val typeName = renderKotlinType(responseType) ?: responseType.toString()
        logger.error(
            ArcDiagnostic.COMMAND_RESPONSE,
            "Handler '$commandName.$HANDLER_NAME' has explicitly nullable response type '$typeName'; " +
                "nullable response nodes are unsupported until command response metadata can preserve branch nullability.",
            handler
        )
        return true
    }

    private fun classifyCollectionResponseLeaf(
        responseType: KSType,
        commandName: String,
        handler: KSFunctionDeclaration
    ): CommandResponseValueModel? {
        val element = resolveResponseTypeArguments(responseType, 1, commandName, handler)?.singleOrNull() ?: return null
        if (element.isError || element.declaration is KSTypeParameter) {
            logger.error("Handler '$commandName.$HANDLER_NAME' has an unresolvable response element type.", handler)
            return null
        }
        if (element.nullability == Nullability.NULLABLE) {
            val elementTypeName = renderKotlinType(element) ?: element.toString()
            logger.error(
                ArcDiagnostic.COMMAND_RESPONSE,
                "Handler '$commandName.$HANDLER_NAME' has nullable response element type '$elementTypeName'; " +
                    "nullable response elements are unsupported until command response metadata can preserve element nullability.",
                handler
            )
            return null
        }
        val elementName = element.declaration.qualifiedName?.asString()
        if (elementName == null) {
            logger.error("Handler '$commandName.$HANDLER_NAME' has an unnamed response element type.", handler)
            return null
        }
        val disposition = if (
            isBuiltInHandledCollectionElement(element) || isDeclarativelyHandled(responseType)
        ) {
            CommandResponseValueDisposition.HANDLED
        } else {
            CommandResponseValueDisposition.CLIENT
        }
        if (disposition == CommandResponseValueDisposition.CLIENT) {
            val identity = "$commandName.$HANDLER_NAME response"
            val shape = metadataCollector.describe(responseType, identity, handler) ?: return null
            if (!metadataCollector.collect(shape, identity, handler)) return null
        }
        return CommandResponseValueModel(elementName, true, disposition)
    }

    private fun resolveResponseTypeArguments(
        responseType: KSType,
        expectedCount: Int,
        commandName: String,
        handler: KSFunctionDeclaration
    ): List<KSType>? {
        if (responseType.arguments.size != expectedCount || responseType.arguments.any { argument -> argument.type == null }) {
            logger.error(
                "Handler '$commandName.$HANDLER_NAME' must use concrete response type arguments; " +
                    "wildcards and star projections are unsupported.",
                handler
            )
            return null
        }
        return responseType.arguments.map { argument -> requireNotNull(argument.type).resolve() }
    }

    private fun isHandledResponseLeaf(type: KSType): Boolean {
        val typeName = type.declaration.qualifiedName?.asString()
        return typeName in HANDLED_RESPONSE_LEAF_TYPES || isChronicleEvent(type) || isDeclarativelyHandled(type)
    }

    // Element contracts are safe only for built-ins whose runtime handlers intentionally consume empty containers.
    private fun isBuiltInHandledCollectionElement(type: KSType): Boolean {
        val typeName = type.declaration.qualifiedName?.asString()
        return typeName in HANDLED_COLLECTION_ELEMENT_TYPES
    }

    private fun isDeclarativelyHandled(type: KSType): Boolean {
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        return sequenceOf(declaration)
            .plus(declaration.getAllSuperTypes().mapNotNull { superType -> superType.declaration as? KSClassDeclaration })
            .mapNotNull { candidate -> candidate.qualifiedName?.asString() }
            .any(declarativeHandledResponseTypes::contains)
    }

    private fun discoverDeclarativeHandledResponseTypes(resolver: Resolver): List<KSAnnotated> {
        // KSP exposes annotated source declarations here, but not arbitrary dependency declarations. Classpath scanning
        // would be nondeterministic, so dependency contracts are used only if a future KSP resolver exposes them.
        val symbols = resolver.getSymbolsWithAnnotation(HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION, inDepth = true)
            .toList()
        val deferred = symbols.filterNot(KSAnnotated::validate).toMutableList()
        symbols.filter(KSAnnotated::validate)
            .filterIsInstance<KSClassDeclaration>()
            .sortedBy { declaration -> declaration.qualifiedName?.asString().orEmpty() }
            .forEach { declaration ->
                val qualifiedName = declaration.qualifiedName?.asString()
                if (qualifiedName == null) {
                    logger.error(
                        ArcDiagnostic.COMMAND_HANDLER,
                        "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME handlers must not be local.",
                        declaration
                    )
                    return@forEach
                }
                val handledTypeSymbols = declaration.annotationsNamed(HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION)
                    .flatMap { annotation -> annotation.argumentValues("value").asSequence() }
                    .mapNotNull { value -> value as? KSType }
                    .toList()
                if (handledTypeSymbols.any(KSType::isError)) {
                    deferred.add(declaration)
                    return@forEach
                }
                if (!processedDeclarativeResponseHandlers.add(qualifiedName)) return@forEach
                if (!isSupportedResponseValueHandler(declaration)) {
                    logger.error(
                        ArcDiagnostic.COMMAND_HANDLER,
                        "@$HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME declaration '$qualifiedName' must implement " +
                            "CommandResponseValueHandler, BlockingCommandResponseValueHandler, or " +
                            "AsyncCommandResponseValueHandler.",
                        declaration
                    )
                    return@forEach
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
                    return@forEach
                }
                declarativeHandledResponseTypes.addAll(handledTypes)
            }
        return deferred
    }

    private fun isSupportedResponseValueHandler(declaration: KSClassDeclaration): Boolean =
        declaration.getAllSuperTypes()
            .mapNotNull { superType -> superType.declaration.qualifiedName?.asString() }
            .any(SUPPORTED_RESPONSE_VALUE_HANDLER_TYPES::contains)

    private fun unwrapSingleTypeArgument(
        type: KSType,
        commandName: String,
        handler: KSFunctionDeclaration,
        wrapperName: String
    ): KSType? {
        if (type.arguments.size != 1 || type.arguments.single().type == null) {
            logger.error(
                "Handler '$commandName.$HANDLER_NAME' must return $wrapperName<T> with a concrete T; " +
                    "wildcards and star projections are unsupported.",
                handler
            )
            return null
        }
        val result = type.arguments.single().type!!.resolve()
        if (result.isError || result.declaration is KSTypeParameter) {
            logger.error("Handler '$commandName.$HANDLER_NAME' has an unresolvable $wrapperName result type.", handler)
            return null
        }
        return result
    }

    private fun isChronicleEvent(type: KSType): Boolean = type.declaration.annotations.any { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == CHRONICLE_EVENT_TYPE_ANNOTATION
    }

    private fun isCompletionStage(type: KSType, resolver: Resolver): Boolean {
        val declaration = resolver.getClassDeclarationByName(resolver.getKSNameFromString(COMPLETION_STAGE_TYPE))
            ?: return false
        return declaration.asStarProjectedType().isAssignableFrom(type)
    }

    private fun isParsedJavaRecord(command: KSClassDeclaration): Boolean {
        if (command.origin != Origin.JAVA) return false
        val source = command.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText() ?: return false
        return parseJavaRecordProperties(source, command.simpleName.asString()) != null
    }

    private fun buildProperties(command: KSClassDeclaration, commandName: String): List<PropertyModel>? =
        metadataCollector.describeProperties(command, commandName)

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
                "$artifactKind '$identity' declares @AllowAnonymous on both the class and operation; declare it once.",
                operation
            )
            return null
        }

        val classAuthorize = declaration.annotationsNamed(AUTHORIZE_ANNOTATION).toList()
        val operationAuthorize = operation.annotationsNamed(AUTHORIZE_ANNOTATION).toList()
        val classRoles = declaration.roleAnnotations()
        val operationRoles = operation.roleAnnotations()
        val hasAuthorization = classAuthorize.isNotEmpty() || operationAuthorize.isNotEmpty() ||
            classRoles.isNotEmpty() || operationRoles.isNotEmpty()
        if ((classAllowAnonymous || operationAllowAnonymous) && hasAuthorization) {
            logger.error(
                "$artifactKind '$identity' cannot combine @AllowAnonymous with @Authorize or @Roles metadata.",
                operation
            )
            return null
        }

        val authorize = classAuthorize + operationAuthorize
        val policies = authorize.mapNotNull { annotation -> annotation.stringArgument("policy")?.takeIf(String::isNotBlank) }
            .distinct()
        if (policies.size > 1) {
            logger.error(
                "$artifactKind '$identity' declares conflicting authorization policies across class and operation.",
                operation
            )
            return null
        }
        val roles = (
            authorize.flatMap { annotation -> annotation.stringListArgument("roles") } +
                classRoles + operationRoles
            ).distinct()
        val schemes = authorize.flatMap { annotation -> annotation.stringListArgument("schemes") }.distinct()
        return AuthorizationModel(
            allowAnonymous = classAllowAnonymous || operationAllowAnonymous,
            policy = policies.singleOrNull(),
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
        if (Modifier.PUBLIC !in readModel.modifiers) {
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
            .mapNotNull { function -> buildQueryModel(readModel, function, resolver, containingFile) }
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
            } else if (Modifier.PUBLIC !in function.modifiers) {
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
        if (outerName == KOTLIN_FLOW_TYPE || outerName in JDK_PUBLISHER_TYPES || isCompletionStage(type, resolver)) {
            type = type.arguments.singleOrNull()?.type?.resolve() ?: return false
        }
        val returnName = type.declaration.qualifiedName?.asString() ?: return false
        if (returnName == readModelName) return true
        if (returnName !in COLLECTION_TYPE_NAMES && returnName != ARRAY_TYPE &&
            returnName != QUERY_PAGE_TYPE && returnName != SPRING_PAGE_TYPE) return false
        return type.arguments.singleOrNull()?.type?.resolve()?.declaration?.qualifiedName?.asString() == readModelName
    }

    private fun buildQueryModel(
        readModel: KSClassDeclaration,
        function: KSFunctionDeclaration,
        resolver: Resolver,
        containingFile: com.google.devtools.ksp.symbol.KSFile
    ): QueryModel? {
        val declaringTypeName = requireNotNull(readModel.qualifiedName).asString()
        val methodName = function.simpleName.asString()
        val identity = "$declaringTypeName.$methodName"
        if (Modifier.PUBLIC !in function.modifiers) {
            logger.error("Query '$identity' must be public.", function)
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
            val typeParameterBounds = function.typeParameters.asSequence()
                .flatMap { typeParameter -> typeParameter.bounds }
                .map { bound -> bound.resolve() }
                .toList()
            val infrastructureBound = typeParameterBounds.firstNotNullOfOrNull { bound ->
                val declaration = bound.declaration as? KSClassDeclaration ?: return@firstNotNullOfOrNull null
                val qualifiedName = declaration.qualifiedName?.asString() ?: return@firstNotNullOfOrNull null
                qualifiedName.takeIf {
                    qualifiedName in QUERY_INFRASTRUCTURE_TYPES ||
                        declaration.simpleName.asString() in QUERY_INFRASTRUCTURE_SIMPLE_NAMES ||
                        declaration.getAllSuperTypes().any { supertype ->
                            supertype.declaration.qualifiedName?.asString() in QUERY_INFRASTRUCTURE_TYPES
                        }
                }
            }
            val hostAdapterBound = typeParameterBounds.firstNotNullOfOrNull { bound ->
                val declaration = bound.declaration as? KSClassDeclaration ?: return@firstNotNullOfOrNull null
                val qualifiedName = declaration.qualifiedName?.asString() ?: return@firstNotNullOfOrNull null
                qualifiedName.takeIf {
                    qualifiedName in QUERY_HOST_ADAPTER_TYPES ||
                        declaration.simpleName.asString() in QUERY_HOST_ADAPTER_SIMPLE_NAMES ||
                        declaration.getAllSuperTypes().any { supertype ->
                            supertype.declaration.qualifiedName?.asString() in QUERY_HOST_ADAPTER_TYPES
                        }
                }
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
            return null
        }

        val parameters = buildQueryParameters(identity, function) ?: return null
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
        val returnShape = determineQueryReturnShape(readModel, function, resolver) ?: return null
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
            logger.error("Query '$identity' declares observable transport but does not return Flow<T> or Flow.Publisher<T>.", function)
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
            containingFile = containingFile,
            source = function
        )
    }

    private fun buildQueryParameters(
        queryName: String,
        function: KSFunctionDeclaration
    ): List<QueryParameterModel>? {
        val parameters = mutableListOf<QueryParameterModel>()
        val infrastructureSources = mutableSetOf<QueryParameterSource>()
        val hostAdapterKinds = mutableSetOf<QueryHostAdapterKind>()
        function.parameters.forEach { parameter ->
            val name = parameter.name?.asString()
            if (name == null) {
                logger.error("Query '$queryName' has an unnamed parameter.", parameter)
                return null
            }
            if (parameter.isVararg) {
                logger.error("Query parameter '$queryName.$name' must not be variadic.", parameter)
                return null
            }
            val type = parameter.type.resolve()
            val declaration = type.declaration
            val qualifiedName = declaration.qualifiedName?.asString()
            if (type.isError || declaration is KSTypeParameter || declaration !is KSClassDeclaration || qualifiedName == null) {
                logger.error("Query parameter '$queryName.$name' must have a resolvable class or interface type.", parameter)
                return null
            }
            val hostAdapterKind = QUERY_HOST_ADAPTER_TYPES[qualifiedName]
            val infrastructureSource = QUERY_INFRASTRUCTURE_TYPES[qualifiedName]
                ?: hostAdapterKind?.let { QueryParameterSource.HOST_ADAPTER }
            val supertypes = declaration.getAllSuperTypes()
                .mapNotNull { supertype -> supertype.declaration.qualifiedName?.asString() }
                .toList()
            val infrastructureSupertype = supertypes.firstOrNull(QUERY_INFRASTRUCTURE_TYPES::containsKey)
            val hostAdapterSupertype = supertypes.firstOrNull(QUERY_HOST_ADAPTER_TYPES::containsKey)
            val infrastructureLike = declaration.simpleName.asString() in QUERY_INFRASTRUCTURE_SIMPLE_NAMES ||
                infrastructureSupertype != null
            val hostAdapterLike = declaration.simpleName.asString() in QUERY_HOST_ADAPTER_SIMPLE_NAMES ||
                hostAdapterSupertype != null
            if (infrastructureSource == null && infrastructureLike) {
                logger.error(
                    ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER,
                    "Query parameter '$queryName.$name' uses infrastructure-like type '$qualifiedName'; only exact " +
                        "non-null '$QUERY_REQUEST_TYPE' and '$QUERY_CONTEXT_TYPE' types are supported as query " +
                        "infrastructure parameters.",
                    parameter
                )
                return null
            }
            if (hostAdapterKind == null && hostAdapterLike) {
                logger.error(
                    ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                    "Query parameter '$queryName.$name' uses host-adapter-like type '$qualifiedName'; only exact " +
                        "non-null '$SPRING_PAGEABLE_TYPE' and '$SPRING_SORT_TYPE' host adapter parameters are supported.",
                    parameter
                )
                return null
            }

            val fromServices = parameter.hasAnnotation(FROM_SERVICES_ANNOTATION)
            if (infrastructureSource != null) {
                val diagnostic = if (hostAdapterKind == null) {
                    ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER
                } else {
                    ArcDiagnostic.HOST_ADAPTER_PARAMETER
                }
                val parameterKind = if (hostAdapterKind == null) "Query infrastructure" else "Query host adapter"
                if (type.arguments.isNotEmpty()) {
                    logger.error(
                        diagnostic,
                        "$parameterKind parameter '$queryName.$name' must use the exact non-parameterized type '$qualifiedName'.",
                        parameter
                    )
                    return null
                }
                if (type.nullability == Nullability.NULLABLE || parameter.hasNullableAnnotation()) {
                    logger.error(
                        diagnostic,
                        "$parameterKind parameter '$queryName.$name' must use the exact non-null type '$qualifiedName'.",
                        parameter
                    )
                    return null
                }
                if (fromServices) {
                    logger.error(
                        diagnostic,
                        "$parameterKind parameter '$queryName.$name' must not be annotated @FromServices; its value is " +
                            if (hostAdapterKind == null) {
                                "supplied by the query execution context."
                            } else {
                                "supplied from the query request."
                            },
                        parameter
                    )
                    return null
                }
                if (function.origin == Origin.KOTLIN && parameter.hasDefault) {
                    if (hostAdapterKind == null) {
                        logger.error(
                            ArcDiagnostic.QUERY_DEFAULT,
                            "Kotlin query parameter default '$queryName.$name' is unsupported because QUERY_REQUEST and " +
                                "QUERY_CONTEXT infrastructure parameters must always be supplied by Arc.",
                            parameter
                        )
                    } else {
                        logger.error(
                            ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                            "Kotlin query host adapter parameter '$queryName.$name' must not declare a default value; " +
                                "Arc always creates it from the query request.",
                            parameter
                        )
                    }
                    return null
                }
                if (hostAdapterKind != null) {
                    if (!hostAdapterKinds.add(hostAdapterKind)) {
                        logger.error(
                            ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                            "Query '$queryName' declares more than one ${hostAdapterKind.name} host adapter parameter; " +
                                "each host adapter kind may appear at most once.",
                            parameter
                        )
                        return null
                    }
                } else if (!infrastructureSources.add(infrastructureSource)) {
                    logger.error(
                        ArcDiagnostic.QUERY_INFRASTRUCTURE_PARAMETER,
                        "Query '$queryName' declares more than one ${infrastructureSource.name} infrastructure " +
                            "parameter; each infrastructure source may appear at most once.",
                        parameter
                    )
                    return null
                }
            }

            if (infrastructureSource == null && hostAdapterKind == null && !fromServices &&
                name.lowercase() in RESERVED_QUERY_PARAMETER_NAMES
            ) {
                logger.error(
                    ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                    "Query client parameter '$queryName.$name' conflicts with reserved paging or sorting control " +
                        "'$name'; use another parameter name.",
                    parameter
                )
                return null
            }

            if (containsMapType(type)) {
                logger.error(
                    ArcDiagnostic.PROXY_SHAPE,
                    "Query '$queryName' parameter '$name' value path 'parameter' uses a map; query, observable, and service parameters cannot use maps.",
                    parameter
                )
                return null
            }
            if (infrastructureSource == null && !fromServices &&
                (declaration.classKind == ClassKind.INTERFACE || Modifier.ABSTRACT in declaration.modifiers)
            ) {
                logger.error(
                    ArcDiagnostic.QUERY_PARAMETER,
                    "Query parameter '$queryName.$name' has service-like interface or abstract type '$qualifiedName'; " +
                        "annotate it with @FromServices or use a concrete client-serializable parameter type.",
                    parameter
                )
                return null
            }
            if (fromServices && type.nullability == Nullability.NULLABLE) {
                logger.error(
                    ArcDiagnostic.QUERY_PARAMETER,
                    "Query service parameter '$queryName.$name' must be non-null because generated service resolution is required.",
                    parameter
                )
                return null
            }
            if (function.origin == Origin.KOTLIN && parameter.hasDefault && fromServices) {
                logger.error(
                    ArcDiagnostic.QUERY_DEFAULT,
                    "Kotlin query parameter default '$queryName.$name' is unsupported because service parameters must " +
                        "always be supplied by Arc.",
                    parameter
                )
                return null
            }
            if (fromServices && type.arguments.isNotEmpty()) {
                logger.error("Query service parameter '$queryName.$name' must not use a parameterized type.", parameter)
                return null
            }
            if (fromServices && qualifiedName == "kotlin.Array") {
                logger.error("Query service parameter '$queryName.$name' must not use an array type.", parameter)
                return null
            }
            val source = infrastructureSource ?: if (fromServices) QueryParameterSource.SERVICE else QueryParameterSource.CLIENT
            val hasDefault = function.origin == Origin.KOTLIN &&
                source == QueryParameterSource.CLIENT && parameter.hasDefault
            val shape = if (source != QueryParameterSource.CLIENT) {
                TypeShape(
                    qualifiedName,
                    TypeShapeDescriptor.value(qualifiedName, type.nullability == Nullability.NULLABLE),
                    type
                )
            } else {
                metadataCollector.describe(type, "$queryName.$name", parameter) ?: return null
            }
            val renderedName = renderKotlinType(type)
            if (renderedName == null) {
                logger.error("Query parameter '$queryName.$name' has an unsupported type.", parameter)
                return null
            }
            if (source == QueryParameterSource.CLIENT &&
                !metadataCollector.collect(shape, "$queryName.$name", parameter)
            ) return null
            val validation = metadataCollector.extractValidation(
                listOf(parameter),
                shape,
                "$queryName.$name",
                parameter
            ) ?: return null
            if (function.origin == Origin.JAVA && source == QueryParameterSource.CLIENT &&
                validation.rules.isNotEmpty()
            ) {
                logger.error(
                    ArcDiagnostic.VALIDATION,
                    "Jakarta constraints declared directly on static Java query parameter '$queryName.$name' are " +
                        "unsupported because Jakarta executable validation requires an invocation receiver; use a " +
                        "QueryValidator or a validated argument model instead.",
                    parameter
                )
                return null
            }
            if (source == QueryParameterSource.SERVICE &&
                (validation.rules.isNotEmpty() || validation.validateRecursively)
            ) {
                logger.error(
                    ArcDiagnostic.VALIDATION,
                    "Validation annotations on service parameter '$queryName.$name' cannot be represented in a client proxy.",
                    parameter
                )
                return null
            }
            parameters.add(
                QueryParameterModel(
                    name = name,
                    typeName = shape.typeName,
                    shape = shape.descriptor,
                    renderedTypeName = renderedName,
                    erasedTypeName = renderRuntimeType(qualifiedName),
                    source = source,
                    hostAdapterKind = hostAdapterKind,
                    hasDefault = hasDefault,
                    isNullable = shape.isNullable,
                    isEnumerable = shape.isEnumerable,
                    elementTypeName = shape.elementTypeName,
                    validationRules = validation.rules,
                    validateRecursively = validation.validateRecursively
                )
            )
        }
        return parameters
    }

    private fun determineQueryReturnShape(
        readModel: KSClassDeclaration,
        function: KSFunctionDeclaration,
        resolver: Resolver
    ): QueryReturnShape? {
        val identity = "${requireNotNull(readModel.qualifiedName).asString()}.${function.simpleName.asString()}"
        var returnType = function.returnType?.resolve()
        if (returnType == null || returnType.isError) {
            logger.error("Query '$identity' has no resolvable return type.", function)
            return null
        }
        if (containsMapType(returnType)) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Query '$identity' return value path 'return' uses a map; query and observable return maps are unsupported.",
                function
            )
            return null
        }
        var invocationKind = QueryInvocationKind.DIRECT
        var isObservable = false
        val outerReturnName = returnType.declaration.qualifiedName?.asString()
        if (outerReturnName == KOTLIN_FLOW_TYPE || outerReturnName in JDK_PUBLISHER_TYPES) {
            if (returnType.arguments.size != 1 || returnType.arguments.single().type == null) {
                logger.error("Query '$identity' must return an observable type with a concrete model shape.", function)
                return null
            }
            returnType = returnType.arguments.single().type!!.resolve()
            if (returnType.isError || returnType.declaration is KSTypeParameter) {
                logger.error("Query '$identity' has an unresolvable observable model shape.", function)
                return null
            }
            if (returnType.nullability == Nullability.NULLABLE) {
                logger.error("Query '$identity' must not return an observable nullable model shape.", function)
                return null
            }
            invocationKind = if (outerReturnName == KOTLIN_FLOW_TYPE) QueryInvocationKind.FLOW else QueryInvocationKind.JDK_PUBLISHER
            isObservable = true
        } else if (isCompletionStage(returnType, resolver)) {
            if (function.origin != Origin.JAVA) {
                logger.error("Query '$identity' may use CompletionStage only when declared in Java.", function)
                return null
            }
            if (returnType.arguments.size != 1 || returnType.arguments.single().type == null) {
                logger.error("Java query '$identity' must return CompletionStage<T> with a concrete T.", function)
                return null
            }
            returnType = returnType.arguments.single().type!!.resolve()
            if (returnType.nullability == Nullability.NULLABLE) {
                logger.error("Java query '$identity' must not return CompletionStage<T?>.", function)
                return null
            }
            invocationKind = QueryInvocationKind.COMPLETION_STAGE
        }

        val returnName = returnType.declaration.qualifiedName?.asString()
        if (returnName == null || returnType.declaration is KSTypeParameter || returnName in VOID_TYPE_NAMES) {
            logger.error("Query '$identity' must return its read model, a supported collection, array, QueryPage, or exact Spring Data Page.", function)
            return null
        }
        if (returnName in UNSUPPORTED_STREAM_TYPES || returnName.endsWith(".Publisher")) {
            logger.error("Query '$identity' returns an unsupported observable publisher type.", function)
            return null
        }

        val returnDeclaration = returnType.declaration as? KSClassDeclaration
        val springPageSupertype = returnDeclaration?.getAllSuperTypes()
            ?.mapNotNull { supertype -> supertype.declaration.qualifiedName?.asString() }
            ?.firstOrNull { supertype -> supertype == SPRING_PAGE_TYPE }
        val isSpringDataPage = returnName == SPRING_PAGE_TYPE
        if (!isSpringDataPage && springPageSupertype != null) {
            logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '$identity' returns Spring Data Page subtype '$returnName'; only exact non-null " +
                    "'$SPRING_PAGE_TYPE<T>' returns are supported.",
                function
            )
            return null
        }
        if (isSpringDataPage && isObservable) {
            logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Observable query '$identity' must not return '$SPRING_PAGE_TYPE<T>'; Spring Data pages are supported " +
                    "only for direct, suspending, and Java CompletionStage queries.",
                function
            )
            return null
        }
        if (isSpringDataPage &&
            (returnType.nullability == Nullability.NULLABLE || function.hasNullableAnnotation())
        ) {
            logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '$identity' must return the exact non-null type '$SPRING_PAGE_TYPE<T>'.",
                function
            )
            return null
        }

        val readModelName = requireNotNull(readModel.qualifiedName).asString()
        if (returnName == readModelName) return QueryReturnShape(invocationKind, false, false, isObservable, false)
        val isArray = returnName == "kotlin.Array"
        val isEnumerable = returnName in COLLECTION_TYPE_NAMES || isArray
        val isArcPage = returnName == QUERY_PAGE_TYPE
        val isPage = isArcPage || isSpringDataPage
        if (!isEnumerable && !isPage) {
            val detail = if (returnType.arguments.isNotEmpty() || returnName in MAP_TYPE_NAMES) {
                "unsupported generic or map return shape"
            } else {
                "return model '${returnName}' does not match annotated read model '$readModelName'"
            }
            logger.error("Query '$identity' has $detail.", function)
            return null
        }
        if (returnType.arguments.size != 1 || returnType.arguments.single().type == null) {
            val diagnostic = if (isSpringDataPage) ArcDiagnostic.HOST_ADAPTER_PARAMETER else ArcDiagnostic.QUERY_RETURN
            logger.error(
                diagnostic,
                "Query '$identity' must return a supported shape with the concrete read model element type.",
                function
            )
            return null
        }
        if (isSpringDataPage && returnType.arguments.single().variance != Variance.INVARIANT) {
            logger.error(
                ArcDiagnostic.HOST_ADAPTER_PARAMETER,
                "Query '$identity' must return exact invariant '$SPRING_PAGE_TYPE<T>'; wildcard, projected, and star " +
                    "page element types are unsupported.",
                function
            )
            return null
        }
        val elementType = returnType.arguments.single().type!!.resolve()
        val elementName = elementType.declaration.qualifiedName?.asString()
        if (elementType.isError || elementType.declaration is KSTypeParameter || elementName == null) {
            val diagnostic = if (isSpringDataPage) ArcDiagnostic.HOST_ADAPTER_PARAMETER else ArcDiagnostic.QUERY_RETURN
            logger.error(diagnostic, "Query '$identity' has an unresolvable return model element type.", function)
            return null
        }
        if (elementName != readModelName) {
            val nested = elementType.arguments.isNotEmpty() || elementType.declaration.parentDeclaration != null
            val detail = if (nested) "nested return models are not supported" else
                "return model '$elementName' does not match annotated read model '$readModelName'"
            val diagnostic = if (isSpringDataPage) ArcDiagnostic.HOST_ADAPTER_PARAMETER else ArcDiagnostic.QUERY_RETURN
            logger.error(diagnostic, "Query '$identity': $detail.", function)
            return null
        }
        return QueryReturnShape(invocationKind, true, isPage, isObservable, isSpringDataPage)
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

    private fun renderKotlinType(type: KSType): String? {
        val qualifiedName = type.declaration.qualifiedName?.asString() ?: return null
        val base = renderRuntimeType(qualifiedName)
        if (type.arguments.isEmpty()) return base + if (type.nullability == Nullability.NULLABLE) "?" else ""
        val arguments = type.arguments.map { argument ->
            val reference = argument.type ?: return null
            renderKotlinType(reference.resolve()) ?: return null
        }
        return "$base<${arguments.joinToString(", ")}>" + if (type.nullability == Nullability.NULLABLE) "?" else ""
    }

    private fun renderRuntimeType(qualifiedName: String): String = when (qualifiedName) {
        "java.lang.Boolean", "boolean" -> "kotlin.Boolean"
        "java.lang.Byte", "byte" -> "kotlin.Byte"
        "java.lang.Character", "char" -> "kotlin.Char"
        "java.lang.Double", "double" -> "kotlin.Double"
        "java.lang.Float", "float" -> "kotlin.Float"
        "java.lang.Integer", "int" -> "kotlin.Int"
        "java.lang.Long", "long" -> "kotlin.Long"
        "java.lang.Short", "short" -> "kotlin.Short"
        "java.lang.String" -> "kotlin.String"
        else -> renderQualifiedName(qualifiedName)
    }

    private fun KSAnnotated.enumArgument(annotationName: String, argumentName: String): String? =
        annotationsNamed(annotationName).firstOrNull()
            ?.arguments
            ?.firstOrNull { argument -> argument.name?.asString() == argumentName }
            ?.value
            ?.toString()
            ?.substringAfterLast('.')

    private data class QueryReturnShape(
        val invocationKind: QueryInvocationKind,
        val isEnumerable: Boolean,
        val supportsPaging: Boolean,
        val isObservable: Boolean,
        val adaptsSpringDataPage: Boolean
    )

    private fun generateHandler(command: CommandModel) {
        val dependencies = Dependencies(aggregating = false, command.containingFile)
        codeGenerator.createNewFile(dependencies, GENERATED_COMMANDS_PACKAGE, command.handlerClassName).bufferedWriter().use {
            writer -> writer.write(renderHandler(command))
        }
    }

    private fun renderHandler(command: CommandModel): String {
        val commandType = renderQualifiedName(command.qualifiedName)
        fun invocation(methodName: String, parameters: List<HandlerParameterModel>): String {
            val arguments = parameters.joinToString(",\n") { parameter ->
                val resolverMethod = when (parameter.resolution) {
                    CommandParameterResolution.REQUIRED -> "resolve"
                    CommandParameterResolution.NULLABLE -> "resolveNullable"
                    CommandParameterResolution.OPTIONAL -> "resolveOptional"
                }
                "            resolver.$resolverMethod(${parameter.sourceTypeName}::class.java, ${quote(methodName)}, " +
                    "${quote(parameter.name)})"
            }
            return if (arguments.isEmpty()) "command.$methodName()"
            else "command.$methodName(\n$arguments\n        )"
        }
        val handlerInvocation = invocation(HANDLER_NAME, command.parameters)
        val invocationBody = buildString {
            if (command.parameters.isNotEmpty()) {
                append("val resolver = io.cratis.arc.commands.CommandHandlerArgumentResolver(context)\n        ")
            }
            append(
                when (command.invocationKind) {
                    InvocationKind.VALUE -> "return $handlerInvocation"
                    InvocationKind.UNIT -> "$handlerInvocation\n        return null"
                    InvocationKind.COMPLETION_STAGE -> "return $handlerInvocation.await()"
                    InvocationKind.COMPLETION_STAGE_VOID -> "$handlerInvocation.await()\n        return null"
                }
            )
        }
        val preparation = command.provide?.let { provide ->
            val provideInvocation = invocation(PROVIDE_NAME, provide.parameters)
            val body = when (provide.invocationKind) {
                InvocationKind.VALUE -> "val provided: kotlin.Any? = $provideInvocation"
                InvocationKind.UNIT -> "$provideInvocation\n        val provided: kotlin.Any? = null"
                InvocationKind.COMPLETION_STAGE -> "val provided: kotlin.Any? = $provideInvocation.await()"
                InvocationKind.COMPLETION_STAGE_VOID -> "$provideInvocation.await()\n        val provided: kotlin.Any? = null"
            }
            val resolver = if (provide.parameters.isEmpty()) "" else
                "        val resolver = io.cratis.arc.commands.CommandHandlerArgumentResolver(context)\n"
            """
    override suspend fun prepare(context: io.cratis.arc.commands.CommandContext): io.cratis.arc.commands.CommandPreparation {
        val command = context.command as $commandType
$resolver        $body
        return io.cratis.arc.commands.CommandPreparation.from(provided, context)
    }
"""
        }.orEmpty()
        val keyResolution = command.commandKeyPropertyName?.let { propertyName ->
            val accessor = renderQualifiedName(propertyName) + if (command.commandKeyUsesFunction) "()" else ""
            """
    override fun resolveCommandKey(command: kotlin.Any): kotlin.Any? {
        val typedCommand = command as $commandType
        return typedCommand.$accessor
    }
"""
        }.orEmpty()
        val properties = if (command.properties.isEmpty()) {
            "emptyList()"
        } else {
            command.properties.joinToString(",\n", "listOf(\n", "\n        )") { property ->
                "            io.cratis.arc.metadata.PropertyDescriptor(" +
                    "name = ${quote(property.name)}, shape = ${renderTypeShape(property.shape)}, " +
                    "isCommandKey = ${property.isCommandKey}, " +
                    "validationRules = ${renderValidationRules(property.validationRules)}, " +
                    "validateRecursively = ${property.validateRecursively})"
            }
        }
        val location = command.qualifiedName.substringBeforeLast('.', "")
            .split('.')
            .filter(String::isNotBlank)
            .joinToString(", ") { quote(it) }
        val roles = command.authorization.roles.joinToString(", ") { quote(it) }
        val schemes = command.authorization.schemes.joinToString(", ") { quote(it) }
        val policy = command.authorization.policy?.let(::quote) ?: "null"
        val responseTypeName = command.responseTypeName?.let(::quote) ?: "null"
        val responseValues = if (command.responseValues.isEmpty()) {
            "emptyList()"
        } else {
            command.responseValues.joinToString(",\n", "listOf(\n", "\n        )") { value ->
                "            io.cratis.arc.metadata.CommandResponseValueDescriptor(" +
                    "typeName = ${quote(value.typeName)}, isEnumerable = ${value.isEnumerable}, " +
                    "disposition = io.cratis.arc.metadata.CommandResponseValueDisposition.${value.disposition.name})"
            }
        }

        return """// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package $GENERATED_COMMANDS_PACKAGE

import io.cratis.arc.commands.await

/** Generated reflection-free command handler for [${command.qualifiedName}]. */
public class ${command.handlerClassName} : io.cratis.arc.commands.CommandHandler {
    override val commandType: java.lang.Class<*> = $commandType::class.java

    override val metadata: io.cratis.arc.metadata.CommandDescriptor = io.cratis.arc.metadata.CommandDescriptor(
        name = ${quote(command.simpleName)},
        typeName = ${quote(command.qualifiedName)},
        properties = $properties,
        location = listOf($location),
        authorization = io.cratis.arc.metadata.AuthorizationMetadata(
            allowAnonymous = ${command.authorization.allowAnonymous},
            policy = $policy,
            roles = listOf($roles),
            schemes = listOf($schemes)
        ),
        treatWarningsAsErrors = ${command.treatWarningsAsErrors},
        responseTypeName = $responseTypeName,
        responseIsEnumerable = ${command.responseIsEnumerable},
        responseValues = $responseValues
    )
$keyResolution$preparation
    override suspend fun invoke(context: io.cratis.arc.commands.CommandContext): kotlin.Any? {
        val command = context.command as $commandType
        $invocationBody
    }
}
"""
    }

    private fun generatePerformer(query: QueryModel) {
        val dependencies = Dependencies(aggregating = false, query.containingFile)
        codeGenerator.createNewFile(dependencies, GENERATED_QUERIES_PACKAGE, query.performerClassName).bufferedWriter().use {
            writer -> writer.write(renderPerformer(query))
        }
    }

    private fun renderPerformer(query: QueryModel): String {
        val declaringType = renderQualifiedName(query.declaringTypeName)
        val defaultParameterIndexes = query.parameters.mapIndexedNotNull { index, parameter ->
            index.takeIf { parameter.hasDefault }
        }
        val defaultBits = defaultParameterIndexes.withIndex().associate { (bit, parameterIndex) ->
            parameterIndex to (1 shl bit)
        }
        val argumentBindings = query.parameters.mapIndexedNotNull { index, parameter ->
            if (parameter.source != QueryParameterSource.CLIENT) return@mapIndexedNotNull null
            val requiresGenericCast = parameter.renderedTypeName.removeSuffix("?") != parameter.erasedTypeName
            val checkedValue = """(_argument${index}Value as? ${parameter.erasedTypeName}
                ?: io.cratis.arc.queries.QueryArgumentResolver.wrongType(
                    ${quote(parameter.name)},
                    ${quote(parameter.typeName)}
                ))"""
            val castValue = if (requiresGenericCast) "$checkedValue as ${parameter.renderedTypeName}" else checkedValue
            val resolution = if (parameter.isNullable) {
                """val _argument${index}Value = io.cratis.arc.queries.QueryArgumentResolver.nullable(
            context.request.arguments,
            ${quote(parameter.name)}
        )
        if (_argument${index}Value == null) {
            null
        } else {
            $castValue
        }"""
            } else {
                """val _argument${index}Value = io.cratis.arc.queries.QueryArgumentResolver.required(
            context.request.arguments,
            ${quote(parameter.name)}
        )
        $castValue"""
            }
            if (parameter.hasDefault) {
                """        fun _resolveArgument$index(): ${parameter.renderedTypeName} = run {
        $resolution
        }"""
            } else {
                """        val _argument$index: ${parameter.renderedTypeName} = run {
        $resolution
        }"""
            }
        }.joinToString("\n")
        val hostAdapterBindings = query.parameters.mapIndexedNotNull { index, parameter ->
            when (parameter.hostAdapterKind) {
                QueryHostAdapterKind.PAGEABLE -> """        val _hostAdapter$index: org.springframework.data.domain.Pageable = run {
            val _sorting$index = if (context.request.sorting.field.isBlank()) {
                org.springframework.data.domain.Sort.unsorted()
            } else {
                org.springframework.data.domain.Sort.by(
                    if (context.request.sorting.direction == io.cratis.arc.queries.QuerySortDirection.DESCENDING) {
                        org.springframework.data.domain.Sort.Direction.DESC
                    } else {
                        org.springframework.data.domain.Sort.Direction.ASC
                    },
                    context.request.sorting.field
                )
            }
            if (context.request.paging.pageSize == 0) {
                org.springframework.data.domain.Pageable.unpaged(_sorting$index)
            } else {
                org.springframework.data.domain.PageRequest.of(
                    context.request.paging.page,
                    context.request.paging.pageSize,
                    _sorting$index
                )
            }
        }"""
                QueryHostAdapterKind.SORT -> """        val _hostAdapter$index: org.springframework.data.domain.Sort =
            if (context.request.sorting.field.isBlank()) {
                org.springframework.data.domain.Sort.unsorted()
            } else {
                org.springframework.data.domain.Sort.by(
                    if (context.request.sorting.direction == io.cratis.arc.queries.QuerySortDirection.DESCENDING) {
                        org.springframework.data.domain.Sort.Direction.DESC
                    } else {
                        org.springframework.data.domain.Sort.Direction.ASC
                    },
                    context.request.sorting.field
                )
            }"""
                null -> null
            }
        }.joinToString("\n")
        val performerBindings = listOf(argumentBindings, hostAdapterBindings)
            .filter(String::isNotBlank)
            .joinToString("\n")

        fun argumentExpression(index: Int, parameter: QueryParameterModel): String = when (parameter.source) {
            QueryParameterSource.CLIENT -> if (parameter.hasDefault) "_resolveArgument$index()" else "_argument$index"
            QueryParameterSource.SERVICE ->
                "context.serviceResolver.require(${parameter.erasedTypeName}::class.java)"
            QueryParameterSource.QUERY_REQUEST -> "context.request"
            QueryParameterSource.QUERY_CONTEXT -> "context"
            QueryParameterSource.HOST_ADAPTER -> {
                requireNotNull(parameter.hostAdapterKind)
                "_hostAdapter$index"
            }
        }

        fun adaptInvocation(invocation: String): String {
            val adaptedInvocation = when (query.invocationKind) {
                QueryInvocationKind.DIRECT, QueryInvocationKind.FLOW -> invocation
                QueryInvocationKind.COMPLETION_STAGE -> "$invocation.await()"
                QueryInvocationKind.JDK_PUBLISHER -> "$invocation.asKotlinFlow()"
            }
            return if (query.adaptsSpringDataPage) {
                """($adaptedInvocation).let { _page ->
            io.cratis.arc.queries.QueryPage(
                _page.content,
                if (_page.pageable.isPaged) _page.number else 0,
                if (_page.pageable.isPaged) _page.size else 0,
                _page.totalElements
            )
        }"""
            } else {
                adaptedInvocation
            }
        }

        val methodName = renderQualifiedName(query.methodName)
        fun positionalInvocation(): String {
            val arguments = query.parameters.mapIndexed { index, parameter ->
                "            ${argumentExpression(index, parameter)}"
            }.joinToString(",\n")
            return if (arguments.isEmpty()) {
                "$declaringType.$methodName()"
            } else {
                "$declaringType.$methodName(\n$arguments\n        )"
            }
        }

        fun namedInvocation(mask: Int): String {
            val arguments = query.parameters.mapIndexedNotNull { index, parameter ->
                val defaultBit = defaultBits[index]
                if (defaultBit != null && mask and defaultBit == 0) return@mapIndexedNotNull null
                "                ${renderQualifiedName(parameter.name)} = ${argumentExpression(index, parameter)}"
            }.joinToString(",\n")
            val invocation = if (arguments.isEmpty()) {
                "$declaringType.$methodName()"
            } else {
                "$declaringType.$methodName(\n$arguments\n            )"
            }
            return adaptInvocation(invocation)
        }

        val invocationBody = if (defaultParameterIndexes.isEmpty()) {
            "return ${adaptInvocation(positionalInvocation())}"
        } else {
            val maskExpression = defaultParameterIndexes.mapIndexed { bit, parameterIndex ->
                val parameter = query.parameters[parameterIndex]
                "            (if (context.request.arguments.containsKey(${quote(parameter.name)})) ${1 shl bit} else 0)"
            }.joinToString(" or\n")
            val branches = (0 until (1 shl defaultParameterIndexes.size)).joinToString("\n") { mask ->
                "            $mask -> ${namedInvocation(mask)}"
            }
            """val _defaultArgumentMask =
$maskExpression
        return when (_defaultArgumentMask) {
$branches
            else -> error("Unreachable default query argument mask")
        }"""
        }
        val parameters = if (query.parameters.isEmpty()) {
            "emptyList()"
        } else {
            query.parameters.joinToString(",\n", "listOf(\n", "\n        )") { parameter ->
                "            io.cratis.arc.metadata.ParameterDescriptor(" +
                    "name = ${quote(parameter.name)}, shape = ${renderTypeShape(parameter.shape)}, " +
                    "source = io.cratis.arc.metadata.QueryParameterSource.${parameter.source.name}, " +
                    "hasDefault = ${parameter.hasDefault}, " +
                    "validationRules = ${renderValidationRules(parameter.validationRules)}, " +
                    "validateRecursively = ${parameter.validateRecursively})"
            }
        }
        val location = query.declaringTypeName.substringBeforeLast('.', "")
            .split('.')
            .filter(String::isNotBlank)
            .joinToString(", ") { quote(it) }
        val roles = query.authorization.roles.joinToString(", ") { quote(it) }
        val schemes = query.authorization.schemes.joinToString(", ") { quote(it) }
        val policy = query.authorization.policy?.let(::quote) ?: "null"
        val explicitPath = query.explicitPath?.let(::quote) ?: "null"

        return """// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package $GENERATED_QUERIES_PACKAGE

import io.cratis.arc.commands.await
import io.cratis.arc.commands.require
import io.cratis.arc.queries.asKotlinFlow

/** Generated reflection-free query performer for [${query.fullyQualifiedName}]. */
public class ${query.performerClassName} : io.cratis.arc.queries.QueryPerformer {
    override val fullyQualifiedName: io.cratis.arc.queries.FullyQualifiedQueryName =
        io.cratis.arc.queries.FullyQualifiedQueryName(${quote(query.fullyQualifiedName)})

    override val descriptor: io.cratis.arc.metadata.QueryDescriptor = io.cratis.arc.metadata.QueryDescriptor(
        name = ${quote(query.methodName)},
        declaringTypeName = ${quote(query.declaringTypeName)},
        returnTypeName = ${quote(query.returnTypeName)},
        parameters = $parameters,
        routeOptions = io.cratis.arc.metadata.RouteOptions(
            path = $explicitPath,
            transport = io.cratis.arc.queries.QueryTransportType.${query.transport}
        ),
        fullyQualifiedName = ${quote(query.fullyQualifiedName)},
        location = listOf($location),
        authorization = io.cratis.arc.metadata.AuthorizationMetadata(
            allowAnonymous = ${query.authorization.allowAnonymous},
            policy = $policy,
            roles = listOf($roles),
            schemes = listOf($schemes)
        ),
        explicitPath = $explicitPath,
        queryHttpMethod = io.cratis.arc.queries.QueryHttpMethodType.${query.queryHttpMethod},
        transport = io.cratis.arc.queries.QueryTransportType.${query.transport},
        isEnumerable = ${query.isEnumerable},
        supportsPaging = ${query.supportsPaging},
        supportsSorting = ${query.supportsSorting},
        treatWarningsAsErrors = ${query.treatWarningsAsErrors}
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun perform(context: io.cratis.arc.queries.QueryContext): kotlin.Any? {
$performerBindings
        $invocationBody
    }
}
"""
    }

    private fun generateModule(moduleName: String) {
        val sortedCommands = commands.sortedBy(CommandModel::qualifiedName)
        val sortedQueries = queries.sortedBy(QueryModel::fullyQualifiedName)
        val sortedTypes = metadataCollector.types
        val sortedInterfaces = metadataCollector.interfaces
        val sortedEnums = metadataCollector.enums
        val sortedConcepts = metadataCollector.concepts
        val dependencies = Dependencies.ALL_FILES
        val className = moduleClassName(moduleName)
        val handlers = renderModuleArtifacts(
            sortedCommands.map { command -> "$GENERATED_COMMANDS_PACKAGE.${command.handlerClassName}()" }
        )
        val performers = renderModuleArtifacts(
            sortedQueries.map { query -> "$GENERATED_QUERIES_PACKAGE.${query.performerClassName}()" }
        )
        val types = renderModuleArtifacts(sortedTypes.map(::renderTypeDescriptor))
        val interfaces = renderModuleArtifacts(sortedInterfaces.map(::renderInterfaceDescriptor))
        val enums = renderModuleArtifacts(sortedEnums.map(::renderEnumDescriptor))
        val concepts = renderModuleArtifacts(sortedConcepts.map(::renderConceptDescriptor))
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
    concepts = $concepts
)
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
        val manifest = buildManifest(
            moduleName,
            sortedCommands,
            sortedQueries,
            sortedTypes,
            sortedInterfaces,
            sortedEnums,
            sortedConcepts
        )
        val manifestJson = ArcObjectMapper.create().writeValueAsString(manifest) + "\n"
        codeGenerator.createNewFileByPath(
            dependencies,
            "META-INF/cratis/arc/$moduleName.json",
            ""
        ).bufferedWriter().use { writer -> writer.write(manifestJson) }
    }

    private fun renderTypeDescriptor(type: TypeModel): String {
        val properties = renderProperties(type.properties)
        val location = type.location.joinToString(", ") { segment -> quote(segment) }
        val baseTypeName = type.baseTypeName?.let(::quote) ?: "null"
        val derivedTypeId = type.derivedTypeId?.let(::quote) ?: "null"
        return "io.cratis.arc.metadata.TypeDescriptor(" +
            "name = ${quote(type.name)}, fullyQualifiedName = ${quote(type.fullyQualifiedName)}, " +
            "location = listOf($location), properties = $properties, baseTypeName = $baseTypeName, " +
            "derivedTypeId = $derivedTypeId)"
    }

    private fun renderInterfaceDescriptor(interfaceModel: InterfaceModel): String {
        val properties = renderProperties(interfaceModel.properties)
        val location = interfaceModel.location.joinToString(", ") { segment -> quote(segment) }
        return "io.cratis.arc.metadata.InterfaceDescriptor(" +
            "name = ${quote(interfaceModel.name)}, fullyQualifiedName = ${quote(interfaceModel.fullyQualifiedName)}, " +
            "location = listOf($location), properties = $properties)"
    }

    private fun renderConceptDescriptor(concept: ConceptModel): String =
        "io.cratis.arc.metadata.ConceptDescriptor(${quote(concept.name)}, ${quote(concept.fullyQualifiedName)}, " +
            "${quote(concept.underlyingTypeName)})"

    private fun renderEnumDescriptor(enum: EnumModel): String {
        val location = enum.location.joinToString(", ") { segment -> quote(segment) }
        val members = if (enum.members.isEmpty()) {
            "emptyList()"
        } else {
            enum.members.joinToString(", ", "listOf(", ")") { member ->
                "io.cratis.arc.metadata.EnumMemberDescriptor(${quote(member.name)}, ${member.value})"
            }
        }
        return "io.cratis.arc.metadata.EnumDescriptor(" +
            "name = ${quote(enum.name)}, fullyQualifiedName = ${quote(enum.fullyQualifiedName)}, " +
            "location = listOf($location), members = $members, isFlags = ${enum.isFlags})"
    }

    private fun renderProperties(properties: List<PropertyModel>): String = if (properties.isEmpty()) {
        "emptyList()"
    } else {
        properties.joinToString(", ", "listOf(", ")") { property ->
            val derivatives = property.derivatives.joinToString(", ") { derivative -> quote(derivative) }
            "io.cratis.arc.metadata.PropertyDescriptor(" +
                "name = ${quote(property.name)}, shape = ${renderTypeShape(property.shape)}, " +
                "isCommandKey = ${property.isCommandKey}, " +
                "validationRules = ${renderValidationRules(property.validationRules)}, " +
                "validateRecursively = ${property.validateRecursively}, derivatives = listOf($derivatives))"
        }
    }

    private fun buildManifest(
        moduleName: String,
        commands: List<CommandModel>,
        queries: List<QueryModel>,
        types: List<TypeModel>,
        interfaces: List<InterfaceModel>,
        enums: List<EnumModel>,
        concepts: List<ConceptModel>
    ): ArcArtifactManifest = ArcArtifactManifest(
        moduleName = moduleName,
        commands = commands.map(::toCommandDescriptor),
        queries = queries.map(::toQueryDescriptor),
        types = types.map { type ->
            TypeDescriptor(
                type.name,
                type.fullyQualifiedName,
                type.location,
                type.properties.map(::toPropertyDescriptor),
                type.baseTypeName,
                type.derivedTypeId
            )
        },
        enums = enums.map { enum ->
            EnumDescriptor(
                enum.name,
                enum.fullyQualifiedName,
                enum.location,
                enum.members.map { member -> EnumMemberDescriptor(member.name, member.value) },
                enum.isFlags
            )
        },
        interfaces = interfaces.map { interfaceModel ->
            InterfaceDescriptor(
                interfaceModel.name,
                interfaceModel.fullyQualifiedName,
                interfaceModel.location,
                interfaceModel.properties.map(::toPropertyDescriptor)
            )
        },
        concepts = concepts.map { concept ->
            ConceptDescriptor(concept.name, concept.fullyQualifiedName, concept.underlyingTypeName)
        }
    )

    private fun toCommandDescriptor(command: CommandModel): CommandDescriptor = CommandDescriptor(
        name = command.simpleName,
        typeName = command.qualifiedName,
        properties = command.properties.map(::toPropertyDescriptor),
        location = command.qualifiedName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        authorization = command.authorization.toDescriptor(),
        treatWarningsAsErrors = command.treatWarningsAsErrors,
        responseTypeName = command.responseTypeName,
        responseIsEnumerable = command.responseIsEnumerable,
        responseValues = command.responseValues.map { value ->
            CommandResponseValueDescriptor(value.typeName, value.isEnumerable, value.disposition)
        }
    )

    private fun toQueryDescriptor(query: QueryModel): QueryDescriptor = QueryDescriptor(
        name = query.methodName,
        declaringTypeName = query.declaringTypeName,
        returnTypeName = query.returnTypeName,
        parameters = query.parameters.map { parameter ->
            ParameterDescriptor(
                parameter.name,
                parameter.shape,
                parameter.source,
                parameter.hasDefault,
                parameter.validationRules.map(::toValidationRuleDescriptor),
                parameter.validateRecursively
            )
        },
        routeOptions = RouteOptions(query.explicitPath, QueryTransportType.valueOf(query.transport)),
        fullyQualifiedName = query.fullyQualifiedName,
        location = query.declaringTypeName.substringBeforeLast('.', "").split('.').filter(String::isNotBlank),
        authorization = query.authorization.toDescriptor(),
        explicitPath = query.explicitPath,
        queryHttpMethod = QueryHttpMethodType.valueOf(query.queryHttpMethod),
        transport = QueryTransportType.valueOf(query.transport),
        isEnumerable = query.isEnumerable,
        supportsPaging = query.supportsPaging,
        supportsSorting = query.supportsSorting,
        treatWarningsAsErrors = query.treatWarningsAsErrors
    )

    private fun toPropertyDescriptor(property: PropertyModel): PropertyDescriptor = PropertyDescriptor(
        property.name,
        property.shape,
        property.isCommandKey,
        property.validationRules.map(::toValidationRuleDescriptor),
        property.validateRecursively,
        property.derivatives
    )

    private fun renderTypeShape(shape: TypeShapeDescriptor): String = when (shape.kind) {
        TypeShapeKind.VALUE -> "io.cratis.arc.metadata.TypeShapeDescriptor.value(" +
            "typeName = ${quote(requireNotNull(shape.typeName))}, nullable = ${shape.nullable})"
        TypeShapeKind.SEQUENCE -> "io.cratis.arc.metadata.TypeShapeDescriptor.sequence(" +
            "sequenceKind = io.cratis.arc.metadata.SequenceKind.${requireNotNull(shape.sequenceKind).name}, " +
            "elementShape = ${renderTypeShape(requireNotNull(shape.elementShape))}, nullable = ${shape.nullable})"
        TypeShapeKind.MAP -> "io.cratis.arc.metadata.TypeShapeDescriptor.map(" +
            "keyShape = ${renderTypeShape(requireNotNull(shape.keyShape))}, " +
            "valueShape = ${renderTypeShape(requireNotNull(shape.valueShape))}, " +
            "keyCodec = io.cratis.arc.metadata.MapKeyCodec.${requireNotNull(shape.keyCodec).name}, " +
            "nullable = ${shape.nullable})"
    }

    private fun toValidationRuleDescriptor(rule: ValidationRuleModel): ValidationRuleDescriptor = ValidationRuleDescriptor(
        rule.ruleName,
        rule.arguments,
        rule.message
    )

    private fun renderValidationRules(rules: List<ValidationRuleModel>): String = if (rules.isEmpty()) {
        "emptyList()"
    } else {
        rules.joinToString(", ", "listOf(", ")") { rule ->
            val arguments = if (rule.arguments.isEmpty()) {
                "emptyList()"
            } else {
                rule.arguments.joinToString(", ", "listOf(", ")") { argument ->
                    when (argument) {
                        is String -> quote(argument)
                        is Number, is Boolean -> argument.toString()
                        else -> error("Unsupported validation rule argument '$argument'.")
                    }
                }
            }
            val message = rule.message?.let(::quote) ?: "null"
            "io.cratis.arc.metadata.ValidationRuleDescriptor(${quote(rule.ruleName)}, $arguments, $message)"
        }
    }

    private fun AuthorizationModel.toDescriptor(): AuthorizationMetadata = AuthorizationMetadata(
        allowAnonymous,
        policy,
        roles,
        schemes
    )

    private fun renderModuleArtifacts(artifacts: List<String>): String = if (artifacts.isEmpty()) {
        "emptyList()"
    } else {
        artifacts.joinToString(",\n", "java.util.List.copyOf(\n        listOf(\n", "\n        )\n    )") { artifact ->
            "            $artifact"
        }
    }

    private fun containsMapType(type: KSType, visiting: MutableSet<String> = mutableSetOf()): Boolean {
        val declarationName = type.declaration.qualifiedName?.asString() ?: return false
        if (declarationName in MAP_TYPE_NAMES) return true
        val identity = "$declarationName:${type.arguments.size}"
        if (!visiting.add(identity)) return false
        return type.arguments.any { argument ->
            argument.type?.resolve()?.let { nested -> containsMapType(nested, visiting) } == true
        }
    }

    private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
        annotationsNamed(qualifiedName).any()

    private fun KSAnnotated.hasNullableAnnotation(): Boolean = annotations.any { annotation ->
        annotation.shortName.asString() == "Nullable"
    }

    private fun KSAnnotated.annotationsNamed(qualifiedName: String): Sequence<KSAnnotation> = annotations.filter {
        annotation -> annotation.annotationType.resolve().declaration.qualifiedName?.asString() == qualifiedName
    }

    private fun KSAnnotated.roleAnnotations(): List<String> = annotations.flatMap { annotation ->
        when (annotation.annotationType.resolve().declaration.qualifiedName?.asString()) {
            ROLES_ANNOTATION -> sequenceOf(annotation).flatMap { it.stringListArgument("value").asSequence() }
            ROLES_CONTAINER_ANNOTATION -> annotation.annotationListArgument("value").asSequence()
                .flatMap { nested -> nested.stringListArgument("value").asSequence() }
            else -> emptySequence()
        }
    }.toList()

    private fun KSAnnotation.stringArgument(name: String): String? =
        arguments.firstOrNull { argument -> argument.name?.asString() == name }?.value as? String

    private fun KSAnnotation.stringListArgument(name: String): List<String> =
        argumentValues(name).filterIsInstance<String>()

    private fun KSAnnotation.annotationListArgument(name: String): List<KSAnnotation> =
        argumentValues(name).filterIsInstance<KSAnnotation>()

    private fun KSAnnotation.argumentValues(name: String): List<*> = when (
        val value = arguments.firstOrNull { argument -> argument.name?.asString() == name }?.value
    ) {
        is List<*> -> value
        is Array<*> -> value.toList()
        else -> emptyList<Any>()
    }

    private fun KSDeclaration.hasCommandKey(): Boolean {
        if (hasAnnotation(COMMAND_KEY_ANNOTATION)) return true
        return (this as? com.google.devtools.ksp.symbol.KSPropertyDeclaration)
            ?.getter
            ?.hasAnnotation(COMMAND_KEY_ANNOTATION) == true
    }

    private companion object {
        const val MODULE_NAME_OPTION = "arc.moduleName"
        const val COMMAND_ANNOTATION = "io.cratis.arc.artifacts.Command"
        const val COMMAND_SIMPLE_NAME = "Command"
        const val COMMAND_KEY_ANNOTATION = "io.cratis.arc.artifacts.CommandKey"
        const val READ_MODEL_ANNOTATION = "io.cratis.arc.artifacts.ReadModel"
        const val READ_MODEL_SIMPLE_NAME = "ReadModel"
        const val FROM_SERVICES_ANNOTATION = "io.cratis.arc.artifacts.FromServices"
        const val TREAT_WARNINGS_AS_ERRORS_ANNOTATION = "io.cratis.arc.artifacts.TreatWarningsAsErrors"
        const val AUTHORIZE_ANNOTATION = "io.cratis.arc.authorization.Authorize"
        const val ROLES_ANNOTATION = "io.cratis.arc.authorization.Roles"
        const val ROLES_CONTAINER_ANNOTATION = "io.cratis.arc.authorization.RolesContainer"
        const val ALLOW_ANONYMOUS_ANNOTATION = "io.cratis.arc.authorization.AllowAnonymous"
        const val PATH_ANNOTATION = "io.cratis.arc.queries.Path"
        const val QUERY_HTTP_METHOD_ANNOTATION = "io.cratis.arc.queries.QueryHttpMethod"
        const val QUERY_TRANSPORT_ANNOTATION = "io.cratis.arc.queries.QueryTransport"
        const val QUERY_PAGE_TYPE = "io.cratis.arc.queries.QueryPage"
        const val JAVA_OPTIONAL_TYPE = "java.util.Optional"
        const val QUERY_REQUEST_TYPE = "io.cratis.arc.queries.QueryRequest"
        const val QUERY_CONTEXT_TYPE = "io.cratis.arc.queries.QueryContext"
        const val SPRING_PAGE_TYPE = "org.springframework.data.domain.Page"
        const val SPRING_PAGEABLE_TYPE = "org.springframework.data.domain.Pageable"
        const val SPRING_SORT_TYPE = "org.springframework.data.domain.Sort"
        const val MAX_DEFAULTED_CLIENT_QUERY_PARAMETERS = 6
        val QUERY_INFRASTRUCTURE_TYPES = mapOf(
            QUERY_REQUEST_TYPE to QueryParameterSource.QUERY_REQUEST,
            QUERY_CONTEXT_TYPE to QueryParameterSource.QUERY_CONTEXT
        )
        val QUERY_INFRASTRUCTURE_SIMPLE_NAMES = QUERY_INFRASTRUCTURE_TYPES.keys
            .map { typeName -> typeName.substringAfterLast('.') }
            .toSet()
        val QUERY_HOST_ADAPTER_TYPES = mapOf(
            SPRING_PAGEABLE_TYPE to QueryHostAdapterKind.PAGEABLE,
            SPRING_SORT_TYPE to QueryHostAdapterKind.SORT
        )
        val QUERY_HOST_ADAPTER_SIMPLE_NAMES = QUERY_HOST_ADAPTER_TYPES.keys
            .map { typeName -> typeName.substringAfterLast('.') }
            .toSet()
        val RESERVED_QUERY_PARAMETER_NAMES = setOf("page", "pagesize", "sortby", "sortdirection")
        const val HANDLER_NAME = "handle"
        const val PROVIDE_NAME = "provide"
        const val COMPLETION_STAGE_TYPE = "java.util.concurrent.CompletionStage"
        const val KOTLIN_FLOW_TYPE = "kotlinx.coroutines.flow.Flow"
        val JDK_PUBLISHER_TYPES = setOf("java.util.concurrent.Flow.Publisher", "java.util.concurrent.Flow\$Publisher")
        const val COMMAND_RESULT_TYPE = "io.cratis.arc.results.CommandResult"
        const val COMMAND_RESPONSE_VALUES_TYPE = "io.cratis.arc.commands.CommandResponseValues"
        const val ARC_ONE_OF_TYPE = "io.cratis.arc.commands.ArcOneOf"
        const val HANDLES_COMMAND_RESPONSE_VALUES_ANNOTATION =
            "io.cratis.arc.commands.HandlesCommandResponseValues"
        const val HANDLES_COMMAND_RESPONSE_VALUES_SIMPLE_NAME = "HandlesCommandResponseValues"
        const val ARRAY_TYPE = "kotlin.Array"
        const val CHRONICLE_EVENT_TYPE_ANNOTATION = "io.cratis.chronicle.events.EventType"
        val VOID_TYPE_NAMES = setOf("void", "java.lang.Void", "kotlin.Unit")
        val PROVIDE_CONTROL_TYPES = setOf(
            "io.cratis.arc.results.ValidationResult",
            "io.cratis.arc.authorization.AuthorizationResult",
            "io.cratis.arc.results.CommandResult"
        )
        val PROVIDE_DYNAMIC_TYPES = setOf("kotlin.Any", "java.lang.Object")
        val AGGREGATE_TYPE_ARITIES = mapOf(
            "kotlin.Pair" to 2,
            "kotlin.Triple" to 3
        )
        val HANDLED_RESPONSE_LEAF_TYPES = setOf(
            "io.cratis.arc.results.ValidationResult",
            "io.cratis.arc.authorization.AuthorizationResult",
            COMMAND_RESULT_TYPE,
            "io.cratis.chronicle.eventSequences.EventForEventSourceId",
            "io.cratis.arc.chronicle.EventsWithConcurrencyScopes"
        )
        val HANDLED_COLLECTION_ELEMENT_TYPES = setOf(
            "io.cratis.arc.results.ValidationResult",
            "io.cratis.chronicle.eventSequences.EventForEventSourceId"
        )
        val SUPPORTED_RESPONSE_VALUE_HANDLER_TYPES = setOf(
            "io.cratis.arc.commands.CommandResponseValueHandler",
            "io.cratis.arc.java.BlockingCommandResponseValueHandler",
            "io.cratis.arc.java.AsyncCommandResponseValueHandler"
        )
        val COLLECTION_TYPE_NAMES = setOf(
            "kotlin.collections.List",
            "kotlin.collections.MutableList",
            "kotlin.collections.Collection",
            "kotlin.collections.MutableCollection",
            "java.util.List",
            "java.util.Collection"
        )
        val MAP_TYPE_NAMES = setOf("kotlin.collections.Map", "kotlin.collections.MutableMap", "java.util.Map")
        val UNSUPPORTED_STREAM_TYPES = setOf("org.reactivestreams.Publisher")
    }
}

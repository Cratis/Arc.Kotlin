// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Origin
import java.io.File

/** The validated handle signature of a command. */
private class HandlerSignature(
    val qualifiedName: String,
    val handler: KSFunctionDeclaration,
    val parameters: List<HandlerParameterModel>,
    val invocationKind: InvocationKind,
    val provide: ProvideModel?
)

/** The result of reading the optional provide method, distinguishing "no provide method" from a failure. */
private class ProvideReading(val model: ProvideModel?)

/** Validates one command and builds its model, emitting its handler once the handler is known to be valid. */
internal class CommandModelBuilder(
    private val services: CommandResponseServices,
    private val resolver: Resolver,
    private val emitHandler: (String, () -> CommandInvocationModel, KSFile) -> Unit
) {
    private val logger = services.logger
    private val shapeValidator = ArtifactShapeValidator(logger)
    private val methodReader = CommandMethodReader(logger)

    fun build(command: KSClassDeclaration): CommandModel? {
        val qualifiedName = command.qualifiedName?.asString()
        if (qualifiedName == null) {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "@$COMMAND_SIMPLE_NAME classes must not be local.", command)
            return null
        }
        val signature = readSignature(command, qualifiedName) ?: return null
        val properties = services.collector.describeProperties(command, qualifiedName) ?: return null
        if (!validateSingleCommandKey(command, qualifiedName, properties)) return null
        val commandKey = properties.singleOrNull(PropertyModel::isCommandKey)
        val eventMetadata = CommandEventMetadataReader(logger, command, qualifiedName).read()
        if (eventMetadata?.isValid == false) return null
        val authorization = AuthorizationReader(logger).read(command, signature.handler, qualifiedName, "Command") ?: return null
        val containingFile = command.containingFile
        if (containingFile == null) {
            logger.error(ArcDiagnostic.COMMAND_SHAPE, "Command '$qualifiedName' does not have a resolvable source file.", command)
            return null
        }

        // Validate unconditional response restrictions without traversing a provisional CLIENT-only graph.
        classifyResponse(signature, collectClientGraph = false) ?: return null
        // The invocation model, including its source probe, is only built when the caller decides to emit.
        emitHandler(
            qualifiedName,
            {
                CommandInvocationModel(
                    qualifiedName, commandHandlerClassName(qualifiedName), signature.parameters, signature.provide,
                    commandKey?.name, commandKey != null && isParsedJavaRecord(command), signature.invocationKind
                )
            },
            containingFile
        )
        val response = classifyResponse(signature, collectClientGraph = true) ?: return null
        return CommandModel(
            qualifiedName = qualifiedName,
            simpleName = command.simpleName.asString(),
            handlerClassName = commandHandlerClassName(qualifiedName),
            parameters = signature.parameters,
            provide = signature.provide,
            properties = properties,
            commandKeyPropertyName = commandKey?.name,
            commandKeyUsesFunction = commandKey != null && isParsedJavaRecord(command),
            authorization = authorization,
            treatWarningsAsErrors = command.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION) ||
                signature.handler.hasAnnotation(TREAT_WARNINGS_AS_ERRORS_ANNOTATION),
            responseTypeName = response.typeName,
            responseIsEnumerable = response.isEnumerable,
            responseValues = response.values,
            invocationKind = signature.invocationKind,
            summary = DocumentationSummaryParser.parse(command.docString).summary,
            eventMetadata = eventMetadata
        )
    }

    private fun classifyResponse(signature: HandlerSignature, collectClientGraph: Boolean): CommandResponseModel? =
        CommandResponseClassifier(services, signature.qualifiedName, signature.handler, collectClientGraph)
            .determine(resolver)

    private fun readSignature(command: KSClassDeclaration, qualifiedName: String): HandlerSignature? {
        if (!shapeValidator.validateCommandClass(command, qualifiedName)) return null
        val handler = shapeValidator.findCommandHandler(command, qualifiedName) ?: return null
        val handlerSite = CommandMethodSite(qualifiedName, handler, HANDLER_NAME)
        val parameters = methodReader.parameters(handlerSite) ?: return null
        val invocationKind = methodReader.invocationKind(handlerSite, resolver) ?: return null
        val provide = readProvide(command, qualifiedName, handler) ?: return null
        return HandlerSignature(qualifiedName, handler, parameters, invocationKind, provide.model)
    }

    private fun readProvide(command: KSClassDeclaration, qualifiedName: String, handler: KSFunctionDeclaration): ProvideReading? {
        val namedProvides = command.getDeclaredFunctions().filter { it.simpleName.asString() == PROVIDE_NAME }.toList()
        if (namedProvides.size > 1) {
            logger.error("Command '$qualifiedName' has overloaded '$PROVIDE_NAME' functions; at most one is supported.", command)
            return null
        }
        val provideMethod = namedProvides.singleOrNull() ?: return ProvideReading(null)
        if (!shapeValidator.validateProvideMethod(provideMethod, qualifiedName)) return null
        val provideSite = CommandMethodSite(qualifiedName, provideMethod, PROVIDE_NAME)
        val model = ProvideModel(
            methodReader.parameters(provideSite) ?: return null,
            methodReader.invocationKind(provideSite, resolver, allowNullableReturn = true) ?: return null
        )
        ProvidedValueWarner(logger).warn(qualifiedName, provideMethod, handler, resolver)
        return ProvideReading(model)
    }

    private fun validateSingleCommandKey(
        command: KSClassDeclaration,
        qualifiedName: String,
        properties: List<PropertyModel>
    ): Boolean {
        val commandKeys = properties.filter(PropertyModel::isCommandKey)
        if (commandKeys.size <= 1) return true
        logger.error(
            "Command '$qualifiedName' declares multiple @CommandKey properties: " +
                commandKeys.joinToString { property -> property.name } + ". Exactly one is supported.",
            command
        )
        return false
    }

    private fun isParsedJavaRecord(command: KSClassDeclaration): Boolean {
        if (command.origin != Origin.JAVA) return false
        val source = command.containingFile?.filePath?.let(::File)?.takeIf(File::isFile)?.readText() ?: return false
        return parseJavaRecordProperties(source, command.simpleName.asString()) != null
    }
}

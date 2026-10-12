// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

internal const val COMMAND_HANDLER_METHOD_NAME = "handle"
internal const val COMMAND_PROVIDE_METHOD_NAME = "provide"

/** Renders the generated command handler source and command descriptor expressions. */
internal object ArcCommandRenderer {
    fun renderHandler(command: CommandInvocationModel, moduleName: String?): String {
        val commandType = renderQualifiedName(command.qualifiedName)
        val invocationBody = renderInvocationBody(command)
        val preparation = renderPreparation(command, commandType)
        val keyResolution = renderKeyResolution(command, commandType)
        return """// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package $GENERATED_COMMANDS_PACKAGE

import io.cratis.arc.commands.await

/** Generated reflection-free command handler for [${command.qualifiedName}]. */
public class ${command.handlerClassName} : io.cratis.arc.commands.CommandHandler {
    override val commandType: java.lang.Class<*> = $commandType::class.java

    override val metadata: io.cratis.arc.metadata.CommandDescriptor =
        $GENERATED_PACKAGE.${moduleName}ArcArtifactMetadata.create${command.handlerClassName}()
$keyResolution$preparation
    override suspend fun invoke(context: io.cratis.arc.commands.CommandContext): kotlin.Any? {
        val command = context.command as $commandType
        $invocationBody
    }
}
"""
    }

    private fun renderInvocation(methodName: String, parameters: List<HandlerParameterModel>): String {
        val arguments = parameters.joinToString(",\n") { parameter ->
            "            resolver.${resolverMethod(parameter.resolution)}(${parameter.sourceTypeName}::class.java, " +
                "${quote(methodName)}, ${quote(parameter.name)})"
        }
        return if (arguments.isEmpty()) "command.$methodName()"
        else "command.$methodName(\n$arguments\n        )"
    }

    private fun resolverMethod(resolution: CommandParameterResolution): String = when (resolution) {
        CommandParameterResolution.REQUIRED -> "resolve"
        CommandParameterResolution.NULLABLE -> "resolveNullable"
        CommandParameterResolution.OPTIONAL -> "resolveOptional"
    }

    private fun renderInvocationBody(command: CommandInvocationModel): String {
        val handlerInvocation = renderInvocation(COMMAND_HANDLER_METHOD_NAME, command.parameters)
        return buildString {
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
    }

    private fun renderPreparation(command: CommandInvocationModel, commandType: String): String {
        val provide = command.provide ?: return ""
        val provideInvocation = renderInvocation(COMMAND_PROVIDE_METHOD_NAME, provide.parameters)
        val body = when (provide.invocationKind) {
            InvocationKind.VALUE -> "val provided: kotlin.Any? = $provideInvocation"
            InvocationKind.UNIT -> "$provideInvocation\n        val provided: kotlin.Any? = null"
            InvocationKind.COMPLETION_STAGE -> "val provided: kotlin.Any? = $provideInvocation.await()"
            InvocationKind.COMPLETION_STAGE_VOID -> "$provideInvocation.await()\n        val provided: kotlin.Any? = null"
        }
        val resolver = if (provide.parameters.isEmpty()) "" else
            "        val resolver = io.cratis.arc.commands.CommandHandlerArgumentResolver(context)\n"
        return """
    override suspend fun prepare(context: io.cratis.arc.commands.CommandContext): io.cratis.arc.commands.CommandPreparation {
        val command = context.command as $commandType
$resolver        $body
        return io.cratis.arc.commands.CommandPreparation.from(provided, context)
    }
"""
    }

    private fun renderKeyResolution(command: CommandInvocationModel, commandType: String): String {
        val propertyName = command.commandKeyPropertyName ?: return ""
        val accessor = renderQualifiedName(propertyName) + if (command.commandKeyUsesFunction) "()" else ""
        return """
    override fun resolveCommandKey(command: kotlin.Any): kotlin.Any? {
        val typedCommand = command as $commandType
        return typedCommand.$accessor
    }
"""
    }

    fun renderDescriptor(command: CommandModel): String {
        val properties = ArcDescriptorRenderer.renderProperties(command.properties)
        val location = renderLocation(command.qualifiedName)
        val responseTypeName = quoteOrNull(command.responseTypeName)
        val eventMetadata = renderEventMetadata(command.eventMetadata)
        val responseValues = renderResponseValues(command.responseValues)

        return """io.cratis.arc.metadata.CommandDescriptor(
        name = ${quote(command.simpleName)},
        typeName = ${quote(command.qualifiedName)},
        properties = $properties,
        routeOptions = io.cratis.arc.metadata.RouteOptions(),
        location = listOf($location),
        authorization = ${renderAuthorization(command.authorization)},
        explicitPath = null,
        treatWarningsAsErrors = ${command.treatWarningsAsErrors},
        responseTypeName = $responseTypeName,
        responseIsEnumerable = ${command.responseIsEnumerable},
        responseValues = $responseValues,
        summary = ${quoteOrNull(command.summary)},
        eventMetadata = $eventMetadata
    )"""
    }

    private fun renderEventMetadata(metadata: CommandEventMetadataModel?): String {
        if (metadata == null) return "null"
        return "io.cratis.arc.metadata.CommandEventMetadata(" +
            "eventSourceType = ${quoteOrNull(metadata.eventSourceType)}, " +
            "eventStreamType = ${quoteOrNull(metadata.eventStreamType)}, " +
            "eventStreamId = ${quoteOrNull(metadata.eventStreamId)}, " +
            "subject = ${quoteOrNull(metadata.subject)})"
    }

    private fun renderResponseValues(values: List<CommandResponseValueModel>): String {
        if (values.isEmpty()) return "emptyList()"
        return values.joinToString(",\n", "listOf(\n", "\n        )") { value ->
            "            io.cratis.arc.metadata.CommandResponseValueDescriptor(" +
                "typeName = ${quote(value.typeName)}, isEnumerable = ${value.isEnumerable}, " +
                "disposition = io.cratis.arc.metadata.CommandResponseValueDisposition.${value.disposition.name})"
        }
    }
}

/** Renders the location segments of a qualified name as a comma separated list of quoted segments. */
internal fun renderLocation(qualifiedName: String): String = qualifiedName.substringBeforeLast('.', "")
    .split('.')
    .filter(String::isNotBlank)
    .joinToString(", ") { quote(it) }

/** Renders an authorization metadata constructor expression for a generated descriptor. */
internal fun renderAuthorization(authorization: AuthorizationModel): String {
    val roles = authorization.roles.joinToString(", ") { quote(it) }
    val schemes = authorization.schemes.joinToString(", ") { quote(it) }
    val policy = quoteOrNull(authorization.policy)
    return """io.cratis.arc.metadata.AuthorizationMetadata(
            allowAnonymous = ${authorization.allowAnonymous},
            policy = $policy,
            roles = listOf($roles),
            schemes = listOf($schemes)
        )"""
}

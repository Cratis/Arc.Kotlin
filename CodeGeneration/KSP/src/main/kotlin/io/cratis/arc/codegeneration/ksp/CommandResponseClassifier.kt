// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.codegeneration.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Nullability
import io.cratis.arc.metadata.CommandResponseValueDisposition

/** Collaborators and discovered state shared by every command response classification. */
internal class CommandResponseServices(
    val logger: ArcDiagnosticReporter,
    val graphLogger: ArcDiagnosticReporter,
    val collector: MetadataCollector,
    val declarativeHandledTypes: Set<String>
)

/** Validates and classifies the response type of one command handler. */
internal class CommandResponseClassifier(
    private val services: CommandResponseServices,
    private val commandName: String,
    private val handler: KSFunctionDeclaration,
    private val collectClientGraph: Boolean = true
) {
    private val logger = services.logger

    fun determine(resolver: Resolver): CommandResponseModel? {
        val responseType = resolveResponseType(resolver) ?: return null
        val values = classify(responseType) ?: return null
        val clientValues = values.filter { value -> value.disposition == CommandResponseValueDisposition.CLIENT }
        if (collectClientGraph && clientValues.size > 1) {
            val conflictingTypes = clientValues.joinToString(", ") { value -> "'${value.typeName}'" }
            services.graphLogger.error(
                ArcDiagnostic.AMBIGUOUS_COMMAND_RESPONSE,
                "Handler '$commandName.$HANDLER_NAME' has ambiguous command response values in declaration order: " +
                    "$conflictingTypes.",
                handler
            )
            return null
        }
        return CommandResponseModel(values)
    }

    private fun fail(message: String): Nothing? {
        logger.error(message, handler)
        return null
    }

    private fun resolveResponseType(resolver: Resolver): KSType? {
        var responseType = handler.returnType?.resolve()
        if (responseType == null || responseType.isError) {
            return fail("Handler '$commandName.$HANDLER_NAME' has no resolvable response type.")
        }
        if (rejectExplicitlyNullable(responseType)) return null
        if (isCompletionStage(responseType, resolver)) {
            responseType = unwrapSingleTypeArgument(responseType, "CompletionStage") ?: return null
        }
        if (responseType.declaration.qualifiedName?.asString() == COMMAND_RESULT_TYPE) {
            responseType = unwrapSingleTypeArgument(responseType, "CommandResult") ?: return null
        }
        if (responseType.declaration.qualifiedName?.asString() in MAP_TYPE_NAMES) {
            logger.error(
                ArcDiagnostic.PROXY_SHAPE,
                "Command '$commandName' response value path 'response' uses a top-level map; command response maps are unsupported.",
                handler
            )
            return null
        }
        return responseType
    }

    private fun classify(responseType: KSType): List<CommandResponseValueModel>? {
        if (responseType.isError || responseType.declaration is KSTypeParameter) {
            return fail("Handler '$commandName.$HANDLER_NAME' has an unresolvable or generic response type.")
        }
        if (rejectExplicitlyNullable(responseType)) return null
        val responseName = responseType.declaration.qualifiedName?.asString()
            ?: return fail("Handler '$commandName.$HANDLER_NAME' has an unnamed response type.")
        return classifyNamed(responseType, responseName)
    }

    private fun classifyNamed(responseType: KSType, responseName: String): List<CommandResponseValueModel>? {
        if (responseName in VOID_TYPE_NAMES || responseName == COMMAND_RESPONSE_VALUES_TYPE) return emptyList()
        if (responseName == COMMAND_RESULT_TYPE || responseName == ARC_ONE_OF_TYPE) return classifySingleMember(responseType)
        val aggregateSize = AGGREGATE_TYPE_ARITIES[responseName]
        if (aggregateSize != null) return classifyAggregate(responseType, aggregateSize)
        if (responseName in COLLECTION_TYPE_NAMES || responseName == ARRAY_TYPE) {
            return classifyCollectionLeaf(responseType)?.let(::listOf)
        }
        return classifyLeaf(responseType, responseName)
    }

    private fun classifySingleMember(responseType: KSType): List<CommandResponseValueModel>? {
        val member = resolveTypeArguments(responseType, 1)?.singleOrNull() ?: return null
        return classify(member)
    }

    private fun classifyAggregate(responseType: KSType, size: Int): List<CommandResponseValueModel>? {
        val members = resolveTypeArguments(responseType, size) ?: return null
        return members.flatMap { member -> classify(member) ?: return null }
    }

    private fun classifyLeaf(responseType: KSType, responseName: String): List<CommandResponseValueModel>? {
        val disposition = if (isHandledLeaf(responseType)) {
            CommandResponseValueDisposition.HANDLED
        } else {
            CommandResponseValueDisposition.CLIENT
        }
        if (!collectClientShape(responseType, disposition)) return null
        return listOf(CommandResponseValueModel(responseName, false, disposition))
    }

    private fun collectClientShape(responseType: KSType, disposition: CommandResponseValueDisposition): Boolean {
        if (!collectClientGraph || disposition != CommandResponseValueDisposition.CLIENT) return true
        val identity = "$commandName.$HANDLER_NAME response"
        val shape = services.collector.describe(responseType, identity, handler) ?: return false
        return services.collector.collect(shape, identity, handler)
    }

    private fun rejectExplicitlyNullable(responseType: KSType): Boolean {
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

    private fun classifyCollectionLeaf(responseType: KSType): CommandResponseValueModel? {
        val element = resolveTypeArguments(responseType, 1)?.singleOrNull() ?: return null
        val elementName = validateCollectionElement(element) ?: return null
        val disposition = if (isBuiltInHandledCollectionElement(element) || isDeclarativelyHandled(responseType)) {
            CommandResponseValueDisposition.HANDLED
        } else {
            CommandResponseValueDisposition.CLIENT
        }
        if (!collectClientShape(responseType, disposition)) return null
        return CommandResponseValueModel(elementName, true, disposition)
    }

    private fun validateCollectionElement(element: KSType): String? {
        if (element.isError || element.declaration is KSTypeParameter) {
            return fail("Handler '$commandName.$HANDLER_NAME' has an unresolvable response element type.")
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
        return element.declaration.qualifiedName?.asString()
            ?: fail("Handler '$commandName.$HANDLER_NAME' has an unnamed response element type.")
    }

    private fun resolveTypeArguments(responseType: KSType, expectedCount: Int): List<KSType>? {
        if (responseType.arguments.size != expectedCount || responseType.arguments.any { argument -> argument.type == null }) {
            return fail(
                "Handler '$commandName.$HANDLER_NAME' must use concrete response type arguments; " +
                    "wildcards and star projections are unsupported."
            )
        }
        return responseType.arguments.map { argument -> requireNotNull(argument.type).resolve() }
    }

    private fun unwrapSingleTypeArgument(type: KSType, wrapperName: String): KSType? {
        if (type.arguments.size != 1 || type.arguments.single().type == null) {
            return fail(
                "Handler '$commandName.$HANDLER_NAME' must return $wrapperName<T> with a concrete T; " +
                    "wildcards and star projections are unsupported."
            )
        }
        val result = type.arguments.single().type!!.resolve()
        if (result.isError || result.declaration is KSTypeParameter) {
            return fail("Handler '$commandName.$HANDLER_NAME' has an unresolvable $wrapperName result type.")
        }
        return result
    }

    private fun isHandledLeaf(type: KSType): Boolean {
        val typeName = type.declaration.qualifiedName?.asString()
        return typeName in HANDLED_RESPONSE_LEAF_TYPES || isChronicleEvent(type) || isDeclarativelyHandled(type)
    }

    // Element contracts are safe only for built-ins whose runtime handlers intentionally consume empty containers.
    private fun isBuiltInHandledCollectionElement(type: KSType): Boolean =
        type.declaration.qualifiedName?.asString() in HANDLED_COLLECTION_ELEMENT_TYPES

    private fun isDeclarativelyHandled(type: KSType): Boolean {
        val declaration = type.declaration as? KSClassDeclaration ?: return false
        return sequenceOf(declaration)
            .plus(declaration.getAllSuperTypes().mapNotNull { superType -> superType.declaration as? KSClassDeclaration })
            .mapNotNull { candidate -> candidate.qualifiedName?.asString() }
            .any(services.declarativeHandledTypes::contains)
    }

    private fun isChronicleEvent(type: KSType): Boolean = type.declaration.annotations.any { annotation ->
        annotation.annotationType.resolve().declaration.qualifiedName?.asString() == CHRONICLE_EVENT_TYPE_ANNOTATION
    }
}

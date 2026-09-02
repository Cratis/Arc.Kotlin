// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.results.merge
import io.cratis.arc.results.withResponse
import io.cratis.arc.results.withValidationResults
import io.cratis.arc.results.withoutResponse
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Default host-agnostic command pipeline. */
public class DefaultCommandPipeline @JvmOverloads constructor(
    private val handlers: CommandHandlerRegistry,
    commandFilters: Iterable<CommandFilter> = emptyList(),
    executionScopes: Iterable<CommandExecutionScope> = emptyList(),
    responseValueHandlers: Iterable<CommandResponseValueHandler> = emptyList(),
    contextValuesProviders: Iterable<CommandContextValuesProvider> = emptyList(),
    /** Cooperative timeout applied independently to each best-effort execution-scope completion. */
    public val executionScopeCompletionTimeout: Duration = Duration.ofSeconds(5)
) : CommandPipeline {
    init {
        require(!executionScopeCompletionTimeout.isNegative && !executionScopeCompletionTimeout.isZero) {
            "executionScopeCompletionTimeout must be positive."
        }
    }

    private val filters = java.util.List.copyOf(commandFilters.toList())
    private val scopes = java.util.List.copyOf(executionScopes.toList())
    private val valueHandlers = java.util.List.copyOf(
        builtInCommandResponseValueHandlers() + responseValueHandlers.toList()
    )
    private val contextValuesProviders = java.util.List.copyOf(contextValuesProviders.toList())

    override suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> {
        val handler = handlers.find(command.javaClass)
            ?: return CommandResult.missingHandler(options.correlationId, command.javaClass.name)
        val inheritedToken = currentCoroutineContext()[CommandExecutionContext]?.token
        val suppliedToken = options.parentExecutionToken
        if (suppliedToken != null && inheritedToken != null) {
            require(suppliedToken.executionOwner === inheritedToken.executionOwner) {
                "A nested command execution cannot join a different active root execution."
            }
        }
        val parentToken = suppliedToken ?: inheritedToken
        val token = if (parentToken == null) {
            CommandExecutionOwner(options.correlationId, options.tenantNamespace).createRoot()
        } else {
            parentToken.executionOwner.createChild(parentToken, options.correlationId, options.tenantNamespace)
        }

        return withContext(CommandExecutionContext(token)) {
            try {
                executeFrame(command, handler, options, token)
            } finally {
                token.executionOwner.close(token)
            }
        }
    }

    private suspend fun executeFrame(
        command: Any,
        handler: CommandHandler,
        options: CommandExecutionOptions,
        token: CommandExecutionToken
    ): CommandResult<*> {
        var context = createContext(command, handler, options).attachExecutionToken(token)
        var result: CommandResult<*> = CommandResult.success(options.correlationId)
        var cancellation: CancellationException? = null
        val begunScopes = ArrayList<CommandExecutionScope>(scopes.size)

        try {
            for (scope in scopes) {
                scope.begin(context)
                begunScopes.add(scope)
            }
            result = executeFilters(context).filterValidation(options.allowedValidationSeverity)
            if (result.isSuccess) {
                val preparation = handler.prepare(context)
                result = result.merge(preparation.controlResult).filterValidation(options.allowedValidationSeverity)
                if (result.isSuccess) {
                    if (preparation.providedValues.isNotEmpty()) {
                        context = context.withProvidedValues(preparation.providedValues)
                    }
                    val response = handler.invoke(context)
                    if (response != null) {
                        val processed = processResponse(response, handler.metadata.responseValues, context, result)
                        context = processed.context
                        result = processed.result.filterValidation(options.allowedValidationSeverity)
                    }
                }
            }
        } catch (exception: CommandDependencyUnavailable) {
            result = result.merge(
                CommandResult.invalid(
                    options.correlationId,
                    listOf(
                        ValidationResult(
                            ValidationResultSeverity.Error,
                            "The required command dependency '${exception.dependencyType.name}' is unavailable.",
                            members = listOf(exception.parameterName),
                            reason = ValidationResultReasons.DEPENDENCY_UNAVAILABLE
                        )
                    )
                )
            )
        } catch (exception: CancellationException) {
            cancellation = exception
            result = result.merge(CommandResult.exception(options.correlationId, exception))
        } catch (exception: Exception) {
            result = result.merge(CommandResult.exception(options.correlationId, exception))
        }

        if (!result.isSuccess) token.executionOwner.markRollbackOnly(token)
        if (token.isRootExecution) token.executionOwner.sealRoot(token)
        result = token.executionOwner.applyRollbackOnly(result)

        withContext(NonCancellable) {
            for (index in begunScopes.indices.reversed()) {
                try {
                    val fragment = withTimeout(executionScopeCompletionTimeout.toMillis()) {
                        begunScopes[index].complete(context, result)
                    }
                    if (fragment != null) result = result.merge(fragment)
                } catch (_: TimeoutCancellationException) {
                    result = result.merge(
                        CommandResult.error(
                            options.correlationId,
                            "Command execution scope completion timed out."
                        )
                    )
                } catch (exception: CancellationException) {
                    result = result.merge(CommandResult.exception(options.correlationId, exception))
                    if (cancellation == null) cancellation = exception
                } catch (exception: Exception) {
                    result = result.merge(CommandResult.exception(options.correlationId, exception))
                }
                if (!result.isSuccess) token.executionOwner.markRollbackOnly(token)
            }
        }

        if (!result.isSuccess) token.executionOwner.markRollbackOnly(token)
        cancellation?.let { throw it }
        return if (result.isSuccess) result else result.withoutResponse()
    }

    override suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> {
        if (handlers.find(command.javaClass) == null) {
            return CommandResult.missingHandler(options.correlationId, command.javaClass.name)
        }
        val context = createContext(command, requireNotNull(handlers.find(command.javaClass)), options)
        var result: CommandResult<*> = CommandResult.success(options.correlationId)

        try {
            result = executeFilters(context).filterValidation(options.allowedValidationSeverity)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            result = result.merge(CommandResult.exception(options.correlationId, exception))
        }
        return if (result.isSuccess) result else result.withoutResponse()
    }

    private fun createContext(
        command: Any,
        handler: CommandHandler,
        options: CommandExecutionOptions
    ): CommandContext = CommandContext(
        correlationId = options.correlationId,
        command = command,
        commandType = command.javaClass,
        principal = options.principal,
        tenantId = options.tenantId,
        tenantNamespace = options.tenantNamespace,
        allowedValidationSeverity = options.allowedValidationSeverity,
        serviceResolver = options.serviceResolver,
        exposeExceptionDetails = options.exposeExceptionDetails,
        values = buildContextValues(command),
        commandKey = handler.resolveCommandKey(command)
    )

    private fun buildContextValues(command: Any): Map<String, Any> = LinkedHashMap<String, Any>().also { values ->
        contextValuesProviders.forEach { provider -> values.putAll(provider.provide(command)) }
    }

    private suspend fun executeFilters(context: CommandContext): CommandResult<*> {
        var result: CommandResult<*> = CommandResult.success(context.correlationId)
        val ordered = filters.filterIsInstance<AuthorizationCommandFilter>() +
            filters.filterNot { it is AuthorizationCommandFilter }

        for (filter in ordered) {
            try {
                result = result.merge(filter.execute(context).withoutResponse())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                result = result.merge(CommandResult.exception(context.correlationId, exception))
            }
            if (!result.isSuccess) break
        }
        return result
    }

    private suspend fun processResponse(
        response: Any,
        responseDescriptors: List<CommandResponseValueDescriptor>,
        context: CommandContext,
        result: CommandResult<*>
    ): ProcessedResponse {
        if (responseDescriptors.isEmpty()) return processLegacyResponse(response, context, result)

        val descriptors = responseDescriptors.iterator()
        val values = flattenMetadataResponse(response, descriptors)
        val metadataAligned = !descriptors.hasNext() &&
            values.none { value -> value.disposition == RuntimeResponseDisposition.INVALID }
        val initialResult = if (metadataAligned) {
            result
        } else {
            result.merge(responseMetadataMismatch(context))
        }

        return if (isAggregateResponse(response)) {
            processMetadataAggregate(values, context, initialResult)
        } else {
            processMetadataSimple(values.singleOrNull(), context, initialResult)
        }
    }

    private suspend fun processLegacyResponse(
        response: Any,
        context: CommandContext,
        result: CommandResult<*>
    ): ProcessedResponse = when (response) {
        is ArcOneOf<*> -> processLegacyResponse(response.value, context, result)
        is CommandResult<*>, is CommandResponseValues, is Pair<*, *>, is Triple<*, *, *> ->
            processLegacyAggregate(flattenLegacyResponse(response), context, result)
        else -> processLegacySimple(response, context, result)
    }

    private fun flattenLegacyResponse(value: Any?): List<Any> = when (value) {
        null, Unit -> emptyList()
        is ArcOneOf<*> -> flattenLegacyResponse(value.value)
        is CommandResult<*> -> listOf(value.withoutResponse()) + flattenLegacyResponse(value.response)
        is CommandResponseValues -> value.values.flatMap(::flattenLegacyResponse)
        is Pair<*, *> -> flattenLegacyResponse(value.first) + flattenLegacyResponse(value.second)
        is Triple<*, *, *> ->
            flattenLegacyResponse(value.first) + flattenLegacyResponse(value.second) + flattenLegacyResponse(value.third)
        else -> listOf(value)
    }

    private fun flattenMetadataResponse(
        value: Any?,
        descriptors: Iterator<CommandResponseValueDescriptor>,
        dynamic: Boolean = false
    ): List<ResponseValue> = when (value) {
        null, Unit -> emptyList()
        is ArcOneOf<*> -> flattenMetadataResponse(value.value, descriptors, dynamic)
        is CommandResult<*> -> listOf(
            ResponseValue(value.withoutResponse(), RuntimeResponseDisposition.INTERNAL_HANDLED)
        ) + flattenMetadataResponse(value.response, descriptors, dynamic)
        is CommandResponseValues -> value.values.flatMap { nested ->
            flattenMetadataResponse(nested, descriptors, dynamic = true)
        }
        is Pair<*, *> ->
            flattenMetadataResponse(value.first, descriptors, dynamic) +
                flattenMetadataResponse(value.second, descriptors, dynamic)
        is Triple<*, *, *> ->
            flattenMetadataResponse(value.first, descriptors, dynamic) +
                flattenMetadataResponse(value.second, descriptors, dynamic) +
                flattenMetadataResponse(value.third, descriptors, dynamic)
        else -> {
            if (dynamic) {
                listOf(ResponseValue(value, RuntimeResponseDisposition.DYNAMIC))
            } else if (descriptors.hasNext()) {
                val descriptor = descriptors.next()
                val disposition = when (descriptor.disposition) {
                    CommandResponseValueDisposition.CLIENT -> RuntimeResponseDisposition.CLIENT
                    CommandResponseValueDisposition.HANDLED -> RuntimeResponseDisposition.HANDLED
                }
                listOf(ResponseValue(value, disposition, descriptor))
            } else {
                listOf(ResponseValue(value, RuntimeResponseDisposition.INVALID))
            }
        }
    }

    private fun isAggregateResponse(value: Any?): Boolean = when (value) {
        is ArcOneOf<*> -> isAggregateResponse(value.value)
        is CommandResult<*>, is CommandResponseValues, is Pair<*, *>, is Triple<*, *, *> -> true
        else -> false
    }

    private suspend fun processLegacySimple(
        value: Any,
        context: CommandContext,
        initialResult: CommandResult<*>
    ): ProcessedResponse {
        val matches = matchingHandlers(context, value)
        var result: CommandResult<*> = initialResult.merge(matches.failures)
        if (matches.handlers.isEmpty()) {
            val updatedContext = context.withResponse(value)
            result = result.withResponse(value)
            return ProcessedResponse(updatedContext, result)
        }

        for (handler in matches.handlers) {
            result = handleValue(handler, context, value, result)
        }
        return ProcessedResponse(context, result)
    }

    private suspend fun processLegacyAggregate(
        values: List<Any>,
        initialContext: CommandContext,
        initialResult: CommandResult<*>
    ): ProcessedResponse {
        var context = initialContext
        var result = initialResult
        var responseValue: Any? = null

        val failedHandlerIndexesByValue = List(values.size) { mutableSetOf<Int>() }

        // Pass one identifies at most one client response before response-dependent handlers are re-evaluated.
        for ((valueIndex, value) in values.withIndex()) {
            val matches = matchingHandlers(context, value, failedHandlerIndexesByValue[valueIndex])
            result = result.merge(matches.failures)
            if (matches.handlers.isEmpty() && !matches.hadFailure) {
                if (responseValue == null) {
                    responseValue = value
                    context = context.withResponse(value)
                    result = result.withResponse(value)
                } else {
                    throw MultipleUnhandledCommandResponseValuesException(listOf(responseValue, value))
                }
            }
        }

        // Pass two captures all handlers after the response has been installed in the immutable context.
        // Predicate failures retain their first-pass position and are merged only once per handler and aggregate value.
        val matchesByValue = values.mapIndexed { valueIndex, value ->
            value to matchingHandlers(context, value, failedHandlerIndexesByValue[valueIndex])
        }
        // Pass three invokes every match in declaration order.
        for ((value, matches) in matchesByValue) {
            result = result.merge(matches.failures)
            for (handler in matches.handlers) {
                result = handleValue(handler, context, value, result)
            }
        }
        return ProcessedResponse(context, result)
    }

    private suspend fun processMetadataSimple(
        responseValue: ResponseValue?,
        context: CommandContext,
        initialResult: CommandResult<*>
    ): ProcessedResponse {
        if (responseValue == null || responseValue.disposition == RuntimeResponseDisposition.INVALID) {
            return ProcessedResponse(context, initialResult)
        }
        if (responseValue.disposition == RuntimeResponseDisposition.CLIENT) {
            return ProcessedResponse(
                context.withResponse(responseValue.value),
                initialResult.withResponse(responseValue.value)
            )
        }

        val matches = matchingHandlers(context, responseValue.value, descriptor = responseValue.descriptor)
        var result: CommandResult<*> = initialResult.merge(matches.failures)
        for (handler in matches.handlers) {
            result = handleValue(handler, context, responseValue.value, result)
        }
        if (responseValue.requiresHandler && matches.handlers.isEmpty()) {
            result = result.merge(unhandledStaticResponseValue(context, responseValue))
        }
        return ProcessedResponse(context, result)
    }

    private suspend fun processMetadataAggregate(
        values: List<ResponseValue>,
        initialContext: CommandContext,
        initialResult: CommandResult<*>
    ): ProcessedResponse {
        var context = initialContext
        var result = initialResult
        var responseValue: Any? = null
        val failedHandlerIndexesByValue = List(values.size) { mutableSetOf<Int>() }

        fun installClient(value: Any) {
            val existingResponseValue = responseValue
            if (existingResponseValue != null) {
                throw MultipleUnhandledCommandResponseValuesException(listOf(existingResponseValue, value))
            }
            responseValue = value
            context = context.withResponse(value)
            result = result.withResponse(value)
        }

        // Pass one installs the statically declared client before response-dependent handlers are re-evaluated.
        for ((valueIndex, value) in values.withIndex()) {
            when (value.disposition) {
                RuntimeResponseDisposition.CLIENT -> installClient(value.value)
                RuntimeResponseDisposition.HANDLED,
                RuntimeResponseDisposition.INTERNAL_HANDLED,
                RuntimeResponseDisposition.DYNAMIC -> {
                    val matches = matchingHandlers(
                        context,
                        value.value,
                        failedHandlerIndexesByValue[valueIndex],
                        value.descriptor
                    )
                    result = result.merge(matches.failures)
                    if (
                        value.disposition == RuntimeResponseDisposition.DYNAMIC &&
                        matches.handlers.isEmpty() &&
                        !matches.hadFailure
                    ) {
                        installClient(value.value)
                    }
                }
                RuntimeResponseDisposition.INVALID -> Unit
            }
        }

        // Pass two captures every handler after the client response has been installed.
        val matchesByValue = values.mapIndexed { valueIndex, value ->
            value to when (value.disposition) {
                RuntimeResponseDisposition.CLIENT,
                RuntimeResponseDisposition.INVALID -> null
                else -> matchingHandlers(
                    context,
                    value.value,
                    failedHandlerIndexesByValue[valueIndex],
                    value.descriptor
                )
            }
        }
        // Pass three invokes every match in declaration order and fails closed for statically handled values.
        for ((value, matches) in matchesByValue) {
            if (matches == null) continue
            result = result.merge(matches.failures)
            for (handler in matches.handlers) {
                result = handleValue(handler, context, value.value, result)
            }
            if (value.requiresHandler && matches.handlers.isEmpty()) {
                result = result.merge(unhandledStaticResponseValue(context, value))
            }
        }
        return ProcessedResponse(context, result)
    }

    private fun responseMetadataMismatch(context: CommandContext): CommandResult<*> = CommandResult.error(
        context.correlationId,
        "The command response did not match its generated response metadata."
    )

    private fun unhandledStaticResponseValue(
        context: CommandContext,
        responseValue: ResponseValue
    ): CommandResult<*> {
        val message = responseValue.descriptor?.let { descriptor ->
            "No command response value handler accepted the statically handled response value '${descriptor.typeName}'."
        } ?: "No command response value handler accepted an internal command result fragment."
        return CommandResult.error(context.correlationId, message)
    }

    private fun matchingHandlers(
        context: CommandContext,
        value: Any,
        recordedFailureIndexes: MutableSet<Int>? = null,
        descriptor: CommandResponseValueDescriptor? = null
    ): HandlerMatches {
        val matches = mutableListOf<CommandResponseValueHandler>()
        var failures: CommandResult<*> = CommandResult.success(context.correlationId)
        var hadFailure = false

        for ((handlerIndex, handler) in valueHandlers.withIndex()) {
            try {
                if (handler.canHandleResponseValue(context, value, descriptor)) matches.add(handler)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                hadFailure = true
                if (recordedFailureIndexes == null || recordedFailureIndexes.add(handlerIndex)) {
                    failures = failures.merge(CommandResult.exception(context.correlationId, exception))
                }
            }
        }
        return HandlerMatches(matches, failures, hadFailure)
    }

    private suspend fun handleValue(
        handler: CommandResponseValueHandler,
        context: CommandContext,
        value: Any,
        initialResult: CommandResult<*>
    ): CommandResult<*> {
        var result = initialResult
        try {
            result = result.merge(handler.handle(context, value).withoutResponse())
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            result = result.merge(CommandResult.exception(context.correlationId, exception))
        }
        return result
    }

    private fun CommandResult<*>.filterValidation(
        allowedSeverity: ValidationResultSeverity?
    ): CommandResult<*> {
        val blocking = validationResults.filter { validation ->
            if (allowedSeverity == null) {
                validation.severity == ValidationResultSeverity.Error
            } else {
                validation.severity.value() > allowedSeverity.value()
            }
        }
        return withValidationResults(blocking)
    }

    private class ProcessedResponse(
        val context: CommandContext,
        val result: CommandResult<*>
    )

    private class ResponseValue(
        val value: Any,
        val disposition: RuntimeResponseDisposition,
        val descriptor: CommandResponseValueDescriptor? = null
    ) {
        val requiresHandler: Boolean
            get() = disposition == RuntimeResponseDisposition.HANDLED ||
                disposition == RuntimeResponseDisposition.INTERNAL_HANDLED
    }

    private enum class RuntimeResponseDisposition {
        CLIENT,
        HANDLED,
        INTERNAL_HANDLED,
        DYNAMIC,
        INVALID
    }

    private class HandlerMatches(
        val handlers: List<CommandResponseValueHandler>,
        val failures: CommandResult<*>,
        val hadFailure: Boolean
    )
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.authorization.AuthorizationResult
import io.cratis.arc.metadata.CommandResponseValueDescriptor
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult

internal fun builtInCommandResponseValueHandlers(): List<CommandResponseValueHandler> = listOf(
    ValidationResultValueHandler(),
    ValidationResultsValueHandler(),
    AuthorizationResultValueHandler(),
    CommandResultValueHandler()
)

internal fun CommandResponseValueHandler.canHandleResponseValue(
    context: CommandContext,
    value: Any,
    descriptor: CommandResponseValueDescriptor?
): Boolean {
    if (this is ValidationResultsValueHandler && descriptor != null && value.isEmptyEnumerable()) {
        return descriptor.isEnumerable && descriptor.typeName == ValidationResult::class.qualifiedName
    }
    return canHandle(context, value)
}

private fun Any.isEmptyEnumerable(): Boolean = when (this) {
    is Collection<*> -> isEmpty()
    is Array<*> -> isEmpty()
    else -> false
}

private class ValidationResultValueHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = value is ValidationResult

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
        CommandResult.invalid(context.correlationId, listOf(value as ValidationResult))
}

private class ValidationResultsValueHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = validationResults(value) != null

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> =
        CommandResult.invalid(context.correlationId, requireNotNull(validationResults(value)))

    private fun validationResults(value: Any): List<ValidationResult>? {
        val values = when (value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> return null
        }
        return values.takeIf { candidates -> candidates.all { candidate -> candidate is ValidationResult } }
            ?.map { candidate -> candidate as ValidationResult }
    }
}

private class AuthorizationResultValueHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = value is AuthorizationResult

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
        val authorizationResult = value as AuthorizationResult
        return if (authorizationResult.isAuthorized) {
            CommandResult.success(context.correlationId)
        } else {
            CommandResult.unauthorized(context.correlationId, authorizationResult.failureReason)
        }
    }
}

private class CommandResultValueHandler : CommandResponseValueHandler {
    override fun canHandle(context: CommandContext, value: Any): Boolean = value is CommandResult<*>

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> = value as CommandResult<*>
}

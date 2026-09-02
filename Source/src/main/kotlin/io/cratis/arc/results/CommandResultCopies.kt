// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

internal fun CommandResult<*>.merge(fragment: CommandResult<*>): CommandResult<Any?> {
    val stackTrace = listOf(exceptionStackTrace, fragment.exceptionStackTrace)
        .filter { it.isNotEmpty() }
        .joinToString(System.lineSeparator())
    val responseValue = response ?: fragment.response

    return CommandResult(
        correlationId = correlationId,
        isAuthorized = isAuthorized && fragment.isAuthorized,
        validationResults = validationResults + fragment.validationResults,
        exceptionMessages = exceptionMessages + fragment.exceptionMessages,
        exceptionStackTrace = stackTrace,
        authorizationFailureReason = authorizationFailureReason.ifEmpty { fragment.authorizationFailureReason },
        response = responseValue
    )
}

internal fun CommandResult<*>.withValidationResults(results: List<ValidationResult>): CommandResult<Any?> = CommandResult(
    correlationId = correlationId,
    isAuthorized = isAuthorized,
    validationResults = results,
    exceptionMessages = exceptionMessages,
    exceptionStackTrace = exceptionStackTrace,
    authorizationFailureReason = authorizationFailureReason,
    response = response
)

internal fun CommandResult<*>.withResponse(value: Any?): CommandResult<Any?> = CommandResult(
    correlationId = correlationId,
    isAuthorized = isAuthorized,
    validationResults = validationResults,
    exceptionMessages = exceptionMessages,
    exceptionStackTrace = exceptionStackTrace,
    authorizationFailureReason = authorizationFailureReason,
    response = value
)

internal fun CommandResult<*>.withoutResponse(): CommandResult<Any?> = CommandResult(
    correlationId = correlationId,
    isAuthorized = isAuthorized,
    validationResults = validationResults,
    exceptionMessages = exceptionMessages,
    exceptionStackTrace = exceptionStackTrace,
    authorizationFailureReason = authorizationFailureReason
)

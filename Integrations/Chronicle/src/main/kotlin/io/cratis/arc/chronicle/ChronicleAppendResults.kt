// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.chronicle.eventSequences.AppendError
import io.cratis.chronicle.eventSequences.AppendResult
import io.cratis.chronicle.eventSequences.ConstraintViolation
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyViolation

internal fun appendResultsToCommandResult(
    context: CommandContext,
    results: List<AppendResult>,
    expectedResultCount: Int
): CommandResult<*> {
    val validationResults = mutableListOf<ValidationResult>()
    val errorMessages = mutableListOf<String>()
    val seenConstraintViolations = linkedSetOf<ConstraintViolation>()
    val seenConcurrencyViolations = linkedSetOf<ConcurrencyViolation>()
    val seenAppendErrors = linkedSetOf<AppendError>()

    results.forEach { result ->
        result.constraintViolations.forEach { violation ->
            if (seenConstraintViolations.add(violation)) {
                val member = violation.details["propertyName"]
                    ?.takeIf(String::isNotBlank)
                    ?.replaceFirstChar(Char::lowercase)
                validationResults.add(
                    ValidationResult(
                        severity = ValidationResultSeverity.Error,
                        message = violation.message,
                        members = member?.let(::listOf) ?: emptyList(),
                        state = violation.details,
                        reason = ValidationResultReasons.CONSTRAINT_VIOLATION,
                        reasonDetail = violation.constraintId
                    )
                )
            }
        }
        result.concurrencyViolation?.let { violation ->
            if (seenConcurrencyViolations.add(violation)) {
                validationResults.add(
                    ValidationResult(
                        severity = ValidationResultSeverity.Error,
                        message = "Event source '${violation.eventSourceId}' changed before its events could be appended.",
                        members = emptyList(),
                        state = mapOf(
                            "expectedSequenceNumber" to violation.expectedSequenceNumber.value,
                            "actualSequenceNumber" to violation.actualSequenceNumber.value
                        ),
                        reason = ValidationResultReasons.CONCURRENCY_VIOLATION,
                        reasonDetail = violation.eventSourceId
                    )
                )
            }
        }
        result.errors.forEach { error ->
            if (seenAppendErrors.add(error)) errorMessages.add(error.message)
        }
        if (!result.isSuccess && result.constraintViolations.isEmpty() &&
            result.concurrencyViolation == null && result.errors.isEmpty()
        ) {
            errorMessages.add("Chronicle rejected an event append without providing a reason.")
        }
    }

    if (results.size != expectedResultCount) {
        errorMessages.add("Chronicle returned ${results.size} append results for $expectedResultCount events.")
    }

    return CommandResult<Any?>(
        correlationId = context.correlationId,
        validationResults = validationResults,
        exceptionMessages = errorMessages
    )
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.validation.ConceptValidation
import io.cratis.arc.validation.ConceptValidator
import java.util.concurrent.CancellationException

/** Runs every validator matching the current command and merges their feedback. */
public class DefaultCommandValidationFilter @JvmOverloads constructor(
    validators: Iterable<CommandValidator<*>>,
    conceptValidators: Iterable<ConceptValidator<*>> = emptyList()
) : CommandFilter {
    private val validators = java.util.List.copyOf(validators.toList())
    private val conceptValidation = ConceptValidation(conceptValidators)

    override suspend fun execute(context: CommandContext): CommandResult<*> {
        val results = mutableListOf<ValidationResult>()
        validators.filter { it.commandType == context.commandType }.forEach { validator ->
            try {
                results.addAll(validate(validator, context))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                results.add(validatorFailure(validator, exception))
            }
        }
        results.addAll(conceptValidation.validate(context.command))
        return if (results.isEmpty()) {
            CommandResult.success(context.correlationId)
        } else {
            CommandResult.invalid(context.correlationId, results)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun validate(
        validator: CommandValidator<*>,
        context: CommandContext
    ): List<ValidationResult> = (validator as CommandValidator<Any>).validate(context.command, context)

    private fun validatorFailure(
        @Suppress("UNUSED_PARAMETER") validator: CommandValidator<*>,
        @Suppress("UNUSED_PARAMETER") exception: Exception
    ): ValidationResult = ValidationResult(
        severity = ValidationResultSeverity.Error,
        message = "The value could not be validated.",
        reason = ValidationResultReasons.VALIDATOR_FAILED
    )
}

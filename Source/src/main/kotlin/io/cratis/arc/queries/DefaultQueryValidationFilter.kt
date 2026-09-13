// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.validation.ConceptValidation
import io.cratis.arc.validation.ConceptValidationExclusion
import io.cratis.arc.validation.ConceptValidator
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator
import java.util.concurrent.CancellationException

/** Runs every validator matching the current query and merges their feedback. */
public class DefaultQueryValidationFilter @JvmOverloads constructor(
    validators: Iterable<QueryValidator>,
    conceptValidators: Iterable<ConceptValidator<*>> = emptyList()
) : QueryFilter {
    private val validators = java.util.List.copyOf(validators.toList())
    private var conceptValidation = ConceptValidation(conceptValidators)

    /** Adds reusable exact model rules while retaining the original constructor descriptors. */
    public constructor(
        validators: Iterable<QueryValidator>,
        conceptValidators: Iterable<ConceptValidator<*>>,
        modelValidators: Iterable<ModelValidator<*>>
    ) : this(validators) {
        conceptValidation = ConceptValidation(conceptValidators, modelValidators)
    }

    /** Adds direct concept-rule exclusions without excluding model rules or other filters. */
    public constructor(
        validators: Iterable<QueryValidator>,
        conceptValidators: Iterable<ConceptValidator<*>>,
        modelValidators: Iterable<ModelValidator<*>>,
        exclusions: Iterable<ConceptValidationExclusion>
    ) : this(validators) {
        conceptValidation = ConceptValidation(conceptValidators, modelValidators, exclusions)
    }

    override suspend fun execute(context: QueryContext): QueryResult<*> {
        val results = mutableListOf<ValidationResult>()
        validators.filter { it.queryName == null || it.queryName == context.queryName }.forEach { validator ->
            try {
                results.addAll(validator.validate(context.request, context))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                results.add(validatorFailure(validator, exception))
            }
        }
        context.request.arguments.forEach { (name, value) -> results.addAll(conceptValidation.validate(value, ModelValidationContext(context, name))) }
        return if (results.isEmpty()) {
            QueryResult.success<Any?>(context.correlationId)
        } else {
            QueryResult.invalid<Any?>(context.correlationId, results)
        }
    }

    private fun validatorFailure(
        @Suppress("UNUSED_PARAMETER") validator: QueryValidator,
        @Suppress("UNUSED_PARAMETER") exception: Exception
    ): ValidationResult = ValidationResult(
        severity = ValidationResultSeverity.Error,
        message = "The value could not be validated.",
        reason = ValidationResultReasons.VALIDATOR_FAILED
    )
}

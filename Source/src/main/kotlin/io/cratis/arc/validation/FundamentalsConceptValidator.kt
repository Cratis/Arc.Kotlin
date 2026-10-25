// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.results.ValidationResult
import io.cratis.fundamentals.concepts.ConceptAs

/**
 * Validator for a Fundamentals concept, registered as a [ModelValidator].
 * Rules match the exact runtime class, just like other model validators.
 * As a concept rule, [ConceptValidationExclusion] suppresses it on the excluded edge;
 * it follows the same concept-identity rules as [ConceptValidator].
 * Legacy Arc concept validators continue to implement [ConceptValidator] unchanged.
 */
public interface FundamentalsConceptValidator<TConcept : ConceptAs<*>> : ModelValidator<TConcept> {
    /** Exact concept class accepted by this validator. */
    public val conceptType: Class<TConcept>

    /** Validates the concept and returns feedback relative to its graph member. */
    public fun validate(concept: TConcept): List<ValidationResult>

    /** Exposes the concept class to the existing model-validator registration path. */
    override val modelType: Class<TConcept> get() = conceptType

    /** Invokes the concept rule within the operation's model-validation pipeline. */
    override suspend fun validate(model: TConcept, context: ModelValidationContext): List<ValidationResult> = validate(model)
}

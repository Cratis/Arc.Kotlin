// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.results.ValidationResult

/** Reusable host-neutral validator for one strongly typed concept. */
public interface ConceptValidator<TConcept : ConceptAs<*>> {
    /** Concept type accepted by this validator; filters also match instances of its subtypes. */
    public val conceptType: Class<TConcept>

    /**
     * Validates [concept] and returns validation feedback in declaration order.
     * Cancellation propagates; other runtime failures become safe `validatorFailed` feedback.
     */
    public fun validate(concept: TConcept): List<ValidationResult>
}

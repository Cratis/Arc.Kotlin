// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.results.ValidationResult

/** Reusable host-neutral validator for one strongly typed concept. */
public interface ConceptValidator<TConcept : ConceptAs<*>> {
    /** Exact concept type accepted by this validator and used by validation filters for matching. */
    public val conceptType: Class<TConcept>

    /** Validates [concept] and returns validation feedback in declaration order. */
    public fun validate(concept: TConcept): List<ValidationResult>
}

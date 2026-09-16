// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import jakarta.validation.Validator
import jakarta.validation.ValidatorFactory

/**
 * A validator with pre-access member exclusion, obtained from an application's actual factory.
 * Preserves the factory's configured resolver and other context defaults; never builds or closes a
 * factory. Keep the factory alive for this validator's lifetime. Class and executable constraints
 * remain active. Use this factory method instead of passing an opaque Validator to Arc. Arc uses
 * full-object validation and executable cascades. Provider-specific APIs and validateProperty /
 * validateValue retain provider semantics; they are not pre-access graph traversal contracts.
 */
public class IgnoreValidationValidator private constructor(delegate: Validator) : Validator by delegate {
    public companion object {
        /** Creates a dedicated validator without replacing the application's Validator bean. */
        @JvmStatic
        public fun fromFactory(factory: ValidatorFactory): IgnoreValidationValidator = IgnoreValidationValidator(
            factory.usingContext()
                .traversableResolver(IgnoreValidationTraversableResolver(factory.traversableResolver))
                .validator
        )
    }
}

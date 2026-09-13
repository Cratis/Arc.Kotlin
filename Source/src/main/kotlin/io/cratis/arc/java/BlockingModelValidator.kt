// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext

/** Ordinary Java implementation surface for server-only reusable model rules. */
public interface BlockingModelValidator<T : Any> {
    /** Exact runtime model class accepted by this rule. */
    public val modelType: Class<T>
    /** Returns relative feedback without coroutine types in the signature. */
    public fun validate(model: T, context: ModelValidationContext): List<ValidationResult>
}

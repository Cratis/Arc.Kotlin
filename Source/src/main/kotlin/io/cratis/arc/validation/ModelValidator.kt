// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.validation

import io.cratis.arc.results.ValidationResult

/** Server-only reusable rule for an exact runtime model type in a command or supplied query argument graph. */
public interface ModelValidator<T : Any> {
    /** Exact runtime class accepted by this rule; base classes do not match derived instances. */
    public val modelType: Class<T>

    /** Returns relative member feedback. Do not retain the operation-scoped [context]. */
    public suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult>
}

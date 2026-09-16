// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext
import java.util.concurrent.CompletionStage

/** CompletionStage-based Java implementation surface for server-only reusable model rules. */
public interface AsyncModelValidator<T : Any> {
    /** Exact runtime model class accepted by this rule. */
    public val modelType: Class<T>
    /** Returns asynchronous relative feedback; the adapter awaits without blocking. */
    public fun validate(model: T, context: ModelValidationContext): CompletionStage<List<ValidationResult>>
}

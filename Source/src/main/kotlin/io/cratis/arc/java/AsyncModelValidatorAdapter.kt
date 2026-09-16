// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.commands.await
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator

/** Adapts an asynchronous Java model rule using Arc's cancellable, nonblocking stage bridge. */
public class AsyncModelValidatorAdapter<T : Any>(private val validator: AsyncModelValidator<T>) : ModelValidator<T> {
    override val modelType: Class<T> get() = validator.modelType
    override suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult> = validator.validate(model, context).await()
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.results.ValidationResult
import io.cratis.arc.validation.ModelValidationContext
import io.cratis.arc.validation.ModelValidator

/** Adapts an ordinary Java model rule to the suspending SPI without scheduling work. */
public class BlockingModelValidatorAdapter<T : Any>(private val validator: BlockingModelValidator<T>) : ModelValidator<T> {
    override val modelType: Class<T> get() = validator.modelType
    override suspend fun validate(model: T, context: ModelValidationContext): List<ValidationResult> = validator.validate(model, context)
}

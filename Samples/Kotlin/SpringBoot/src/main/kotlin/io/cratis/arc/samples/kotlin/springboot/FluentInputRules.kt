// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.validation.FluentModelValidator

class FluentInputRules : FluentModelValidator<FluentInput>(FluentInput::class.java) {
    init {
        ruleFor("required").notNull()
        ruleFor("nonempty").notEmpty().withMessage("{PropertyName} must have text; {PropertyName}")
        ruleFor("minimum").minLength(2)
        ruleFor("maximum").maxLength(2)
        ruleFor("range").length(1, 3)
        ruleFor("email").emailAddress()
        ruleFor("phone").phone()
        ruleFor("url").url()
        ruleFor("pattern").matches("^([A-Z]+|[\\]]+)$")
        ruleFor("greater").greaterThan(2)
        ruleFor("atLeast").greaterThanOrEqual(2)
        ruleFor("less").lessThan(2)
        ruleFor("atMost").lessThanOrEqual(2)
    }
}

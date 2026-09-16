// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.validation.FluentModelValidator

class IgnoredInputRules : FluentModelValidator<IgnoredInput>(IgnoredInput::class.java) {
    init {
        ruleFor("ignoredText").notEmpty()
        ruleFor("ignoredList").notEmpty()
        ruleFor("ignoredArray").minLength(2)
        ruleFor("sibling").notEmpty()
    }
}

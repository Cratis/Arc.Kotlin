// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot;

import io.cratis.arc.validation.FluentModelValidator;

public final class JavaIgnoredInputRules extends FluentModelValidator<JavaIgnoredInput> {
    public JavaIgnoredInputRules() {
        super(JavaIgnoredInput.class);
        ruleFor("ignoredText").notEmpty();
        ruleFor("ignoredList").notEmpty();
        ruleFor("sibling").notEmpty();
    }
}

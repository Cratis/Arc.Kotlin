// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.validation.FluentModelValidator;

public final class JavaIgnoreContractRules extends FluentModelValidator<JavaIgnoreContractInput> {
    public JavaIgnoreContractRules() {
        super(JavaIgnoreContractInput.class);
        ruleFor("ignored").notEmpty();
        ruleFor("ignoredList").notEmpty();
        ruleFor("sibling").notEmpty();
    }
}

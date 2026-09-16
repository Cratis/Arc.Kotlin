// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures;

import io.cratis.arc.validation.FluentModelValidator;

public final class JavaFluentContractRules extends FluentModelValidator<JavaFluentContractInput> {
    public JavaFluentContractRules() {
        super(JavaFluentContractInput.class);
        ruleFor("name").notEmpty().maxLength(5);
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.validation.FluentModelValidator;

public final class InvalidJavaFluentBody extends FluentModelValidator<FluentNegativePerson> {
    public InvalidJavaFluentBody() {
        super(FluentNegativePerson.class);
        if (true) ruleFor("name").notNull();
    }
}

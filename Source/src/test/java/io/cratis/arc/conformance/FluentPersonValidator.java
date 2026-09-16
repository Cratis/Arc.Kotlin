// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.conformance;

import io.cratis.arc.validation.FluentModelValidator;

/** Public final top-level, directly typed declaration with an ordinary no-argument Java constructor. */
public final class FluentPersonValidator extends FluentModelValidator<FluentPerson> {
    public FluentPersonValidator() {
        super(FluentPerson.class);
        ruleFor("name").notNull().minLength(2).maxLength(10).withMessage("{PropertyName} is too long");
        ruleFor("age").greaterThanOrEqual(18).lessThan(150);
    }
}

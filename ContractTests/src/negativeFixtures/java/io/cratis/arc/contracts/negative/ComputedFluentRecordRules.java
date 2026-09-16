// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative;

import io.cratis.arc.validation.FluentModelValidator;

public final class ComputedFluentRecordRules extends FluentModelValidator<ComputedFluentRecord> {
    public ComputedFluentRecordRules() { super(ComputedFluentRecord.class); ruleFor("name").minLength(2); }
}

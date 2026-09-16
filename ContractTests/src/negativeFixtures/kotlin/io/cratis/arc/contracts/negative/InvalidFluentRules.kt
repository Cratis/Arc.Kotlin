// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.validation.FluentModelValidator

class FluentNegativePerson(val name: String)

class InvalidKotlinFluentBody : FluentModelValidator<FluentNegativePerson>(FluentNegativePerson::class.java) {
    init { if (true) ruleFor("name").notNull() }
}

class InvalidKotlinFluentRule : FluentModelValidator<FluentNegativePerson>(FluentNegativePerson::class.java) {
    init { ruleFor("name").creditCard() }
}

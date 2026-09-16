// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.validation.FluentModelValidator

data class FluentContractInput(val name: String)

class FluentContractRules : FluentModelValidator<FluentContractInput>(FluentContractInput::class.java) {
    init { ruleFor("name").notEmpty().maxLength(5) }
}

@Command
@AllowAnonymous
data class FluentContractCommand(val input: FluentContractInput, val javaInput: JavaFluentContractInput) {
    fun handle(): String = "accepted"
}

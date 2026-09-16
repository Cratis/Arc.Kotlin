// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryHttpMethod
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.validation.FluentModelValidator
import io.cratis.arc.validation.IgnoreValidation

class IgnoreContractInput(
    @IgnoreValidation val ignored: String?,
    @field:IgnoreValidation val ignoredChild: FluentContractInput?,
    @get:IgnoreValidation val ignoredList: List<FluentContractInput>,
    @IgnoreValidation val ignoredArray: Array<FluentContractInput>,
    @IgnoreValidation val ignoredMap: Map<String, String>,
    val validated: FluentContractInput?,
    val sibling: String?
)

class IgnoreContractRules : FluentModelValidator<IgnoreContractInput>(IgnoreContractInput::class.java) {
    init { ruleFor("ignored").notEmpty(); ruleFor("ignoredList").notEmpty(); ruleFor("sibling").notEmpty() }
}

@Command
@AllowAnonymous
data class IgnoreContractCommand(val input: IgnoreContractInput) {
    fun handle(): String = "accepted"
}

@ReadModel
@AllowAnonymous
data class IgnoreContractView(val value: String) {
    companion object {
        @Path("/contracts/ignore")
        @QueryHttpMethod(QueryHttpMethodType.QUERY)
        fun checkIgnoredContract(input: IgnoreContractInput): IgnoreContractView = IgnoreContractView(input.ignored ?: "null")
    }
}

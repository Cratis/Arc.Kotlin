// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.validation.FluentModelValidator

class ComputedFluentMember(input: String) { val name = input; get() = field.trim() }
class ComputedFluentMemberRules : FluentModelValidator<ComputedFluentMember>(ComputedFluentMember::class.java) {
    init { ruleFor("name").minLength(2) }
}
class AmbiguousFluentPattern(val name: String)
class AmbiguousFluentPatternRules : FluentModelValidator<AmbiguousFluentPattern>(AmbiguousFluentPattern::class.java) {
    init { ruleFor("name").matches("[]]") }
}

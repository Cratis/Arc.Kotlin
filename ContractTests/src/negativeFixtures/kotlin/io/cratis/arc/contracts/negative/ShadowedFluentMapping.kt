// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative.shadowed

import io.cratis.arc.validation.FluentModelValidator
import kotlin.reflect.KClass

class ShadowedFluentModel(val name: String)
val KClass<ShadowedFluentModel>.java: Class<ShadowedFluentModel>
    get() { check(System.getProperty("allow.mapping") == "true"); return javaObjectType }
class ShadowedFluentRules : FluentModelValidator<ShadowedFluentModel>(ShadowedFluentModel::class.java) {
    init { ruleFor("name").minLength(2) }
}

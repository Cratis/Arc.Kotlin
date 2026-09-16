// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.Command
import io.cratis.arc.authorization.AllowAnonymous

@Command
@AllowAnonymous
data class ValidateFluent(val input: FluentInput?, val siblings: List<FluentInput>, val javaInput: JavaFluentInput?, val group: FluentGroup? = null) {
    fun handle(): String = "accepted"
}

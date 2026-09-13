// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.artifacts.ReadModel
import jakarta.validation.constraints.Size

// Intentionally ordinary consumer visibility, not the framework's explicit-public source style.
interface ImplicitNamed { val value: String }

@Command
class ImplicitVisibilityCommand(override val value: String) : ImplicitNamed {
    @CommandKey var id: String = "implicit-key"
    @get:Size(min = 2, max = 30)
    var title: String = "title"
        private set

    fun provide(): String = value + title
    fun handle(provided: String): String = provided + id
}

@ReadModel
data class ImplicitVisibilityView(override val value: String) : ImplicitNamed {
    companion object {
        fun findImplicit(value: String): ImplicitVisibilityView = ImplicitVisibilityView(value)
    }
}

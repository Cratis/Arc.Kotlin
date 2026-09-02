// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.artifacts.TreatWarningsAsErrors
import io.cratis.arc.authorization.Authorize
import io.cratis.arc.authorization.Roles

/** Value fetched by a suspending provide method. */
public data class KotlinSuspendProvided(public val commandId: String)

/** Kotlin command fixture with a suspending dependency-injected handler. */
@Command
@Authorize(policy = "orders", roles = ["operator"], schemes = ["bearer"])
@Roles("admin")
@TreatWarningsAsErrors
public data class KotlinSuspendCommand(
    @CommandKey public val commandId: String,
    public val optionalNote: String?
) {
    public suspend fun provide(): KotlinSuspendProvided = KotlinSuspendProvided(commandId)

    @Roles("auditor")
    public suspend fun handle(provided: KotlinSuspendProvided, dependency: KotlinSuspendDependency): String =
        dependency.respond(provided.commandId)
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.CommandKey
import io.cratis.arc.authorization.AllowAnonymous

/** Value fetched during command preparation. */
public data class KotlinRegularProvided(public val message: String)

/** Kotlin command fixture with a regular dependency-injected handler. */
@Command
@AllowAnonymous
public data class KotlinRegularCommand(
    @CommandKey public val commandId: String,
    public val message: String,
    public val optionalLabel: String?
) {
    public fun provide(): KotlinRegularProvided = KotlinRegularProvided(message)

    public fun handle(provided: KotlinRegularProvided, dependency: KotlinRegularDependency): String =
        dependency.respond(provided.message)
}

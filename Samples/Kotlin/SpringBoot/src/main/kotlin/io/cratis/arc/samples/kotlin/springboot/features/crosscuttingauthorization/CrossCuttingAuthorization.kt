// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.crosscuttingauthorization

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The read model a query filter guards without any annotation on this file.
 *
 * Nothing here says "authorized". The rule lives in `CrossCuttingAuthorizationQueryFilter`, which
 * matches on the package name — which is the whole idea of a cross-cutting rule: one place decides
 * for an entire feature, and adding a new query to the package inherits the rule for free.
 *
 * @property message A human-readable status message.
 * @property checkedAt The timestamp for when the status was generated.
 */
@ReadModel
public data class CrossCuttingAuthorizationStatus(
    public val message: String,
    public val checkedAt: OffsetDateTime
) {
    public companion object {
        /** Gets the current secured status. */
        @JvmStatic
        public fun secured(): CrossCuttingAuthorizationStatus = CrossCuttingAuthorizationStatus(
            "Query authorized and executed through a custom query filter.",
            OffsetDateTime.now()
        )
    }
}

/**
 * The command the matching command filter guards.
 *
 * @property message The message to echo back from the command execution.
 */
@Command
public data class RunSecuredCommand(public val message: String) {
    /** Handles the command. */
    public fun handle(): String {
        val stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        return "Command authorized and executed at $stamp — $message"
    }
}

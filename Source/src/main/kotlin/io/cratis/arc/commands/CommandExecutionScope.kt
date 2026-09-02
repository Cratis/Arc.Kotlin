// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult

/** Brackets command execution with a host-agnostic lifetime concern such as a transaction. */
public interface CommandExecutionScope {
    /** Begins synchronously before filters and the handler run. */
    public fun begin(context: CommandContext)

    /**
     * Completes in reverse begin order for every post-begin outcome.
     *
     * An immutable result fragment may be returned to amend the final outcome; `null` leaves it unchanged.
     */
    public suspend fun complete(context: CommandContext, result: CommandResult<*>): CommandResult<*>?
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult

/** Handles a control value returned by a generated command handler. */
public interface CommandResponseValueHandler {
    /** Returns whether this handler consumes [value] in the supplied [context]. */
    public fun canHandle(context: CommandContext, value: Any): Boolean

    /** Consumes [value] and returns an immutable result fragment. */
    public suspend fun handle(context: CommandContext, value: Any): CommandResult<*>
}

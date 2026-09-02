// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult

/** Filter applied before a generated command handler is invoked. */
public fun interface CommandFilter {
    /** Evaluates [context] and returns a result fragment to merge into the command outcome. */
    public suspend fun execute(context: CommandContext): CommandResult<*>
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult

/** Host-agnostic command execution pipeline. */
public interface CommandPipeline {
    /** Executes [command] using only the explicit [options]. */
    public suspend fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*>

    /** Runs command filters without scopes, response handling, or handler invocation. */
    public suspend fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*>
}

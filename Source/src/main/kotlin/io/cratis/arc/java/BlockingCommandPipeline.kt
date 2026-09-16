// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.results.CommandResult

/**
 * Caller-thread blocking facade; owns no executor, scope, or timeout. Prefer the suspending or async
 * pipeline inside coroutine work. Arc bounded work and reentrant blocking calls are rejected.
 *
 * Results and ordinary propagated failures/cancellation are unchanged. Any directly observed
 * InterruptedException in framework-managed callbacks of this invocation means cancellation,
 * including a propagated worker exception: this is not proof the caller was physically interrupted.
 * The facade waits for cooperative cleanup, sets the caller's interrupt flag and throws
 * CancellationException with the observed InterruptedException as its cause. Application-swallowed
 * interruption is undetectable. Application code owns deadlines and any interrupting caller task.
 * [boundOptions] enables short calls only with explicitly supplied context; no ambient lookup occurs.
 */
public class BlockingCommandPipeline @JvmOverloads constructor(
    private val pipeline: CommandPipeline,
    private val boundOptions: CommandExecutionOptions? = null
) {
    /** Executes with explicit per-call options, overriding any constructor-bound options. */
    public fun execute(command: Any, options: CommandExecutionOptions): CommandResult<*> =
        BlockingPipelineGuard.run { pipeline.execute(command, options) }

    /** Executes with constructor-bound options, or fails before execution if none were supplied. */
    public fun execute(command: Any): CommandResult<*> = execute(command, requireBoundOptions())

    /** Validates without invoking the command, with explicit per-call options. */
    public fun validate(command: Any, options: CommandExecutionOptions): CommandResult<*> =
        BlockingPipelineGuard.run { pipeline.validate(command, options) }

    /** Validates with constructor-bound options, or fails before execution if none were supplied. */
    public fun validate(command: Any): CommandResult<*> = validate(command, requireBoundOptions())

    private fun requireBoundOptions(): CommandExecutionOptions = checkNotNull(boundOptions) {
        "Supply CommandExecutionOptions per call or bind them explicitly in the BlockingCommandPipeline constructor."
    }
}

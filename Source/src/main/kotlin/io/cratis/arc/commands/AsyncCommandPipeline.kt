// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.java.launchStage
import io.cratis.arc.results.CommandResult
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Java-friendly bridge from the suspending [CommandPipeline] API to `CompletionStage`. */
public class AsyncCommandPipeline internal constructor(
    private val pipeline: CommandPipeline,
    private val coroutineScope: CoroutineScope
) {
    public companion object {
        /** Kotlin host-integration factory; Java callers should use JavaAsyncScope. */
        @JvmStatic
        @JvmSynthetic
        public fun fromCoroutineScope(
            pipeline: CommandPipeline,
            coroutineScope: CoroutineScope
        ): AsyncCommandPipeline = AsyncCommandPipeline(pipeline, coroutineScope)
    }

    /** Executes [command] asynchronously using the caller-owned coroutine scope. */
    public fun execute(command: Any, options: CommandExecutionOptions): CompletionStage<CommandResult<*>> =
        coroutineScope.launchStage { pipeline.execute(command, options) }

    /** Validates [command] asynchronously without invoking it. */
    public fun validate(command: Any, options: CommandExecutionOptions): CompletionStage<CommandResult<*>> =
        coroutineScope.launchStage { pipeline.validate(command, options) }
}

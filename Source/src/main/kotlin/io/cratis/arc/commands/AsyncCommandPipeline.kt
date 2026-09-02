// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.results.CommandResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
        launch { pipeline.execute(command, options) }

    /** Validates [command] asynchronously without invoking it. */
    public fun validate(command: Any, options: CommandExecutionOptions): CompletionStage<CommandResult<*>> =
        launch { pipeline.validate(command, options) }

    private fun launch(operation: suspend () -> CommandResult<*>): CompletionStage<CommandResult<*>> {
        val future = CompletableFuture<CommandResult<*>>()
        lateinit var job: Job
        job = coroutineScope.launch {
            try {
                future.complete(operation())
            } catch (exception: CancellationException) {
                future.cancel(false)
                throw exception
            } catch (exception: Exception) {
                future.completeExceptionally(exception)
            }
        }
        future.whenComplete { _, _ ->
            if (future.isCancelled) job.cancel()
        }
        job.invokeOnCompletion { cause ->
            if (cause != null && !future.isDone) {
                if (cause is CancellationException) future.cancel(false) else future.completeExceptionally(cause)
            }
        }
        return future
    }
}

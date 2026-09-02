// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.CommandScenarioResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Java `CompletionStage` bridge using a caller-owned bounded or structured [CoroutineScope]. */
public class AsyncCommandScenario<TCommand : Any>(
    private val scenario: CommandScenario<TCommand>,
    private val coroutineScope: CoroutineScope
) {
    /** Creates a bridge for an exact command from a generated module. */
    public constructor(
        module: ArcArtifactModule,
        commandType: Class<TCommand>,
        coroutineScope: CoroutineScope
    ) : this(CommandScenario(module, commandType), coroutineScope)

    /** Creates a bridge for one real manual handler. */
    public constructor(handler: CommandHandler, coroutineScope: CoroutineScope) :
        this(CommandScenario(handler), coroutineScope)

    /** Executes [command] asynchronously. Cancellation of the returned future cancels its child job. */
    public fun execute(command: TCommand): CompletionStage<CommandScenarioResult<Any?>> =
        launch { scenario.execute(command) }

    /** Validates [command] asynchronously without invoking the handler. */
    public fun validate(command: TCommand): CompletionStage<CommandScenarioResult<Any?>> =
        launch { scenario.validate(command) }

    private fun launch(
        operation: suspend () -> CommandScenarioResult<Any?>
    ): CompletionStage<CommandScenarioResult<Any?>> {
        val future = CompletableFuture<CommandScenarioResult<Any?>>()
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
        future.whenComplete { _, _ -> if (future.isCancelled) job.cancel() }
        job.invokeOnCompletion { cause ->
            if (cause != null && !future.isDone) {
                if (cause is CancellationException) future.cancel(false) else future.completeExceptionally(cause)
            }
        }
        return future
    }
}

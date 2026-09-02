// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.CommandScenarioResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/** Java blocking bridge backed by an owned, bounded coroutine scope. Close it after use. */
public class BlockingCommandScenario<TCommand : Any> @JvmOverloads constructor(
    private val scenario: CommandScenario<TCommand>,
    parallelism: Int = 1
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val dispatcher: ExecutorCoroutineDispatcher
    private val coroutineScope: CoroutineScope

    init {
        require(parallelism > 0) { "parallelism must be greater than zero" }
        dispatcher = Executors.newFixedThreadPool(parallelism) { runnable ->
            Thread(runnable, "arc-command-scenario").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        coroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    /** Creates a bridge for an exact command from a generated module. */
    @JvmOverloads
    public constructor(
        module: ArcArtifactModule,
        commandType: Class<TCommand>,
        parallelism: Int = 1
    ) : this(CommandScenario(module, commandType), parallelism)

    /** Creates a bridge for one real manual handler. */
    @JvmOverloads
    public constructor(handler: CommandHandler, parallelism: Int = 1) :
        this(CommandScenario(handler), parallelism)

    /** Executes [command] and blocks the calling thread for the result. */
    public fun execute(command: TCommand): CommandScenarioResult<Any?> = waitFor { scenario.execute(command) }

    /** Validates [command] without invoking its handler and blocks for the result. */
    public fun validate(command: TCommand): CommandScenarioResult<Any?> = waitFor { scenario.validate(command) }

    /** Cancels owned work and closes the bounded dispatcher. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            coroutineScope.cancel()
            dispatcher.close()
        }
    }

    private fun <T> waitFor(operation: suspend () -> T): T {
        check(!closed.get()) { "The blocking command scenario is closed." }
        val deferred = coroutineScope.async { operation() }
        return runBlocking { deferred.await() }
    }
}

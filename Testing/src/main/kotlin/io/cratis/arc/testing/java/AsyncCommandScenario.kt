// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.CommandScenarioResult
import io.cratis.arc.java.JavaAsyncScope
import io.cratis.arc.java.launchStage
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Java `CompletionStage` bridge borrowing a caller-owned scope; close the owner explicitly. */
public class AsyncCommandScenario<TCommand : Any> private constructor(
    private val scenario: CommandScenario<TCommand>,
    private val launch: (suspend () -> CommandScenarioResult<Any?>) -> CompletionStage<CommandScenarioResult<Any?>>
) {
    /** Borrows a Kotlin host's bounded or structured scope. */
    public constructor(scenario: CommandScenario<TCommand>, coroutineScope: CoroutineScope) :
        this(scenario, { coroutineScope.launchStage(it) })

    /** Borrows a Java owner. Calls after owner close return cancelled stages without executing user code. */
    public constructor(scenario: CommandScenario<TCommand>, owner: JavaAsyncScope) :
        this(scenario, { owner.launchStage(it) })

    /** Creates a bridge for an exact command from a generated module, borrowing [owner]. */
    public constructor(module: ArcArtifactModule, commandType: Class<TCommand>, owner: JavaAsyncScope) :
        this(CommandScenario(module, commandType), owner)

    /** Creates a bridge for one real manual handler, borrowing [owner]. */
    public constructor(handler: CommandHandler, owner: JavaAsyncScope) : this(CommandScenario(handler), owner)
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
}

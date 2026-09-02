// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.artifacts.ArcArtifactModuleRegistry
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryPerformer

/** Clear setup failure raised before a scenario enters an Arc pipeline. */
public class ScenarioSetupException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Registers complete generated modules in the real Arc registries and selects artifacts by exact identity.
 *
 * This helper deliberately has no fallback handler or performer. A typo or stale generated module fails setup.
 */
public class ScenarioArtifactRegistry {
    internal val commandHandlers = ConcurrentCommandHandlerRegistry()
    internal val queryPerformers = ConcurrentQueryPerformerRegistry()

    /** Registers every command and query in [module], rejecting duplicate exact identities. */
    public fun register(module: ArcArtifactModule): ScenarioArtifactRegistry {
        try {
            ArcArtifactModuleRegistry.register(module, commandHandlers, queryPerformers)
        } catch (exception: IllegalStateException) {
            throw ScenarioSetupException(
                "The Arc artifact module '${module.javaClass.name}' could not be registered: ${exception.message}",
                exception
            )
        }
        return this
    }

    /** Registers one real manual [handler], primarily for framework and extension tests. */
    public fun register(handler: CommandHandler): ScenarioArtifactRegistry {
        try {
            commandHandlers.register(handler)
        } catch (exception: IllegalStateException) {
            throw ScenarioSetupException("The command handler could not be registered: ${exception.message}", exception)
        }
        return this
    }

    /** Registers one real manual [performer], primarily for framework and extension tests. */
    public fun register(performer: QueryPerformer): ScenarioArtifactRegistry {
        try {
            queryPerformers.register(performer)
        } catch (exception: IllegalStateException) {
            throw ScenarioSetupException("The query performer could not be registered: ${exception.message}", exception)
        }
        return this
    }

    /** Selects the single handler registered for the exact [commandType]. */
    public fun <T : Any> command(commandType: Class<T>): CommandHandler =
        commandHandlers.find(commandType) ?: throw ScenarioSetupException(
            "No command handler for '${commandType.name}' exists in the registered Arc artifact module(s). " +
                "Regenerate the module or select the exact command class."
        )

    /** Selects the single performer registered for the exact [queryName]. */
    public fun query(queryName: FullyQualifiedQueryName): QueryPerformer =
        queryPerformers.find(queryName) ?: throw ScenarioSetupException(
            "No query performer for '$queryName' exists in the registered Arc artifact module(s). " +
                "Regenerate the module or select the exact fully qualified query name."
        )
}

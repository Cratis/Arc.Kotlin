// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ArcOneOf
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.CommandResponseValues
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.results.CommandResult
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.identity.Identity
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Allows a Chronicle reactor to declare the exact system roles used by returned Arc commands. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class ExecuteCommandsAsSystem(vararg val roles: String)

/**
 * Executes a Chronicle reactor's returned Arc command values sequentially through [CommandPipeline].
 *
 * Reactors inject this handler and pass their returned value explicitly. No ambient identity is consulted:
 * an unannotated reactor
 * uses the event's causing identity with no roles, while an annotated reactor gets only its declared
 * system roles.
 */
public class ChronicleCommandSideEffectHandler(
    private val pipeline: CommandPipeline,
    private val commandHandlers: CommandHandlerRegistry,
    private val serviceResolver: ServiceResolver,
    private val coroutineScope: CoroutineScope
) {
    /** Whether [value] is one Arc command or a non-empty homogeneous command collection. */
    public fun canHandle(value: Any?): Boolean = commands(value) != null

    /** Executes [value], stopping and returning the first failed command result. */
    public suspend fun execute(
        value: Any?,
        reactorType: Class<*>,
        eventContext: EventContext
    ): CommandResult<*> {
        val commands = commands(value) ?: return CommandResult.error(
            eventContext.correlationId,
            "The Chronicle side effect is not a registered Arc command or command collection."
        )
        val principal = principalFor(reactorType, eventContext)
        val namespace = eventContext.namespace.takeIf(String::isNotBlank)
        val options = CommandExecutionOptions(
            correlationId = eventContext.correlationId,
            principal = principal,
            serviceResolver = ChronicleEventCausationServiceResolver(
                serviceResolver,
                eventContext.causation
            ),
            tenantId = namespace,
            tenantNamespace = namespace
        )
        commands.forEach { command ->
            val result = pipeline.execute(command, options)
            if (!result.isSuccess) return result
        }
        return CommandResult.success(eventContext.correlationId)
    }

    /** Java-friendly asynchronous form of [execute]. */
    public fun executeAsync(
        value: Any?,
        reactorType: Class<*>,
        eventContext: EventContext
    ): CompletionStage<CommandResult<*>> = coroutineScope.asCompletionStage {
        execute(value, reactorType, eventContext)
    }

    private fun commands(value: Any?): List<Any>? {
        val commands = mutableListOf<Any>()

        fun collect(candidate: Any?): Boolean = when (candidate) {
            null, Unit -> false
            is ArcOneOf<*> -> collect(candidate.value)
            is CommandResponseValues -> candidate.values.isNotEmpty() && candidate.values.all(::collect)
            is Pair<*, *> -> collect(candidate.first) && collect(candidate.second)
            is Triple<*, *, *> -> collect(candidate.first) && collect(candidate.second) && collect(candidate.third)
            is Iterable<*> -> {
                val values = candidate.toList()
                values.isNotEmpty() && values.all(::collect)
            }
            is Array<*> -> candidate.isNotEmpty() && candidate.all(::collect)
            else -> if (commandHandlers.find(candidate.javaClass) != null) {
                commands.add(candidate)
                true
            } else {
                false
            }
        }

        return commands.takeIf { collect(value) && it.isNotEmpty() }
    }

    private fun principalFor(reactorType: Class<*>, eventContext: EventContext): ArcPrincipal {
        val system = reactorType.getAnnotation(ExecuteCommandsAsSystem::class.java)
        if (system != null) {
            val roles = system.roles.map(String::trim)
            require(roles.all(String::isNotEmpty)) { "ExecuteCommandsAsSystem roles cannot be blank." }
            return ArcPrincipal(
                name = Identity.system.name,
                isAuthenticated = true,
                roles = LinkedHashSet(roles),
                id = Identity.system.subject,
                authenticationScheme = "ChronicleSystem"
            )
        }
        val identity = eventContext.causedBy
        val authenticated = identity != Identity.notSet && identity != Identity.unknown && identity.subject.isNotBlank()
        return ArcPrincipal(
            name = identity.name,
            isAuthenticated = authenticated,
            roles = emptySet(),
            id = identity.subject,
            authenticationScheme = "ChronicleEvent"
        )
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.queries.CanResolveReadModelForCommand

/** Adds an integration-specific policy to a command scenario without coupling arc-testing to that integration. */
public fun interface CommandScenarioExtender {
    /** Configures the scenario through its restricted extension context. */
    public fun extend(context: CommandScenarioExtensionContext)
}

/** Restricted policy seam exposed to command-scenario integrations. */
public class CommandScenarioExtensionContext internal constructor(
    /** The exact real handler registry used by this scenario. */
    public val commandHandlers: CommandHandlerRegistry,
    private val scopeAdder: (CommandExecutionScope) -> Unit,
    private val responseHandlerAdder: (CommandResponseValueHandler) -> Unit,
    private val readModelResolverAdder: (CanResolveReadModelForCommand) -> Unit,
    private val serviceAdder: (Class<*>, Any) -> Unit,
    private val policyAdder: (String, AuthorizationPolicy) -> Unit
) {
    /** Adds an execution scope. */
    public fun addScope(scope: CommandExecutionScope) {
        scopeAdder.invoke(scope)
    }

    /** Adds a response handler. */
    public fun addResponseHandler(handler: CommandResponseValueHandler) {
        responseHandlerAdder.invoke(handler)
    }

    /** Adds a command-side read-model resolver. */
    public fun addReadModelResolver(resolver: CanResolveReadModelForCommand) {
        readModelResolverAdder.invoke(resolver)
    }

    /** Adds one exact service registration to the scenario. */
    public fun <T : Any> addService(type: Class<T>, service: T) {
        serviceAdder.invoke(type, service)
    }

    /** Adds a named authorization policy to the scenario. */
    public fun addPolicy(name: String, policy: AuthorizationPolicy) {
        policyAdder.invoke(name, policy)
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.metadata.CommandDescriptor

/** Build-time generated, reflection-free invoker for one command type. */
public interface CommandHandler {
    /** Exact command type accepted by this handler. */
    public val commandType: Class<*>

    /** Generated command metadata used by hosts and filters. */
    public val metadata: CommandDescriptor

    /** Whether the command explicitly allows an unauthenticated caller. */
    public val allowsAnonymous: Boolean
        get() = metadata.authorization.allowAnonymous

    /**
     * Resolves the command key without reflection.
     *
     * Generated handlers override this for a single [io.cratis.arc.artifacts.CommandKey] property. Commands without
     * such a property can implement [CommandKeyProvider]; all other commands legitimately have no key.
     */
    public fun resolveCommandKey(command: Any): Any? = (command as? CommandKeyProvider)?.commandKey()

    /**
     * Runs the generated reflection-free `provide` phase.
     *
     * The default preserves source compatibility for manual and previously generated handlers.
     */
    public suspend fun prepare(context: CommandContext): CommandPreparation =
        CommandPreparation.empty(context.correlationId)

    /** Invokes the generated handler with the explicit execution [context]. */
    public suspend fun invoke(context: CommandContext): Any?
}

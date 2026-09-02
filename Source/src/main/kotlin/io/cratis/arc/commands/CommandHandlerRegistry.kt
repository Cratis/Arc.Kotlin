// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Registry of generated command invokers. */
public interface CommandHandlerRegistry {
    /** Monotonic version incremented after every successful registration. */
    public val version: Long
        get() = 0L

    /** Registers [handler], rejecting every duplicate command type. */
    public fun register(handler: CommandHandler)

    /** Finds the handler registered for [commandType]. */
    public fun find(commandType: Class<*>): CommandHandler?

    /** Returns an immutable snapshot sorted deterministically by command type name. */
    public fun snapshot(): List<CommandHandler>
}

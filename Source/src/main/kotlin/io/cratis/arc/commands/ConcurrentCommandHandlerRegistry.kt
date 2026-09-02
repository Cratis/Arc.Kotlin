// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Thread-safe command handler registry with exact-type lookup. */
public class ConcurrentCommandHandlerRegistry : CommandHandlerRegistry {
    private val handlers = ConcurrentHashMap<Class<*>, CommandHandler>()
    private val registryVersion = AtomicLong()

    override val version: Long
        get() = registryVersion.get()

    override fun register(handler: CommandHandler) {
        val existing = handlers.putIfAbsent(handler.commandType, handler)
        if (existing != null) throw DuplicateCommandHandlerException(handler.commandType)
        registryVersion.incrementAndGet()
    }

    override fun find(commandType: Class<*>): CommandHandler? = handlers[commandType]

    override fun snapshot(): List<CommandHandler> =
        java.util.List.copyOf(handlers.values.sortedBy { it.commandType.name })
}

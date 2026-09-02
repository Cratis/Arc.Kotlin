// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import java.util.Optional
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Request-local invocation SPI used by generated command handlers.
 *
 * Its suspending resolution methods are public because generated handlers live in consumer packages. Ordinary Java
 * applications use those generated handlers through [CommandPipeline]; they do not call this coroutine SPI directly.
 */
public class CommandHandlerArgumentResolver(private val context: CommandContext) {
    private val candidates = context.providedValues.toMutableList()

    /** Generated-handler SPI resolving a required argument from infrastructure, provided values, read models, or services. */
    public suspend fun <T : Any> resolve(
        type: Class<T>,
        methodName: String,
        parameterName: String
    ): T {
        resolveInfrastructure(type)?.let { return it }
        provided(type)?.let { return it }
        val readModels = ownedReadModels(type)
        if (readModels != null) {
            requireCommandKey(type, methodName, parameterName)
            val resolved = readModels.resolve(type, context)
            if (resolved != null) return type.cast(resolved)
            throw CommandDependencyUnavailable(context.commandType, methodName, parameterName, type)
        }
        context.serviceResolver.resolve(type)?.let { return it }
        throw CannotResolveCommandDependency(context.commandType, methodName, parameterName, type)
    }

    /** Generated-handler SPI for a nullable Kotlin read-model argument; only owned absence resolves to `null`. */
    public suspend fun <T : Any> resolveNullable(
        type: Class<T>,
        methodName: String,
        parameterName: String
    ): T? {
        provided(type)?.let { return it }
        val readModels = ownedReadModels(type)
        if (readModels != null) {
            requireCommandKey(type, methodName, parameterName)
            return readModels.resolve(type, context)?.let(type::cast)
        }
        context.serviceResolver.resolve(type)?.let { return it }
        throw CannotResolveCommandDependency(context.commandType, methodName, parameterName, type)
    }

    /** Generated-handler SPI for Java `Optional<T>`; only owned read-model absence resolves to empty. */
    public suspend fun <T : Any> resolveOptional(
        type: Class<T>,
        methodName: String,
        parameterName: String
    ): Optional<T> {
        provided(type)?.let { return Optional.of(it) }
        val readModels = ownedReadModels(type)
        if (readModels != null) {
            requireCommandKey(type, methodName, parameterName)
            return Optional.ofNullable(readModels.resolve(type, context)?.let(type::cast))
        }
        context.serviceResolver.resolve(type)?.let { return Optional.of(it) }
        throw CannotResolveCommandDependency(context.commandType, methodName, parameterName, type)
    }

    private suspend fun <T : Any> resolveInfrastructure(type: Class<T>): T? {
        val coroutineContext = currentCoroutineContext()
        if (type == CoroutineContext::class.java) {
            @Suppress("UNCHECKED_CAST")
            return coroutineContext as T
        }
        if (type == Job::class.java) {
            @Suppress("UNCHECKED_CAST")
            return (coroutineContext[Job] ?: error("The command coroutine has no Job.")) as T
        }
        return null
    }

    private fun <T : Any> provided(type: Class<T>): T? {
        val index = candidates.indexOfFirst { candidate -> boxed(type).isInstance(candidate) }
        if (index < 0) return null
        return type.cast(candidates.removeAt(index))
    }

    private fun ownedReadModels(type: Class<*>): ReadModelForCommandResolverRegistry? =
        context.serviceResolver.resolve(ReadModelForCommandResolverRegistry::class.java)
            ?.takeIf { registry -> registry.contains(type) }

    private fun requireCommandKey(type: Class<*>, methodName: String, parameterName: String) {
        if (context.commandKey == null) {
            throw CommandDependencyUnavailable(context.commandType, methodName, parameterName, type)
        }
    }

    private fun boxed(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }
}

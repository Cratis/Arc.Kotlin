// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.await
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Strength with which a provider claims a command-side read-model type. */
public enum class ReadModelForCommandOwnership {
    /** An application artifact explicitly declares that the provider owns the type. */
    DECLARED,

    /** The provider fills a type only when no declaring provider exists. */
    FALLBACK
}

/** Resolves owned read models by the current command's generated key without depending on a storage technology. */
public interface CanResolveReadModelForCommand {
    /** Types this provider can resolve. */
    public fun readModelTypes(): Set<Class<*>>

    /** Ownership strength for every reported type. */
    public fun ownership(): ReadModelForCommandOwnership

    /** Asynchronously resolves [readModelType] for [key], returning null when the keyed model does not exist. */
    public fun resolve(
        readModelType: Class<*>,
        commandContext: CommandContext,
        key: Any
    ): CompletionStage<Any?>
}

/** Kotlin property view of the read-model types this provider can resolve. */
@get:JvmSynthetic
public val CanResolveReadModelForCommand.types: Set<Class<*>>
    get() = readModelTypes()

/** Kotlin property view of this provider's ownership strength. */
@get:JvmSynthetic
public val CanResolveReadModelForCommand.ownership: ReadModelForCommandOwnership
    get() = ownership()

/** Blocking provider convenience for stores with blocking lookup APIs. */
public interface BlockingReadModelForCommandResolver : CanResolveReadModelForCommand {
    /** Resolves synchronously. */
    public fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any?

    override fun resolve(
        readModelType: Class<*>,
        commandContext: CommandContext,
        key: Any
    ): CompletionStage<Any?> {
        val future = CompletableFuture<Any?>()
        try {
            future.complete(resolveBlocking(readModelType, commandContext, key))
        } catch (_: CancellationException) {
            future.cancel(false)
        } catch (exception: Exception) {
            future.completeExceptionally(exception)
        }
        return future
    }
}

/** Raised when equally strong providers claim the same command-side read-model type. */
public class MultipleReadModelResolversForCommandException private constructor(
    public val readModelType: Class<*>,
    message: String
) : IllegalStateException(message) {
    public constructor(readModelType: Class<*>) : this(
        readModelType,
        "Multiple command-side read-model resolvers claim '${readModelType.name}' with equal ownership."
    )

    internal constructor(
        readModelType: Class<*>,
        conflictingResolvers: Collection<CanResolveReadModelForCommand>
    ) : this(
        readModelType,
        "Multiple command-side read-model resolvers claim '${readModelType.name}' with equal ownership: " +
            conflictingResolvers.map { it.javaClass.name }.sorted().joinToString() + "."
    )
}

/** Raised when a read model is requested for a command without a usable generated or explicit command key. */
public class UnableToResolveReadModelFromCommandContext(public val readModelType: Class<*>) : IllegalStateException(
    "Cannot resolve read model '${readModelType.name}' because the command context has no key."
)

/** Immutable ownership-arbitrating registry for command-side read-model resolvers. */
public class ReadModelForCommandResolverRegistry(
    resolvers: Iterable<CanResolveReadModelForCommand> = emptyList()
) {
    private val owners: Map<Class<*>, CanResolveReadModelForCommand> = arbitrate(resolvers.toList())

    /** Whether a provider owns [readModelType]. */
    public fun contains(readModelType: Class<*>): Boolean = owners.containsKey(readModelType)

    /** Snapshot of all owned types. */
    public fun readModelTypes(): Set<Class<*>> = java.util.Collections.unmodifiableSet(owners.keys)

    /** Resolves asynchronously for Java callers. */
    public fun resolveAsync(readModelType: Class<*>, context: CommandContext): CompletionStage<Any?> {
        val resolver = owners[readModelType] ?: return CompletableFuture.completedFuture(null)
        val key = context.commandKey ?: return CompletableFuture.failedFuture(
            UnableToResolveReadModelFromCommandContext(readModelType)
        )
        return try {
            resolver.resolve(readModelType, context, key)
        } catch (_: CancellationException) {
            CompletableFuture<Any?>().apply { cancel(false) }
        } catch (exception: Exception) {
            CompletableFuture.failedFuture(exception)
        }
    }

    /** Resolves with blocking semantics for Java and framework adapters. */
    public fun resolveBlocking(readModelType: Class<*>, context: CommandContext): Any? =
        resolveAsync(readModelType, context).toCompletableFuture().join()

    /** Resolves without blocking the command coroutine. */
    public suspend fun resolve(readModelType: Class<*>, context: CommandContext): Any? =
        resolveAsync(readModelType, context).await()

    private fun arbitrate(resolvers: List<CanResolveReadModelForCommand>): Map<Class<*>, CanResolveReadModelForCommand> {
        val claims = linkedMapOf<Class<*>, MutableList<CanResolveReadModelForCommand>>()
        resolvers.forEach { resolver ->
            resolver.readModelTypes().forEach { type -> claims.getOrPut(type) { mutableListOf() }.add(resolver) }
        }
        return claims.entries
            .sortedBy { it.key.name }
            .associate { (type, candidates) ->
                val declared = candidates.filter { it.ownership() == ReadModelForCommandOwnership.DECLARED }
                val winners = if (declared.isNotEmpty()) declared else candidates
                if (winners.size != 1) throw MultipleReadModelResolversForCommandException(type, winners)
                type to winners.single()
            }
    }
}

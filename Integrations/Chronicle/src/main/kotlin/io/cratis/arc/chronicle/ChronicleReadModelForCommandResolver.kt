// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.queries.CanResolveReadModelForCommand
import io.cratis.arc.queries.ReadModelForCommandOwnership
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Resolves Chronicle-owned command read models by the command key through `IReadModelsService`. */
public class ChronicleReadModelForCommandResolver @JvmOverloads constructor(
    readModelTypes: Iterable<Class<*>>,
    private val eventStoreResolver: TenantEventStoreResolver,
    private val coroutineScope: CoroutineScope,
    private val ownership: ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
) : CanResolveReadModelForCommand {
    private val types: Set<Class<*>> = java.util.Collections.unmodifiableSet(LinkedHashSet(readModelTypes.toList()))

    /** Creates a resolver backed by one tenant-safe event store. */
    @JvmOverloads
    public constructor(
        readModelTypes: Iterable<Class<*>>,
        eventStore: io.cratis.chronicle.IEventStore,
        coroutineScope: CoroutineScope,
        ownership: ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
    ) : this(readModelTypes, tenantEventStoreResolver(eventStore), coroutineScope, ownership)

    init {
        require(types.none(Class<*>::isPrimitive)) { "Chronicle read-model types cannot be primitive." }
    }

    override fun readModelTypes(): Set<Class<*>> = types

    override fun ownership(): ReadModelForCommandOwnership = ownership

    override fun resolve(
        readModelType: Class<*>,
        commandContext: CommandContext,
        key: Any
    ): CompletionStage<Any?> {
        if (readModelType !in types) return CompletableFuture.completedFuture(null)
        val eventSourceId = key.toChronicleKey()
            ?: return CompletableFuture.failedFuture(
                IllegalArgumentException(
                    "Command key for '${readModelType.name}' must be backed by a nonblank String, UUID, or number."
                )
            )
        val eventStore = try {
            eventStoreResolver.resolve(commandContext.tenantNamespace)?.takeIf { store ->
                commandContext.tenantNamespace == null || store.namespace == commandContext.tenantNamespace
            }
        } catch (exception: Exception) {
            return CompletableFuture.failedFuture(exception)
        } ?: return CompletableFuture.failedFuture(
            IllegalStateException("A Chronicle event store is unavailable for the command tenant namespace.")
        )

        return coroutineScope.asCompletionStage {
            @Suppress("UNCHECKED_CAST")
            eventStore.readModels.getInstanceByKey(readModelType.kotlin as kotlin.reflect.KClass<Any>, eventSourceId)
        }
    }
}

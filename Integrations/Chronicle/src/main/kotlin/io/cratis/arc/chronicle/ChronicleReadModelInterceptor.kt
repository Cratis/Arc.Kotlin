// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.queries.InterceptReadModel
import io.cratis.arc.queries.QueryContext
import io.cratis.chronicle.IEventStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Releases compliance-protected values for Chronicle read models before Arc serves them. */
public class ChronicleReadModelInterceptor(
    readModelTypes: Iterable<Class<*>>,
    private val eventStoreResolver: TenantEventStoreResolver,
    private val coroutineScope: CoroutineScope
) : InterceptReadModel<Any> {
    private val types: Set<Class<*>> = java.util.Collections.unmodifiableSet(LinkedHashSet(readModelTypes.toList()))

    /** Creates an interceptor backed by one tenant-safe event store. */
    public constructor(
        readModelTypes: Iterable<Class<*>>,
        eventStore: IEventStore,
        coroutineScope: CoroutineScope
    ) : this(readModelTypes, tenantEventStoreResolver(eventStore), coroutineScope)

    override fun readModelType(): Class<Any> = Any::class.java

    override fun intercept(readModel: Any, context: QueryContext): CompletionStage<Any> {
        if (readModel.javaClass !in types) return CompletableFuture.completedFuture(readModel)
        val eventStore = try {
            eventStoreResolver.resolve(context.tenantNamespace)?.takeIf { store ->
                context.tenantNamespace == null || store.namespace == context.tenantNamespace
            }
        } catch (exception: Exception) {
            return CompletableFuture.failedFuture(exception)
        } ?: return CompletableFuture.failedFuture(
            IllegalStateException("A Chronicle event store is unavailable for the query tenant namespace.")
        )
        return coroutineScope.asCompletionStage { eventStore.readModels.release(readModel) }
    }
}

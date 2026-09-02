// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.chronicle

import io.cratis.arc.chronicle.TenantEventStoreResolver

/** Reads task projections from one explicit Chronicle namespace. */
public interface TaskViewReader {
    /** Gets one task by identifier. */
    public suspend fun byId(namespace: String, id: String): TaskView?

    /** Gets every task in the namespace. */
    public suspend fun all(namespace: String): List<TaskView>
}

internal class ChronicleTaskViewReader(
    private val eventStoreResolver: TenantEventStoreResolver
) : TaskViewReader {
    override suspend fun byId(namespace: String, id: String): TaskView? =
        eventStore(namespace).readModels.getInstanceByKey(TaskView::class, id)

    override suspend fun all(namespace: String): List<TaskView> =
        eventStore(namespace).readModels.getInstances(TaskView::class)

    private fun eventStore(namespace: String) = requireNotNull(eventStoreResolver.resolve(namespace)) {
        "Chronicle event store is unavailable for tenant namespace '$namespace'."
    }.also { eventStore ->
        check(eventStore.namespace == namespace) {
            "Chronicle event store namespace '${eventStore.namespace}' does not match '$namespace'."
        }
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * In-process notifier that coalesces duplicate changes in a transaction and publishes them only after commit.
 * Cross-process delivery requires an application-provided [DatabaseChangeNotifier]; polling is never enabled implicitly.
 */
public class TransactionAwareDatabaseChangeNotifier @JvmOverloads constructor(
    options: JpaObservationOptions = JpaObservationOptions()
) : DatabaseChangeNotifier, DatabaseChangePublisher {
    private val changes = MutableSharedFlow<DatabaseChange>(
        replay = 0,
        extraBufferCapacity = options.bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val resourceKey = Any()

    override fun changes(entityType: Class<*>, tenantId: String?): Flow<DatabaseChange> = changes.filter { change ->
        change.entityType == entityType && change.tenantId == tenantId
    }

    override fun publish(entityType: Class<*>, tenantId: String?) {
        val change = DatabaseChange(entityType, tenantId)
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            changes.tryEmit(change)
            return
        }

        @Suppress("UNCHECKED_CAST")
        val pending = TransactionSynchronizationManager.getResource(resourceKey) as? MutableSet<DatabaseChange>
        if (pending != null) {
            pending.add(change)
            return
        }

        val transactionChanges = linkedSetOf(change)
        TransactionSynchronizationManager.bindResource(resourceKey, transactionChanges)
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                transactionChanges.forEach(changes::tryEmit)
            }

            override fun afterCompletion(status: Int) {
                if (TransactionSynchronizationManager.hasResource(resourceKey)) {
                    TransactionSynchronizationManager.unbindResource(resourceKey)
                }
                transactionChanges.clear()
            }
        })
    }

    /** Java Publisher bridge for committed changes. */
    @JvmOverloads
    public fun changesPublisher(entityType: Class<*>, tenantId: String? = null): java.util.concurrent.Flow.Publisher<DatabaseChange> =
        DemandAwareFlowPublisher(changes(entityType, tenantId))
}

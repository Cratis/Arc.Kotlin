// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class DatabaseChangeNotifierTests {
    @AfterEach
    fun tearDownSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `changes are coalesced and published in order only after commit for the captured tenant`() = runBlocking {
        val notifier = TransactionAwareDatabaseChangeNotifier(
            JpaObservationOptions(Duration.ZERO, bufferCapacity = 2)
        )
        val changes = notifier.changes(JpaTaskReadModel::class.java, "tenant-a").produceIn(this)
        delay(25)
        TransactionSynchronizationManager.initSynchronization()

        notifier.publish(JpaTaskReadModel::class.java, "tenant-a")
        notifier.publish(JpaTaskReadModel::class.java, "tenant-a")
        notifier.publish(JpaTaskReadModel::class.java, "tenant-b")
        assertTrue(changes.tryReceive().isFailure)

        val synchronizations = TransactionSynchronizationManager.getSynchronizations()
        synchronizations.forEach(TransactionSynchronization::afterCommit)
        synchronizations.forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
        TransactionSynchronizationManager.clearSynchronization()

        assertEquals("tenant-a", changes.receive().tenantId)
        assertTrue(changes.tryReceive().isFailure)
        changes.cancel()
    }

    @Test
    fun `rollback and cancellation do not deliver changes`() = runBlocking {
        val notifier = TransactionAwareDatabaseChangeNotifier()
        val changes = notifier.changes(JpaTaskReadModel::class.java, null).produceIn(this)
        delay(25)
        TransactionSynchronizationManager.initSynchronization()
        notifier.publish(JpaTaskReadModel::class.java, null)

        TransactionSynchronizationManager.getSynchronizations()
            .forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
        TransactionSynchronizationManager.clearSynchronization()
        changes.cancel()

        notifier.publish(JpaTaskReadModel::class.java, null)
        assertTrue(changes.tryReceive().isFailure)
    }
}

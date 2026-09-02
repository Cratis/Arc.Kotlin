// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory

class JpaObservationTests {
    @Test
    fun `snapshot reads do not run unbounded ahead of a slow collector`() = runBlocking {
        val entityManagerFactory = mock(EntityManagerFactory::class.java)
        val entityManager = mock(EntityManager::class.java)
        `when`(entityManagerFactory.createEntityManager()).thenReturn(entityManager)
        val notifier = DatabaseChangeNotifier { entityType, tenantId ->
            flow {
                while (currentCoroutineContext().isActive) emit(DatabaseChange(entityType, tenantId))
            }
        }
        val reads = AtomicInteger()
        val queries = JpaObservableQuery(
            entityManagerFactory,
            notifier,
            JpaObservationOptions(Duration.ZERO, bufferCapacity = 1)
        )
        val receivedFirst = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collection = launch {
            queries.observe(
                JpaTaskReadModel::class.java,
                JpaSnapshotQuery {
                    val read = reads.incrementAndGet()
                    listOf(JpaTaskReadModel(read.toString(), "Read $read"))
                }
            ).collect {
                receivedFirst.complete(Unit)
                releaseCollector.await()
            }
        }

        withTimeout(2_000) { receivedFirst.await() }
        delay(50)
        assertTrue(reads.get() <= 2, "Read ${reads.get()} snapshots while the collector was blocked")
        collection.cancelAndJoin()
        verify(entityManager, atLeastOnce()).close()
        queries.close()
    }
}

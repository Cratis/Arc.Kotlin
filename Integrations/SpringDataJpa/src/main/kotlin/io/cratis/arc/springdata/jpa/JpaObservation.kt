// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import java.time.Duration
import java.util.concurrent.Flow as JdkFlow
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** Identifies a committed database change relevant to a JPA observable query. */
public data class DatabaseChange(
    public val entityType: Class<*>,
    public val tenantId: String?
)

/** SPI for committed database changes. Implementations may bridge database-native notifications. */
public fun interface DatabaseChangeNotifier {
    /** Returns a cold view of changes for [entityType] and the captured [tenantId]. */
    public fun changes(entityType: Class<*>, tenantId: String?): Flow<DatabaseChange>
}

/** Publishes local JPA changes. Transaction-aware implementations delay delivery until commit. */
public fun interface DatabaseChangePublisher {
    /** Publishes a change for [entityType] in [tenantId]. */
    public fun publish(entityType: Class<*>, tenantId: String?)
}

/** Java-friendly snapshot callback executed with a short-lived entity manager. */
public fun interface JpaSnapshotQuery<T : Any> {
    /** Reads one complete snapshot. */
    public fun execute(entityManager: EntityManager): List<T>
}

/** Configures bounded notification buffering and snapshot coalescing. */
public data class JpaObservationOptions @JvmOverloads constructor(
    public val coalesceWindow: Duration = Duration.ofMillis(25),
    public val bufferCapacity: Int = 64
) {
    init {
        require(!coalesceWindow.isNegative)
        require(bufferCapacity > 0)
    }
}

/** Injectable JPA Flow helpers intended for `@ReadModel` query methods through `@FromServices`. */
public class JpaObservableQuery @JvmOverloads constructor(
    private val entityManagerFactory: EntityManagerFactory,
    private val notifier: DatabaseChangeNotifier,
    private val options: JpaObservationOptions = JpaObservationOptions(),
    private val callbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : AutoCloseable {
    /** Emits an initial snapshot and complete replacement snapshots after committed changes. */
    @JvmOverloads
    public fun <T : Any> observe(
        entityType: Class<T>,
        query: JpaSnapshotQuery<T> = JpaSnapshotQuery { entityManager ->
            entityManager.createQuery("select entity from ${entityName(entityManager, entityType)} entity", entityType)
                .resultList
        },
        tenantId: String? = null
    ): Flow<List<T>> = snapshots(entityType, query, tenantId)

    /** Alias emphasizing that each emission is a complete list snapshot. */
    @JvmOverloads
    public fun <T : Any> observeList(
        entityType: Class<T>,
        query: JpaSnapshotQuery<T> = JpaSnapshotQuery { entityManager ->
            entityManager.createQuery("select entity from ${entityName(entityManager, entityType)} entity", entityType)
                .resultList
        },
        tenantId: String? = null
    ): Flow<List<T>> = observe(entityType, query, tenantId)

    /** Emits the first item in each non-empty snapshot. Absence is not emitted. */
    @JvmOverloads
    public fun <T : Any> observeSingle(
        entityType: Class<T>,
        query: JpaSnapshotQuery<T> = JpaSnapshotQuery { entityManager ->
            entityManager.createQuery("select entity from ${entityName(entityManager, entityType)} entity", entityType)
                .setMaxResults(1)
                .resultList
        },
        tenantId: String? = null
    ): Flow<T> = snapshots(entityType, query, tenantId).mapNotNull(List<T>::firstOrNull).distinctUntilChanged()

    /** Emits the entity with [id] after the initial lookup and relevant committed changes. */
    @JvmOverloads
    public fun <T : Any> observeById(entityType: Class<T>, id: Any, tenantId: String? = null): Flow<T> =
        snapshots(entityType, JpaSnapshotQuery { entityManager -> listOfNotNull(entityManager.find(entityType, id)) }, tenantId)
            .mapNotNull(List<T>::firstOrNull)
            .distinctUntilChanged()

    /** Shares one cold observation while subscribers exist and replays its latest snapshot. */
    @JvmOverloads
    public fun <T : Any> observeShared(
        scope: CoroutineScope,
        entityType: Class<T>,
        query: JpaSnapshotQuery<T>,
        tenantId: String? = null
    ): SharedFlow<List<T>> = observe(entityType, query, tenantId)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Java bridge with demand-aware backpressure. */
    @JvmOverloads
    public fun <T : Any> observePublisher(
        entityType: Class<T>,
        query: JpaSnapshotQuery<T>,
        tenantId: String? = null
    ): JdkFlow.Publisher<List<T>> = DemandAwareFlowPublisher(observe(entityType, query, tenantId))

    /** Java callback bridge. Closing the result cancels collection immediately. */
    @JvmOverloads
    public fun <T : Any> observe(
        entityType: Class<T>,
        query: JpaSnapshotQuery<T>,
        tenantId: String? = null,
        callback: Consumer<List<T>>
    ): AutoCloseable {
        val job = callbackScope.launch { observe(entityType, query, tenantId).collect(callback::accept) }
        return AutoCloseable(job::cancel)
    }

    override fun close() {
        callbackScope.coroutineContext[Job]?.cancel()
    }

    @OptIn(FlowPreview::class)
    private fun <T : Any> snapshots(
        entityType: Class<T>,
        query: JpaSnapshotQuery<T>,
        tenantId: String?
    ): Flow<List<T>> = flow {
        emit(readSnapshot(query))
        notifier.changes(entityType, tenantId)
            .debounce(options.coalesceWindow.toMillis())
            .collect { emit(readSnapshot(query)) }
    }.flowOn(Dispatchers.IO).buffer(0).distinctUntilChanged()

    private fun <T : Any> readSnapshot(query: JpaSnapshotQuery<T>): List<T> {
        val entityManager = entityManagerFactory.createEntityManager()
        return try {
            query.execute(entityManager).toList()
        } finally {
            entityManager.close()
        }
    }

    private companion object {
        fun <T : Any> entityName(entityManager: EntityManager, entityType: Class<T>): String =
            entityManager.metamodel.entity(entityType).name
    }
}

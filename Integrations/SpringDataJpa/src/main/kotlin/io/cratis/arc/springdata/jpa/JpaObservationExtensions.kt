// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import java.util.concurrent.Flow as JdkFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

// ── Kotlin extension functions ────────────────────────────────────────────────
// These are inline+reified and therefore @JvmSynthetic — invisible from Java.
// Java callers must use the JpaObservations static helpers below.
// Every function delegates directly to JpaObservableQuery — one implementation,
// two call styles.
//
// JPA observation is backed by in-process TransactionAwareDatabaseChangeNotifier;
// there is no cross-process change-stream mechanism, unlike MongoDB change streams.

/** Returns a snapshot [Flow] for [T], refreshed after committed changes. */
public inline fun <reified T : Any> JpaObservableQuery.observe(
    query: JpaSnapshotQuery<T> = JpaSnapshotQuery { em ->
        em.createQuery(
            "select entity from ${em.metamodel.entity(T::class.java).name} entity",
            T::class.java
        ).resultList
    },
    tenantId: String? = null
): Flow<List<T>> = observe(T::class.java, query, tenantId)

/** Alias emphasizing that each emission is a complete list snapshot. */
public inline fun <reified T : Any> JpaObservableQuery.observeList(
    query: JpaSnapshotQuery<T> = JpaSnapshotQuery { em ->
        em.createQuery(
            "select entity from ${em.metamodel.entity(T::class.java).name} entity",
            T::class.java
        ).resultList
    },
    tenantId: String? = null
): Flow<List<T>> = observeList(T::class.java, query, tenantId)

/** Emits the first item in each non-empty snapshot for [T]. Absence is not emitted. */
public inline fun <reified T : Any> JpaObservableQuery.observeSingle(
    query: JpaSnapshotQuery<T> = JpaSnapshotQuery { em ->
        em.createQuery(
            "select entity from ${em.metamodel.entity(T::class.java).name} entity",
            T::class.java
        ).setMaxResults(1).resultList
    },
    tenantId: String? = null
): Flow<T> = observeSingle(T::class.java, query, tenantId)

/** Emits the [T] entity with [id] after the initial lookup and relevant committed changes. */
public inline fun <reified T : Any> JpaObservableQuery.observeById(
    id: Any,
    tenantId: String? = null
): Flow<T> = observeById(T::class.java, id, tenantId)

/** Shares one observation of [T] while subscribers exist, replaying the latest snapshot. */
public inline fun <reified T : Any> JpaObservableQuery.observeShared(
    scope: CoroutineScope,
    query: JpaSnapshotQuery<T> = JpaSnapshotQuery { em ->
        em.createQuery(
            "select entity from ${em.metamodel.entity(T::class.java).name} entity",
            T::class.java
        ).resultList
    },
    tenantId: String? = null
): SharedFlow<List<T>> = observeShared(scope, T::class.java, query, tenantId)

// ── Java static helpers ───────────────────────────────────────────────────────
// Java cannot call inline+reified extension functions. These @JvmStatic helpers
// fill the ergonomic gaps for Java callers:
//   - observeShared: the existing method has no default query, so Java must always
//     provide one. The helper provides the same default-all-entities JPQL query.
//   - observePublisher: same — no default query in the existing API.
// All other JpaObservableQuery methods already have @JvmOverloads defaults that
// Java can call without these helpers.
//
// Extensions cannot be placed on Spring Data's JPA repository interfaces; those
// are foreign types. All entry points here are on Arc's own JpaObservableQuery.
//
// JPA observation is in-process only; there is no cross-process notification
// mechanism. Callers needing cross-process notifications must supply a custom
// DatabaseChangeNotifier bean.

/**
 * Static helpers giving Java callers ergonomic access to [JpaObservableQuery] for
 * the two entry points ([observeShared] and [observePublisher]) that require a
 * query argument with no default in the base API. Each helper supplies the same
 * default select-all JPQL query as the Kotlin extension functions above.
 */
public object JpaObservations {
    /**
     * Shares one observation of [entityType] while subscribers exist, using the
     * default select-all JPQL query. Replays the latest snapshot to new subscribers.
     *
     * For a custom query, call [JpaObservableQuery.observeShared] directly.
     */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> observeShared(
        scope: CoroutineScope,
        queries: JpaObservableQuery,
        entityType: Class<T>,
        tenantId: String? = null
    ): SharedFlow<List<T>> =
        queries.observeShared(scope, entityType, defaultListQuery(entityType), tenantId)

    /**
     * Returns a demand-aware [JdkFlow.Publisher] of list snapshots for [entityType],
     * using the default select-all JPQL query.
     *
     * For a custom query, call [JpaObservableQuery.observePublisher] directly.
     */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> observePublisher(
        queries: JpaObservableQuery,
        entityType: Class<T>,
        tenantId: String? = null
    ): JdkFlow.Publisher<List<T>> =
        queries.observePublisher(entityType, defaultListQuery(entityType), tenantId)

    private fun <T : Any> defaultListQuery(entityType: Class<T>): JpaSnapshotQuery<T> =
        JpaSnapshotQuery { em ->
            em.createQuery(
                "select entity from ${em.metamodel.entity(entityType).name} entity",
                entityType
            ).resultList
        }
}

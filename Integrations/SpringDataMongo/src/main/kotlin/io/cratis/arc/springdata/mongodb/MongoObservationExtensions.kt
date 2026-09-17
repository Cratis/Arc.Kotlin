// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import java.util.concurrent.Flow as JdkFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

// ── Kotlin extension functions ────────────────────────────────────────────────
// These are inline+reified and therefore @JvmSynthetic — invisible from Java.
// Java callers must use the MongoObservations static helpers below.
// Every function delegates directly to MongoObservableQuery — one implementation,
// two call styles.

/** Returns a snapshot [Flow] for [T], re-read after each relevant MongoDB change. */
public inline fun <reified T : Any> MongoObservableQuery.observe(
    query: Query = Query(),
    tenantId: String? = null
): Flow<List<T>> = observe(T::class.java, query, tenantId)

/** Returns a snapshot [Flow] for [T] matching [criteria], re-read after each relevant MongoDB change. */
public inline fun <reified T : Any> MongoObservableQuery.observe(
    criteria: Criteria,
    tenantId: String? = null
): Flow<List<T>> = observe(T::class.java, Query.query(criteria), tenantId)

/** Alias emphasizing that each emission is a complete list snapshot. */
public inline fun <reified T : Any> MongoObservableQuery.observeList(
    query: Query = Query(),
    tenantId: String? = null
): Flow<List<T>> = observeList(T::class.java, query, tenantId)

/** Alias emphasizing that each emission is a complete list snapshot, filtered by [criteria]. */
public inline fun <reified T : Any> MongoObservableQuery.observeList(
    criteria: Criteria,
    tenantId: String? = null
): Flow<List<T>> = observeList(T::class.java, Query.query(criteria), tenantId)

/** Emits matching single-document snapshots for [T]. Absence is not emitted. */
public inline fun <reified T : Any> MongoObservableQuery.observeSingle(
    query: Query = Query(),
    tenantId: String? = null
): Flow<T> = observeSingle(T::class.java, query, tenantId)

/** Emits matching single-document snapshots for [T] satisfying [criteria]. Absence is not emitted. */
public inline fun <reified T : Any> MongoObservableQuery.observeSingle(
    criteria: Criteria,
    tenantId: String? = null
): Flow<T> = observeSingle(T::class.java, Query.query(criteria), tenantId)

/** Emits snapshots for the [T] document with [id]. Unrelated document changes are ignored. */
public inline fun <reified T : Any> MongoObservableQuery.observeById(
    id: Any,
    tenantId: String? = null
): Flow<T> = observeById(T::class.java, id, tenantId)

/** Shares one observation of [T] while subscribers exist, replaying the latest snapshot. */
public inline fun <reified T : Any> MongoObservableQuery.observeShared(
    scope: CoroutineScope,
    query: Query = Query(),
    tenantId: String? = null
): SharedFlow<List<T>> = observeShared(scope, T::class.java, query, tenantId)

/** Shares one observation of [T] matching [criteria] while subscribers exist, replaying the latest snapshot. */
public inline fun <reified T : Any> MongoObservableQuery.observeShared(
    scope: CoroutineScope,
    criteria: Criteria,
    tenantId: String? = null
): SharedFlow<List<T>> = observeShared(scope, T::class.java, Query.query(criteria), tenantId)

// ── Java static helpers ───────────────────────────────────────────────────────
// Java cannot call inline+reified extension functions. These @JvmStatic helpers
// expose Criteria-based convenience overloads for Java callers. Each delegates
// to the corresponding MongoObservableQuery method — one implementation, two
// call styles.
//
// Extensions cannot be placed on Spring Data's MongoCollection<T> or repository
// interfaces; those are foreign types. All entry points here are on Arc's own
// MongoObservableQuery.

/**
 * Static helpers giving Java callers [Criteria]-based convenience access to
 * [MongoObservableQuery]. For [Query]-based and callback access, call the
 * `@JvmOverloads` methods on [MongoObservableQuery] directly.
 *
 * Each method wraps the [Criteria] into a [Query] and delegates to the
 * matching [MongoObservableQuery] method — no independent snapshot or
 * change-stream logic lives here.
 */
public object MongoObservations {
    /**
     * Returns a demand-aware [JdkFlow.Publisher] of list snapshots for [documentType]
     * matching [criteria]. Wraps [Criteria] into [Query] and delegates to
     * [MongoObservableQuery.observePublisher].
     */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> observe(
        queries: MongoObservableQuery,
        documentType: Class<T>,
        criteria: Criteria,
        tenantId: String? = null
    ): JdkFlow.Publisher<List<T>> =
        DemandAwareFlowPublisher(queries.observe(documentType, Query.query(criteria), tenantId))

    /**
     * Returns a demand-aware [JdkFlow.Publisher] of list snapshots for [documentType]
     * matching [criteria] (alias of [observe] emphasizing complete list semantics).
     */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> observeList(
        queries: MongoObservableQuery,
        documentType: Class<T>,
        criteria: Criteria,
        tenantId: String? = null
    ): JdkFlow.Publisher<List<T>> =
        DemandAwareFlowPublisher(queries.observeList(documentType, Query.query(criteria), tenantId))

    /**
     * Returns a demand-aware [JdkFlow.Publisher] of single-document snapshots for
     * [documentType] matching [criteria]. Absence is not emitted.
     */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> observeSingle(
        queries: MongoObservableQuery,
        documentType: Class<T>,
        criteria: Criteria,
        tenantId: String? = null
    ): JdkFlow.Publisher<T> =
        DemandAwareFlowPublisher(queries.observeSingle(documentType, Query.query(criteria), tenantId))
}

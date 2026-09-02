// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.commands.await
import io.cratis.arc.results.PagingInfo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/** Mutable-stage-free result passed between ordered query renderers before Arc creates the result envelope. */
public class QueryRendererResult(
    /** Current rendered data. */
    public val data: Any?,
    /** Current response paging metadata. */
    public val paging: PagingInfo = PagingInfo(0, 0, 0)
)

/** Transforms one supported query value before it is placed in a [io.cratis.arc.results.QueryResult]. */
public interface QueryRendererFor<T : Any> {
    /** Runtime type accepted by this renderer. Assignable values are supported. */
    public fun queryType(): Class<T>

    /** Stable ascending order. Renderers with the same order retain registration order. */
    public fun order(): Int = 0

    /** Asynchronously transforms [current]. [query] is the original performer value. */
    public fun render(
        query: T,
        current: QueryRendererResult,
        context: QueryContext
    ): CompletionStage<QueryRendererResult>
}

/** Kotlin property view of the runtime type accepted by this renderer. */
@get:JvmSynthetic
public val <T : Any> QueryRendererFor<T>.type: Class<T>
    get() = queryType()

/** Kotlin property view of this renderer's stable ascending order. */
@get:JvmSynthetic
public val QueryRendererFor<*>.order: Int
    get() = order()

/** Blocking implementation convenience for Kotlin and Java renderers that do not need asynchronous work. */
public interface BlockingQueryRendererFor<T : Any> : QueryRendererFor<T> {
    /** Performs the blocking transformation. */
    public fun renderBlocking(query: T, current: QueryRendererResult, context: QueryContext): QueryRendererResult

    override fun render(
        query: T,
        current: QueryRendererResult,
        context: QueryContext
    ): CompletionStage<QueryRendererResult> = CompletableFuture.completedFuture(renderBlocking(query, current, context))
}

/** Applies all matching query renderers in deterministic order. */
public interface QueryRenderers {
    /** Renders [query], starting from [initial]. */
    public suspend fun render(query: Any, initial: QueryRendererResult, context: QueryContext): QueryRendererResult
}

/** Default immutable renderer registry. */
public class DefaultQueryRenderers(renderers: Iterable<QueryRendererFor<*>> = emptyList()) : QueryRenderers {
    private val renderers = java.util.List.copyOf(
        renderers.toList()
            .let { configured ->
                if (configured.any { it is QueryableQueryRenderer }) configured else configured + QueryableQueryRenderer()
            }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<QueryRendererFor<*>>> { it.value.order() }.thenBy { it.index })
            .map(IndexedValue<QueryRendererFor<*>>::value)
    )

    override suspend fun render(query: Any, initial: QueryRendererResult, context: QueryContext): QueryRendererResult {
        var current = initial
        renderers.forEach { renderer ->
            if (renderer.queryType().isInstance(query)) {
                @Suppress("UNCHECKED_CAST")
                current = (renderer as QueryRendererFor<Any>).render(query, current, context).await()
            }
        }
        return current
    }
}

/**
 * JVM equivalent of Arc's queryable renderer for in-memory [Iterable] values.
 *
 * It counts before paging, applies optional property sorting, then applies the requested page. Database integrations
 * should return [QueryPage] or provide a store-specific renderer so filtering remains owned by the database.
 */
public class QueryableQueryRenderer : BlockingQueryRendererFor<Iterable<*>> {
    private val propertyAccessors = ConcurrentHashMap<PropertyKey, KProperty1<Any, *>>()

    @Suppress("UNCHECKED_CAST")
    override fun queryType(): Class<Iterable<*>> = Iterable::class.java as Class<Iterable<*>>

    override fun renderBlocking(
        query: Iterable<*>,
        current: QueryRendererResult,
        context: QueryContext
    ): QueryRendererResult {
        var values = query.toList()
        val totalItems = values.size.toLong()
        val sorting = context.request.sorting
        if (sorting.field.isNotBlank()) {
            values = values.sortedWith { left, right ->
                val comparison = compareValues(readProperty(left, sorting.field), readProperty(right, sorting.field))
                if (sorting.direction == QuerySortDirection.DESCENDING) -comparison else comparison
            }
        }
        val paging = context.request.paging
        if (paging.pageSize > 0) {
            val start = (paging.page.toLong() * paging.pageSize.toLong()).coerceAtMost(values.size.toLong()).toInt()
            val end = (start.toLong() + paging.pageSize.toLong()).coerceAtMost(values.size.toLong()).toInt()
            values = values.subList(start, end)
        }
        return QueryRendererResult(
            java.util.Collections.unmodifiableList(ArrayList(values)),
            PagingInfo(paging.page, paging.pageSize, totalItems)
        )
    }

    private fun readProperty(instance: Any?, name: String): Comparable<Any>? {
        if (instance == null) return null
        val key = PropertyKey(instance.javaClass, name)
        val property = propertyAccessors.computeIfAbsent(key) {
            val resolved = instance::class.memberProperties.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException("Cannot sort '${instance.javaClass.name}' by unknown property '$name'.")
            resolved.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            resolved as KProperty1<Any, *>
        }
        @Suppress("UNCHECKED_CAST")
        return property.get(instance) as? Comparable<Any>
    }

    private fun compareValues(left: Comparable<Any>?, right: Comparable<Any>?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
    }

    private data class PropertyKey(val type: Class<*>, val name: String)
}

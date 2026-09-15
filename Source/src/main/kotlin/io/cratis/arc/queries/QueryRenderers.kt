// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.commands.await
import io.cratis.arc.results.PagingInfo
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.kotlinFunction

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

/**
 * Immutable application-renderer registry with an iterable fallback only when no configured renderer matches.
 * A matching application chain owns its data and paging. Register [QueryableQueryRenderer] explicitly to apply
 * its processing within that chain; configured order and original-value matching remain unchanged.
 */
public class DefaultQueryRenderers(renderers: Iterable<QueryRendererFor<*>> = emptyList()) : QueryRenderers {
    private val iterableFallback = QueryableQueryRenderer()
    private val renderers = java.util.List.copyOf(
        renderers.toList()
            .withIndex()
            .sortedWith(compareBy<IndexedValue<QueryRendererFor<*>>> { it.value.order() }.thenBy { it.index })
            .map(IndexedValue<QueryRendererFor<*>>::value)
    )

    override suspend fun render(query: Any, initial: QueryRendererResult, context: QueryContext): QueryRendererResult {
        val matching = renderers.filter { it.queryType().isInstance(query) }
        if (matching.isEmpty()) {
            return if (query is Iterable<*>) iterableFallback.render(query, initial, context).await() else initial
        }
        var current = initial
        matching.forEach { renderer ->
            @Suppress("UNCHECKED_CAST")
            current = (renderer as QueryRendererFor<Any>).render(query, current, context).await()
        }
        return current
    }
}

/**
 * JVM equivalent of Arc's queryable renderer for in-memory [Iterable] values.
 *
 * It counts [QueryRendererResult.data] before paging, applies optional property sorting, then applies the requested page.
 * A null or non-iterable current projection is preserved; original rows are never restored. Database integrations
 * should return [QueryPage] or provide an authoritative store-specific renderer without an additional iterable stage.
 * Sorting reads public instance properties only, including Java record accessors and field-backed bean getters;
 * it never bypasses a public getter to read private storage. Serialization annotations are not a sort-key ACL.
 */
public class QueryableQueryRenderer : BlockingQueryRendererFor<Iterable<*>> {
    private val propertyAccessors = ConcurrentHashMap<PropertyKey, (Any) -> Any?>()

    override fun queryType(): Class<Iterable<*>> = Iterable::class.java

    override fun renderBlocking(
        query: Iterable<*>,
        current: QueryRendererResult,
        context: QueryContext
    ): QueryRendererResult {
        val iterable = current.data as? Iterable<*> ?: return current
        var values = iterable.toList()
        val totalItems = values.size.toLong()
        val sorting = context.request.sorting
        if (sorting.field.isNotBlank()) {
            // Validate all participating types without invoking getters. Even a singleton must not accept an
            // unknown/private key, and mixed results must fail before another row's getter is evaluated.
            values.asSequence().filterNotNull().map { it.javaClass }.distinct().forEach { accessor(it, sorting.field) }
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
        val value = try {
            accessor(instance.javaClass, name)(instance)
        } catch (exception: InvocationTargetException) {
            val original = exception.targetException
            var cause = original
            val seen = IdentityHashMap<Throwable, Unit>()
            while (seen.put(cause, Unit) == null) {
                cause = when (cause) {
                    is InvocationTargetException -> cause.targetException ?: break
                    is CompletionException, is ExecutionException -> cause.cause ?: break
                    else -> break
                }
            }
            if (cause is CancellationException || cause is Error) throw cause
            // Preserve the ordinary reflective failure path: an exception from a model getter must not
            // accidentally become an ignorable request-validation result just because it was unwrapped.
            throw exception
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Comparable<Any>
    }

    private fun accessor(type: Class<*>, name: String): (Any) -> Any? =
        propertyAccessors.computeIfAbsent(PropertyKey(type, name)) { resolveAccessor(type, name) }

    private fun resolveAccessor(type: Class<*>, name: String): (Any) -> Any? {
        val property = type.kotlin.memberProperties.firstOrNull { it.name == name }
        val kotlinDeclaration = property?.javaGetter?.declaringClass?.isAnnotationPresent(Metadata::class.java) == true ||
            property?.javaField?.declaringClass?.isAnnotationPresent(Metadata::class.java) == true
        if (kotlinDeclaration) {
            if (property.visibility != KVisibility.PUBLIC) throw unknownProperty(type, name)
            // Relax declaring-class access only AFTER checking the property's Kotlin visibility. A public
            // getter on a nonpublic model remains usable; internal/private/protected members never reach here.
            val getter = property.getter
            getter.isAccessible = true
            return { instance -> getter.call(instance) }
        }
        if (type.isRecord) {
            val getter = type.recordComponents.firstOrNull { it.name == name }?.accessor
                ?: throw unknownProperty(type, name)
            return methodAccessor(type, name, getter)
        }
        // Preserve the established field-backed Java model boundary; do not expose arbitrary zero-argument
        // methods or invent getter-only bean properties that have no represented instance field.
        val field = findField(type, name) ?: throw unknownProperty(type, name)
        if (Modifier.isStatic(field.modifiers)) throw unknownProperty(type, name)
        val suffix = name.replaceFirstChar(Char::uppercaseChar)
        val getter = publicGetter(type, "is$suffix", booleanOnly = true)
            ?: publicGetter(type, "get$suffix")
            ?: if (name.startsWith("is") && name.getOrNull(2)?.isUpperCase() == true) {
                publicGetter(type, name, booleanOnly = true)
            } else null
        if (getter != null) return methodAccessor(type, name, getter)
        if (!Modifier.isPublic(field.modifiers) || !field.trySetAccessible()) throw unknownProperty(type, name)
        return { instance -> field.get(instance) }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var owner: Class<*>? = type
        while (owner != null) {
            try {
                return owner.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                owner = owner.superclass
            }
        }
        return null
    }

    private fun publicGetter(type: Class<*>, name: String, booleanOnly: Boolean = false): Method? {
        val method = try {
            type.getMethod(name)
        } catch (_: NoSuchMethodException) {
            return null
        }
        if (Modifier.isStatic(method.modifiers) || method.returnType == Void.TYPE) return null
        if (booleanOnly && method.returnType != Boolean::class.javaPrimitiveType && method.returnType != Boolean::class.javaObjectType) return null
        return method
    }

    private fun methodAccessor(type: Class<*>, name: String, method: Method): (Any) -> Any? {
        if (!Modifier.isPublic(method.modifiers) || Modifier.isStatic(method.modifiers)) throw unknownProperty(type, name)
        if (method.declaringClass.isAnnotationPresent(Metadata::class.java)) {
            // Bean naming can select a differently named inherited Kotlin declaration. JVM-public does not
            // imply Kotlin-public (for example an internal getter with @JvmName); check the method's origin.
            val declaredProperty = method.declaringClass.kotlin.memberProperties.firstOrNull { it.javaGetter == method }
            val visibility = declaredProperty?.visibility ?: method.kotlinFunction?.visibility
            if (visibility != KVisibility.PUBLIC) throw unknownProperty(type, name)
        }
        if (!method.trySetAccessible()) throw unknownProperty(type, name)
        return { instance -> method.invoke(instance) }
    }

    // Deliberately do not distinguish an inaccessible existing member from an absent one.
    private fun unknownProperty(type: Class<*>, name: String): IllegalArgumentException =
        IllegalArgumentException("Cannot sort '${type.name}' by unknown property '$name'.")

    private fun compareValues(left: Comparable<Any>?, right: Comparable<Any>?): Int = when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> left.compareTo(right)
    }

    private data class PropertyKey(val type: Class<*>, val name: String)
}

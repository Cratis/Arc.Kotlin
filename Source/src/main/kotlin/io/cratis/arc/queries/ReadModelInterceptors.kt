// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.commands.await
import java.lang.reflect.Array as ReflectArray
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Intercepts one typed read model before Arc serves it to a client. */
public interface InterceptReadModel<T : Any> {
    /** Runtime read-model type accepted by this interceptor. */
    public fun readModelType(): Class<T>

    /** Stable ascending order. Interceptors with equal order retain registration order. */
    public fun order(): Int = 0

    /** Returns the original or a replacement immutable read model. */
    public fun intercept(readModel: T, context: QueryContext): CompletionStage<T>
}

/** Kotlin property view of the runtime read-model type accepted by this interceptor. */
@get:JvmSynthetic
public val <T : Any> InterceptReadModel<T>.type: Class<T>
    get() = readModelType()

/** Kotlin property view of this interceptor's stable ascending order. */
@get:JvmSynthetic
public val InterceptReadModel<*>.order: Int
    get() = order()

/** Blocking implementation convenience for interceptors without asynchronous work. */
public interface BlockingReadModelInterceptor<T : Any> : InterceptReadModel<T> {
    /** Intercepts synchronously. */
    public fun interceptBlocking(readModel: T, context: QueryContext): T

    override fun intercept(readModel: T, context: QueryContext): CompletionStage<T> =
        CompletableFuture.completedFuture(interceptBlocking(readModel, context))
}

/** Applies matching typed interceptors while preserving single-value and collection shapes. */
public interface ReadModelInterceptors {
    /** Intercepts [value], applying matching interceptors to every collection item. */
    public suspend fun intercept(value: Any?, context: QueryContext): Any?
}

/** Default immutable typed interceptor registry. */
public class DefaultReadModelInterceptors(interceptors: Iterable<InterceptReadModel<*>> = emptyList()) : ReadModelInterceptors {
    private val interceptors = java.util.List.copyOf(
        interceptors.withIndex()
            .sortedWith(compareBy<IndexedValue<InterceptReadModel<*>>> { it.value.order() }.thenBy { it.index })
            .map(IndexedValue<InterceptReadModel<*>>::value)
    )

    override suspend fun intercept(value: Any?, context: QueryContext): Any? = when (value) {
        null -> null
        is String -> interceptOne(value, context)
        is Iterable<*> -> java.util.Collections.unmodifiableList(value.map { item ->
            if (item == null) null else interceptOne(item, context)
        })
        is Array<*> -> java.util.Collections.unmodifiableList(value.map { item ->
            if (item == null) null else interceptOne(item, context)
        })
        else -> if (value.javaClass.isArray) {
            java.util.Collections.unmodifiableList((0 until ReflectArray.getLength(value)).map { index ->
                ReflectArray.get(value, index)?.let { interceptOne(it, context) }
            })
        } else {
            interceptOne(value, context)
        }
    }

    private suspend fun interceptOne(value: Any, context: QueryContext): Any {
        var current = value
        interceptors.forEach { interceptor ->
            if (interceptor.readModelType().isInstance(current)) {
                @Suppress("UNCHECKED_CAST")
                current = (interceptor as InterceptReadModel<Any>).intercept(current, context).await()
            }
        }
        return current
    }
}

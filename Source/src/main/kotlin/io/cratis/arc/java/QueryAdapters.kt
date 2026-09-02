// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.commands.await
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.queries.AuthorizationQueryFilter
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.asKotlinFlow
import io.cratis.arc.results.QueryResult
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow as JdkFlow

/** Synchronous Java implementation surface for a query filter. */
public fun interface BlockingQueryFilter {
    public fun execute(context: QueryContext): QueryResult<*>
}

/** CompletionStage-based Java implementation surface for a query filter. */
public fun interface AsyncQueryFilter {
    public fun execute(context: QueryContext): CompletionStage<QueryResult<*>>
}

/** Synchronous Java marker for authorization query filters. */
public fun interface BlockingAuthorizationQueryFilter : BlockingQueryFilter

/** CompletionStage-based Java marker for authorization query filters. */
public fun interface AsyncAuthorizationQueryFilter : AsyncQueryFilter

/** Adapts a blocking query filter to Arc's suspending SPI. */
public class BlockingQueryFilterAdapter(private val filter: BlockingQueryFilter) : QueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> = filter.execute(context)
}

/** Adapts an asynchronous query filter to Arc's suspending SPI. */
public class AsyncQueryFilterAdapter(private val filter: AsyncQueryFilter) : QueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> = filter.execute(context).await()
}

/** Adapts a blocking authorization query filter while retaining marker ordering. */
public class BlockingAuthorizationQueryFilterAdapter(
    private val filter: BlockingAuthorizationQueryFilter
) : AuthorizationQueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> = filter.execute(context)
}

/** Adapts an asynchronous authorization query filter while retaining marker ordering. */
public class AsyncAuthorizationQueryFilterAdapter(
    private val filter: AsyncAuthorizationQueryFilter
) : AuthorizationQueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> = filter.execute(context).await()
}

/** Synchronous Java implementation surface for a manually registered query performer. */
public interface BlockingQueryPerformer {
    public val descriptor: QueryDescriptor
    public val fullyQualifiedName: FullyQualifiedQueryName
    public fun perform(context: QueryContext): Any?
}

/** CompletionStage-based Java implementation surface for a manually registered query performer. */
public interface AsyncQueryPerformer {
    public val descriptor: QueryDescriptor
    public val fullyQualifiedName: FullyQualifiedQueryName
    public fun perform(context: QueryContext): CompletionStage<*>
}

/** Adapts a blocking manual query performer to Arc's suspending SPI. */
public class BlockingQueryPerformerAdapter(private val performer: BlockingQueryPerformer) : QueryPerformer {
    override val descriptor: QueryDescriptor get() = performer.descriptor
    override val fullyQualifiedName: FullyQualifiedQueryName get() = performer.fullyQualifiedName
    override suspend fun perform(context: QueryContext): Any? = adaptPublisher(performer.perform(context))
}

/** Adapts an asynchronous manual query performer to Arc's suspending SPI. */
public class AsyncQueryPerformerAdapter(private val performer: AsyncQueryPerformer) : QueryPerformer {
    override val descriptor: QueryDescriptor get() = performer.descriptor
    override val fullyQualifiedName: FullyQualifiedQueryName get() = performer.fullyQualifiedName
    override suspend fun perform(context: QueryContext): Any? = adaptPublisher(performer.perform(context).await())
}

@Suppress("UNCHECKED_CAST")
private fun adaptPublisher(value: Any?): Any? =
    (value as? JdkFlow.Publisher<Any>)?.asKotlinFlow() ?: value

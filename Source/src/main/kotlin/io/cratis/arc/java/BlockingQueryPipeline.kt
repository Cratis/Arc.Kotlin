// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.java

import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.results.QueryResult

/**
 * Caller-thread blocking one-shot query facade, with the same guard, interrupt/cleanup and
 * caller-owned timeout contract as [BlockingCommandPipeline]. No observable or async bridge is added.
 * [boundOptions] enables short calls using explicit context, never ambient request state.
 */
public class BlockingQueryPipeline @JvmOverloads constructor(
    private val pipeline: QueryPipeline,
    private val boundOptions: QueryExecutionOptions? = null
) {
    /** Performs a one-shot query with explicit options, overriding constructor-bound options. */
    public fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> =
        BlockingPipelineGuard.run { pipeline.perform(request, options) }

    /** Performs with constructor-bound options, or fails before execution if none were supplied. */
    public fun perform(request: QueryRequest): QueryResult<*> = perform(request, checkNotNull(boundOptions) {
        "Supply QueryExecutionOptions per call or bind them explicitly in the BlockingQueryPipeline constructor."
    })
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.QueryResult

/** Host-agnostic one-shot query pipeline. */
public interface QueryPipeline {
    /** Performs [request] using explicit host [options]. */
    public suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*>
}

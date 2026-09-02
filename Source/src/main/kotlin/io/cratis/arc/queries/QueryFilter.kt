// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.QueryResult

/** One host-agnostic filter stage for a query. */
public fun interface QueryFilter {
    /** Returns the result fragment to merge before query invocation. */
    public suspend fun execute(context: QueryContext): QueryResult<*>
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Sorting requested by a query. */
public data class QuerySorting(
    public val field: String,
    public val direction: QuerySortDirection
) {
    public companion object {
        /** Reusable request value that disables sorting. */
        @JvmField
        public val UNSORTED: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
    }
}

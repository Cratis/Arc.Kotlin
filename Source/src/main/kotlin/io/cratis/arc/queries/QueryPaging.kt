// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Paging requested by a query. The request wire field is `pageSize`. */
public data class QueryPaging(public val page: Int, public val pageSize: Int) {
    init {
        require(page >= 0) { "page cannot be negative" }
        require(pageSize >= 0) { "pageSize cannot be negative" }
    }

    public companion object {
        /** Reusable request value that disables paging. */
        @JvmField
        public val UNPAGED: QueryPaging = QueryPaging(0, 0)
    }
}

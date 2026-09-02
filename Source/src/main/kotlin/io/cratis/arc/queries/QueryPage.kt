// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Database-owned page returned by a generated query performer. */
public class QueryPage<T>(
    items: List<T>,
    public val page: Int,
    public val pageSize: Int,
    public val totalItems: Long
) {
    /** Materialized page items, defensively copied. */
    public val items: List<T> = java.util.List.copyOf(items)

    init {
        require(page >= 0) { "page cannot be negative" }
        require(pageSize >= 0) { "pageSize cannot be negative" }
        require(totalItems >= 0) { "totalItems cannot be negative" }
    }
}

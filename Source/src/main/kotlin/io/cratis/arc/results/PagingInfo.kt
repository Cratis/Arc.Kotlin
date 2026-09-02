// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

/** Immutable paging metadata using Arc's zero-size and ceiling semantics. */
public class PagingInfo(
    /** Zero-based page number supplied by the query layer. */
    public val page: Int,
    /** Requested page size. The response wire name is `size`. */
    public val size: Int,
    /** Total number of matching items. */
    public val totalItems: Long
) {
    /** Java- and source-compatible convenience overload for integer totals. */
    public constructor(page: Int, size: Int, totalItems: Int) : this(page, size, totalItems.toLong())

    init {
        require(page >= 0) { "page cannot be negative" }
        require(size >= 0) { "size cannot be negative" }
        require(totalItems >= 0) { "totalItems cannot be negative" }
    }

    /** Number of pages, rounded up; zero when [size] is zero. */
    public val totalPages: Int = if (size == 0) {
        0
    } else {
        val pages = totalItems / size + if (totalItems % size == 0L) 0 else 1
        pages.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

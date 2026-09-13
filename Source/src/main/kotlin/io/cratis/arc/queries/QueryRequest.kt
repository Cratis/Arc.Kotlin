// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Transport-independent query request with a shallow read-only argument map, not an opening-time deep freeze. */
public class QueryRequest @JvmOverloads constructor(
    public val queryName: FullyQualifiedQueryName,
    arguments: Map<String, Any?> = emptyMap(),
    public val paging: QueryPaging = QueryPaging(0, 0),
    public val sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
) {
    /** Caller-supplied arguments: map entries are copied in iteration order; nested values retain caller/performer ownership. */
    public val arguments: Map<String, Any?> = java.util.Collections.unmodifiableMap(LinkedHashMap(arguments))
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Immutable, transport-independent one-shot query request. */
public class QueryRequest @JvmOverloads constructor(
    public val queryName: FullyQualifiedQueryName,
    arguments: Map<String, Any?> = emptyMap(),
    public val paging: QueryPaging = QueryPaging(0, 0),
    public val sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
) {
    /** Caller-supplied query arguments, defensively copied in iteration order. */
    public val arguments: Map<String, Any?> = java.util.Collections.unmodifiableMap(LinkedHashMap(arguments))
}

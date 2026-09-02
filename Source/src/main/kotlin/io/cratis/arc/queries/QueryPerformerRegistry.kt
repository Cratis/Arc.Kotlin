// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Registry of generated query performers keyed by exact fully qualified name. */
public interface QueryPerformerRegistry {
    /** Monotonic version incremented after every successful registration. */
    public val version: Long
        get() = 0L

    public fun register(performer: QueryPerformer)
    public fun find(queryName: FullyQualifiedQueryName): QueryPerformer?
    public fun snapshot(): List<QueryPerformer>
}

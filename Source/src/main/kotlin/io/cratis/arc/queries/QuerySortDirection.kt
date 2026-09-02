// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.concepts.ArcEnum

/** Supported one-shot query sort directions. */
public enum class QuerySortDirection(private val wireValue: Int) : ArcEnum {
    /** Ascending order. */
    ASCENDING(1),

    /** Descending order. */
    DESCENDING(2);

    override fun value(): Int = wireValue
}

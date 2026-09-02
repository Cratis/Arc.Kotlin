// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.concepts.ConceptAs

/** Exact fully qualified name used to address a generated query performer. */
public class FullyQualifiedQueryName(public val value: String) :
    Comparable<FullyQualifiedQueryName>,
    ConceptAs<String> {
    init {
        require(value.isNotBlank()) { "value cannot be blank" }
    }

    override fun value(): String = value
    override fun compareTo(other: FullyQualifiedQueryName): Int = value.compareTo(other.value)
    override fun equals(other: Any?): Boolean = other is FullyQualifiedQueryName && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value
}

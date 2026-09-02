// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.commands

/** Raised when an aggregate contains more than one possible client response. */
public class MultipleUnhandledCommandResponseValuesException(values: Collection<Any>) : IllegalStateException(
    "A command response aggregate contains multiple unhandled values: " +
        values.joinToString(", ") { it.javaClass.name }
) {
    /** Ambiguous values in their original order. */
    public val values: List<Any> = java.util.List.copyOf(values)
}

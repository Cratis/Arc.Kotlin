// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Reflection-free primitives used by generated query performers to bind client arguments. */
public object QueryArgumentResolver {
    /** Returns a required argument or raises a deterministic client-input failure. */
    @JvmStatic
    public fun required(arguments: Map<String, Any?>, name: String): Any =
        arguments[name] ?: throw QueryArgumentException(name, "Query argument '$name' is required.")

    /** Returns a nullable argument; an absent argument and an explicit `null` both resolve to `null`. */
    @JvmStatic
    public fun nullable(arguments: Map<String, Any?>, name: String): Any? = arguments[name]

    /** Raises a deterministic failure when a supplied argument does not have the generated source type. */
    @JvmStatic
    public fun wrongType(name: String, expectedTypeName: String): Nothing =
        throw QueryArgumentException(name, "Query argument '$name' must be of type '$expectedTypeName'.")
}

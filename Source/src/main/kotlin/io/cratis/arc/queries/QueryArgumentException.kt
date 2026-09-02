// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Deterministic client-input failure raised while binding a generated query argument. */
public class QueryArgumentException(
    public val argumentName: String,
    message: String
) : IllegalArgumentException(message)

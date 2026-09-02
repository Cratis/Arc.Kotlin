// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Raised when more than one performer is registered for an exact query name. */
public class DuplicateQueryPerformerException(public val queryName: FullyQualifiedQueryName) : IllegalStateException(
    "A query performer is already registered for '$queryName'."
)

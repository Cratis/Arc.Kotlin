// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Describes how a query result is transported to a caller. */
public enum class QueryTransportType {
    /** A single request produces a single result. */
    REQUEST_RESPONSE,

    /** Results can continue to arrive as the underlying data changes. */
    OBSERVABLE
}

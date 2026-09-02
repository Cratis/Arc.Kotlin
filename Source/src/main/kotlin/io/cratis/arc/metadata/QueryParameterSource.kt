// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import com.fasterxml.jackson.annotation.JsonFormat

/** Identifies the canonical source of a query parameter value. */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum class QueryParameterSource {
    /** Supplied by the query client. */
    CLIENT,

    /** Resolved from the host's service container. */
    SERVICE,

    /** Supplied from the current query request. */
    QUERY_REQUEST,

    /** Supplied from the current query execution context. */
    QUERY_CONTEXT,

    /** Supplied by a host adapter. */
    HOST_ADAPTER
}

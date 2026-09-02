// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

import io.cratis.arc.queries.QueryTransportType

/**
 * Carries explicit route metadata for later hosting layers.
 *
 * This type deliberately does not calculate routes.
 */
public data class RouteOptions @JvmOverloads constructor(
    /** Explicit path override, or null when the later route layer should apply its convention. */
    public val path: String? = null,
    /** Query transport behavior associated with the route. */
    public val transport: QueryTransportType = QueryTransportType.REQUEST_RESPONSE
)

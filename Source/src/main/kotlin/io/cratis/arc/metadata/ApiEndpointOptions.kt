// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.metadata

/** Options controlling Arc's conventional HTTP endpoint routes. */
public data class ApiEndpointOptions @JvmOverloads constructor(
    public val routePrefix: String = "api",
    public val segmentsToSkipForRoute: Int = 0,
    public val includeCommandNameInRoute: Boolean = true,
    public val includeQueryNameInRoute: Boolean = true,
    public val enableQueryHttpMethod: Boolean = true
) {
    init {
        require(segmentsToSkipForRoute >= 0) { "segmentsToSkipForRoute cannot be negative." }
    }
}

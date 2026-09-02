// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.Path

/** Read model demonstrating the supported non-reserved string-keyed primitive map wire shape. */
@ReadModel
@AllowAnonymous
public data class MapView(
    public val strings: Map<String, String>,
    public val numbers: Map<String, List<Int>>,
    public val nested: Map<String, Map<String, Boolean>>,
    public val optional: Map<String, String>?
) {
    public companion object {
        /** Gets a deterministic map-bearing model without accepting a map query parameter. */
        @JvmStatic
        @Path("/api/maps")
        public fun current(): MapView = MapView(
            mapOf("source" to "query"),
            mapOf("values" to listOf(1, 2)),
            mapOf("flags" to mapOf("ready" to true)),
            null
        )
    }
}

/** Echoes supported map property shapes with non-reserved string keys through a typed model response. */
@Command
@AllowAnonymous
public data class EchoMaps(
    public val strings: Map<String, String>,
    public val numbers: Map<String, List<Int>>,
    public val nested: Map<String, Map<String, Boolean>>,
    public val optional: Map<String, String>?
) {
    /** Returns the received maps inside a generated model rather than as a top-level map response. */
    public fun handle(): MapView = MapView(strings, numbers, nested, optional)
}

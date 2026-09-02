// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.Command
import io.cratis.arc.artifacts.ReadModel

/** Kotlin command fixture covering recursive maps with non-reserved string keys and empty maps. */
@Command
public data class KotlinMapMetadataCommand(
    public val strings: Map<String, String> = emptyMap(),
    public val numbers: Map<String, List<Int>> = emptyMap(),
    public val nested: Map<String, Map<String, Boolean>> = emptyMap(),
    public val optional: Map<String, String>? = null
) {
    /** Completes without a client response. */
    public fun handle(): Unit = Unit
}

/** Kotlin read-model fixture proving map fields remain JSON objects while the query result stays model-shaped. */
@ReadModel
public data class KotlinMapReadModel(
    public val strings: Map<String, String>,
    public val numbers: Map<String, List<Int>>,
    public val nested: Map<String, Map<String, Boolean>>,
    public val optional: Map<String, String>?
) {
    public companion object {
        /** Returns a deterministic recursive map model without accepting maps as query parameters. */
        @JvmStatic
        public fun getKotlinMap(): KotlinMapReadModel = KotlinMapReadModel(
            mapOf("language" to "kotlin"),
            mapOf("values" to listOf(1, 2)),
            mapOf("flags" to mapOf("ready" to true)),
            null
        )
    }
}

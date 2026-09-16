// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.ReadModel
import java.util.UUID

@ReadModel
public data class ProjectedArrayQueries(public val value: String) {
    public companion object {
        public fun covariant(ids: Array<out UUID>): ProjectedArrayQueries = ProjectedArrayQueries(ids.size.toString())
        public fun contravariant(ids: Array<in UUID>): ProjectedArrayQueries = ProjectedArrayQueries(ids.size.toString())
        public fun starred(ids: Array<*>): ProjectedArrayQueries = ProjectedArrayQueries(ids.size.toString())
        public fun nullableEntries(ids: Array<UUID?>): ProjectedArrayQueries = ProjectedArrayQueries(ids.size.toString())
    }
}

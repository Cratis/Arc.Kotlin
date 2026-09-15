// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

/** Test-only Kotlin declaration inherited by an ordinary Java model. */
public open class PublicSortBase(@get:JvmName("labelValue") public val label: String) {
    @get:JvmName("getInternalRank")
    internal val internalRank: Int = 99

    /** A public function can serve as a conventional getter for an actual Java subclass field. */
    public fun getSortingLabel(): String = label.reversed()
}

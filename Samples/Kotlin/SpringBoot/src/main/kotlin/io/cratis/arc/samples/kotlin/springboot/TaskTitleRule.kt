// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

/** Shared title invariant for task commands and their defensive handlers. */
internal object TaskTitleRule {
    const val MAXIMUM_LENGTH: Int = 80

    fun isBlank(title: String): Boolean = title.isBlank()

    fun isTooLong(title: String): Boolean = title.length > MAXIMUM_LENGTH

    fun requireValid(title: String) {
        require(!isBlank(title)) { "A task title is required." }
        require(!isTooLong(title)) { "A task title cannot exceed $MAXIMUM_LENGTH characters." }
    }
}

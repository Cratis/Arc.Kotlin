// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

/** Nullable slots keep binding separate from the shared rule being demonstrated. */
data class FluentInput(
    val required: String?, val nonempty: String?, val minimum: String?, val maximum: String?, val range: String?,
    val email: String?, val phone: String?, val url: String?, val pattern: String?,
    val greater: Double?, val atLeast: Double?, val less: Double?, val atMost: Double?
)

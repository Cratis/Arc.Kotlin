// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot

import io.cratis.arc.validation.IgnoreValidation

/** Ignored state still belongs to the wire model; only validation edges are cut. */
data class IgnoredInput(
    @IgnoreValidation val ignoredText: String?,
    @field:IgnoreValidation val ignoredChild: JavaFluentInput?,
    @get:IgnoreValidation val ignoredList: List<JavaFluentInput>,
    @IgnoreValidation val ignoredArray: Array<JavaFluentInput>,
    @IgnoreValidation val ignoredMap: Map<String, String>,
    val validated: JavaFluentInput?,
    val sibling: String?,
    @IgnoreValidation val ignoredNext: IgnoredInput? = null
)

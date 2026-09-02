// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.Command
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Command
public data class InvalidSizeBounds(
    @field:Size(min = 3, max = 2) public val value: String
) {
    public fun handle(): String = value
}

@Command
public data class UnrepresentableDecimalBound(
    @field:DecimalMin("9007199254740992") public val value: Long
) {
    public fun handle(): Long = value
}

@Command
public data class UnrepresentablePattern(
    @field:Pattern(regexp = "\\A[a-z]+\\z") public val value: String
) {
    public fun handle(): String = value
}

@Command
public data class ContradictoryNumericBounds(
    @field:Min(10) @field:Max(5) public val value: Int
) {
    public fun handle(): Int = value
}

@Command
public data class ContradictoryLengthBounds(
    @field:NotEmpty @field:Size(max = 0) public val value: String
) {
    public fun handle(): String = value
}

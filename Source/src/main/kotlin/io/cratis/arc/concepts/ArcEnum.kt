// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.concepts

/**
 * Supplies an explicit integer wire value for an enum.
 *
 * Ordinary enums use their ordinal. Implement this interface when values must remain compatible with an explicit
 * .NET enum contract or stable when declaration order changes. KSP records constructor integer literals when it can
 * prove them; otherwise each entry must carry [ArcEnumValue].
 */
public interface ArcEnum {
    /** Returns the integer value written to the Arc wire format. */
    public fun value(): Int
}

/** Kotlin property view of the integer value written to the Arc wire format. */
@get:JvmSynthetic
public val ArcEnum.wireValue: Int
    get() = value()

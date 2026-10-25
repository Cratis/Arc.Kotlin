// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.polymorphism

import io.cratis.fundamentals.serialization.DerivedType as FundamentalsDerivedType

/**
 * Resolves the `_derivedTypeId` of a class from either `@DerivedType` annotation.
 *
 * When both annotations are present Arc's own (legacy) identifier wins, exactly as before the
 * Fundamentals annotation was honoured.
 */
internal object DerivedTypeIdentifiers {
    /**
     * The single switch for classes that carry only the Fundamentals annotation (Cratis/Arc.Kotlin#301, Q-79).
     * `true` honours them: they are registered and written with `_derivedTypeId`. Returning `false`
     * restores the previous behaviour in the registry and the serializer; KSP consults the same seam.
     */
    fun honoursFundamentalsOnlyDerivedTypes(): Boolean = true

    /** Returns the identifier declared on [type], or null when it carries no honoured annotation. */
    fun of(type: Class<*>): String? {
        val legacy = type.getAnnotation(DerivedType::class.java)?.id
        if (legacy != null) {
            return legacy
        }
        if (!honoursFundamentalsOnlyDerivedTypes()) {
            return null
        }
        return type.getAnnotation(FundamentalsDerivedType::class.java)?.value
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.polymorphism

/**
 * Identifies a concrete type in Arc's `_derivedTypeId` polymorphic wire format.
 *
 * Deprecated: Arc honours [io.cratis.fundamentals.serialization.DerivedType] and writes the same identifier.
 * When a class declares both annotations this one's identifier is used.
 */
@Deprecated(
    "Use io.cratis.fundamentals.serialization.DerivedType; Arc reads both and writes the same _derivedTypeId.",
    ReplaceWith("DerivedType(id)", "io.cratis.fundamentals.serialization.DerivedType")
)
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class DerivedType(public val id: String)

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.polymorphism

/** Identifies a concrete type in Arc's `_derivedTypeId` polymorphic wire format. */
@MustBeDocumented
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
public annotation class DerivedType(public val id: String)

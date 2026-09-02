// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.concepts

/**
 * Represents a strongly typed concept backed by a single wire value.
 *
 * The method shape intentionally matches a Java record component named `value`, allowing a record such as
 * `record OrderId(UUID value) implements ConceptAs<UUID> {}` to implement this contract without boilerplate.
 */
public interface ConceptAs<T> {
    /** Returns the scalar value carried by this concept. */
    public fun value(): T
}

/** Kotlin property view of the scalar value carried by this concept. */
@get:JvmSynthetic
public val <T> ConceptAs<T>.value: T
    get() = value()

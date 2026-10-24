// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:Suppress("DEPRECATION")

package io.cratis.arc.concepts

/**
 * Represents a strongly typed concept backed by a single wire value.
 * Implement [io.cratis.fundamentals.concepts.ConceptAs] instead.
 *
 * The method shape intentionally matches a Java record component named `value`, allowing a record such as
 * `record OrderId(UUID value) implements ConceptAs<UUID> {}` to implement this contract without boilerplate.
 */
@Deprecated("Implement io.cratis.fundamentals.concepts.ConceptAs.", ReplaceWith("ConceptAs<T>", "io.cratis.fundamentals.concepts.ConceptAs"))
public interface ConceptAs<T> : io.cratis.fundamentals.concepts.ConceptAs<T> {
    /** Returns the scalar value carried by this concept. */
    override fun value(): T
}

/** Kotlin property view of the scalar value carried by this concept; prefer [io.cratis.fundamentals.concepts.value]. */
@Deprecated("Use io.cratis.fundamentals.concepts.value.", ReplaceWith("value", "io.cratis.fundamentals.concepts.value"))
@get:JvmSynthetic
public val <T> ConceptAs<T>.value: T
    get() = value()

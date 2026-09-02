// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.polymorphism

/** Registry used to resolve Arc derived-type identifiers without classpath scanning. */
public interface DerivedTypeRegistry {
    /** Registers an annotated concrete type for the supplied base type. Re-registering the same mapping is idempotent. */
    public fun register(baseType: Class<*>, derivedType: Class<*>)

    /** Resolves an identifier for a declared base type, or returns null when it is unknown. */
    public fun resolve(baseType: Class<*>, id: String): Class<*>?

    /** Resolves the registered identifier for a concrete type under a declared base type. */
    public fun idFor(baseType: Class<*>, derivedType: Class<*>): String?

    /** Returns a stable snapshot of base types that currently have registrations. */
    public fun registeredBaseTypes(): Set<Class<*>>
}

/** Kotlin property view of the base types that currently have registrations. */
@get:JvmSynthetic
public val DerivedTypeRegistry.baseTypes: Set<Class<*>>
    get() = registeredBaseTypes()

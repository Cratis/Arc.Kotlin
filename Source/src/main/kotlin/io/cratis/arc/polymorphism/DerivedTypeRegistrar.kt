// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.polymorphism

/**
 * Contributes derived types that code generation could not see.
 *
 * Generated artifact modules already carry every `@DerivedType` declared in their own compilation. An application
 * supplies one of these for a hierarchy that arrives from a dependency binary Arc's code generation never processed.
 * Registrars run after the generated registrations, in declaration order.
 */
public fun interface DerivedTypeRegistrar {
    /** Adds registrations to [registry]. Re-registering a mapping the modules already contributed is idempotent. */
    public fun registerDerivedTypes(registry: DerivedTypeRegistry)
}

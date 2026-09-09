// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.polymorphism

/**
 * One base-to-derivative mapping a compilation module contributes to a [DerivedTypeRegistry].
 *
 * Code generation emits these as real class references, so a host populates a registry without scanning the classpath
 * and without resolving names at runtime.
 */
public class DerivedTypeRegistration(
    /** Type a polymorphic value is declared as on the wire. */
    public val baseType: Class<*>,
    /** Concrete [DerivedType]-annotated implementation of [baseType]. */
    public val derivedType: Class<*>
) {
    override fun equals(other: Any?): Boolean = other is DerivedTypeRegistration &&
        baseType == other.baseType && derivedType == other.derivedType

    override fun hashCode(): Int = 31 * baseType.hashCode() + derivedType.hashCode()

    override fun toString(): String = "DerivedTypeRegistration(baseType=${baseType.name}, derivedType=${derivedType.name})"
}

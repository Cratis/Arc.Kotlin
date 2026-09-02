// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

/** Resolves a certified JPA persistence unit from the complete command tenant context. */
public interface JpaPersistenceUnitResolver {
    /** Immutable snapshot of all exact read-model types this resolver may serve. */
    public fun readModelTypes(): Set<Class<*>>

    /** Returns the unit certified for exactly [tenantId] and [tenantNamespace], or `null` when none is available. */
    public fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit?
}

internal class FixedJpaPersistenceUnitResolver(internal val persistenceUnit: JpaPersistenceUnit) :
    JpaPersistenceUnitResolver {
    private val types: Set<Class<*>> = persistenceUnit.readModelTypes()

    override fun readModelTypes(): Set<Class<*>> = types

    override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? =
        persistenceUnit.takeIf { tenantId == null && tenantNamespace == null }
}

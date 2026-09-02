// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.ReadModelForCommandOwnership
import java.util.Collections
import java.util.LinkedHashSet

/** Resolves JPA-owned command read models through tenant-certified persistence units. */
public class JpaReadModelForCommandResolver @JvmOverloads constructor(
    internal val persistenceUnits: JpaPersistenceUnitResolver,
    internal val tenancyRequired: Boolean = false
) : BlockingReadModelForCommandResolver {
    private val types: Set<Class<*>> = snapshotClaims(persistenceUnits.readModelTypes())

    override fun readModelTypes(): Set<Class<*>> = types

    override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED

    override fun resolveBlocking(readModelType: Class<*>, commandContext: CommandContext, key: Any): Any? {
        if (readModelType !in types) return null
        if (tenancyRequired) {
            require(!commandContext.tenantId.isNullOrBlank()) {
                "A nonblank tenant identifier is required for JPA command read-model resolution."
            }
        }

        val persistenceUnit = checkNotNull(
            persistenceUnits.resolve(commandContext.tenantId, commandContext.tenantNamespace)
        ) {
            "No JPA persistence unit is available for tenant identifier '${commandContext.tenantId}' " +
                "and namespace '${commandContext.tenantNamespace}'."
        }
        check(persistenceUnit.tenantId == commandContext.tenantId &&
            persistenceUnit.tenantNamespace == commandContext.tenantNamespace) {
            "The resolved JPA persistence unit certificate does not match the command tenant context."
        }
        check(readModelType in persistenceUnit.readModelTypes()) {
            "The resolved JPA persistence unit does not map read model ${readModelType.name}."
        }
        return persistenceUnit.find(readModelType, key)
    }

    private companion object {
        private fun snapshotClaims(claims: Set<Class<*>>?): Set<Class<*>> {
            val copied = LinkedHashSet(checkNotNull(claims) {
                "JpaPersistenceUnitResolver.readModelTypes() cannot return null."
            })
            require(copied.none(Class<*>::isPrimitive)) { "JPA read-model types cannot be primitive." }
            require(copied.all { type -> type.isAnnotationPresent(ReadModel::class.java) }) {
                "JPA read-model types must be annotated with @ReadModel."
            }
            return Collections.unmodifiableSet(copied)
        }
    }
}

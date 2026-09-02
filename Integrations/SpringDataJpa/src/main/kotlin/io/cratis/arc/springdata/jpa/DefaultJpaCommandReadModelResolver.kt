// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.commands.CommandHandlerRegistry
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.springframework.orm.jpa.SharedEntityManagerCreator

/**
 * Compatibility-only JPA command read-model lookup.
 *
 * This resolver recomputes command keys and cannot certify tenant ownership. It is unsafe for required tenancy; use
 * [JpaReadModelForCommandResolver] for contextual command argument resolution.
 */
public class DefaultJpaCommandReadModelResolver(
    entityManagerFactory: EntityManagerFactory,
    private val handlers: CommandHandlerRegistry
) : JpaCommandReadModelResolver {
    private val entityManager: EntityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)

    override fun <T : Any> resolve(readModelType: Class<T>, command: Any): T? {
        require(readModelType.isAnnotationPresent(ReadModel::class.java)) {
            "${readModelType.name} is not annotated with @ReadModel."
        }
        val handler = checkNotNull(handlers.find(command.javaClass)) {
            "No Arc command handler is registered for ${command.javaClass.name}."
        }
        val key = handler.resolveCommandKey(command)
        require(key != null && (key !is String || key.isNotEmpty())) {
            "Command ${command.javaClass.name} does not provide a usable command key for ${readModelType.name}."
        }

        val entityType = entityManager.metamodel.entity(readModelType)
        check(entityType.hasSingleIdAttribute()) {
            "Read model ${readModelType.name} must have exactly one JPA identifier."
        }
        return entityManager.find(readModelType, key)
    }
}

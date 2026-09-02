// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.artifacts.ReadModel
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.SharedEntityManagerCreator

/**
 * A certified JPA persistence unit used for command-side read-model lookup.
 *
 * The unit owns neither [entityManagerFactory] nor [transactionManager]. The shared entity-manager proxy participates
 * in application-managed Spring transactions and is retained for the lifetime of this value.
 */
public class JpaPersistenceUnit @JvmOverloads constructor(
    public val entityManagerFactory: EntityManagerFactory,
    public val transactionManager: JpaTransactionManager? = null,
    public val tenantId: String? = null,
    public val tenantNamespace: String? = null
) {
    private val entityManager: EntityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)
    private val identifiers: Map<Class<*>, Class<*>>
    public val readModelTypes: Set<Class<*>>

    init {
        require(tenantId == null || tenantId.isNotBlank()) { "A certified JPA tenant identifier cannot be blank." }
        require(tenantNamespace == null || tenantNamespace.isNotBlank()) {
            "A certified JPA tenant namespace cannot be blank."
        }
        if (transactionManager != null) {
            require(transactionManager.entityManagerFactory === entityManagerFactory) {
                "The JPA transaction manager must use the persistence unit's EntityManagerFactory instance."
            }
        }

        val discovered = LinkedHashMap<Class<*>, Class<*>>()
        entityManagerFactory.metamodel.entities
            .sortedBy { entity -> entity.javaType.name }
            .filter { entity -> entity.javaType.isAnnotationPresent(ReadModel::class.java) }
            .forEach { entity ->
                check(entity.hasSingleIdAttribute()) {
                    "Read model ${entity.javaType.name} must have exactly one JPA identifier."
                }
                val identifierType = checkNotNull(entity.idType?.javaType) {
                    "Read model ${entity.javaType.name} does not expose a JPA identifier type."
                }
                discovered[entity.javaType] = boxed(identifierType)
            }
        identifiers = Collections.unmodifiableMap(discovered)
        readModelTypes = Collections.unmodifiableSet(LinkedHashSet(discovered.keys))
    }

    /** Returns the immutable exact set of `@ReadModel` entity types mapped by this persistence unit. */
    public fun readModelTypes(): Set<Class<*>> = readModelTypes

    internal fun find(readModelType: Class<*>, key: Any): Any? {
        val identifierType = checkNotNull(identifiers[readModelType]) {
            "JPA persistence unit does not map read model ${readModelType.name}."
        }
        require(identifierType.isInstance(key)) {
            "Command key for '${readModelType.name}' must be an instance of '${identifierType.name}', " +
                "but was '${key.javaClass.name}'."
        }
        return entityManager.find(readModelType, key)
    }

    /** Builds a persistence-unit certificate while keeping Java call sites explicit. */
    public class Builder(public val entityManagerFactory: EntityManagerFactory) {
        private var transactionManager: JpaTransactionManager? = null
        private var tenantId: String? = null
        private var tenantNamespace: String? = null

        /** Uses [value] when it is aligned with this builder's entity-manager factory. */
        public fun transactionManager(value: JpaTransactionManager?): Builder = apply {
            transactionManager = value
        }

        /** Certifies the unit for exactly [tenantId] and [tenantNamespace]. */
        @JvmOverloads
        public fun tenant(tenantId: String, tenantNamespace: String? = null): Builder = apply {
            this.tenantId = tenantId
            this.tenantNamespace = tenantNamespace
        }

        /** Creates the immutable persistence-unit certificate. */
        public fun build(): JpaPersistenceUnit = JpaPersistenceUnit(
            entityManagerFactory,
            transactionManager,
            tenantId,
            tenantNamespace
        )
    }

    public companion object {
        /** Creates a fixed, non-tenant persistence unit. */
        @JvmStatic
        public fun fixed(entityManagerFactory: EntityManagerFactory): JpaPersistenceUnit =
            JpaPersistenceUnit(entityManagerFactory)

        /** Creates a fixed, non-tenant persistence unit with its matching transaction manager. */
        @JvmStatic
        public fun fixed(
            entityManagerFactory: EntityManagerFactory,
            transactionManager: JpaTransactionManager
        ): JpaPersistenceUnit = JpaPersistenceUnit(entityManagerFactory, transactionManager)

        /** Starts a Java-friendly persistence-unit builder. */
        @JvmStatic
        public fun builder(entityManagerFactory: EntityManagerFactory): Builder = Builder(entityManagerFactory)
    }
}

private fun boxed(type: Class<*>): Class<*> = when (type) {
    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
    java.lang.Byte.TYPE -> java.lang.Byte::class.java
    java.lang.Character.TYPE -> java.lang.Character::class.java
    java.lang.Short.TYPE -> java.lang.Short::class.java
    java.lang.Integer.TYPE -> java.lang.Integer::class.java
    java.lang.Long.TYPE -> java.lang.Long::class.java
    java.lang.Float.TYPE -> java.lang.Float::class.java
    java.lang.Double.TYPE -> java.lang.Double::class.java
    java.lang.Void.TYPE -> java.lang.Void::class.java
    else -> type
}

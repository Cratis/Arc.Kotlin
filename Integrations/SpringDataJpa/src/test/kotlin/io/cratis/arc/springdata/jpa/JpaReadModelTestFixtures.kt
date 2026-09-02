// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.metamodel.EntityType
import jakarta.persistence.metamodel.Metamodel
import jakarta.persistence.metamodel.Type
import java.util.UUID
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate

internal data class JpaMapping(
    val entityType: Class<*>,
    val identifierType: Class<*>,
    val hasSingleIdentifier: Boolean = true
)

internal data class MockJpaStore(
    val entityManagerFactory: EntityManagerFactory,
    val entityManager: EntityManager
)

@Suppress("UNCHECKED_CAST")
internal fun mockJpaStore(vararg mappings: JpaMapping): MockJpaStore {
    val factory = mock(EntityManagerFactory::class.java)
    val entityManager = mock(EntityManager::class.java)
    val metamodel = mock(Metamodel::class.java)
    val entities = mappings.map { mapping ->
        val entity = mock(EntityType::class.java) as EntityType<Any>
        val identifier = mock(Type::class.java) as Type<Any>
        `when`(entity.javaType).thenReturn(mapping.entityType as Class<Any>)
        `when`(entity.hasSingleIdAttribute()).thenReturn(mapping.hasSingleIdentifier)
        `when`(identifier.javaType).thenReturn(mapping.identifierType as Class<Any>)
        `when`(entity.idType).thenReturn(identifier)
        entity
    }.toSet()
    `when`(factory.metamodel).thenReturn(metamodel)
    `when`(metamodel.entities).thenReturn(entities)
    `when`(factory.createEntityManager()).thenReturn(entityManager)
    return MockJpaStore(factory, entityManager)
}

internal class H2JpaStore(name: String) : AutoCloseable {
    private val factoryBean = LocalContainerEntityManagerFactoryBean().apply {
        dataSource = DriverManagerDataSource("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1", "sa", "")
        setPackagesToScan(JpaTaskReadModel::class.java.packageName)
        persistenceUnitName = name
        jpaVendorAdapter = HibernateJpaVendorAdapter()
        setJpaPropertyMap(
            mapOf(
                "hibernate.hbm2ddl.auto" to "create-drop",
                "hibernate.show_sql" to "false"
            )
        )
        afterPropertiesSet()
    }

    val entityManagerFactory: EntityManagerFactory = checkNotNull(factoryBean.nativeEntityManagerFactory)
    val transactionManager: JpaTransactionManager = JpaTransactionManager(entityManagerFactory)
    val sharedEntityManager: EntityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory)

    fun store(readModel: JpaTaskReadModel) {
        TransactionTemplate(transactionManager).executeWithoutResult {
            sharedEntityManager.persist(readModel)
        }
    }

    override fun close() {
        factoryBean.destroy()
    }
}

internal fun commandContext(
    tenantId: String? = null,
    tenantNamespace: String? = null,
    key: Any = "task-1",
    serviceResolver: ServiceResolver = EmptyJpaServiceResolver
): CommandContext {
    val command = Any()
    return CommandContext(
        correlationId = UUID.randomUUID(),
        command = command,
        commandType = command.javaClass,
        principal = ArcPrincipal.anonymous(),
        tenantId = tenantId,
        tenantNamespace = tenantNamespace,
        serviceResolver = serviceResolver,
        commandKey = key
    )
}

private data object EmptyJpaServiceResolver : ServiceResolver {
    override fun <T : Any> resolve(type: Class<T>): T? = null
}

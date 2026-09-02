// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.queries.CanResolveReadModelForCommand
import io.cratis.arc.queries.ReadModelForCommandOwnership
import io.cratis.arc.springdata.jpa.springboot.ArcSpringDataJpaAutoConfiguration
import io.cratis.arc.springboot.ArcProperties
import jakarta.persistence.EntityManagerFactory
import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus

internal class JpaAutoConfigurationTests {
    @Test
    fun `optional tenancy configures fixed resolver contextual provider and legacy resolver without a transaction scope by default`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val transactionManager = JpaTransactionManager(store.entityManagerFactory)

        runner(store.entityManagerFactory)
            .withBean(JpaTransactionManager::class.java, { transactionManager })
            .run { context ->
                assertThat(context).hasSingleBean(JpaPersistenceUnitResolver::class.java)
                assertThat(context).hasSingleBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context).hasSingleBean(JpaCommandReadModelResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
                val provider = context.getBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context.getBeansOfType(CanResolveReadModelForCommand::class.java).values).contains(provider)
                val unit = context.getBean(JpaPersistenceUnitResolver::class.java).resolve(null, null)
                assertSame(transactionManager, unit?.transactionManager)
            }
    }

    @Test
    fun `unrelated ownership provider does not suppress contextual JPA provider`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val unrelated = object : CanResolveReadModelForCommand {
            override fun readModelTypes(): Set<Class<*>> = emptySet()
            override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.FALLBACK
            override fun resolve(
                readModelType: Class<*>,
                commandContext: io.cratis.arc.commands.CommandContext,
                key: Any
            ) = CompletableFuture.completedFuture<Any?>(null)
        }

        runner(store.entityManagerFactory)
            .withBean(CanResolveReadModelForCommand::class.java, { unrelated })
            .run { context ->
                assertThat(context).hasSingleBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context.getBeansOfType(CanResolveReadModelForCommand::class.java)).hasSize(2)
            }
    }

    @Test
    fun `required tenancy with mapped read models fails without an application unit resolver`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))

        runner(store.entityManagerFactory, required = true).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("exactly one JpaPersistenceUnitResolver")
        }
    }

    @Test
    fun `required tenancy without mapped read models does not invent a fixed resolver`() {
        val store = mockJpaStore()

        runner(store.entityManagerFactory, required = true).run { context ->
            assertThat(context).doesNotHaveBean(JpaPersistenceUnitResolver::class.java)
            assertThat(context).doesNotHaveBean(JpaReadModelForCommandResolver::class.java)
            assertThat(context).doesNotHaveBean(JpaCommandReadModelResolver::class.java)
            assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
        }
    }

    @Test
    fun `required tenancy uses custom tenant resolver and does not configure legacy resolver or fixed scope`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val transactionManager = JpaTransactionManager(store.entityManagerFactory)
        val unit = JpaPersistenceUnit(
            store.entityManagerFactory,
            transactionManager,
            "tenant-one",
            "namespace-one"
        )
        val applicationResolver = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = unit.readModelTypes()
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? =
                unit.takeIf { tenantId == "tenant-one" && tenantNamespace == "namespace-one" }
        }

        runner(store.entityManagerFactory, required = true)
            .withBean(JpaTransactionManager::class.java, { transactionManager })
            .withBean(JpaPersistenceUnitResolver::class.java, { applicationResolver })
            .run { context ->
                assertSame(applicationResolver, context.getBean(JpaPersistenceUnitResolver::class.java))
                assertThat(context).hasSingleBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandReadModelResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
            }
    }

    @Test
    fun `optional dynamic resolver never enables the fixed legacy adapter or transaction scope even under the reserved bean name`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val unit = JpaPersistenceUnit(store.entityManagerFactory, null, "tenant-one", "namespace-one")
        val dynamicResolver = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = unit.readModelTypes()
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? = unit
        }

        runner(store.entityManagerFactory)
            .withBean("arcJpaPersistenceUnitResolver", JpaPersistenceUnitResolver::class.java, { dynamicResolver })
            .run { context ->
                assertThat(context.startupFailure).isNull()
                assertSame(dynamicResolver, context.getBean(JpaPersistenceUnitResolver::class.java))
                assertThat(context).hasSingleBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandReadModelResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
            }
    }

    @Test
    fun `required tenancy rejects incomplete persistence unit and provider claims`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val incompleteResolver = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = emptySet()
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? = null
        }
        val provider = JpaReadModelForCommandResolver(incompleteResolver, true)

        runner(store.entityManagerFactory, required = true)
            .withBean(JpaPersistenceUnitResolver::class.java, { incompleteResolver })
            .withBean(JpaReadModelForCommandResolver::class.java, { provider })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("claim every mapped @ReadModel type")
            }
    }

    @Test
    fun `required tenancy rejects a custom contextual provider that was constructed without required enforcement`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val unit = JpaPersistenceUnit(store.entityManagerFactory, null, "tenant-one", "namespace-one")
        val dynamicResolver = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = unit.readModelTypes()
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? = unit
        }
        val unsafeProvider = JpaReadModelForCommandResolver(dynamicResolver)

        runner(store.entityManagerFactory, required = true)
            .withBean(JpaPersistenceUnitResolver::class.java, { dynamicResolver })
            .withBean(JpaReadModelForCommandResolver::class.java, { unsafeProvider })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("tenancyRequired=true")
            }
    }

    @Test
    fun `multiple entity manager factories back off optional fixed and legacy paths`() {
        val first = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val second = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))

        baseRunner()
            .withBean("firstEntityManagerFactory", EntityManagerFactory::class.java, { first.entityManagerFactory })
            .withBean("secondEntityManagerFactory", EntityManagerFactory::class.java, { second.entityManagerFactory })
            .run { context ->
                assertThat(context).doesNotHaveBean(JpaPersistenceUnitResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandReadModelResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
                assertThat(context).doesNotHaveBean(JpaObservableQuery::class.java)
            }
    }

    @Test
    fun `multiple entity manager factories fail the required tenancy guard when either maps read models`() {
        val first = mockJpaStore()
        val second = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))

        baseRunner(required = true)
            .withBean("firstEntityManagerFactory", EntityManagerFactory::class.java, { first.entityManagerFactory })
            .withBean("secondEntityManagerFactory", EntityManagerFactory::class.java, { second.entityManagerFactory })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("JpaPersistenceUnitResolver")
            }
    }

    @Test
    fun `aligned fixed transaction scope requires explicit opt in`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val transactionManager = JpaTransactionManager(store.entityManagerFactory)

        runner(store.entityManagerFactory)
            .withPropertyValues("cratis.arc.spring-data.jpa.command-transactions-enabled=true")
            .withBean(JpaTransactionManager::class.java, { transactionManager })
            .run { context ->
                assertThat(context).hasSingleBean(JpaCommandExecutionScope::class.java)
            }
    }

    @Test
    fun `multiple JPA transaction managers do not create a fixed command scope`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))

        runner(store.entityManagerFactory)
            .withBean("firstJpaTransactionManager", JpaTransactionManager::class.java, {
                JpaTransactionManager(store.entityManagerFactory)
            })
            .withBean("secondJpaTransactionManager", JpaTransactionManager::class.java, {
                JpaTransactionManager(store.entityManagerFactory)
            })
            .run { context ->
                assertThat(context).hasSingleBean(JpaPersistenceUnitResolver::class.java)
                assertThat(context).hasSingleBean(JpaReadModelForCommandResolver::class.java)
                assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
                val unit = context.getBean(JpaPersistenceUnitResolver::class.java).resolve(null, null)
                assertThat(unit?.transactionManager).isNull()
            }
    }

    @Test
    fun `mismatched JPA transaction manager is not attached and arbitrary platform manager is ignored`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val other = mockJpaStore()

        runner(store.entityManagerFactory)
            .withBean(JpaTransactionManager::class.java, { JpaTransactionManager(other.entityManagerFactory) })
            .withBean("unrelatedTransactionManager", UnrelatedTransactionManager::class.java, {
                UnrelatedTransactionManager()
            })
            .run { context ->
                assertThat(context).doesNotHaveBean(JpaCommandExecutionScope::class.java)
                val unit = context.getBean(JpaPersistenceUnitResolver::class.java).resolve(null, null)
                assertThat(unit?.transactionManager).isNull()
            }
    }

    @Test
    fun `backs off for application legacy resolver while retaining contextual path`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val applicationResolver = object : JpaCommandReadModelResolver {
            override fun <T : Any> resolve(readModelType: Class<T>, command: Any): T? = null
        }

        runner(store.entityManagerFactory)
            .withBean(JpaCommandReadModelResolver::class.java, { applicationResolver })
            .run { context ->
                assertSame(applicationResolver, context.getBean(JpaCommandReadModelResolver::class.java))
                assertThat(context).hasSingleBean(JpaReadModelForCommandResolver::class.java)
            }
    }

    private fun runner(
        entityManagerFactory: EntityManagerFactory,
        required: Boolean = false
    ): ApplicationContextRunner = baseRunner(required)
        .withBean(EntityManagerFactory::class.java, { entityManagerFactory })

    private fun baseRunner(required: Boolean = false): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ArcSpringDataJpaAutoConfiguration::class.java))
        .withPropertyValues("cratis.arc.tenancy.required=$required")
        .withBean(ArcProperties::class.java, { ArcProperties().also { it.tenancy.isRequired = required } })
        .withBean(CommandHandlerRegistry::class.java, { mock(CommandHandlerRegistry::class.java) })

    private class UnrelatedTransactionManager : AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: org.springframework.transaction.TransactionDefinition) = Unit
        override fun doCommit(status: DefaultTransactionStatus) = Unit
        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.commands.CommandHandlerArgumentResolver
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import java.util.Collections
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate

internal class JpaTenantIsolationTests {
    @Test
    fun `same key is isolated across two tenant factories and transaction managers under concurrency`() {
        H2JpaStore("arc-jpa-tenant-one").use { first ->
            H2JpaStore("arc-jpa-tenant-two").use { second ->
                first.store(JpaTaskReadModel("same-key", "Tenant one"))
                second.store(JpaTaskReadModel("same-key", "Tenant two"))
                val firstUnit = JpaPersistenceUnit(
                    first.entityManagerFactory,
                    first.transactionManager,
                    "tenant-one",
                    "namespace-one"
                )
                val secondUnit = JpaPersistenceUnit(
                    second.entityManagerFactory,
                    second.transactionManager,
                    "tenant-two",
                    "namespace-two"
                )
                val provider = JpaReadModelForCommandResolver(TwoTenantUnits(firstUnit, secondUnit), true)

                assertEquals(
                    "Tenant one",
                    resolveTitle(provider, "tenant-one", "namespace-one", "same-key")
                )
                assertEquals(
                    "Tenant two",
                    resolveTitle(provider, "tenant-two", "namespace-two", "same-key")
                )

                val executor = Executors.newFixedThreadPool(8)
                try {
                    val calls = (0 until 100).map { index ->
                        Callable {
                            if (index % 2 == 0) {
                                resolveTitle(provider, "tenant-one", "namespace-one", "same-key")
                            } else {
                                resolveTitle(provider, "tenant-two", "namespace-two", "same-key")
                            }
                        }
                    }
                    val results = executor.invokeAll(calls).map { future -> future.get() }
                    results.forEachIndexed { index, title ->
                        assertEquals(if (index % 2 == 0) "Tenant one" else "Tenant two", title)
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `unit shared entity manager participates in an application transaction`() {
        H2JpaStore("arc-jpa-shared-transaction").use { store ->
            val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory, store.transactionManager)
            val provider = JpaReadModelForCommandResolver(FixedJpaPersistenceUnitResolver(unit))
            val pending = JpaTaskReadModel("pending", "Pending")

            TransactionTemplate(store.transactionManager).executeWithoutResult { status ->
                store.sharedEntityManager.persist(pending)

                val resolved = provider.resolveBlocking(
                    JpaTaskReadModel::class.java,
                    commandContext(key = "pending"),
                    "pending"
                )

                assertSame(pending, resolved)
                status.setRollbackOnly()
            }

            assertEquals(null, provider.resolveBlocking(JpaTaskReadModel::class.java, commandContext(), "pending"))
        }
    }

    @Test
    fun `command argument resolver loads the JPA model through the ownership registry`() = runBlocking {
        H2JpaStore("arc-jpa-argument-resolver").use { store ->
            store.store(JpaTaskReadModel("argument-key", "Argument resolved"))
            val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory, store.transactionManager)
            val provider = JpaReadModelForCommandResolver(FixedJpaPersistenceUnitResolver(unit))
            val registry = ReadModelForCommandResolverRegistry(listOf(provider))
            val services = object : ServiceResolver {
                override fun <T : Any> resolve(type: Class<T>): T? =
                    if (type == ReadModelForCommandResolverRegistry::class.java) type.cast(registry) else null
            }
            val context = commandContext(key = "argument-key", serviceResolver = services)

            val resolved = CommandHandlerArgumentResolver(context).resolve(
                JpaTaskReadModel::class.java,
                "handle",
                "readModel"
            )

            assertEquals("Argument resolved", resolved.title)
        }
    }

    private fun resolveTitle(
        provider: JpaReadModelForCommandResolver,
        tenantId: String,
        tenantNamespace: String,
        key: String
    ): String? = (provider.resolveBlocking(
        JpaTaskReadModel::class.java,
        commandContext(tenantId, tenantNamespace, key),
        key
    ) as JpaTaskReadModel?)?.title

    private class TwoTenantUnits(vararg units: JpaPersistenceUnit) : JpaPersistenceUnitResolver {
        private val byTenant = units.associateBy { unit -> unit.tenantId to unit.tenantNamespace }
        private val types = Collections.unmodifiableSet(
            units.flatMap { unit -> unit.readModelTypes() }.toCollection(linkedSetOf())
        )

        override fun readModelTypes(): Set<Class<*>> = types

        override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? =
            byTenant[tenantId to tenantNamespace]
    }
}

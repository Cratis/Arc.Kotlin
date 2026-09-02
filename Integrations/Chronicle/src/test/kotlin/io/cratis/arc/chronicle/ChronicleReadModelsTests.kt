// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.ReadModelForCommandOwnership
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.readModels.IReadModelsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class ChronicleReadModelsTests {
    private val coroutineScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `resolver declares exact Chronicle ownership and resolves by command key from tenant store`() {
        val readModels = mockk<IReadModelsService>()
        val eventStore = mockk<IEventStore>()
        val expected = AccountBalance(42)
        every { eventStore.namespace } returns "tenant-one"
        every { eventStore.readModels } returns readModels
        coEvery { readModels.getInstanceByKey(AccountBalance::class, "account-42") } returns expected
        val resolver = ChronicleReadModelForCommandResolver(
            listOf(AccountBalance::class.java),
            TenantEventStoreResolver { namespace -> eventStore.takeIf { namespace == "tenant-one" } },
            coroutineScope
        )

        val resolved = resolver.resolve(
            AccountBalance::class.java,
            commandContext("tenant-one"),
            "account-42"
        ).toCompletableFuture().join()

        assertEquals(setOf(AccountBalance::class.java), resolver.readModelTypes())
        assertEquals(ReadModelForCommandOwnership.DECLARED, resolver.ownership())
        assertSame(expected, resolved)
        coVerify(exactly = 1) { readModels.getInstanceByKey(AccountBalance::class, "account-42") }
    }

    @Test
    fun `resolver fails closed for a tenant mismatched store without reading a model`() {
        val readModels = mockk<IReadModelsService>(relaxed = true)
        val eventStore = mockk<IEventStore>()
        every { eventStore.namespace } returns "tenant-two"
        every { eventStore.readModels } returns readModels
        val resolver = ChronicleReadModelForCommandResolver(
            listOf(AccountBalance::class.java),
            TenantEventStoreResolver { eventStore },
            coroutineScope
        )

        assertThrows(java.util.concurrent.CompletionException::class.java) {
            resolver.resolve(
                AccountBalance::class.java,
                commandContext("tenant-one"),
                "account-42"
            ).toCompletableFuture().join()
        }
        coVerify(exactly = 0) { readModels.getInstanceByKey<Any>(any(), any()) }
    }

    @Test
    fun `interceptor releases owned read model through tenant store and preserves replacement`() {
        val readModels = mockk<IReadModelsService>()
        val eventStore = mockk<IEventStore>()
        val protected = AccountBalance(0)
        val released = AccountBalance(42)
        every { eventStore.namespace } returns "tenant-one"
        every { eventStore.readModels } returns readModels
        coEvery { readModels.release(protected) } returns released
        val interceptor = ChronicleReadModelInterceptor(
            listOf(AccountBalance::class.java),
            TenantEventStoreResolver { namespace -> eventStore.takeIf { namespace == "tenant-one" } },
            coroutineScope
        )

        val result = interceptor.intercept(protected, queryContext("tenant-one")).toCompletableFuture().join()

        assertSame(released, result)
        coVerify(exactly = 1) { readModels.release(protected) }
    }

    @Test
    fun `interceptor does not resolve a store or release an unowned type`() {
        var resolverInvoked = false
        val interceptor = ChronicleReadModelInterceptor(
            listOf(AccountBalance::class.java),
            TenantEventStoreResolver {
                resolverInvoked = true
                null
            },
            coroutineScope
        )
        val unowned = UnownedModel("unchanged")

        val result = interceptor.intercept(unowned, queryContext("tenant-one")).toCompletableFuture().join()

        assertSame(unowned, result)
        assertEquals(false, resolverInvoked)
    }

    private fun commandContext(tenantNamespace: String?): CommandContext = CommandContext(
        correlationId = UUID.randomUUID(),
        command = TestCommand("account-42"),
        commandType = TestCommand::class.java,
        principal = ArcPrincipal.anonymous(),
        tenantId = tenantNamespace,
        tenantNamespace = tenantNamespace,
        serviceResolver = EmptyServiceResolver,
        commandKey = "account-42"
    )

    private fun queryContext(tenantNamespace: String?): QueryContext {
        val queryName = FullyQualifiedQueryName("accounts.balance")
        return QueryContext(
            correlationId = UUID.randomUUID(),
            request = QueryRequest(queryName),
            queryName = queryName,
            principal = ArcPrincipal.anonymous(),
            tenantId = tenantNamespace,
            tenantNamespace = tenantNamespace,
            serviceResolver = EmptyServiceResolver,
            allowedValidationSeverity = null,
            exposeExceptionDetails = true
        )
    }

    private data class TestCommand(val key: String)
    private data class AccountBalance(val amount: Int)
    private data class UnownedModel(val value: String)

    private data object EmptyServiceResolver : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    }
}

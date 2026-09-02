// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.identity

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.tenancy.AsyncTenantsProvider
import io.cratis.arc.tenancy.AsyncTenantsProviderAdapter
import io.cratis.arc.tenancy.Tenant
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.TenantName
import io.cratis.arc.tenancy.TenantsProvider
import io.cratis.arc.tenancy.TenantsProviderAggregator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DevelopmentProvidersTest {
    @Test
    fun `users aggregate in provider order and first duplicate wins`() = runBlocking {
        val first = user("one", "first")
        val duplicate = user("one", "duplicate")
        val second = user("two", "second")
        val providers = mutableListOf<UsersProvider>(
            UsersProvider { listOf(first, second) },
            UsersProvider { listOf(duplicate, user("three", "third")) }
        )
        val aggregator = UsersProviderAggregator(providers)
        providers.clear()

        val result = aggregator.provide()

        assertEquals(listOf(first, second, user("three", "third")), result)
        assertThrows(UnsupportedOperationException::class.java) {
            (result as MutableList<User>).add(user("four", "fourth"))
        }
    }

    @Test
    fun `tenants aggregate in provider order and first duplicate wins`() = runBlocking {
        val first = tenant("one", "First")
        val duplicate = tenant("one", "Duplicate")
        val second = tenant("two", "Second")
        val aggregator = TenantsProviderAggregator(
            listOf(
                TenantsProvider { listOf(first, second) },
                TenantsProvider { listOf(duplicate, tenant("three", "Third")) }
            )
        )

        assertEquals(listOf(first, second, tenant("three", "Third")), aggregator.provide())
    }

    @Test
    fun `provider failures propagate and later providers are not invoked`() {
        var laterInvoked = false
        val failure = IllegalStateException("provider failed")
        val aggregator = UsersProviderAggregator(
            listOf(
                UsersProvider { throw failure },
                UsersProvider { laterInvoked = true; emptyList() }
            )
        )

        val thrown = assertThrows(IllegalStateException::class.java) { runBlocking { aggregator.provide() } }
        assertEquals(failure, thrown)
        assertEquals(false, laterInvoked)
    }

    @Test
    fun `Java asynchronous adapters and aggregate bridges preserve results`() {
        val asyncUsers = AsyncUsersProvider { CompletableFuture.completedFuture(listOf(user("one", "One"))) }
        val asyncTenants = AsyncTenantsProvider { CompletableFuture.completedFuture(listOf(tenant("one", "One"))) }
        val users = UsersProviderAggregator(listOf(AsyncUsersProviderAdapter(asyncUsers)))
        val tenants = TenantsProviderAggregator(listOf(AsyncTenantsProviderAdapter(asyncTenants)))
        val scope = CoroutineScope(Dispatchers.Default)

        assertEquals("one", users.provideAsync(scope).toCompletableFuture().get(5, TimeUnit.SECONDS)[0].principal.id)
        assertEquals(TenantId("one"), tenants.provideAsync(scope).toCompletableFuture().get(5, TimeUnit.SECONDS)[0].id)
        assertEquals("one", users.provideBlocking()[0].principal.id)
        assertEquals(TenantId("one"), tenants.provideBlocking()[0].id)
    }

    @Test
    fun `user model retains immutable principal snapshot`() {
        val roles = linkedSetOf("operator")
        val claims = mutableListOf(IdentityClaim("sub", "one"))
        val principal = ArcPrincipal("One", true, roles, "one", claims)
        val user = User(principal, mapOf("display" to "One"))
        roles.add("admin")
        claims.add(IdentityClaim("late", "value"))

        assertEquals(setOf("operator"), user.principal.roles)
        assertEquals(listOf(IdentityClaim("sub", "one")), user.principal.claims)
        assertNotSame(roles, user.principal.roles)
    }

    private fun user(id: String, name: String): User = User(ArcPrincipal(name, true, id = id))

    private fun tenant(id: String, name: String): Tenant = Tenant(TenantId(id), TenantName(name))
}

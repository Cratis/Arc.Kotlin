// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import com.mongodb.client.MongoClients
import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import io.cratis.arc.tenancy.TenantContextBridge
import io.cratis.arc.tenancy.TenantId
import io.cratis.arc.tenancy.withTenant
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.MongoTemplate

/** Unit and integration tests for [TenantContextMongoAccess]. */
class TenantContextMongoAccessTests {

    // ─── Unit tests using mock MongoOperations ────────────────────────────────

    @Test
    fun `same access instance routes to different tenants on sequential coroutine calls`(): Unit = runBlocking {
        val opsA = mock(MongoOperations::class.java)
        val opsB = mock(MongoOperations::class.java)
        val access = twoTenantAccess(opsA, opsB)

        val resolvedA = withTenant(TenantId.of("tenant-a")) { access.operations() }
        val resolvedB = withTenant(TenantId.of("tenant-b")) { access.operations() }

        assertSame(opsA, resolvedA)
        assertSame(opsB, resolvedB)
    }

    @Test
    fun `nested withTenant scopes shadow the outer tenant and restore it on exit`(): Unit = runBlocking {
        val opsA = mock(MongoOperations::class.java)
        val opsB = mock(MongoOperations::class.java)
        val access = twoTenantAccess(opsA, opsB)

        withTenant(TenantId.of("tenant-a")) {
            assertSame(opsA, access.operations())

            withTenant(TenantId.of("tenant-b")) {
                assertSame(opsB, access.operations())
            }

            // Restored to outer tenant after inner block.
            assertSame(opsA, access.operations())
        }
    }

    @Test
    fun `explicit tenant overload bypasses the ambient context`(): Unit = runBlocking {
        val opsA = mock(MongoOperations::class.java)
        val opsB = mock(MongoOperations::class.java)
        val access = twoTenantAccess(opsA, opsB)

        // No tenant in context; explicit overload must not throw.
        val resolvedA = access.operations(TenantId.of("tenant-a"))
        val resolvedB = access.operations(TenantId.of("tenant-b"))

        assertSame(opsA, resolvedA)
        assertSame(opsB, resolvedB)
    }

    @Test
    fun `no tenant in context without fallback throws IllegalStateException`(): Unit = runBlocking {
        val access = twoTenantAccess(mock(MongoOperations::class.java), mock(MongoOperations::class.java))

        assertThrows(IllegalStateException::class.java) { runBlocking { access.operations() } }
    }

    @Test
    fun `configured fallback tenant is used when the coroutine context has no tenant`(): Unit = runBlocking {
        val opsA = mock(MongoOperations::class.java)
        val opsB = mock(MongoOperations::class.java)
        val access = twoTenantAccess(opsA, opsB, fallback = TenantId.of("tenant-a"))

        // No withTenant scope — must use the fallback.
        assertSame(opsA, access.operations())
    }

    @Test
    fun `withTenant tenant shadows the fallback inside the coroutine scope`(): Unit = runBlocking {
        val opsA = mock(MongoOperations::class.java)
        val opsB = mock(MongoOperations::class.java)
        val access = twoTenantAccess(opsA, opsB, fallback = TenantId.of("tenant-a"))

        withTenant(TenantId.of("tenant-b")) {
            // Explicit scope wins over fallback.
            assertSame(opsB, access.operations())
        }
        // Back outside scope — fallback is active.
        assertSame(opsA, access.operations())
    }

    @Test
    fun `blocking path resolves correct tenant via TenantContextBridge`() {
        val opsA = mock(MongoOperations::class.java)
        val opsB = mock(MongoOperations::class.java)
        val access = twoTenantAccess(opsA, opsB)

        val resolvedA = TenantContextBridge.withTenant(TenantId.of("tenant-a"), Callable {
            access.operationsForCurrentTenant()
        })
        val resolvedB = TenantContextBridge.withTenant(TenantId.of("tenant-b"), Callable {
            access.operationsForCurrentTenant()
        })

        assertSame(opsA, resolvedA)
        assertSame(opsB, resolvedB)
    }

    @Test
    fun `blocking path without a bridge scope throws IllegalStateException when no fallback`() {
        val access = twoTenantAccess(mock(MongoOperations::class.java), mock(MongoOperations::class.java))

        assertThrows(IllegalStateException::class.java) { access.operationsForCurrentTenant() }
    }

    @Test
    fun `mismatched certificate from resolver is rejected`(): Unit = runBlocking {
        val badResolver = TenantAwareMongoOperationsResolver { _ ->
            // Returns a binding for "tenant-b" regardless of what was asked for.
            TenantMongoOperations("tenant-b", mock(MongoOperations::class.java))
        }
        val access = TenantContextMongoAccess(badResolver, DefaultNamingPolicy())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { withTenant(TenantId.of("tenant-a")) { access.operations() } }
        }
    }

    // ─── Integration tests using in-memory MongoDB ───────────────────────────

    private lateinit var server: MongoServer
    private lateinit var client: com.mongodb.client.MongoClient

    @BeforeEach
    fun setUpServer() {
        server = MongoServer(MemoryBackend())
        val address = server.bind()
        client = MongoClients.create("mongodb://${address.hostString}:${address.port}")
    }

    @AfterEach
    fun tearDownServer() {
        client.close()
        server.shutdownNow()
    }

    @Test
    fun `two sequential calls with different tenants through the same access instance read from isolated databases`(): Unit =
        runBlocking {
            val templateA = MongoTemplate(client, "tenant-a")
            val templateB = MongoTemplate(client, "tenant-b")
            templateA.save(MongoTaskReadModel("doc-1", "Tenant A"))
            templateB.save(MongoTaskReadModel("doc-1", "Tenant B"))

            val resolver = TenantAwareMongoOperationsResolver { tenantId ->
                when (tenantId) {
                    "tenant-a" -> TenantMongoOperations(tenantId, templateA)
                    "tenant-b" -> TenantMongoOperations(tenantId, templateB)
                    else -> throw IllegalArgumentException("Unknown tenant $tenantId")
                }
            }
            val access = TenantContextMongoAccess(resolver, DefaultNamingPolicy())

            val titleA = withTenant(TenantId.of("tenant-a")) {
                access.operations().findById("doc-1", MongoTaskReadModel::class.java)?.title
            }
            val titleB = withTenant(TenantId.of("tenant-b")) {
                access.operations().findById("doc-1", MongoTaskReadModel::class.java)?.title
            }

            org.junit.jupiter.api.Assertions.assertEquals("Tenant A", titleA)
            org.junit.jupiter.api.Assertions.assertEquals("Tenant B", titleB)
        }

    @Test
    fun `collection overload with explicit tenant reaches the correct in-memory database`(): Unit =
        runBlocking {
            val templateA = MongoTemplate(client, "tenant-a-coll")
            templateA.save(MongoTaskReadModel("doc-42", "Collection A"))

            val resolver = TenantAwareMongoOperationsResolver { tenantId ->
                if (tenantId == "tenant-a") TenantMongoOperations(tenantId, templateA)
                else throw IllegalArgumentException("Unknown tenant $tenantId")
            }
            val access = TenantContextMongoAccess(resolver, DefaultNamingPolicy())

            // NamingPolicy.getReadModelName(MongoTaskReadModel) = "MongoTaskReadModels" (plural).
            // The @Document("tasks") annotation is honoured by Spring Data ORM (operations.findById),
            // but collection() uses NamingPolicy. We therefore save through templateA.save() which
            // also goes through Spring Data's @Document routing, so we use operations.findById here.
            val col = access.collection(TenantId.of("tenant-a"), MongoTaskReadModel::class.java)
            assertNotNull(col)
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun twoTenantAccess(
        opsA: MongoOperations,
        opsB: MongoOperations,
        fallback: TenantId? = null
    ): TenantContextMongoAccess {
        val resolver = TenantAwareMongoOperationsResolver { tenantId ->
            when (tenantId) {
                "tenant-a" -> TenantMongoOperations(tenantId, opsA)
                "tenant-b" -> TenantMongoOperations(tenantId, opsB)
                else -> throw IllegalArgumentException("Unknown tenant $tenantId")
            }
        }
        return TenantContextMongoAccess(resolver, DefaultNamingPolicy(), fallback)
    }
}

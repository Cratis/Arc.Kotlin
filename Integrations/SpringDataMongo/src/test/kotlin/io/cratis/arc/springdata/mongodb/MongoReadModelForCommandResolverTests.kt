// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.queries.ReadModelForCommandOwnership
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.mapping.MongoMappingContext

class MongoReadModelForCommandResolverTests {
    @Test
    fun `claims an immutable deterministic snapshot of exact concrete mapped read models as fallback`() {
        val mappingContext = mappingContext(
            UnannotatedDocument::class.java,
            DerivedReadModel::class.java,
            AbstractReadModel::class.java,
            StringMongoReadModel::class.java,
            BaseReadModel::class.java,
            IntMongoReadModel::class.java
        )
        val resolver = MongoReadModelForCommandResolver(mappingContext, MongoOperationsResolver { mock() })

        assertEquals(
            listOf(BaseReadModel::class.java, IntMongoReadModel::class.java, StringMongoReadModel::class.java),
            resolver.readModelTypes().toList()
        )
        assertEquals(ReadModelForCommandOwnership.FALLBACK, resolver.ownership())
        assertFalse(resolver.readModelTypes().contains(DerivedReadModel::class.java))
        assertFalse(resolver.readModelTypes().contains(AbstractReadModel::class.java))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (resolver.readModelTypes() as MutableSet<Class<*>>).add(ObjectIdMongoReadModel::class.java)
        }

        mappingContext.getPersistentEntity(ObjectIdMongoReadModel::class.java)
        assertFalse(resolver.readModelTypes().contains(ObjectIdMongoReadModel::class.java))
    }

    @Test
    fun `rejects a mapped read model without an identifier when taking the snapshot`() {
        val exception = assertThrows(IllegalStateException::class.java) {
            MongoReadModelForCommandResolver(
                mappingContext(MissingIdMongoReadModel::class.java),
                MongoOperationsResolver { mock() }
            )
        }

        assertTrue(exception.message!!.contains("identifier"))
    }

    @Test
    fun `returns null for an unclaimed type without tenant resolver or store access`() {
        val operations = mock(MongoOperations::class.java)
        val calls = AtomicInteger()
        val tenantResolver = TenantAwareMongoOperationsResolver { tenantId ->
            calls.incrementAndGet()
            TenantMongoOperations(tenantId, operations)
        }
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            tenantResolver,
            tenancyRequired = true
        )

        assertNull(resolver.resolveBlocking(UnannotatedDocument::class.java, commandContext(null), "same-id"))
        assertEquals(0, calls.get())
        verifyNoInteractions(operations)
    }

    @Test
    fun `uses only the supplied key and validates boxed identifier assignability`() {
        val operations = mock(MongoOperations::class.java)
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(IntMongoReadModel::class.java, ObjectIdMongoReadModel::class.java),
            MongoOperationsResolver { operations }
        )
        val expected = IntMongoReadModel(42, "stored")
        `when`(operations.findById(42, IntMongoReadModel::class.java)).thenReturn(expected)

        assertSame(expected, resolver.resolveBlocking(IntMongoReadModel::class.java, commandContext(), 42))
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveBlocking(IntMongoReadModel::class.java, commandContext(), 42L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveBlocking(ObjectIdMongoReadModel::class.java, commandContext(), ObjectId().toHexString())
        }
        verify(operations).findById(42, IntMongoReadModel::class.java)
    }

    @Test
    fun `required tenancy rejects a plain resolver and null or blank tenants before store resolution`() {
        val operations = mock(MongoOperations::class.java)
        assertThrows(IllegalArgumentException::class.java) {
            MongoReadModelForCommandResolver(
                mappingContext(StringMongoReadModel::class.java),
                MongoOperationsResolver { operations },
                tenancyRequired = true
            )
        }

        val calls = AtomicInteger()
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            TenantAwareMongoOperationsResolver { tenantId ->
                calls.incrementAndGet()
                TenantMongoOperations(tenantId, operations)
            },
            tenancyRequired = true
        )
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext(null), "same-id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("  "), "same-id")
        }
        assertEquals(0, calls.get())
        verifyNoInteractions(operations)
    }

    @Test
    fun `optional tenancy permits a plain resolver only for a null tenant and rejects blank tenants`() {
        val operations = mock(MongoOperations::class.java)
        val calls = AtomicInteger()
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            MongoOperationsResolver {
                calls.incrementAndGet()
                operations
            }
        )

        assertNull(resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext(null), "missing"))
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("tenant-a"), "missing")
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext(""), "missing")
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `routes the same identifier to each tenant exactly once without default fallback`() {
        val tenantAOperations = mock(MongoOperations::class.java)
        val tenantBOperations = mock(MongoOperations::class.java)
        val tenantA = StringMongoReadModel("same-id", "tenant-a")
        val tenantB = StringMongoReadModel("same-id", "tenant-b")
        `when`(tenantAOperations.findById("same-id", StringMongoReadModel::class.java)).thenReturn(tenantA)
        `when`(tenantBOperations.findById("same-id", StringMongoReadModel::class.java)).thenReturn(tenantB)
        val selectedTenants = mutableListOf<String?>()
        val unknownTenant = IllegalStateException("unknown tenant")
        val operationsResolver = TenantAwareMongoOperationsResolver { tenantId ->
            selectedTenants += tenantId
            when (tenantId) {
                "tenant-a" -> TenantMongoOperations(tenantId, tenantAOperations)
                "tenant-b" -> TenantMongoOperations(tenantId, tenantBOperations)
                else -> throw unknownTenant
            }
        }
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            operationsResolver,
            tenancyRequired = true
        )

        assertSame(tenantA, resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("tenant-a"), "same-id"))
        assertSame(tenantB, resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("tenant-b"), "same-id"))
        assertSame(
            unknownTenant,
            assertThrows(IllegalStateException::class.java) {
                resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("unknown"), "same-id")
            }
        )
        assertEquals(listOf("tenant-a", "tenant-b", "unknown"), selectedTenants)
        verify(tenantAOperations).findById("same-id", StringMongoReadModel::class.java)
        verify(tenantBOperations).findById("same-id", StringMongoReadModel::class.java)
    }

    @Test
    fun `tenant certificate mismatch fails before storage access for provider and observation adapter`() {
        val operations = mock(MongoOperations::class.java)
        val mismatched = TenantAwareMongoOperationsResolver {
            TenantMongoOperations("tenant-b", operations)
        }
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            mismatched,
            tenancyRequired = true
        )

        assertThrows(IllegalStateException::class.java) {
            resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("tenant-a"), "same-id")
        }
        assertThrows(IllegalStateException::class.java) {
            TenantAwareMongoOperationsAdapter(mismatched).resolve("tenant-a")
        }
        verifyNoInteractions(operations)
    }

    @Test
    fun `tenant binding rejects null and blank certificates`() {
        val operations = mock(MongoOperations::class.java)

        assertThrows(IllegalArgumentException::class.java) { TenantMongoOperations(null, operations) }
        assertThrows(IllegalArgumentException::class.java) { TenantMongoOperations(" ", operations) }
        assertThrows(IllegalArgumentException::class.java) { TenantMongoOperations("tenant-a", null) }
    }

    @Test
    fun `returns missing documents and propagates store failures unchanged`() {
        val operations = mock(MongoOperations::class.java)
        val failure = IllegalStateException("store failed")
        `when`(operations.findById("failure", StringMongoReadModel::class.java)).thenThrow(failure)
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            MongoOperationsResolver { operations }
        )

        assertNull(resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext(), "missing"))
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext(), "failure")
            }
        )
    }

    @Test
    fun `supports concurrent resolution without shared mutable request state`() {
        val operations = mock(MongoOperations::class.java)
        val expected = StringMongoReadModel("same-id", "stored")
        `when`(operations.findById("same-id", StringMongoReadModel::class.java)).thenReturn(expected)
        val calls = AtomicInteger()
        val resolver = MongoReadModelForCommandResolver(
            mappingContext(StringMongoReadModel::class.java),
            TenantAwareMongoOperationsResolver { tenantId ->
                calls.incrementAndGet()
                TenantMongoOperations(tenantId, operations)
            },
            tenancyRequired = true
        )
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..100).map {
                executor.submit<Any?> {
                    resolver.resolveBlocking(StringMongoReadModel::class.java, commandContext("tenant-a"), "same-id")
                }
            }
            assertTrue(futures.all { it.get() === expected })
            assertEquals(100, calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun mappingContext(vararg types: Class<*>): MongoMappingContext = MongoMappingContext().also { context ->
        context.setInitialEntitySet(types.toSet())
        context.afterPropertiesSet()
    }

    private fun commandContext(tenantId: String? = null): CommandContext = CommandContext(
        UUID.randomUUID(),
        TestCommand,
        TestCommand::class.java,
        ArcPrincipal.anonymous(),
        tenantId = tenantId,
        serviceResolver = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        }
    )
}

private object TestCommand

@Document
@ReadModel
private data class StringMongoReadModel(@Id val id: String, val value: String)

@Document
@ReadModel
private data class IntMongoReadModel(@Id val id: Int, val value: String)

@Document
@ReadModel
private data class ObjectIdMongoReadModel(@Id val id: ObjectId, val value: String)

@Document
private data class UnannotatedDocument(@Id val id: String)

@Document
@ReadModel
private open class BaseReadModel(@Id val id: String)

@Document
private class DerivedReadModel(id: String) : BaseReadModel(id)

@Document
@ReadModel
private abstract class AbstractReadModel(@Id val id: String)

@Document
@ReadModel
private data class MissingIdMongoReadModel(val value: String)

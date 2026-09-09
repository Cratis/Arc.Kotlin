// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.queries.ReadModelForCommandOwnership
import jakarta.persistence.PersistenceException
import java.io.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.orm.jpa.JpaTransactionManager

internal class JpaPersistenceUnitTests {
    @Test
    fun `unit discovers only exact annotated entities and exposes an immutable snapshot`() {
        val store = mockJpaStore(
            JpaMapping(JpaTaskReadModel::class.java, String::class.java),
            JpaMapping(UnannotatedEntity::class.java, String::class.java)
        )

        val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory)

        assertEquals(setOf(JpaTaskReadModel::class.java), unit.readModelTypes())
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (unit.readModelTypes() as MutableSet<Class<*>>).add(UnannotatedEntity::class.java)
        }
        verify(store.entityManagerFactory, never()).close()
    }

    @Test
    fun `unit rejects an annotated entity without exactly one identifier`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, CompositeKey::class.java, false))

        val failure = assertThrows(IllegalStateException::class.java) {
            JpaPersistenceUnit.fixed(store.entityManagerFactory)
        }

        check(failure.message.orEmpty().contains("exactly one JPA identifier"))
    }

    @Test
    fun `unit rejects a transaction manager backed by another factory`() {
        val first = mockJpaStore()
        val second = mockJpaStore()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            JpaPersistenceUnit.fixed(first.entityManagerFactory, JpaTransactionManager(second.entityManagerFactory))
        }

        check(failure.message.orEmpty().contains("EntityManagerFactory instance"))
    }

    @Test
    fun `provider snapshots exact declared claims`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory)
        val mutableClaims = linkedSetOf<Class<*>>(JpaTaskReadModel::class.java)
        val resolver = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = mutableClaims
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit = unit
        }
        val provider = JpaReadModelForCommandResolver(resolver)
        mutableClaims.clear()

        assertEquals(setOf(JpaTaskReadModel::class.java), provider.readModelTypes())
        assertEquals(ReadModelForCommandOwnership.DECLARED, provider.ownership())
        assertNull(provider.resolveBlocking(UnannotatedEntity::class.java, commandContext(), "task-1"))
    }

    @Test
    fun `provider rejects primitive and unannotated claims`() {
        val primitive = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(Int::class.javaPrimitiveType!!)
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? = null
        }
        val unannotated = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(UnannotatedEntity::class.java)
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? = null
        }

        assertThrows(IllegalArgumentException::class.java) { JpaReadModelForCommandResolver(primitive) }
        assertThrows(IllegalArgumentException::class.java) { JpaReadModelForCommandResolver(unannotated) }
    }

    @Test
    fun `provider uses the supplied key and validates embedded identifier Java type`() {
        val store = mockJpaStore(JpaMapping(EmbeddedReadModel::class.java, CompositeKey::class.java))
        val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory)
        val provider = JpaReadModelForCommandResolver(FixedJpaPersistenceUnitResolver(unit))
        val key = CompositeKey("one", 42)
        val expected = EmbeddedReadModel(key)
        `when`(store.entityManager.find(EmbeddedReadModel::class.java, key)).thenReturn(expected)

        assertSame(expected, provider.resolveBlocking(EmbeddedReadModel::class.java, commandContext(), key))
        assertThrows(IllegalArgumentException::class.java) {
            provider.resolveBlocking(EmbeddedReadModel::class.java, commandContext(), "one-42")
        }
        verify(store.entityManager).find(EmbeddedReadModel::class.java, key)
    }

    @ParameterizedTest
    @MethodSource("identifierTypes")
    fun `provider validates exact boxed keys for primitive and boxed metamodel identifiers`(
        identifierType: Class<*>,
        key: Any
    ) {
        val store = mockJpaStore(JpaMapping(ScalarReadModel::class.java, identifierType))
        val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory)
        val provider = JpaReadModelForCommandResolver(FixedJpaPersistenceUnitResolver(unit))
        val expected = ScalarReadModel(key)
        `when`(store.entityManager.find(ScalarReadModel::class.java, key)).thenReturn(expected)

        assertSame(expected, provider.resolveBlocking(ScalarReadModel::class.java, commandContext(key = key), key))
        verify(store.entityManager).find(ScalarReadModel::class.java, key)
        val wrongKey: Any = if (key is Int) 42L else 42
        val failure = assertThrows(IllegalArgumentException::class.java) {
            provider.resolveBlocking(ScalarReadModel::class.java, commandContext(key = wrongKey), wrongKey)
        }
        assertEquals(
            "Command key for '${ScalarReadModel::class.java.name}' must be an instance of '${key.javaClass.name}', " +
                "but was '${wrongKey.javaClass.name}'.",
            failure.message
        )
        verify(store.entityManager, never()).find(ScalarReadModel::class.java, wrongKey)
    }

    @Test
    fun `void identifier types still reject an ordinary key before storage lookup`() {
        listOf(java.lang.Void.TYPE, java.lang.Void::class.java).forEach { identifierType ->
            val store = mockJpaStore(JpaMapping(ScalarReadModel::class.java, identifierType))
            val provider = JpaReadModelForCommandResolver(
                FixedJpaPersistenceUnitResolver(JpaPersistenceUnit.fixed(store.entityManagerFactory))
            )

            assertThrows(IllegalArgumentException::class.java) {
                provider.resolveBlocking(ScalarReadModel::class.java, commandContext(), "task-1")
            }
            verify(store.entityManager, never()).find(ScalarReadModel::class.java, "task-1")
        }
    }

    @Test
    fun `provider returns null for a missing row and propagates storage failures`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory)
        val provider = JpaReadModelForCommandResolver(FixedJpaPersistenceUnitResolver(unit))
        `when`(store.entityManager.find(JpaTaskReadModel::class.java, "broken")).thenThrow(PersistenceException("broken"))

        assertNull(provider.resolveBlocking(JpaTaskReadModel::class.java, commandContext(), "missing"))
        val failure = assertThrows(PersistenceException::class.java) {
            provider.resolveBlocking(JpaTaskReadModel::class.java, commandContext(), "broken")
        }
        assertEquals("broken", failure.message)
    }

    @Test
    fun `required tenancy rejects absent and blank tenant identifiers before unit resolution`() {
        var calls = 0
        val resolver = object : JpaPersistenceUnitResolver {
            override fun readModelTypes(): Set<Class<*>> = setOf(JpaTaskReadModel::class.java)
            override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? {
                calls++
                return null
            }
        }
        val provider = JpaReadModelForCommandResolver(resolver, true)

        assertThrows(IllegalArgumentException::class.java) {
            provider.resolveBlocking(JpaTaskReadModel::class.java, commandContext(), "task-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.resolveBlocking(JpaTaskReadModel::class.java, commandContext("  "), "task-1")
        }
        assertEquals(0, calls)
    }

    @Test
    fun `provider fails for unknown tenant certificate mismatch and missing exact mapping`() {
        val mappedStore = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val mismatchedUnit = JpaPersistenceUnit(
            mappedStore.entityManagerFactory,
            tenantId = "tenant-two",
            tenantNamespace = "namespace-two"
        )
        val emptyStore = mockJpaStore()
        val emptyUnit = JpaPersistenceUnit(
            emptyStore.entityManagerFactory,
            tenantId = "tenant-one",
            tenantNamespace = "namespace-one"
        )
        val unknown = resolver(setOf(JpaTaskReadModel::class.java)) { _, _ -> null }
        val mismatch = resolver(setOf(JpaTaskReadModel::class.java)) { _, _ -> mismatchedUnit }
        val missingMapping = resolver(setOf(JpaTaskReadModel::class.java)) { _, _ -> emptyUnit }
        val context = commandContext("tenant-one", "namespace-one")

        assertThrows(IllegalStateException::class.java) {
            JpaReadModelForCommandResolver(unknown, true)
                .resolveBlocking(JpaTaskReadModel::class.java, context, "task-1")
        }
        assertThrows(IllegalStateException::class.java) {
            JpaReadModelForCommandResolver(mismatch, true)
                .resolveBlocking(JpaTaskReadModel::class.java, context, "task-1")
        }
        assertThrows(IllegalStateException::class.java) {
            JpaReadModelForCommandResolver(missingMapping, true)
                .resolveBlocking(JpaTaskReadModel::class.java, context, "task-1")
        }
    }

    @Test
    fun `fixed resolver refuses any tenant context`() {
        val store = mockJpaStore(JpaMapping(JpaTaskReadModel::class.java, String::class.java))
        val unit = JpaPersistenceUnit.fixed(store.entityManagerFactory)
        val resolver = FixedJpaPersistenceUnitResolver(unit)

        assertSame(unit, resolver.resolve(null, null))
        assertNull(resolver.resolve("tenant-one", null))
        assertNull(resolver.resolve(null, "namespace-one"))
    }

    @Test
    fun `resolver configuration failures propagate unchanged`() {
        val expected = IllegalStateException("resolver failed")
        val resolver = resolver(setOf(JpaTaskReadModel::class.java)) { _, _ -> throw expected }
        val provider = JpaReadModelForCommandResolver(resolver)

        val actual = assertThrows(IllegalStateException::class.java) {
            provider.resolveBlocking(JpaTaskReadModel::class.java, commandContext(), "task-1")
        }
        assertSame(expected, actual)
    }

    private fun resolver(
        types: Set<Class<*>>,
        resolve: (String?, String?) -> JpaPersistenceUnit?
    ): JpaPersistenceUnitResolver = object : JpaPersistenceUnitResolver {
        override fun readModelTypes(): Set<Class<*>> = types
        override fun resolve(tenantId: String?, tenantNamespace: String?): JpaPersistenceUnit? =
            resolve(tenantId, tenantNamespace)
    }

    private class UnannotatedEntity

    @ReadModel
    private class EmbeddedReadModel(val id: CompositeKey)

    @ReadModel
    private class ScalarReadModel(val id: Any)

    private data class CompositeKey(val left: String, val right: Int) : Serializable

    companion object {
        @JvmStatic
        fun identifierTypes(): List<Arguments> = listOf(true, 42.toByte(), 'x', 42.toShort(), 42, 42L, 42f, 42.0)
            .flatMap { key ->
                listOf(checkNotNull(key.javaClass.kotlin.javaPrimitiveType), key.javaClass)
                    .map { identifierType -> Arguments.of(identifierType, key) }
            }
    }
}

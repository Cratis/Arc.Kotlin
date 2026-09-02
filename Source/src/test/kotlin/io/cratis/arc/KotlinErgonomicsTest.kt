// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc

import io.cratis.arc.commands.CommandKeyProvider
import io.cratis.arc.commands.MissingServiceException
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.commands.commandProvidedValuesOf
import io.cratis.arc.commands.commandResponseValuesOf
import io.cratis.arc.commands.key
import io.cratis.arc.commands.require
import io.cratis.arc.commands.resolve
import io.cratis.arc.concepts.ArcEnum
import io.cratis.arc.concepts.ConceptAs
import io.cratis.arc.concepts.value
import io.cratis.arc.concepts.wireValue
import io.cratis.arc.polymorphism.DerivedTypeRegistry
import io.cratis.arc.polymorphism.baseTypes
import io.cratis.arc.queries.BlockingQueryRendererFor
import io.cratis.arc.queries.BlockingReadModelForCommandResolver
import io.cratis.arc.queries.BlockingReadModelInterceptor
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryRendererResult
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.queries.ReadModelForCommandOwnership
import io.cratis.arc.queries.order
import io.cratis.arc.queries.ownership
import io.cratis.arc.queries.type
import io.cratis.arc.queries.types
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

internal class KotlinErgonomicsTest {
    @Test
    fun `concepts and Arc enums expose Kotlin property views`() {
        val concept = TestConcept("concept")

        assertEquals("concept", concept.value)
        assertEquals(7, TestEnum.VALUE.wireValue)
    }

    @Test
    fun `service resolver supports reified resolve and require`() {
        val resolver = MapServiceResolver(mapOf(String::class.java to "service"))

        assertEquals("service", resolver.resolve<String>())
        assertEquals("service", resolver.require<String>())
        assertNull(resolver.resolve<Int>())
        assertThrows(MissingServiceException::class.java) { resolver.require<Int>() }
    }

    @Test
    fun `framework strategy methods have Kotlin property views`() {
        val renderer = TestRenderer()
        val interceptor = TestInterceptor()
        val resolver = TestReadModelResolver()
        val registry = TestDerivedTypeRegistry()
        val commandKeyProvider = CommandKeyProvider { 42 }

        assertSame(String::class.java, renderer.type)
        assertEquals(3, renderer.order)
        assertSame(String::class.java, interceptor.type)
        assertEquals(4, interceptor.order)
        assertEquals(setOf(String::class.java), resolver.types)
        assertEquals(ReadModelForCommandOwnership.DECLARED, resolver.ownership)
        assertEquals(setOf(Number::class.java), registry.baseTypes)
        assertEquals(42, commandKeyProvider.key)
    }

    @Test
    fun `command value factories preserve declaration order`() {
        assertEquals(listOf("first", 2), commandProvidedValuesOf("first", 2).values)
        assertEquals(listOf("response", 3), commandResponseValuesOf("response", 3).values)
    }

    @Test
    fun `query paging and sorting are data values with reusable disabled instances`() {
        val paging = QueryPaging(1, 25)
        val sorting = QuerySorting("name", QuerySortDirection.DESCENDING)
        val (page, pageSize) = paging
        val (field, direction) = sorting

        assertEquals(QueryPaging(2, 25), paging.copy(page = 2))
        assertEquals(QuerySorting("name", QuerySortDirection.ASCENDING), sorting.copy(direction = QuerySortDirection.ASCENDING))
        assertEquals(listOf(1, 25), listOf(page, pageSize))
        assertEquals(listOf("name", QuerySortDirection.DESCENDING), listOf(field, direction))
        assertEquals(QueryPaging(0, 0), QueryPaging.UNPAGED)
        assertEquals(QuerySorting("", QuerySortDirection.ASCENDING), QuerySorting.UNSORTED)
    }

    private class TestConcept(private val rawValue: String) : ConceptAs<String> {
        override fun value(): String = rawValue
    }

    private enum class TestEnum(private val rawValue: Int) : ArcEnum {
        VALUE(7);

        override fun value(): Int = rawValue
    }

    private class MapServiceResolver(private val services: Map<Class<*>, Any>) : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = services[type]?.let(type::cast)
    }

    private class TestRenderer : BlockingQueryRendererFor<String> {
        override fun queryType(): Class<String> = String::class.java
        override fun order(): Int = 3
        override fun renderBlocking(
            query: String,
            current: QueryRendererResult,
            context: QueryContext
        ): QueryRendererResult = current
    }

    private class TestInterceptor : BlockingReadModelInterceptor<String> {
        override fun readModelType(): Class<String> = String::class.java
        override fun order(): Int = 4
        override fun interceptBlocking(readModel: String, context: QueryContext): String = readModel
    }

    private class TestReadModelResolver : BlockingReadModelForCommandResolver {
        override fun readModelTypes(): Set<Class<*>> = setOf(String::class.java)
        override fun ownership(): ReadModelForCommandOwnership = ReadModelForCommandOwnership.DECLARED
        override fun resolveBlocking(
            readModelType: Class<*>,
            commandContext: io.cratis.arc.commands.CommandContext,
            key: Any
        ): Any? = null
    }

    private class TestDerivedTypeRegistry : DerivedTypeRegistry {
        override fun register(baseType: Class<*>, derivedType: Class<*>) = Unit
        override fun resolve(baseType: Class<*>, id: String): Class<*>? = null
        override fun idFor(baseType: Class<*>, derivedType: Class<*>): String? = null
        override fun registeredBaseTypes(): Set<Class<*>> = setOf(Number::class.java)
    }
}

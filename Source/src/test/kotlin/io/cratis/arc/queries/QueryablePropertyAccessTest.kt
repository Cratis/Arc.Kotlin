// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.results.QueryResult
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class QueryablePropertyAccessTest {
    @Test
    fun `private state absent from JSON cannot control public row ordering`(): Unit = runBlocking {
        val rows = listOf(ConfidentialRow("first", 99), ConfidentialRow("second", 1))
        val serialized = ArcObjectMapper.create().readTree(ArcObjectMapper.create().writeValueAsString(rows))
        assertFalse(serialized[0].has("privateRank"))
        val result = perform(rows, "privateRank")
        assertFalse(result.isSuccess, "Sorting must not reveal the ordering of a private value")
        assertNull(result.data)
        assertTrue(result.hasExceptions)
    }

    @ParameterizedTest
    @ValueSource(strings = ["privateRank", "moduleRank", "protectedRank", "missing"])
    fun `nonpublic and absent properties fail even for a single row`(field: String): Unit = runBlocking {
        val result = perform(listOf(ConfidentialRow("single", 1)), field)
        assertFalse(result.isSuccess, "An invalid accessor must be rejected before comparisons are needed")
        assertNull(result.data)
    }

    @Test
    fun `public properties on private model classes remain usable`(): Unit = runBlocking {
        val first = ConfidentialRow("a", 99)
        val second = ConfidentialRow("b", 1)
        assertEquals(listOf(first, second), perform(listOf(second, first), "label").data)
    }

    @Test
    fun `public getters remain usable with private setters`(): Unit = runBlocking {
        val first = MutablePublicRow("a")
        val second = MutablePublicRow("b")
        assertEquals(listOf(first, second), perform(listOf(second, first), "label").data)
    }

    @Test
    fun `a rejected mixed row shape never invokes another rows getter`(): Unit = runBlocking {
        var reads = 0
        val readable = object { val rank: Int get() { reads++; return 1 } }
        val hidden = object { private val rank: Int = 2 }
        val result = perform(listOf(readable, hidden), "rank")
        assertFalse(result.isSuccess)
        assertEquals(0, reads)
        assertNull(result.data)
    }

    @Test
    fun `getter cancellation is not wrapped into a successful or failed query envelope`() {
        val row = object { val label: String get() = throw CancellationException("stop sorting") }
        assertThrows(CancellationException::class.java) { runBlocking { perform(listOf(row, row), "label") } }
    }

    @Test
    fun `getter fatal errors are not converted into ordinary query failures`() {
        val row = object { val label: String get() = throw AssertionError("fatal getter") }
        assertThrows(AssertionError::class.java) { runBlocking { perform(listOf(row, row), "label") } }
    }

    @Test
    fun `ordinary getter failures keep the exception path rather than becoming request validation`(): Unit = runBlocking {
        val row = object { val label: String get() = throw QueryArgumentException("label", "Getter failed") }
        val result = perform(listOf(row, row), "label")
        assertFalse(result.isSuccess)
        assertTrue(result.hasExceptions)
        assertTrue(result.validationResults.isEmpty())
        assertNull(result.data)
    }

    @Test
    fun `Kotlin subclass honors the public Java getter rather than inherited field storage`(): Unit = runBlocking {
        val first = KotlinBeanRow("ab")
        val second = KotlinBeanRow("ba")
        val result = perform(listOf(first, second), "name")
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(listOf(second, first), result.data)
    }

    @Test
    fun `nested invocation and stage wrappers preserve getter cancellation`() {
        val cancellation = CancellationException("nested stop")
        val row = object {
            val label: String get() = throw java.lang.reflect.InvocationTargetException(
                java.util.concurrent.ExecutionException(java.util.concurrent.CompletionException(cancellation)))
        }
        val thrown = assertThrows(CancellationException::class.java) { runBlocking { perform(listOf(row, row), "label") } }
        assertTrue(thrown === cancellation)
    }

    private suspend fun perform(rows: List<Any>, field: String): QueryResult<*> {
        val name = FullyQualifiedQueryName("Access.rows")
        val registry = ConcurrentQueryPerformerRegistry().apply {
            register(object : QueryPerformer {
                override val fullyQualifiedName = name
                override val descriptor = QueryDescriptor("rows", "Access", "Row", isEnumerable = true)
                override suspend fun perform(context: QueryContext): Any = rows
            })
        }
        return DefaultQueryPipeline(registry).perform(QueryRequest(name, sorting = QuerySorting(field, QuerySortDirection.ASCENDING)),
            QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), object : ServiceResolver {
                override fun <T : Any> resolve(type: Class<T>): T? = null
            }))
    }

    private class ConfidentialRow(val label: String, rank: Int) {
        private val privateRank = rank
        internal val moduleRank = rank
        protected val protectedRank = rank
    }

    private class KotlinBeanRow(name: String) : JavaSortBase(name)

    private class MutablePublicRow(label: String) {
        var label: String = label
            private set
    }
}

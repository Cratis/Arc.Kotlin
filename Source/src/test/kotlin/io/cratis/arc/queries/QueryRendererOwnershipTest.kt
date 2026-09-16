// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.results.PagingInfo
import io.cratis.arc.results.QueryResult
import java.util.UUID
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class QueryRendererOwnershipTest {
    private val name = FullyQualifiedQueryName("Composition.rows")
    private val rows = listOf(Row(0, false), Row(2, true), Row(1, true))
    private val request = QueryRequest(name, paging = QueryPaging(0, 1), sorting = QuerySorting("id", QuerySortDirection.ASCENDING))

    @Test
    fun `automatic fallback never restores rows discarded by an application renderer`(): Unit = runBlocking {
        val renderer = iterableRenderer { _, current, _ -> QueryRendererResult(emptyList<Row>(), current.paging) }
        val result = perform(rows, DefaultQueryRenderers(listOf(renderer)))
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(emptyList<Row>(), result.data)
    }

    @Test
    fun `null and scalar projections are authoritative instead of reviving the original iterable`(): Unit = runBlocking {
        for (projection in listOf(null, "redacted")) {
            val renderer = iterableRenderer { _, _, _ -> QueryRendererResult(projection) }
            val result = perform(rows, DefaultQueryRenderers(listOf(renderer)))
            assertTrue(result.isSuccess, result.exceptionMessages.toString())
            assertEquals(projection, result.data)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [-100, 0, 100])
    fun `provider renderer owns paging without materializing the original query at any order`(order: Int): Unit = runBlocking {
        var enumerations = 0
        val query = Iterable<Row> { enumerations++; error("Provider query must not be materialized by fallback") }
        val renderer = iterableRenderer(order) { original, _, _ ->
            assertSame(query, original)
            QueryRendererResult(listOf(Row(3, true), Row(4, true)), PagingInfo(1, 2, 40))
        }
        val result = perform(query, DefaultQueryRenderers(listOf(renderer)), QueryRequest(name, paging = QueryPaging(1, 2)))
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(listOf(Row(3, true), Row(4, true)), result.data)
        assertPaging(PagingInfo(1, 2, 40), result.paging)
        assertEquals(0, enumerations)
    }

    @Test
    fun `explicit iterable processing sorts and pages the filtered current data only`(): Unit = runBlocking {
        val filter = iterableRenderer { _, current, _ ->
            QueryRendererResult((current.data as List<*>).filter { (it as Row).visible }, current.paging)
        }
        val result = perform(rows, DefaultQueryRenderers(listOf(filter, QueryableQueryRenderer())), request)
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(listOf(Row(1, true)), result.data)
        assertPaging(PagingInfo(0, 1, 2), result.paging)
    }

    @Test
    fun `explicit iterable renderer does not recover original rows from non iterable current data`(): Unit = runBlocking {
        for (projection in listOf(null, "summary")) {
            val projectionRenderer = iterableRenderer { _, _, _ -> QueryRendererResult(projection, PagingInfo(2, 3, 19)) }
            val result = perform(rows, DefaultQueryRenderers(listOf(projectionRenderer, QueryableQueryRenderer())), request)
            assertTrue(result.isSuccess, result.exceptionMessages.toString())
            assertEquals(projection, result.data)
            assertPaging(PagingInfo(2, 3, 19), result.paging)
        }
    }

    @Test
    fun `an identity application renderer also owns its output unless builtin processing is explicit`(): Unit = runBlocking {
        val identity = iterableRenderer { _, current, _ -> current }
        val result = perform(rows, DefaultQueryRenderers(listOf(identity)), request)
        assertEquals(rows, result.data)
        assertPaging(PagingInfo(0, 0, 0), result.paging)
        val explicit = perform(rows, DefaultQueryRenderers(listOf(identity, QueryableQueryRenderer())), request)
        assertEquals(listOf(Row(0, false)), explicit.data)
        assertPaging(PagingInfo(0, 1, 3), explicit.paging)
    }

    @Test
    fun `unrelated application renderers do not disable ordinary iterable fallback`(): Unit = runBlocking {
        val stringRenderer = object : BlockingQueryRendererFor<String> {
            override fun queryType(): Class<String> = String::class.java
            override fun renderBlocking(query: String, current: QueryRendererResult, context: QueryContext): QueryRendererResult =
                error("String renderer must not match iterable source or a later projection")
        }
        val result = perform(rows, DefaultQueryRenderers(listOf(stringRenderer)), request)
        assertEquals(listOf(Row(0, false)), result.data)
        assertPaging(PagingInfo(0, 1, 3), result.paging)
        val projection = iterableRenderer { _, _, _ -> QueryRendererResult("summary") }
        assertEquals("summary", perform(rows, DefaultQueryRenderers(listOf(projection, stringRenderer))).data)
    }

    @Test
    fun `configured renderers retain stable ordering original dispatch and current result chaining`(): Unit = runBlocking {
        val order = mutableListOf<String>()
        fun stage(label: String, priority: Int) = iterableRenderer(priority) { original, current, _ ->
            assertSame(rows, original)
            order += label
            QueryRendererResult((current.data as List<*>).filter { (it as Row).visible }, current.paging)
        }
        val result = perform(rows, DefaultQueryRenderers(listOf(stage("last", 10), stage("first", -10), stage("second", -10))))
        assertEquals(listOf("first", "second", "last"), order)
        assertEquals(listOf(Row(2, true), Row(1, true)), result.data)
    }

    @Test
    fun `observable results retain application filtering and provider paging`(): Unit = runBlocking {
        val registry = registry(rows, observable = true)
        val provider = iterableRenderer { _, _, _ -> QueryRendererResult(listOf(Row(4, true)), PagingInfo(3, 1, 40)) }
        val pipeline = DefaultObservableQueryPipeline(registry, renderers = DefaultQueryRenderers(listOf(provider)))
        val opened = pipeline.open(QueryRequest(name), options()) as ObservableQueryOpenResult.Stream
        val result = withTimeout(5_000) { opened.results.single() }
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(listOf(Row(4, true)), result.data)
        assertPaging(PagingInfo(3, 1, 40), result.paging)
    }

    @Test
    fun `explicit builtin before a later application stage preserves configured order`(): Unit = runBlocking {
        var calls = 0
        val later = iterableRenderer(10) { original, current, _ ->
            assertSame(rows, original)
            assertEquals(listOf(Row(0, false)), current.data)
            assertPaging(PagingInfo(0, 1, 3), current.paging)
            calls++
            current
        }
        val result = perform(rows, DefaultQueryRenderers(listOf(later, QueryableQueryRenderer())), request)
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(1, calls)
        assertEquals(listOf(Row(0, false)), result.data)
    }

    @Test
    fun `observable late provider is not enumerated before its renderer runs`(): Unit = runBlocking {
        var enumerations = 0
        val source = Iterable<Row> { enumerations++; error("Unexpected fallback enumeration") }
        val provider = iterableRenderer(100) { _, _, _ -> QueryRendererResult(listOf(Row(4, true)), PagingInfo(3, 1, 40)) }
        val pipeline = DefaultObservableQueryPipeline(registry(source, observable = true), renderers = DefaultQueryRenderers(listOf(provider)))
        val opened = pipeline.open(QueryRequest(name), options()) as ObservableQueryOpenResult.Stream
        val result = withTimeout(5_000) { opened.results.single() }
        assertTrue(result.isSuccess, result.exceptionMessages.toString())
        assertEquals(listOf(Row(4, true)), result.data)
        assertPaging(PagingInfo(3, 1, 40), result.paging)
        assertEquals(0, enumerations)
    }

    @Test
    fun `application renderer cancellation propagates without fallback enumeration`() {
        var enumerations = 0
        val source = Iterable<Row> { enumerations++; error("Unexpected fallback enumeration") }
        val cancellation = java.util.concurrent.CancellationException("Provider canceled")
        val renderer = iterableRenderer(100) { _, _, _ -> throw cancellation }
        val thrown = org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CancellationException::class.java) {
            runBlocking { perform(source, DefaultQueryRenderers(listOf(renderer))) }
        }
        assertSame(cancellation, thrown)
        assertEquals(0, enumerations)
    }

    private fun assertPaging(expected: PagingInfo, actual: PagingInfo) {
        assertEquals(expected.page, actual.page)
        assertEquals(expected.size, actual.size)
        assertEquals(expected.totalItems, actual.totalItems)
        assertEquals(expected.totalPages, actual.totalPages)
    }

    private fun iterableRenderer(order: Int = 0, action: (Any, QueryRendererResult, QueryContext) -> QueryRendererResult) =
        object : BlockingQueryRendererFor<Iterable<*>> {
            override fun queryType(): Class<Iterable<*>> = Iterable::class.java
            override fun order(): Int = order
            override fun renderBlocking(query: Iterable<*>, current: QueryRendererResult, context: QueryContext): QueryRendererResult =
                action(query, current, context)
        }

    private fun registry(value: Any, observable: Boolean = false) = ConcurrentQueryPerformerRegistry().apply {
        register(object : QueryPerformer {
            override val fullyQualifiedName = name
            override val descriptor = QueryDescriptor("rows", "Composition", Row::class.java.name,
                isEnumerable = true, transport = if (observable) QueryTransportType.OBSERVABLE else QueryTransportType.REQUEST_RESPONSE)
            override suspend fun perform(context: QueryContext): Any = if (observable) flowOf(value) else value
        })
    }

    private suspend fun perform(value: Any, renderers: QueryRenderers, query: QueryRequest = QueryRequest(name)): QueryResult<*> =
        DefaultQueryPipeline(registry(value), renderers = renderers).perform(query, options())

    private fun options() = QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), object : ServiceResolver {
        override fun <T : Any> resolve(type: Class<T>): T? = null
    })

    private data class Row(val id: Int, val visible: Boolean)
}

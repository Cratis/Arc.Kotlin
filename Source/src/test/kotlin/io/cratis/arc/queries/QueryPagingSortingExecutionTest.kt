// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.metadata.QueryDescriptor
import io.cratis.arc.metadata.SequenceKind
import io.cratis.arc.metadata.TypeShapeDescriptor
import io.cratis.arc.results.QueryResult
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class QueryPagingSortingExecutionTest {
    private val rows = listOf(Row("a"), Row("c"), Row("b"), Row("d"))
    private val name = FullyQualifiedQueryName("Tests.PagingRows.all")
    private val request = QueryRequest(
        name,
        paging = QueryPaging(1, 2),
        sorting = QuerySorting("name", QuerySortDirection.DESCENDING)
    )

    @Test
    fun `list is sorted before zero based paging even with both capability flags false`(): Unit = runBlocking {
        val result = perform(rows)

        assertEquals(listOf(Row("b"), Row("a")), result.data)
        assertPaging(result, 1, 2, 4, 2)
        assertEquals(listOf("a", "c", "b", "d"), rows.map(Row::name))
    }

    @Test
    fun `non collection iterable uses the same default sorting and paging`(): Unit = runBlocking {
        val iterable = Iterable { rows.iterator() }
        val result = perform(iterable, SequenceKind.COLLECTION)

        assertEquals(listOf(Row("b"), Row("a")), result.data)
        assertPaging(result, 1, 2, 4, 2)
    }

    @Test
    fun `provider query page keeps its order items and total without a second page`(): Unit = runBlocking {
        val page = QueryPage(listOf(Row("a"), Row("c")), 3, 2, 40)
        val result = perform(page)

        assertEquals(page.items, result.data)
        assertPaging(result, 3, 2, 40, 20)
    }

    @Test
    fun `original array is normalized but bypasses iterable sorting paging and counting`(): Unit = runBlocking {
        val result = perform(rows.toTypedArray(), SequenceKind.ARRAY)

        assertEquals(rows, result.data)
        assertPaging(result, 0, 0, 0, 0)
    }

    private suspend fun perform(value: Any, kind: SequenceKind = SequenceKind.LIST): QueryResult<*> {
        val performer = object : QueryPerformer {
            override val fullyQualifiedName = name
            override val descriptor = QueryDescriptor(
                "all",
                "Tests.PagingRows",
                TypeShapeDescriptor.sequence(kind, TypeShapeDescriptor.value(Row::class.java.name)),
                supportsPaging = false,
                supportsSorting = false
            )

            // The model operation does not consume QueryRequest or QueryContext; rendering owns both operations.
            override suspend fun perform(context: QueryContext): Any = value
        }
        assertFalse(performer.descriptor.supportsPaging)
        assertFalse(performer.descriptor.supportsSorting)
        assertFalse(performer.supportsPaging)
        assertFalse(performer.supportsSorting)
        assertTrue(performer.descriptor.parameters.isEmpty())
        val registry = ConcurrentQueryPerformerRegistry()
        registry.register(performer)
        val services = object : ServiceResolver {
            override fun <T : Any> resolve(type: Class<T>): T? = null
        }
        val result = DefaultQueryPipeline(registry).perform(
            request,
            QueryExecutionOptions(UUID.randomUUID(), ArcPrincipal.anonymous(), services)
        )
        assertTrue(result.isSuccess, result.exceptionMessages.joinToString())
        return result
    }

    private fun assertPaging(result: QueryResult<*>, page: Int, size: Int, total: Long, totalPages: Int) {
        assertEquals(page, result.paging.page)
        assertEquals(size, result.paging.size)
        assertEquals(total, result.paging.totalItems)
        assertEquals(totalPages, result.paging.totalPages)
    }

    private data class Row(val name: String)
}

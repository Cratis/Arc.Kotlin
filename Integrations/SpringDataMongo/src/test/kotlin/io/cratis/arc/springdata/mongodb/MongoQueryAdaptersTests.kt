// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

class MongoQueryAdaptersTests {
    @Test
    fun `translates zero based paging and descending sorting`() {
        val request = QueryRequest(
            FullyQualifiedQueryName("Tasks.All"),
            paging = QueryPaging(3, 10),
            sorting = QuerySorting("title", QuerySortDirection.DESCENDING)
        )

        val pageable = MongoQueryRequestAdapter.toPageable(request)

        assertEquals(3, pageable.pageNumber)
        assertEquals(10, pageable.pageSize)
        assertEquals(Sort.Direction.DESC, pageable.sort.getOrderFor("title")?.direction)
    }

    @Test
    fun `blank sorting and zero page size produce an unpaged request`() {
        val request = QueryRequest(FullyQualifiedQueryName("Tasks.All"))

        val pageable = MongoQueryRequestAdapter.toPageable(request)

        assertTrue(pageable.isUnpaged)
        assertTrue(pageable.sort.isUnsorted)
    }

    @Test
    fun `maps an unpaged Spring page without inventing paging metadata`() {
        val arcPage = MongoQueryPageAdapter.toQueryPage(PageImpl(listOf("one", "two")))

        assertEquals(0, arcPage.page)
        assertEquals(0, arcPage.pageSize)
        assertEquals(2, arcPage.totalItems)
    }

    @Test
    fun `maps a Spring page to an Arc query page`() {
        val springPage = PageImpl(listOf("one"), PageRequest.of(4, 1), 8)

        val arcPage = MongoQueryPageAdapter.toQueryPage(springPage)

        assertEquals(listOf("one"), arcPage.items)
        assertEquals(4, arcPage.page)
        assertEquals(1, arcPage.pageSize)
        assertEquals(8, arcPage.totalItems)
    }
}

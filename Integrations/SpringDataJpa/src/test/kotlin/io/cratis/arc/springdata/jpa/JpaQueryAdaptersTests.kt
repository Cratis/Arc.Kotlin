// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest

class JpaQueryAdaptersTests {
    @Test
    fun `translates zero based paging and descending sorting`() {
        val request = QueryRequest(
            FullyQualifiedQueryName("Tasks.All"),
            paging = QueryPaging(2, 25),
            sorting = QuerySorting("title", QuerySortDirection.DESCENDING)
        )

        val pageable = JpaQueryRequestAdapter.toPageable(request)

        assertEquals(2, pageable.pageNumber)
        assertEquals(25, pageable.pageSize)
        assertEquals(org.springframework.data.domain.Sort.Direction.DESC, pageable.sort.getOrderFor("title")?.direction)
    }

    @Test
    fun `preserves sorting for an unpaged request`() {
        val request = QueryRequest(
            FullyQualifiedQueryName("Tasks.All"),
            sorting = QuerySorting("title", QuerySortDirection.ASCENDING)
        )

        val pageable = JpaQueryRequestAdapter.toPageable(request)

        assertTrue(pageable.isUnpaged)
        assertEquals(org.springframework.data.domain.Sort.Direction.ASC, pageable.sort.getOrderFor("title")?.direction)
    }

    @Test
    fun `maps an unpaged Spring page without inventing paging metadata`() {
        val arcPage = JpaQueryPageAdapter.toQueryPage(PageImpl(listOf("one", "two")))

        assertEquals(0, arcPage.page)
        assertEquals(0, arcPage.pageSize)
        assertEquals(2, arcPage.totalItems)
    }

    @Test
    fun `maps a Spring page to an Arc query page`() {
        val springPage = PageImpl(listOf("one", "two"), PageRequest.of(1, 2), 9)

        val arcPage = JpaQueryPageAdapter.toQueryPage(springPage)

        assertEquals(listOf("one", "two"), arcPage.items)
        assertEquals(1, arcPage.page)
        assertEquals(2, arcPage.pageSize)
        assertEquals(9, arcPage.totalItems)
        assertFalse(arcPage.items.isEmpty())
    }
}

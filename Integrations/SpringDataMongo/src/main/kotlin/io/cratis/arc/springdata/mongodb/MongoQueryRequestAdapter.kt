// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.mongodb

import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/** Translates Arc paging and sorting into Spring Data MongoDB request types. */
public object MongoQueryRequestAdapter {
    /** Creates a Spring Data [Sort], preserving an explicitly requested direction. */
    @JvmStatic
    public fun toSort(request: QueryRequest): Sort {
        val field = request.sorting.field
        if (field.isBlank()) return Sort.unsorted()

        val direction = when (request.sorting.direction) {
            QuerySortDirection.ASCENDING -> Sort.Direction.ASC
            QuerySortDirection.DESCENDING -> Sort.Direction.DESC
        }
        return Sort.by(direction, field)
    }

    /**
     * Creates a zero-based Spring Data [Pageable]. A page size of zero means unpaged; sorting still applies.
     */
    @JvmStatic
    public fun toPageable(request: QueryRequest): Pageable {
        val sort = toSort(request)
        return if (request.paging.pageSize == 0) {
            Pageable.unpaged(sort)
        } else {
            PageRequest.of(request.paging.page, request.paging.pageSize, sort)
        }
    }
}

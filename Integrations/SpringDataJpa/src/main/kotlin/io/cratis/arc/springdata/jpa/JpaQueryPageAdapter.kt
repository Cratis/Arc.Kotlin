// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springdata.jpa

import io.cratis.arc.queries.QueryPage
import org.springframework.data.domain.Page

/** Converts database-owned Spring Data pages into Arc query pages. */
public object JpaQueryPageAdapter {
    /** Materializes [page] as the Arc payload understood by the query pipeline. */
    @JvmStatic
    public fun <T> toQueryPage(page: Page<T>): QueryPage<T> {
        val isUnpaged = page.pageable.isUnpaged
        return QueryPage(
            page.content,
            if (isUnpaged) 0 else page.number,
            if (isUnpaged) 0 else page.size,
            page.totalElements
        )
    }
}

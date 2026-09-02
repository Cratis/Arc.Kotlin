// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.negative

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel

/** Generic lookalike that must not be classified as Arc's query request. */
public class QueryRequest<T>

/** Lookalike that must not be classified as Arc's query context. */
public class QueryContext

/** Pageable lookalike that must not be classified as Spring Data's host adapter. */
public class Pageable

/** Sort lookalike that must not be classified as Spring Data's host adapter. */
public class Sort

/** Unsupported subtype of Spring Data's exact Pageable host adapter. */
public interface PageableSubtype : org.springframework.data.domain.Pageable

@ReadModel
public data class NullableInfrastructureReadModel(public val value: String) {
    public companion object {
        public fun invalid(request: io.cratis.arc.queries.QueryRequest?): NullableInfrastructureReadModel =
            NullableInfrastructureReadModel(request?.queryName?.value.orEmpty())
    }
}

@ReadModel
public data class DefaultedInfrastructureReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            context: io.cratis.arc.queries.QueryContext = error("not invoked")
        ): DefaultedInfrastructureReadModel = DefaultedInfrastructureReadModel(context.queryName.value)
    }
}

@ReadModel
public data class DefaultedRequestReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            request: io.cratis.arc.queries.QueryRequest = error("not invoked")
        ): DefaultedRequestReadModel = DefaultedRequestReadModel(request.queryName.value)
    }
}

@ReadModel
public data class DefaultedServiceReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            @FromServices dependency: DefaultedService = DefaultedService()
        ): DefaultedServiceReadModel = DefaultedServiceReadModel(dependency.value)
    }
}

public class DefaultedService(public val value: String = "service")

@ReadModel
public data class ExcessDefaultedClientsReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            first: String = "1",
            second: String = "2",
            third: String = "3",
            fourth: String = "4",
            fifth: String = "5",
            sixth: String = "6",
            seventh: String = "7"
        ): ExcessDefaultedClientsReadModel =
            ExcessDefaultedClientsReadModel(first + second + third + fourth + fifth + sixth + seventh)
    }
}

@ReadModel
public data class ServiceInfrastructureReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            @FromServices request: io.cratis.arc.queries.QueryRequest
        ): ServiceInfrastructureReadModel = ServiceInfrastructureReadModel(request.queryName.value)
    }
}

@ReadModel
public data class DuplicateInfrastructureReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            first: io.cratis.arc.queries.QueryContext,
            second: io.cratis.arc.queries.QueryContext
        ): DuplicateInfrastructureReadModel = DuplicateInfrastructureReadModel(first.queryName.value + second.queryName.value)
    }
}

@ReadModel
public data class GenericInfrastructureLookalikeReadModel(public val value: String) {
    public companion object {
        public fun invalid(request: QueryRequest<String>): GenericInfrastructureLookalikeReadModel =
            GenericInfrastructureLookalikeReadModel(request.toString())
    }
}

@ReadModel
public data class InfrastructureLookalikeReadModel(public val value: String) {
    public companion object {
        public fun invalid(context: QueryContext): InfrastructureLookalikeReadModel =
            InfrastructureLookalikeReadModel(context.toString())
    }
}

@ReadModel
public data class GenericInfrastructureReadModel(public val value: String) {
    public companion object {
        public fun <T : io.cratis.arc.queries.QueryRequest> invalid(request: T): GenericInfrastructureReadModel =
            GenericInfrastructureReadModel(request.queryName.value)
    }
}

@ReadModel
public data class NullablePageableReadModel(public val value: String) {
    public companion object {
        public fun invalid(pageable: org.springframework.data.domain.Pageable?): NullablePageableReadModel =
            NullablePageableReadModel(pageable.toString())
    }
}

@ReadModel
public data class DefaultedSortReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            sort: org.springframework.data.domain.Sort = org.springframework.data.domain.Sort.unsorted()
        ): DefaultedSortReadModel = DefaultedSortReadModel(sort.toString())
    }
}

@ReadModel
public data class ServicePageableReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            @FromServices pageable: org.springframework.data.domain.Pageable
        ): ServicePageableReadModel = ServicePageableReadModel(pageable.toString())
    }
}

@ReadModel
public data class DuplicateSortReadModel(public val value: String) {
    public companion object {
        public fun invalid(
            first: org.springframework.data.domain.Sort,
            second: org.springframework.data.domain.Sort
        ): DuplicateSortReadModel = DuplicateSortReadModel(first.toString() + second.toString())
    }
}

@ReadModel
public data class PageableSubtypeReadModel(public val value: String) {
    public companion object {
        public fun invalid(pageable: PageableSubtype): PageableSubtypeReadModel =
            PageableSubtypeReadModel(pageable.toString())
    }
}

@ReadModel
public data class PageableLookalikeReadModel(public val value: String) {
    public companion object {
        public fun invalid(pageable: Pageable): PageableLookalikeReadModel =
            PageableLookalikeReadModel(pageable.toString())
    }
}

@ReadModel
public data class SortLookalikeReadModel(public val value: String) {
    public companion object {
        public fun invalid(sort: Sort): SortLookalikeReadModel = SortLookalikeReadModel(sort.toString())
    }
}

@ReadModel
public data class GenericPageableReadModel(public val value: String) {
    public companion object {
        public fun <T : org.springframework.data.domain.Pageable> invalid(pageable: T): GenericPageableReadModel =
            GenericPageableReadModel(pageable.toString())
    }
}

@ReadModel
public data class PageableCollectionReadModel(public val value: String) {
    public companion object {
        public fun invalid(pageable: org.springframework.data.domain.Pageable): List<PageableCollectionReadModel> =
            listOf(PageableCollectionReadModel(pageable.toString()))
    }
}

@ReadModel
public data class SortCollectionReadModel(public val value: String) {
    public companion object {
        public fun invalid(sort: org.springframework.data.domain.Sort): List<SortCollectionReadModel> =
            listOf(SortCollectionReadModel(sort.toString()))
    }
}

@ReadModel
public data class ReservedPageReadModel(public val value: String) {
    public companion object {
        public fun invalid(page: Int): ReservedPageReadModel = ReservedPageReadModel(page.toString())
    }
}

@ReadModel
public data class ReservedPageSizeReadModel(public val value: String) {
    public companion object {
        public fun invalid(pageSize: Int): ReservedPageSizeReadModel = ReservedPageSizeReadModel(pageSize.toString())
    }
}

@ReadModel
public data class ReservedSortByReadModel(public val value: String) {
    public companion object {
        public fun invalid(sortBy: String): ReservedSortByReadModel = ReservedSortByReadModel(sortBy)
    }
}

@ReadModel
public data class ReservedSortDirectionReadModel(public val value: String) {
    public companion object {
        public fun invalid(sortDirection: String): ReservedSortDirectionReadModel =
            ReservedSortDirectionReadModel(sortDirection)
    }
}

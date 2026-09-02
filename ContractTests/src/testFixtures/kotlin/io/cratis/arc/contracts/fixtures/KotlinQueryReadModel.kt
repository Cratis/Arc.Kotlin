// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.FromServices
import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.artifacts.TreatWarningsAsErrors
import io.cratis.arc.authorization.Authorize
import io.cratis.arc.authorization.Roles
import io.cratis.arc.queries.Path
import io.cratis.arc.queries.QueryHttpMethod
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryHttpMethodType
import io.cratis.arc.queries.QueryPage
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryTransport
import io.cratis.arc.queries.QueryTransportType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

/** Kotlin read model fixture covering generated one-shot query shapes. */
@ReadModel
@Authorize(policy = "catalog", roles = ["viewer"], schemes = ["bearer"])
@Roles("reader")
@TreatWarningsAsErrors
public data class KotlinQueryReadModel(public val value: String) {
    public companion object {
        /** Returns one model from a required client argument. */
        @Path("/kotlin-models/by-id")
        @QueryHttpMethod(QueryHttpMethodType.GET)
        @QueryTransport(QueryTransportType.REQUEST_RESPONSE)
        public fun single(
            @NotBlank(message = "Identifier is required") identifier: String
        ): KotlinQueryReadModel = KotlinQueryReadModel(identifier)

        /** Returns one model with request and context values in their declared positions. */
        public fun contextualKotlin(
            request: QueryRequest,
            label: String,
            context: QueryContext
        ): KotlinQueryReadModel {
            require(context.request === request)
            return KotlinQueryReadModel("$label-${context.correlationId}")
        }

        /** Returns models from a suspending query with contextual values and a generated service dependency. */
        @Roles("auditor")
        public suspend fun all(
            prefix: String,
            request: QueryRequest,
            @FromServices dependency: KotlinQueryDependency,
            context: QueryContext
        ): List<KotlinQueryReadModel> {
            require(context.request === request)
            return dependency.models(prefix)
        }

        /** Applies interspersed Kotlin client defaults while Arc supplies infrastructure and services. */
        public fun defaulted(
            required: String,
            prefix: String = "default",
            request: QueryRequest,
            suffix: String = "$prefix-suffix",
            @FromServices dependency: KotlinQueryDependency,
            count: Int = 2,
            context: QueryContext
        ): KotlinQueryReadModel {
            require(context.request === request)
            dependency.models(required)
            return KotlinQueryReadModel("$required|$prefix|$suffix|$count")
        }

        /** Applies a Kotlin client default in a suspending query. */
        public suspend fun defaultedSuspend(
            request: QueryRequest,
            label: String = "suspend-default",
            @FromServices dependency: KotlinQueryDependency,
            context: QueryContext
        ): List<KotlinQueryReadModel> {
            require(context.request === request)
            return dependency.models(label)
        }

        /** Returns models using a reachable DTO query argument. */
        public fun filtered(@Valid filter: FixtureFilter): List<KotlinQueryReadModel> =
            filter.ids.map { identifier -> KotlinQueryReadModel(identifier.toString()) }

        /** Returns a collection from a nullable client argument. */
        public fun optional(label: String?): Collection<KotlinQueryReadModel> =
            listOf(KotlinQueryReadModel(label ?: "none"))

        /** Returns an observable model collection. */
        public fun observeAll(
            context: QueryContext,
            @Pattern(regexp = "^[a-z]+$", message = "Observable label must be lowercase") label: String,
            @FromServices dependency: KotlinQueryDependency,
            request: QueryRequest
        ): Flow<List<KotlinQueryReadModel>> {
            require(context.request === request)
            dependency.models(label)
            return flowOf(
                listOf(KotlinQueryReadModel("observable-$label-true-${context.correlationId}"))
            )
        }

        /** Returns an observable collection with a Kotlin client default. */
        public fun observeDefaulted(
            context: QueryContext,
            label: String = "flow-default",
            request: QueryRequest
        ): Flow<List<KotlinQueryReadModel>> {
            require(context.request === request)
            return flowOf(listOf(KotlinQueryReadModel(label)))
        }

        /** Returns an observable single model. */
        public fun observeSingle(): Flow<KotlinQueryReadModel> = flowOf(KotlinQueryReadModel("observable-single"))

        /** Adapts request paging and sorting for a direct Spring Data page query. */
        public fun springDataDirect(
            label: String,
            pageable: Pageable,
            @FromServices dependency: KotlinQueryDependency,
            request: QueryRequest,
            sort: Sort
        ): Page<KotlinQueryReadModel> {
            require(pageable.sort == sort)
            val paging = if (pageable.isPaged) "${pageable.pageNumber}:${pageable.pageSize}" else "unpaged"
            val direction = sort.getOrderFor(request.sorting.field)?.direction?.name ?: "UNSORTED"
            return dependency.page("$label|$paging|$direction", pageable, 37)
        }

        /** Adapts request paging and sorting for a suspending Spring Data page query. */
        public suspend fun springDataSuspend(
            request: QueryRequest,
            sort: Sort,
            label: String,
            pageable: Pageable,
            @FromServices dependency: KotlinQueryDependency
        ): Page<KotlinQueryReadModel> {
            require(pageable.sort == sort)
            val paging = if (pageable.isPaged) "${pageable.pageNumber}:${pageable.pageSize}" else "unpaged"
            val direction = sort.getOrderFor(request.sorting.field)?.direction?.name ?: "UNSORTED"
            return dependency.page("$label|$paging|$direction", pageable, 53)
        }

        /** Returns database-owned paging metadata. */
        public fun page(pageNumber: Int): QueryPage<KotlinQueryReadModel> = QueryPage(
            items = listOf(KotlinQueryReadModel("page-$pageNumber")),
            page = pageNumber,
            pageSize = 1,
            totalItems = 3
        )
    }
}

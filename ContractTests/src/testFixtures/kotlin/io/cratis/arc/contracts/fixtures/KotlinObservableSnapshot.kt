// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts.fixtures

import io.cratis.arc.artifacts.ReadModel
import io.cratis.arc.artifacts.TreatWarningsAsErrors
import io.cratis.arc.authorization.AllowAnonymous
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.results.ValidationResultSeverity
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Generated invocation must receive declared JVM values, not untyped JSON snapshots. */
@ReadModel
@AllowAnonymous
public data class KotlinObservableSnapshot(public val value: String) {
    public companion object {
        /** Keeps the stream alive until the transport explicitly cancels the subscription. */
        @TreatWarningsAsErrors
        public fun observeKotlinSnapshot(
            id: UUID,
            date: LocalDate,
            concept: JavaOrderId,
            ordinary: FixtureState,
            coded: ExplicitFixtureState,
            small: Long,
            ids: Array<UUID>,
            longs: Array<Long>,
            request: QueryRequest,
            context: QueryContext,
            optional: Long? = 9L
        ): Flow<KotlinObservableSnapshot> {
            require(context.allowedValidationSeverity == ValidationResultSeverity.Information)
            require(id == concept.value() && ids.single() == id)
            require(ids.javaClass.componentType == UUID::class.java)
            require(longs.javaClass.componentType == Long::class.javaObjectType)
            require(longs.single() == small)
            require(ordinary == FixtureState.Active && coded.value() == 17)
            val presence = if (request.arguments.containsKey("optional")) "supplied" else "omitted"
            val value = "${date.plusDays(1)}|${small + 1}|$presence|$optional|${request.paging.page}|${request.sorting.field}"
            return MutableStateFlow(KotlinObservableSnapshot(value))
        }
    }
}

// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing.java

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.testing.QueryScenario
import io.cratis.arc.testing.QueryScenarioResult
import io.cratis.arc.java.JavaAsyncScope
import io.cratis.arc.java.launchStage
import java.util.concurrent.CompletionStage
import kotlinx.coroutines.CoroutineScope

/** Java `CompletionStage` query bridge borrowing a caller-owned scope; close the owner explicitly. */
public class AsyncQueryScenario<TData> private constructor(
    private val scenario: QueryScenario<TData>,
    private val launch: (suspend () -> QueryScenarioResult<TData>) -> CompletionStage<QueryScenarioResult<TData>>
) {
    /** Borrows a Kotlin host's bounded or structured scope. */
    public constructor(scenario: QueryScenario<TData>, coroutineScope: CoroutineScope) :
        this(scenario, { coroutineScope.launchStage(it) })

    /** Borrows a Java owner. Calls after owner close return cancelled stages without executing user code. */
    public constructor(scenario: QueryScenario<TData>, owner: JavaAsyncScope) :
        this(scenario, { owner.launchStage(it) })

    /** Creates a bridge for an exact query from a generated module, borrowing [owner]. */
    public constructor(module: ArcArtifactModule, queryName: FullyQualifiedQueryName, owner: JavaAsyncScope) :
        this(QueryScenario(module, queryName), owner)

    /** Creates a bridge for one real manual performer, borrowing [owner]. */
    public constructor(performer: QueryPerformer, owner: JavaAsyncScope) : this(QueryScenario(performer), owner)
    /** Creates a bridge for an exact query from a generated module. */
    public constructor(
        module: ArcArtifactModule,
        queryName: FullyQualifiedQueryName,
        coroutineScope: CoroutineScope
    ) : this(QueryScenario(module, queryName), coroutineScope)

    /** Creates a bridge for one real manual performer. */
    public constructor(performer: QueryPerformer, coroutineScope: CoroutineScope) :
        this(QueryScenario(performer), coroutineScope)

    /** Performs the query asynchronously. Cancellation of the returned future cancels its child job. */
    @JvmOverloads
    public fun perform(
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
    ): CompletionStage<QueryScenarioResult<TData>> = launch { scenario.perform(arguments, paging, sorting) }
}

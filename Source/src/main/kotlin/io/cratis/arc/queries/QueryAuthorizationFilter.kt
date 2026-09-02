// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.results.QueryResult

/** Built-in host-neutral query authorization filter. */
public class QueryAuthorizationFilter(
    private val performers: QueryPerformerRegistry,
    private val evaluator: AuthorizationEvaluator
) : AuthorizationQueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> {
        val performer = performers.find(context.queryName)
            ?: return QueryResult.missingPerformer<Any?>(context.correlationId, context.queryName.value)
        val result = evaluator.evaluate(performer.descriptor.authorization, context.principal)
        return if (result.isAuthorized) {
            QueryResult.success<Any?>(context.correlationId)
        } else {
            QueryResult.unauthorized<Any?>(context.correlationId)
        }
    }
}

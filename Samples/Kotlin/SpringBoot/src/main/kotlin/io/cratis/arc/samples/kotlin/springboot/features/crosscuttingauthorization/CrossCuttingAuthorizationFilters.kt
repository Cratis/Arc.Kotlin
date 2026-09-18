// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.kotlin.springboot.features.crosscuttingauthorization

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.queries.QueryContext
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult

/** The package every artifact in this feature lives under. */
internal const val PROTECTED_PACKAGE: String =
    "io.cratis.arc.samples.kotlin.springboot.features.crosscuttingauthorization"

/** The role the filters below require. */
internal const val REQUIRED_ROLE: String = "CrossCuttingAuthorization"

/**
 * Requires a role for every command declared in this feature's package.
 *
 * A filter runs inside the Arc pipeline, so it sees the resolved principal that authentication
 * already produced and can reject before the handler is ever called. Reach for this instead of
 * repeating `@Roles` on twenty commands that all answer to the same rule.
 */
public class CrossCuttingAuthorizationCommandFilter : CommandFilter {
    override suspend fun execute(context: CommandContext): CommandResult<*> {
        if (!context.commandType.name.startsWith(PROTECTED_PACKAGE)) {
            return CommandResult.success(context.correlationId)
        }
        if (context.principal.isInRole(REQUIRED_ROLE)) {
            return CommandResult.success(context.correlationId)
        }
        return CommandResult.unauthorized(
            context.correlationId,
            "Role '$REQUIRED_ROLE' is required for commands in package '$PROTECTED_PACKAGE'."
        )
    }
}

/** Requires the same role for every query declared in this feature's package. */
public class CrossCuttingAuthorizationQueryFilter : QueryFilter {
    override suspend fun execute(context: QueryContext): QueryResult<*> {
        if (!context.queryName.value.startsWith(PROTECTED_PACKAGE)) {
            return QueryResult.success<Any>(context.correlationId)
        }
        if (context.principal.isInRole(REQUIRED_ROLE)) {
            return QueryResult.success<Any>(context.correlationId)
        }
        return QueryResult.unauthorized<Any>(context.correlationId)
    }
}

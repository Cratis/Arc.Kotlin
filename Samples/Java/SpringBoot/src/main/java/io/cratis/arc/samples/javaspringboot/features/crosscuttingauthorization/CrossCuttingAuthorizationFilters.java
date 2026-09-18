// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot.features.crosscuttingauthorization;

import io.cratis.arc.commands.CommandContext;
import io.cratis.arc.java.BlockingCommandFilter;
import io.cratis.arc.java.BlockingQueryFilter;
import io.cratis.arc.queries.QueryContext;
import io.cratis.arc.results.CommandResult;
import io.cratis.arc.results.QueryResult;

/**
 * Role checks that apply to a whole feature package rather than to one artifact at a time.
 *
 * A filter runs inside the Arc pipeline, so it sees the resolved principal that authentication
 * already produced and can reject before the handler is ever called. Reach for this instead of
 * repeating {@code @Roles} on twenty commands that all answer to the same rule.
 *
 * Both filters are ordinary Java — no coroutines. Arc adapts a blocking Java filter through
 * {@code BlockingCommandFilterAdapter} and {@code BlockingQueryFilterAdapter}.
 */
public final class CrossCuttingAuthorizationFilters {
    /** The package every artifact in this feature lives under. */
    public static final String PROTECTED_PACKAGE =
        "io.cratis.arc.samples.javaspringboot.features.crosscuttingauthorization";

    /** The role the filters below require. */
    public static final String REQUIRED_ROLE = "CrossCuttingAuthorization";

    private CrossCuttingAuthorizationFilters() {
    }

    /** Requires a role for every command declared in this feature's package. */
    public static final class CommandFilter implements BlockingCommandFilter {
        @Override
        public CommandResult<?> execute(CommandContext context) {
            if (!context.getCommandType().getName().startsWith(PROTECTED_PACKAGE)
                || context.getPrincipal().isInRole(REQUIRED_ROLE)) {
                return CommandResult.success(context.getCorrelationId());
            }
            return CommandResult.unauthorized(
                context.getCorrelationId(),
                "Role '" + REQUIRED_ROLE + "' is required for commands in package '" + PROTECTED_PACKAGE + "'.");
        }
    }

    /** Requires the same role for every query declared in this feature's package. */
    public static final class QueryFilter implements BlockingQueryFilter {
        @Override
        public QueryResult<?> execute(QueryContext context) {
            if (!context.getQueryName().getValue().startsWith(PROTECTED_PACKAGE)
                || context.getPrincipal().isInRole(REQUIRED_ROLE)) {
                return QueryResult.success(context.getCorrelationId());
            }
            return QueryResult.unauthorized(context.getCorrelationId());
        }
    }
}

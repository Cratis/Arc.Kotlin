// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc

import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult

/** Produces client-safe copies of exception-bearing results after the host has logged their full detail. */
public object ExceptionDetailRedactor {
    /** Generic message used in place of internal exception detail on production wires. */
    public const val REDACTED_MESSAGE: String =
        "An internal error occurred while processing the request. See server logs for details."

    /**
     * Redacts a command result unless exception detail may be exposed.
     *
     * A host must log the original result before calling this helper. Results without exceptions are returned unchanged.
     */
    @JvmStatic
    public fun <T> redact(result: CommandResult<T>, exposeExceptionDetails: Boolean): CommandResult<T> {
        if (exposeExceptionDetails || !result.hasExceptions) return result

        return CommandResult(
            correlationId = result.correlationId,
            isAuthorized = result.isAuthorized,
            validationResults = result.validationResults,
            exceptionMessages = listOf(REDACTED_MESSAGE),
            exceptionStackTrace = "",
            authorizationFailureReason = result.authorizationFailureReason,
            response = result.response
        )
    }

    /**
     * Redacts a query result unless exception detail may be exposed.
     *
     * A host must log the original result before calling this helper. Results without exceptions are returned unchanged.
     */
    @JvmStatic
    public fun <T> redact(result: QueryResult<T>, exposeExceptionDetails: Boolean): QueryResult<T> {
        if (exposeExceptionDetails || !result.hasExceptions) return result

        return QueryResult(
            correlationId = result.correlationId,
            data = result.data,
            isReady = result.isReady,
            isAuthorized = result.isAuthorized,
            validationResults = result.validationResults,
            exceptionMessages = listOf(REDACTED_MESSAGE),
            exceptionStackTrace = "",
            paging = result.paging,
            changeSet = result.changeSet
        )
    }
}

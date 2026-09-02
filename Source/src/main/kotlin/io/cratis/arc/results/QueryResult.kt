// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.results

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

/** Immutable outcome of performing a query. */
public class QueryResult<TData> @JvmOverloads constructor(
    /** Correlates this outcome with the originating request. */
    public val correlationId: UUID,
    data: TData? = null,
    /** Whether data is ready for consumption. */
    @get:JsonProperty("isReady")
    public val isReady: Boolean = true,
    /** Whether authorization succeeded. */
    @get:JsonProperty("isAuthorized")
    public val isAuthorized: Boolean = true,
    validationResults: List<ValidationResult> = emptyList(),
    exceptionMessages: List<String> = emptyList(),
    /** Stack trace retained for host-side logging and redaction before production serialization. */
    public val exceptionStackTrace: String = "",
    /** Paging metadata, always present on the wire. */
    public val paging: PagingInfo = PagingInfo(0, 0, 0),
    /** Optional incremental changes associated with the result. */
    public val changeSet: ChangeSet<*>? = null
) {
    /** Query data, which may be one model, a collection, or `null`. Lists are defensively copied. */
    @Suppress("UNCHECKED_CAST")
    public val data: TData? = when (data) {
        is List<*> -> java.util.Collections.unmodifiableList(ArrayList(data)) as TData
        else -> data
    }

    /** Validation feedback. This collection is always present in JSON, including when empty. */
    public val validationResults: List<ValidationResult> = java.util.List.copyOf(validationResults)

    /** Exception messages retained for host-side logging and redaction. This collection is always present in JSON. */
    public val exceptionMessages: List<String> = java.util.List.copyOf(exceptionMessages)

    /** True only when no validation results were produced, regardless of severity. */
    @get:JsonProperty("isValid")
    public val isValid: Boolean = this.validationResults.isEmpty()

    /** True when at least one exception message was produced. */
    @get:JsonProperty("hasExceptions")
    public val hasExceptions: Boolean = this.exceptionMessages.isNotEmpty()

    /** True when the result is ready, authorized, valid, and free of exceptions. */
    @get:JsonProperty("isSuccess")
    public val isSuccess: Boolean = isReady && isAuthorized && isValid && !hasExceptions

    public companion object {
        /** Creates a ready, successful result, including a successful `null` result. */
        @JvmStatic
        @JvmOverloads
        public fun <TData> success(
            correlationId: UUID,
            data: TData? = null,
            paging: PagingInfo = PagingInfo(0, 0, 0),
            changeSet: ChangeSet<*>? = null
        ): QueryResult<TData> = QueryResult(
            correlationId = correlationId,
            data = data,
            paging = paging,
            changeSet = changeSet
        )

        /** Creates a result whose data is not ready yet. */
        @JvmStatic
        public fun <TData> notReady(correlationId: UUID): QueryResult<TData> =
            QueryResult(correlationId = correlationId, isReady = false)

        /** Creates an unauthorized result. */
        @JvmStatic
        public fun <TData> unauthorized(correlationId: UUID): QueryResult<TData> =
            QueryResult(correlationId = correlationId, isAuthorized = false)

        /** Creates a result rejected by validation. */
        @JvmStatic
        public fun <TData> invalid(
            correlationId: UUID,
            validationResults: List<ValidationResult>
        ): QueryResult<TData> = QueryResult(correlationId = correlationId, validationResults = validationResults)

        /** Creates a framework error when no query performer exists. */
        @JvmStatic
        public fun <TData> missingPerformer(correlationId: UUID, queryName: String): QueryResult<TData> =
            error(correlationId, "No performer found for query $queryName")

        /** Creates a failure from a message that is known to be safe to expose. */
        @JvmStatic
        public fun <TData> error(correlationId: UUID, message: String): QueryResult<TData> = QueryResult(
            correlationId = correlationId,
            exceptionMessages = listOf(message)
        )

        /** Creates an exception result retaining full detail for host-side logging and redaction. */
        @JvmStatic
        public fun <TData> exception(correlationId: UUID, exception: Throwable): QueryResult<TData> = QueryResult(
            correlationId = correlationId,
            exceptionMessages = listOf(exception.message ?: exception.javaClass.simpleName),
            exceptionStackTrace = exception.stackTraceToString()
        )
    }
}

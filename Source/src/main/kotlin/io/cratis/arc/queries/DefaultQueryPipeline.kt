// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.PagingInfo
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow

/** Default host-agnostic one-shot query pipeline. */
public class DefaultQueryPipeline @JvmOverloads constructor(
    private val performers: QueryPerformerRegistry,
    queryFilters: Iterable<QueryFilter> = emptyList(),
    private val renderers: QueryRenderers = DefaultQueryRenderers(),
    private val readModelInterceptors: ReadModelInterceptors = DefaultReadModelInterceptors()
) : QueryPipeline {
    private val filters = java.util.List.copyOf(queryFilters.toList())

    override suspend fun perform(request: QueryRequest, options: QueryExecutionOptions): QueryResult<*> {
        val performer = performers.find(request.queryName)
            ?: return QueryResult.missingPerformer<Any?>(options.correlationId, request.queryName.value)
        if (performer.descriptor.transport == QueryTransportType.OBSERVABLE) {
            return QueryResult.error<Any?>(
                options.correlationId,
                "Observable query ${request.queryName.value} cannot be performed as a one-shot query."
            )
        }
        val context = QueryContext(
            correlationId = options.correlationId,
            request = request,
            queryName = request.queryName,
            principal = options.principal,
            tenantId = options.tenantId,
            tenantNamespace = options.tenantNamespace,
            serviceResolver = options.serviceResolver,
            allowedValidationSeverity = options.allowedValidationSeverity,
            exposeExceptionDetails = options.exposeExceptionDetails
        )
        var result = executeFilters(context).filterValidation(options.allowedValidationSeverity)
        if (!result.isSuccess) return result

        try {
            val value = performer.perform(context)
            result = when (value) {
                is Flow<*> -> result.merge(
                    QueryResult.error<Any?>(options.correlationId, "Query ${request.queryName.value} returned a Flow on the one-shot path.")
                )
                is QueryResult<*> -> {
                    val rendered = renderPayload(value, context, QueryRendererResult(value.data, value.paging))
                    result.merge(value.withPayload(rendered.data, rendered.paging), includePayload = true)
                }
                is QueryPage<*> -> {
                    val rendered = renderPayload(
                        value,
                        context,
                        QueryRendererResult(value.items, PagingInfo(value.page, value.pageSize, value.totalItems))
                    )
                    result.withPayload(rendered.data, rendered.paging)
                }
                null -> result.withPayload(null, result.paging)
                else -> {
                    val normalized = normalizeData(value)
                    val rendered = renderPayload(value, context, QueryRendererResult(normalized, result.paging))
                    result.withPayload(rendered.data, rendered.paging)
                }
            }.filterValidation(options.allowedValidationSeverity)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: QueryArgumentException) {
            result = result.merge(
                QueryResult.invalid<Any?>(
                    options.correlationId,
                    listOf(
                        ValidationResult(
                            ValidationResultSeverity.Error,
                            requireNotNull(exception.message),
                            listOf(exception.argumentName)
                        )
                    )
                )
            ).filterValidation(options.allowedValidationSeverity)
        } catch (exception: Exception) {
            result = result.merge(QueryResult.exception<Any?>(options.correlationId, exception))
        }
        return result
    }

    private suspend fun executeFilters(context: QueryContext): QueryResult<Any?> {
        var result: QueryResult<Any?> = QueryResult.success(context.correlationId)
        val ordered = filters.filterIsInstance<AuthorizationQueryFilter>() +
            filters.filterNot { it is AuthorizationQueryFilter }

        for (filter in ordered) {
            try {
                result = result.merge(filter.execute(context)).filterValidation(context.allowedValidationSeverity)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                result = result.merge(QueryResult.exception<Any?>(context.correlationId, exception))
            }
            if (!result.isSuccess) break
        }
        return result
    }

    private fun QueryResult<*>.filterValidation(
        allowedSeverity: ValidationResultSeverity?
    ): QueryResult<Any?> {
        val blocking = validationResults.filter { validation ->
            if (allowedSeverity == null) {
                validation.severity == ValidationResultSeverity.Error
            } else {
                validation.severity.value() > allowedSeverity.value()
            }
        }
        return QueryResult(
            correlationId = correlationId,
            data = data,
            isReady = isReady,
            isAuthorized = isAuthorized,
            validationResults = blocking,
            exceptionMessages = exceptionMessages,
            exceptionStackTrace = exceptionStackTrace,
            paging = paging,
            changeSet = changeSet
        )
    }

    private fun QueryResult<*>.merge(
        fragment: QueryResult<*>,
        includePayload: Boolean = false
    ): QueryResult<Any?> = QueryResult(
        correlationId = correlationId,
        data = if (includePayload) fragment.data else data,
        isReady = isReady && fragment.isReady,
        isAuthorized = isAuthorized && fragment.isAuthorized,
        validationResults = validationResults + fragment.validationResults,
        exceptionMessages = exceptionMessages + fragment.exceptionMessages,
        exceptionStackTrace = listOf(exceptionStackTrace, fragment.exceptionStackTrace)
            .filter { it.isNotEmpty() }
            .joinToString(System.lineSeparator()),
        paging = if (includePayload) fragment.paging else paging,
        changeSet = if (includePayload) fragment.changeSet else changeSet
    )

    private suspend fun renderPayload(
        original: Any,
        context: QueryContext,
        initial: QueryRendererResult
    ): QueryRendererResult {
        val rendered = renderers.render(original, initial, context)
        return QueryRendererResult(readModelInterceptors.intercept(rendered.data, context), rendered.paging)
    }

    private fun QueryResult<*>.withPayload(data: Any?, paging: PagingInfo): QueryResult<Any?> = QueryResult(
        correlationId = correlationId,
        data = data,
        isReady = isReady,
        isAuthorized = isAuthorized,
        validationResults = validationResults,
        exceptionMessages = exceptionMessages,
        exceptionStackTrace = exceptionStackTrace,
        paging = paging,
        changeSet = changeSet
    )

    private fun normalizeData(value: Any?): Any? = when (value) {
        // QueryResult takes the single defensive copy for list payloads.
        is List<*> -> value
        is Array<*> -> java.util.Collections.unmodifiableList(value.toList())
        is BooleanArray -> java.util.Collections.unmodifiableList(value.toList())
        is ByteArray -> java.util.Collections.unmodifiableList(value.toList())
        is CharArray -> java.util.Collections.unmodifiableList(value.toList())
        is DoubleArray -> java.util.Collections.unmodifiableList(value.toList())
        is FloatArray -> java.util.Collections.unmodifiableList(value.toList())
        is IntArray -> java.util.Collections.unmodifiableList(value.toList())
        is LongArray -> java.util.Collections.unmodifiableList(value.toList())
        is ShortArray -> java.util.Collections.unmodifiableList(value.toList())
        else -> value
    }
}

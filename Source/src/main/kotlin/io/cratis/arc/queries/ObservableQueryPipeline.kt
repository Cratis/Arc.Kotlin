// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import io.cratis.arc.results.PagingInfo
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultSeverity
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

/** Result of opening an observable query. */
public sealed interface ObservableQueryOpenResult {
    /** Query filters or performer creation rejected the subscription. */
    public class Failure(public val result: QueryResult<*>) : ObservableQueryOpenResult

    /** A controlled cold stream of query results. */
    public class Stream(public val results: Flow<QueryResult<*>>) : ObservableQueryOpenResult
}

/** Host-neutral observable-query execution pipeline. */
public interface ObservableQueryPipeline {
    /** Opens an observable query using only explicit request context. */
    public suspend fun open(
        request: QueryRequest,
        options: QueryExecutionOptions,
        transferMode: ObservableQueryTransferMode = ObservableQueryTransferMode.FULL,
        keyExtractor: ((Any) -> Any?)? = null
    ): ObservableQueryOpenResult
}

/** Default observable pipeline using the same performer registry and filters as one-shot queries. */
public class DefaultObservableQueryPipeline @JvmOverloads constructor(
    private val performers: QueryPerformerRegistry,
    queryFilters: Iterable<QueryFilter> = emptyList(),
    private val changeSets: ChangeSetComputer = ChangeSetComputer(),
    private val renderers: QueryRenderers = DefaultQueryRenderers(),
    private val readModelInterceptors: ReadModelInterceptors = DefaultReadModelInterceptors(),
    private val emissionGuards: ObservableQueryEmissionGuards = DefaultObservableQueryEmissionGuards()
) : ObservableQueryPipeline {
    private val filters = java.util.List.copyOf(queryFilters.toList())

    override suspend fun open(
        request: QueryRequest,
        options: QueryExecutionOptions,
        transferMode: ObservableQueryTransferMode,
        keyExtractor: ((Any) -> Any?)?
    ): ObservableQueryOpenResult {
        val performer = performers.find(request.queryName)
            ?: return ObservableQueryOpenResult.Failure(
                QueryResult.missingPerformer<Any?>(options.correlationId, request.queryName.value)
            )
        if (performer.descriptor.transport != QueryTransportType.OBSERVABLE) {
            return ObservableQueryOpenResult.Failure(
                QueryResult.error<Any?>(options.correlationId, "Query ${request.queryName.value} is not observable.")
            )
        }
        val context = QueryContext(
            options.correlationId,
            request,
            request.queryName,
            options.principal,
            options.tenantId,
            options.tenantNamespace,
            options.serviceResolver,
            options.allowedValidationSeverity,
            options.exposeExceptionDetails
        )
        val filterResult = executeFilters(context).filterValidation(options.allowedValidationSeverity)
        if (!filterResult.isSuccess) return ObservableQueryOpenResult.Failure(filterResult)

        val upstream = try {
            performer.perform(context)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: QueryArgumentException) {
            return ObservableQueryOpenResult.Failure(argumentFailure(options, exception))
        } catch (exception: Exception) {
            return ObservableQueryOpenResult.Failure(QueryResult.exception<Any?>(options.correlationId, exception))
        }
        if (upstream !is Flow<*>) {
            return ObservableQueryOpenResult.Failure(
                QueryResult.error<Any?>(options.correlationId, "Observable query ${request.queryName.value} did not return a Flow.")
            )
        }

        val paging = PagingInfo(request.paging.page, request.paging.pageSize, 0)
        val results = flow<QueryResult<*>> {
            var previous: List<*>? = null
            var isFirstEmission = true
            try {
                upstream.collect { value ->
                    val wrapped = wrapEmission(filterResult, value, context, paging)
                        .filterValidation(options.allowedValidationSeverity)
                    val verdict = if (emissionGuards.hasGuards) {
                        emissionGuards.guard(ObservableQueryEmissionContext(
                            request.queryName,
                            request.arguments,
                            options.principal,
                            options.tenantId,
                            options.tenantNamespace,
                            options.correlationId,
                            options.serviceResolver,
                            isFirstEmission,
                            wrapped.data
                        ))
                    } else {
                        ObservableQueryEmissionVerdict.ALLOW
                    }
                    isFirstEmission = false
                    when (verdict) {
                        ObservableQueryEmissionVerdict.SUPPRESS -> return@collect
                        ObservableQueryEmissionVerdict.DENY_AND_TERMINATE -> {
                            emit(QueryResult.unauthorized<Any?>(options.correlationId))
                            throw ObservableQueryTerminatedException
                        }
                        ObservableQueryEmissionVerdict.ALLOW -> Unit
                    }
                    val current = wrapped.data as? List<*>
                    if (transferMode == ObservableQueryTransferMode.DELTA && previous != null && current != null) {
                        val changeSet = changeSets.compute(previous, current, keyExtractor)
                        if (changeSet != null) emit(wrapped.copyPayload(null, resultChangeSet = changeSet)) else emit(wrapped)
                    } else {
                        emit(wrapped)
                    }
                    if (current != null) previous = java.util.Collections.unmodifiableList(ArrayList(current))
                }
            } catch (_: ObservableQueryTerminatedException) {
                // Guard denial is a normal terminal outcome already represented by the unauthorized result.
            }
        }.catch { exception ->
            if (exception is CancellationException) throw exception
            emit(QueryResult.exception<Any?>(options.correlationId, exception))
        }
        return ObservableQueryOpenResult.Stream(results)
    }

    private suspend fun executeFilters(context: QueryContext): QueryResult<Any?> {
        var result: QueryResult<Any?> = QueryResult.success(context.correlationId)
        val ordered = filters.filterIsInstance<AuthorizationQueryFilter>() +
            filters.filterNot { it is AuthorizationQueryFilter }
        for (filter in ordered) {
            result = try {
                result.merge(filter.execute(context))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                result.merge(QueryResult.exception<Any?>(context.correlationId, exception))
            }.filterValidation(context.allowedValidationSeverity)
            if (!result.isSuccess) break
        }
        return result
    }

    private suspend fun wrapEmission(
        base: QueryResult<*>,
        value: Any?,
        context: QueryContext,
        paging: PagingInfo
    ): QueryResult<Any?> {
        val original = value ?: return base.copyPayload(null, paging).copyCorrelation(context.correlationId)
        val initial = when (value) {
            is QueryResult<*> -> QueryRendererResult(value.data, value.paging)
            is QueryPage<*> -> QueryRendererResult(value.items, PagingInfo(value.page, value.pageSize, value.totalItems))
            else -> QueryRendererResult(normalizeData(value), paging)
        }
        val rendered = renderers.render(original, initial, context)
        val intercepted = readModelInterceptors.intercept(rendered.data, context)
        val payload = QueryRendererResult(intercepted, rendered.paging)
        return when (value) {
            is QueryResult<*> -> base.merge(value.withPayload(payload.data, payload.paging), true)
            else -> base.copyPayload(payload.data, payload.paging)
        }.copyCorrelation(context.correlationId)
    }

    private fun argumentFailure(options: QueryExecutionOptions, exception: QueryArgumentException): QueryResult<Any?> =
        QueryResult.invalid(
            options.correlationId,
            listOf(ValidationResult(ValidationResultSeverity.Error, requireNotNull(exception.message), listOf(exception.argumentName)))
        )

    private fun QueryResult<*>.filterValidation(severity: ValidationResultSeverity?): QueryResult<Any?> {
        val blocking = validationResults.filter {
            if (severity == null) it.severity == ValidationResultSeverity.Error else it.severity.value() > severity.value()
        }
        return QueryResult(correlationId, data, isReady, isAuthorized, blocking, exceptionMessages, exceptionStackTrace, paging, changeSet)
    }

    private fun QueryResult<*>.merge(fragment: QueryResult<*>, includePayload: Boolean = false): QueryResult<Any?> =
        QueryResult(
            correlationId,
            if (includePayload) fragment.data else data,
            isReady && fragment.isReady,
            isAuthorized && fragment.isAuthorized,
            validationResults + fragment.validationResults,
            exceptionMessages + fragment.exceptionMessages,
            listOf(exceptionStackTrace, fragment.exceptionStackTrace).filter(String::isNotEmpty).joinToString(System.lineSeparator()),
            if (includePayload) fragment.paging else paging,
            if (includePayload) fragment.changeSet else changeSet
        )

    private fun QueryResult<*>.copyPayload(
        value: Any?,
        resultPaging: PagingInfo = paging,
        resultChangeSet: io.cratis.arc.results.ChangeSet<*>? = changeSet
    ): QueryResult<Any?> = QueryResult(
        correlationId, value, isReady, isAuthorized, validationResults, exceptionMessages, exceptionStackTrace, resultPaging, resultChangeSet
    )

    private fun QueryResult<*>.withPayload(value: Any?, resultPaging: PagingInfo): QueryResult<Any?> = QueryResult(
        correlationId, value, isReady, isAuthorized, validationResults, exceptionMessages, exceptionStackTrace, resultPaging, changeSet
    )

    private fun QueryResult<*>.copyCorrelation(value: java.util.UUID): QueryResult<Any?> = QueryResult(
        value, data, isReady, isAuthorized, validationResults, exceptionMessages, exceptionStackTrace, paging, changeSet
    )

    private object ObservableQueryTerminatedException : RuntimeException()

    private fun normalizeData(value: Any?): Any? = when (value) {
        // QueryResult takes the single defensive copy for list payloads.
        is List<*> -> value
        is Array<*> -> java.util.Collections.unmodifiableList(value.toList())
        else -> value
    }
}

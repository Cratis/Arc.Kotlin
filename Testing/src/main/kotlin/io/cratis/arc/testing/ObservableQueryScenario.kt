// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry
import io.cratis.arc.queries.DefaultObservableQueryEmissionGuards
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryRenderers
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.DefaultReadModelInterceptors
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.GuardObservableQueryEmission
import io.cratis.arc.queries.InterceptReadModel
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryAuthorizationFilter
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryPaging
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryRendererFor
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySortDirection
import io.cratis.arc.queries.QuerySorting
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.tenancy.TenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import java.util.UUID
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout

/** Collects a bounded number of emissions through the real observable-query pipeline. */
public class ObservableQueryScenario<TData> private constructor(
    private val artifacts: ScenarioArtifactRegistry,
    public val queryName: FullyQualifiedQueryName
) {
    private val filters = mutableListOf<QueryFilter>()
    private val validators = mutableListOf<QueryValidator>()
    private val renderers = mutableListOf<QueryRendererFor<*>>()
    private val readModelInterceptors = mutableListOf<InterceptReadModel<*>>()
    private val emissionGuards = mutableListOf<GuardObservableQueryEmission>()
    private val policies = linkedMapOf<String, AuthorizationPolicy>()
    private var services = ScenarioServiceResolver.empty()
    private var principal = ArcPrincipal.anonymous()
    private var tenantId: String? = null
    private var tenantNamespace: String? = null
    private var correlationId = UUID.randomUUID()
    private var allowedSeverity: ValidationResultSeverity? = null

    public constructor(module: ArcArtifactModule, queryName: FullyQualifiedQueryName) :
        this(ScenarioArtifactRegistry().register(module), queryName)

    public constructor(performer: QueryPerformer) :
        this(ScenarioArtifactRegistry().register(performer), performer.fullyQualifiedName)

    public fun addFilter(filter: QueryFilter): ObservableQueryScenario<TData> = apply { filters.add(filter) }
    public fun addValidator(validator: QueryValidator): ObservableQueryScenario<TData> = apply { validators.add(validator) }
    public fun addRenderer(renderer: QueryRendererFor<*>): ObservableQueryScenario<TData> = apply {
        renderers.add(renderer)
    }
    public fun addReadModelInterceptor(interceptor: InterceptReadModel<*>): ObservableQueryScenario<TData> = apply {
        readModelInterceptors.add(interceptor)
    }
    public fun addEmissionGuard(guard: GuardObservableQueryEmission): ObservableQueryScenario<TData> = apply {
        emissionGuards.add(guard)
    }
    public fun addPolicy(name: String, policy: AuthorizationPolicy): ObservableQueryScenario<TData> = apply {
        require(policies.putIfAbsent(name, policy) == null) { "An authorization policy named '$name' is already configured." }
    }
    public fun withServices(value: ScenarioServiceResolver): ObservableQueryScenario<TData> = apply { services = value }
    public fun <T : Any> addService(type: Class<T>, service: T): ObservableQueryScenario<TData> = apply {
        services = services.put(type, service)
    }
    public fun withPrincipal(value: ArcPrincipal): ObservableQueryScenario<TData> = apply { principal = value }
    @JvmOverloads
    public fun withTenant(id: String?, namespace: String? = id): ObservableQueryScenario<TData> = apply {
        tenantId = id
        tenantNamespace = namespace
    }
    /** Resolves tenant context explicitly and uses the resolved identifier as the default namespace. */
    @JvmOverloads
    public fun withTenantResolution(
        resolver: TenantIdResolver,
        context: TenantResolutionContext,
        namespace: String? = null
    ): ObservableQueryScenario<TData> = apply {
        val resolved = resolver.resolve(context)?.value()
        tenantId = resolved
        tenantNamespace = namespace ?: resolved
    }
    public fun withCorrelationId(value: UUID): ObservableQueryScenario<TData> = apply { correlationId = value }
    public fun withAllowedValidationSeverity(value: ValidationResultSeverity?): ObservableQueryScenario<TData> = apply {
        allowedSeverity = value
    }

    /** Collects exactly up to [maximumEmissions], failing if [timeoutMillis] elapses first. */
    @JvmOverloads
    public suspend fun collect(
        maximumEmissions: Int,
        timeoutMillis: Long = 5_000,
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING),
        transferMode: ObservableQueryTransferMode = ObservableQueryTransferMode.FULL
    ): ObservableQueryScenarioResult<TData> {
        require(maximumEmissions > 0) { "maximumEmissions must be positive." }
        require(timeoutMillis > 0) { "timeoutMillis must be positive." }
        val policyRegistry = ConcurrentAuthorizationPolicyRegistry()
        policies.forEach(policyRegistry::register)
        val builtIns = listOf<QueryFilter>(
            QueryAuthorizationFilter(artifacts.queryPerformers, AuthorizationEvaluator(policyRegistry)),
            DefaultQueryValidationFilter(validators)
        )
        val pipeline = DefaultObservableQueryPipeline(
            artifacts.queryPerformers,
            builtIns + filters,
            renderers = DefaultQueryRenderers(renderers),
            readModelInterceptors = DefaultReadModelInterceptors(readModelInterceptors),
            emissionGuards = DefaultObservableQueryEmissionGuards(emissionGuards)
        )
        val opened = pipeline.open(
            QueryRequest(queryName, arguments, paging, sorting),
            QueryExecutionOptions(
                correlationId,
                principal,
                services,
                tenantId,
                tenantNamespace,
                allowedSeverity,
                true
            ),
            transferMode
        )
        return when (opened) {
            is ObservableQueryOpenResult.Failure -> ObservableQueryScenarioResult(opened.result, emptyList())
            is ObservableQueryOpenResult.Stream -> {
                val values = withTimeout(timeoutMillis) { opened.results.take(maximumEmissions).toList() }
                @Suppress("UNCHECKED_CAST")
                ObservableQueryScenarioResult(null, values as List<QueryResult<TData>>)
            }
        }
    }
}

/** Framework-neutral assertions for observable scenario outcomes. */
public class ObservableQueryScenarioResult<TData>(
    public val failure: QueryResult<*>?,
    emissions: List<QueryResult<TData>>
) {
    public val emissions: List<QueryResult<TData>> = java.util.List.copyOf(emissions)

    public fun shouldSucceed(): ObservableQueryScenarioResult<TData> = apply {
        if (failure != null || emissions.any { !it.isSuccess }) throw AssertionError("Expected observable query to succeed.")
    }

    public fun shouldFail(): QueryResult<*> = failure ?: throw AssertionError("Expected observable query opening to fail.")

    public fun shouldHaveEmissionCount(expected: Int): ObservableQueryScenarioResult<TData> = apply {
        if (emissions.size != expected) throw AssertionError("Expected $expected emissions but received ${emissions.size}.")
    }

    public fun shouldHaveData(index: Int, expected: TData?): ObservableQueryScenarioResult<TData> = apply {
        if (emissions[index].data != expected) throw AssertionError("Expected emission $index data to equal '$expected'.")
    }

    public fun shouldTerminateUnauthorized(): ObservableQueryScenarioResult<TData> = apply {
        if (failure != null || emissions.isEmpty() || emissions.last().isAuthorized) {
            throw AssertionError("Expected observable query to terminate with an unauthorized emission.")
        }
    }
}

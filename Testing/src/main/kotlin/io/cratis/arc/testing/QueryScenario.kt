// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.testing

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.artifacts.ArcArtifactModule
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry
import io.cratis.arc.json.ArcObjectMapper
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.DefaultQueryRenderers
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.DefaultReadModelInterceptors
import io.cratis.arc.queries.FullyQualifiedQueryName
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
import io.cratis.arc.queries.InterceptReadModel
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.tenancy.TenantIdResolver
import io.cratis.arc.tenancy.TenantResolutionContext
import java.util.LinkedHashMap
import java.util.UUID

/** Runs one exact generated or manual query performer through the real in-process Arc query pipeline. */
public class QueryScenario<TData> private constructor(
    private val artifacts: ScenarioArtifactRegistry,
    public val queryName: FullyQualifiedQueryName
) {
    private val selectedPerformer: QueryPerformer = artifacts.query(queryName)
    private val filters = mutableListOf<QueryFilter>()
    private val validators = mutableListOf<QueryValidator>()
    private val renderers = mutableListOf<QueryRendererFor<*>>()
    private val readModelInterceptors = mutableListOf<InterceptReadModel<*>>()
    private val policies = LinkedHashMap<String, AuthorizationPolicy>()
    private val objectMapper: ObjectMapper = ArcObjectMapper.create()
    private var services: ScenarioServiceResolver = ScenarioServiceResolver.empty()
    private var principal: ArcPrincipal = ArcPrincipal.anonymous()
    private var tenantId: String? = null
    private var tenantNamespace: String? = null
    private var correlationId: UUID = UUID.randomUUID()
    private var allowedValidationSeverity: ValidationResultSeverity? =
        if (selectedPerformer.descriptor.treatWarningsAsErrors) ValidationResultSeverity.Information else null
    private var exposeExceptionDetails: Boolean = true
    private var roundTripArguments: Boolean = true
    private var roundTripResultData: Boolean = true

    /** Creates a scenario from a complete generated [module] and exact [queryName]. */
    public constructor(module: ArcArtifactModule, queryName: FullyQualifiedQueryName) :
        this(ScenarioArtifactRegistry().register(module), queryName)

    /** Creates a scenario around one real manual [performer]. */
    public constructor(performer: QueryPerformer) :
        this(ScenarioArtifactRegistry().register(performer), performer.fullyQualifiedName)

    /** Adds a query filter and returns this scenario. */
    public fun addFilter(filter: QueryFilter): QueryScenario<TData> = apply { filters.add(filter) }

    /** Adds a query validator and returns this scenario. */
    public fun addValidator(validator: QueryValidator): QueryScenario<TData> = apply { validators.add(validator) }

    /** Adds a query renderer and returns this scenario. */
    public fun addRenderer(renderer: QueryRendererFor<*>): QueryScenario<TData> = apply { renderers.add(renderer) }

    /** Adds a typed read-model interceptor and returns this scenario. */
    public fun addReadModelInterceptor(interceptor: InterceptReadModel<*>): QueryScenario<TData> = apply {
        readModelInterceptors.add(interceptor)
    }

    /** Adds a named authorization policy, rejecting duplicate names. */
    public fun addPolicy(name: String, policy: AuthorizationPolicy): QueryScenario<TData> = apply {
        require(name.isNotBlank()) { "Policy name cannot be blank." }
        require(!policies.containsKey(name)) { "An authorization policy named '$name' is already configured." }
        policies[name] = policy
    }

    /** Replaces the immutable service resolver and returns this scenario. */
    public fun withServices(services: ScenarioServiceResolver): QueryScenario<TData> = apply {
        this.services = services
    }

    /** Adds one exact service registration by replacing the immutable resolver snapshot. */
    public fun <T : Any> addService(type: Class<T>, service: T): QueryScenario<TData> = apply {
        services = services.put(type, service)
    }

    /** Kotlin convenience for [addService]. */
    public inline fun <reified T : Any> addService(service: T): QueryScenario<TData> =
        addService(T::class.java, service)

    /** Uses [principal] for authorization. */
    public fun withPrincipal(principal: ArcPrincipal): QueryScenario<TData> = apply { this.principal = principal }

    /** Uses explicit tenant context. */
    @JvmOverloads
    public fun withTenant(tenantId: String?, tenantNamespace: String? = tenantId): QueryScenario<TData> = apply {
        this.tenantId = tenantId
        this.tenantNamespace = tenantNamespace
    }

    /** Resolves tenant context explicitly and uses the resolved identifier as the default namespace. */
    @JvmOverloads
    public fun withTenantResolution(
        resolver: TenantIdResolver,
        context: TenantResolutionContext,
        tenantNamespace: String? = null
    ): QueryScenario<TData> = apply {
        val resolved = resolver.resolve(context)?.value()
        tenantId = resolved
        this.tenantNamespace = tenantNamespace ?: resolved
    }

    /** Uses a stable correlation identifier. */
    public fun withCorrelationId(correlationId: UUID): QueryScenario<TData> = apply {
        this.correlationId = correlationId
    }

    /** Configures the maximum non-blocking validation severity. */
    public fun withAllowedValidationSeverity(severity: ValidationResultSeverity?): QueryScenario<TData> = apply {
        allowedValidationSeverity = severity
    }

    /** Configures retention of exception details in the real pipeline result. */
    public fun withExposedExceptionDetails(enabled: Boolean): QueryScenario<TData> = apply {
        exposeExceptionDetails = enabled
    }

    /** Enables or disables Arc JSON round trips for both arguments and returned data. */
    public fun withSerializationRoundTrip(enabled: Boolean): QueryScenario<TData> = apply {
        roundTripArguments = enabled
        roundTripResultData = enabled
    }

    /** Configures the argument round trip independently. */
    public fun withArgumentSerializationRoundTrip(enabled: Boolean): QueryScenario<TData> = apply {
        roundTripArguments = enabled
    }

    /** Configures the result-data round trip independently. */
    public fun withResultSerializationRoundTrip(enabled: Boolean): QueryScenario<TData> = apply {
        roundTripResultData = enabled
    }

    /** Performs the query with transport-independent arguments, paging, and sorting. */
    @JvmOverloads
    public suspend fun perform(
        arguments: Map<String, Any?> = emptyMap(),
        paging: QueryPaging = QueryPaging(0, 0),
        sorting: QuerySorting = QuerySorting("", QuerySortDirection.ASCENDING)
    ): QueryScenarioResult<TData> {
        val request = QueryRequest(
            queryName,
            if (roundTripArguments) roundTripArguments(arguments) else arguments,
            paging,
            sorting
        )
        val result = pipeline().perform(request, options())
        return wrap(if (roundTripResultData) roundTripResult(result) else result)
    }

    private fun pipeline(): DefaultQueryPipeline {
        val policyRegistry = ConcurrentAuthorizationPolicyRegistry()
        policies.forEach(policyRegistry::register)
        val builtInFilters = listOf<QueryFilter>(
            QueryAuthorizationFilter(artifacts.queryPerformers, AuthorizationEvaluator(policyRegistry)),
            DefaultQueryValidationFilter(validators)
        )
        return DefaultQueryPipeline(
            artifacts.queryPerformers,
            builtInFilters + filters,
            DefaultQueryRenderers(renderers),
            DefaultReadModelInterceptors(readModelInterceptors)
        )
    }

    private fun options(): QueryExecutionOptions = QueryExecutionOptions(
        correlationId,
        principal,
        services,
        tenantId,
        tenantNamespace,
        allowedValidationSeverity,
        exposeExceptionDetails
    )

    private fun roundTripArguments(arguments: Map<String, Any?>): Map<String, Any?> =
        LinkedHashMap<String, Any?>().also { copy ->
            arguments.forEach { (name, value) -> copy[name] = roundTripValue(value) }
        }

    private fun roundTripValue(value: Any?): Any? = when (value) {
        null -> null
        is List<*> -> java.util.List.copyOf(value.map(::roundTripValue))
        else -> objectMapper.readValue(objectMapper.writeValueAsBytes(value), value.javaClass)
    }

    private fun roundTripResult(result: QueryResult<*>): QueryResult<*> = QueryResult<Any?>(
        correlationId = result.correlationId,
        data = roundTripValue(result.data),
        isReady = result.isReady,
        isAuthorized = result.isAuthorized,
        validationResults = result.validationResults,
        exceptionMessages = result.exceptionMessages,
        exceptionStackTrace = result.exceptionStackTrace,
        paging = result.paging,
        changeSet = result.changeSet
    )

    @Suppress("UNCHECKED_CAST")
    private fun wrap(result: QueryResult<*>): QueryScenarioResult<TData> =
        QueryScenarioResult(result as QueryResult<TData>)
}

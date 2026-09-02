// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.SerializationFeature
import io.cratis.arc.authentication.AsyncAuthentication
import io.cratis.arc.authentication.AsyncAuthenticationHandler
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationHandler
import io.cratis.arc.authentication.DefaultAuthentication
import io.cratis.arc.authentication.asAuthenticationHandler
import io.cratis.arc.authorization.AuthorizationEvaluator
import io.cratis.arc.authorization.AuthorizationPolicy
import io.cratis.arc.authorization.AuthorizationPolicyRegistry
import io.cratis.arc.authorization.ConcurrentAuthorizationPolicyRegistry
import io.cratis.arc.commands.AsyncCommandPipeline
import io.cratis.arc.commands.CommandAuthorizationFilter
import io.cratis.arc.commands.CommandExecutionScope
import io.cratis.arc.commands.CommandFilter
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandContextValuesProvider
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.commands.CommandValidator
import io.cratis.arc.commands.ConcurrentCommandHandlerRegistry
import io.cratis.arc.commands.DefaultCommandPipeline
import io.cratis.arc.commands.DefaultCommandValidationFilter
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.json.ArcJacksonModule
import io.cratis.arc.introspection.DefaultIntrospectionService
import io.cratis.arc.introspection.IntrospectionService
import io.cratis.arc.json.ArcPropertyNamingStrategy
import io.cratis.arc.queries.AsyncQueryPipeline
import io.cratis.arc.queries.ConcurrentQueryPerformerRegistry
import io.cratis.arc.queries.CanResolveReadModelForCommand
import io.cratis.arc.queries.DefaultObservableQueryEmissionGuards
import io.cratis.arc.queries.DefaultObservableQueryPipeline
import io.cratis.arc.queries.DefaultQueryHealthTracker
import io.cratis.arc.queries.DefaultQueryPipeline
import io.cratis.arc.queries.DefaultQueryRenderers
import io.cratis.arc.queries.DefaultQueryValidationFilter
import io.cratis.arc.queries.DefaultReadModelInterceptors
import io.cratis.arc.queries.GuardObservableQueryEmission
import io.cratis.arc.queries.InterceptReadModel
import io.cratis.arc.queries.ObservableQueryEmissionGuards
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.QueryAuthorizationFilter
import io.cratis.arc.queries.QueryFilter
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryRendererFor
import io.cratis.arc.queries.QueryRenderers
import io.cratis.arc.queries.QueryValidator
import io.cratis.arc.queries.QueryableQueryRenderer
import io.cratis.arc.queries.ReadModelForCommandResolverRegistry
import io.cratis.arc.queries.ReadModelInterceptors
import io.cratis.arc.tenancy.TenantIdResolver
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean

/** Host-neutral Arc runtime wiring shared by web and non-web Spring Boot applications. */
@AutoConfiguration
@EnableConfigurationProperties(ArcProperties::class)
public class ArcAutoConfiguration {
    /** Resolves tenants from the configured strategy chain unless the application supplies an override. */
    @Bean
    @ConditionalOnMissingBean(TenantIdResolver::class)
    public fun arcTenantIdResolver(properties: ArcProperties): TenantIdResolver =
        configuredTenantIdResolver(properties)

    /** Enforces authenticated tenant memberships unless the application supplies an override. */
    @Bean
    @ConditionalOnMissingBean(TenantAccessEvaluator::class)
    public fun arcTenantAccessEvaluator(properties: ArcProperties): TenantAccessEvaluator =
        defaultTenantAccessEvaluator(properties)

    /** Captures and resolves tenant context once at each transport entry point. */
    @Bean
    @ConditionalOnMissingBean
    internal fun arcTenantResolutionService(
        resolver: TenantIdResolver,
        accessEvaluator: TenantAccessEvaluator,
        properties: ArcProperties
    ): ArcTenantResolutionService = ArcTenantResolutionService(resolver, accessEvaluator, properties)

    /** Registry populated from generated artifact modules. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcCommandHandlerRegistry(): CommandHandlerRegistry = ConcurrentCommandHandlerRegistry()

    /** Registry populated from generated query performers. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcQueryPerformerRegistry(): QueryPerformerRegistry = ConcurrentQueryPerformerRegistry()

    /** Loads generated modules from both ServiceLoader and ordinary Spring beans. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcArtifactModules(
        applicationContext: ApplicationContext,
        commandHandlers: CommandHandlerRegistry,
        queryPerformers: QueryPerformerRegistry
    ): ArcArtifactModules = ArcArtifactModules(applicationContext, commandHandlers, queryPerformers)

    /** Ordered host-neutral authentication chain; an application may replace the complete service. */
    @Bean
    @ConditionalOnMissingBean(Authentication::class)
    public fun arcAuthentication(
        handlers: ObjectProvider<AuthenticationHandler>,
        asyncHandlers: ObjectProvider<AsyncAuthenticationHandler>
    ): Authentication = DefaultAuthentication(
        handlers.orderedStream().toList() + asyncHandlers.orderedStream().map { it.asAuthenticationHandler() }.toList()
    )

    /** Java-friendly asynchronous authentication service. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcAsyncAuthentication(
        authentication: Authentication,
        coroutineScope: ArcApplicationCoroutineScope
    ): AsyncAuthentication = AsyncAuthentication.fromCoroutineScope(authentication, coroutineScope)

    /** Deterministic registry-backed command and query metadata. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcIntrospectionService(
        commandHandlers: CommandHandlerRegistry,
        queryPerformers: QueryPerformerRegistry,
        properties: ArcProperties
    ): IntrospectionService = DefaultIntrospectionService(commandHandlers, queryPerformers, properties.endpoints.toOptions())

    /** Registry containing host-neutral policies keyed by their Spring bean names. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcAuthorizationPolicyRegistry(
        policies: Map<String, AuthorizationPolicy>
    ): AuthorizationPolicyRegistry = ConcurrentAuthorizationPolicyRegistry().also { registry ->
        policies.toSortedMap().forEach(registry::register)
    }

    /** Evaluates generated authorization metadata. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcAuthorizationEvaluator(policies: AuthorizationPolicyRegistry): AuthorizationEvaluator =
        AuthorizationEvaluator(policies)

    /** Resolves generated handler dependencies from Spring. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcServiceResolver(applicationContext: ApplicationContext): ServiceResolver =
        SpringServiceResolver(applicationContext)

    /** Applies generated command authorization before all other command filters. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcCommandAuthorizationFilter(
        handlers: CommandHandlerRegistry,
        evaluator: AuthorizationEvaluator
    ): CommandAuthorizationFilter = CommandAuthorizationFilter(handlers, evaluator)

    /** Adapts every host-neutral typed validator declared as a Spring bean. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcCommandValidationFilter(
        validators: ObjectProvider<CommandValidator<*>>
    ): DefaultCommandValidationFilter = DefaultCommandValidationFilter(validators.orderedStream().toList())

    /** Applies generated query authorization before all other query filters. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcQueryAuthorizationFilter(
        performers: QueryPerformerRegistry,
        evaluator: AuthorizationEvaluator
    ): QueryAuthorizationFilter = QueryAuthorizationFilter(performers, evaluator)

    /** Adapts every host-neutral query validator declared as a Spring bean. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcQueryValidationFilter(
        validators: ObjectProvider<QueryValidator>
    ): DefaultQueryValidationFilter = DefaultQueryValidationFilter(validators.orderedStream().toList())

    /** Bounded scope owned and closed by the application context. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public fun arcApplicationCoroutineScope(properties: ArcProperties): ArcApplicationCoroutineScope =
        ArcApplicationCoroutineScope(properties.coroutineParallelism, properties.coroutineQueueCapacity)

    /** The real coroutine-first command pipeline. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcCommandPipeline(
        handlers: CommandHandlerRegistry,
        filters: ObjectProvider<CommandFilter>,
        scopes: ObjectProvider<CommandExecutionScope>,
        responseHandlers: ObjectProvider<CommandResponseValueHandler>,
        contextValuesProviders: ObjectProvider<CommandContextValuesProvider>,
        properties: ArcProperties
    ): CommandPipeline = DefaultCommandPipeline(
        handlers,
        filters.orderedStream().toList(),
        scopes.orderedStream().toList(),
        responseHandlers.orderedStream().toList(),
        contextValuesProviders.orderedStream().toList(),
        properties.commandScopeCompletionTimeout
    )

    /** Java-friendly asynchronous facade over the command pipeline. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcAsyncCommandPipeline(
        pipeline: CommandPipeline,
        coroutineScope: ArcApplicationCoroutineScope
    ): AsyncCommandPipeline = AsyncCommandPipeline.fromCoroutineScope(pipeline, coroutineScope)

    /** Aggregates ordered query renderer beans and the JVM in-memory iterable renderer. */
    @Bean
    @ConditionalOnMissingBean(QueryRenderers::class)
    public fun arcQueryRenderers(renderers: ObjectProvider<QueryRendererFor<*>>): QueryRenderers {
        val discovered = renderers.orderedStream().toList()
        val configured = if (discovered.any { it is QueryableQueryRenderer }) discovered else discovered + QueryableQueryRenderer()
        return DefaultQueryRenderers(configured)
    }

    /** Aggregates ordered typed read-model interceptor beans. */
    @Bean
    @ConditionalOnMissingBean(ReadModelInterceptors::class)
    public fun arcReadModelInterceptors(interceptors: ObjectProvider<InterceptReadModel<*>>): ReadModelInterceptors =
        DefaultReadModelInterceptors(interceptors.orderedStream().toList())

    /** Aggregates pluggable observable emission guard beans. */
    @Bean
    @ConditionalOnMissingBean(ObservableQueryEmissionGuards::class)
    public fun arcObservableQueryEmissionGuards(
        guards: ObjectProvider<GuardObservableQueryEmission>
    ): ObservableQueryEmissionGuards = DefaultObservableQueryEmissionGuards(guards.orderedStream().toList())

    /** Tracks observable-query connection and subscription health. */
    @Bean
    @ConditionalOnMissingBean(QueryHealthTracker::class)
    public fun arcQueryHealthTracker(): QueryHealthTracker = DefaultQueryHealthTracker()

    /** Arbitrates command-side read-model resolver beans by declared or fallback ownership. */
    @Bean
    @ConditionalOnMissingBean(ReadModelForCommandResolverRegistry::class)
    public fun arcReadModelForCommandResolverRegistry(
        resolvers: ObjectProvider<CanResolveReadModelForCommand>
    ): ReadModelForCommandResolverRegistry = ReadModelForCommandResolverRegistry(resolvers.orderedStream().toList())

    /** The real coroutine-first one-shot query pipeline. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcQueryPipeline(
        performers: QueryPerformerRegistry,
        filters: ObjectProvider<QueryFilter>,
        renderers: QueryRenderers,
        interceptors: ReadModelInterceptors
    ): QueryPipeline = DefaultQueryPipeline(performers, filters.orderedStream().toList(), renderers, interceptors)

    /** The real coroutine-first observable query pipeline. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcObservableQueryPipeline(
        performers: QueryPerformerRegistry,
        filters: ObjectProvider<QueryFilter>,
        renderers: QueryRenderers,
        interceptors: ReadModelInterceptors,
        emissionGuards: ObservableQueryEmissionGuards
    ): ObservableQueryPipeline = DefaultObservableQueryPipeline(
        performers,
        filters.orderedStream().toList(),
        renderers = renderers,
        readModelInterceptors = interceptors,
        emissionGuards = emissionGuards
    )

    /** Java-friendly asynchronous facade over the one-shot query pipeline. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcAsyncQueryPipeline(
        pipeline: QueryPipeline,
        coroutineScope: ArcApplicationCoroutineScope
    ): AsyncQueryPipeline = AsyncQueryPipeline.fromCoroutineScope(pipeline, coroutineScope)

    /** Arc serializers and deserializers, discovered by Spring Boot's Jackson auto-configuration. */
    @Bean
    @ConditionalOnMissingBean
    public fun arcJacksonModule(): ArcJacksonModule = ArcJacksonModule()

    /** Applies Arc's wire defaults to an application mapper without replacing that mapper. */
    @Bean
    @ConditionalOnMissingBean(name = ["arcJacksonCustomizer"])
    public fun arcJacksonCustomizer(): Jackson2ObjectMapperBuilderCustomizer = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.propertyNamingStrategy(ArcPropertyNamingStrategy())
        builder.serializationInclusion(JsonInclude.Include.NON_NULL)
        builder.featuresToEnable(
            JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature(),
            JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature()
        )
        builder.featuresToDisable(
            SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
            SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS
        )
    }
}

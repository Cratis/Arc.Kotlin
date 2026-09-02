// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandExecutionOptions
import io.cratis.arc.commands.CommandHandler
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandPipeline
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.http.ArcHttpStatusMapper
import io.cratis.arc.identity.AsyncIdentityDetailsProvider
import io.cratis.arc.identity.AsyncUsersProvider
import io.cratis.arc.identity.IdentityDetailsProvider
import io.cratis.arc.identity.UsersProvider
import io.cratis.arc.introspection.IntrospectionService
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryPipeline
import io.cratis.arc.queries.QueryTransportType
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.arc.tenancy.AsyncTenantsProvider
import io.cratis.arc.tenancy.TenantIdResolver
import io.cratis.arc.tenancy.TenantsProvider
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.convert.ConversionService
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncManager
import org.springframework.web.context.request.async.WebAsyncUtils
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping

private const val IDENTITY_ROUTE = "/.cratis/me"
private const val IDENTITY_SCHEMA_ROUTE = "/.cratis/identity-details/schema"
private const val COMMANDS_INTROSPECTION_ROUTE = "/.cratis/commands"
private const val QUERIES_INTROSPECTION_ROUTE = "/.cratis/queries"
private const val QUERY_HEALTH_ROUTE = "/.cratis/queries/health"
private const val IDENTITY_ARTIFACT = "Arc identity"
private const val ARC_AUTHENTICATION_FILTER_ORDER = -90
private const val IDENTITY_SCHEMA_ARTIFACT = "Arc identity details schema"

/** Servlet-only dynamic command and one-shot query endpoint hosting. */
@AutoConfiguration(after = [ArcAutoConfiguration::class, ArcValidationAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["jakarta.servlet.Servlet", "org.springframework.web.servlet.DispatcherServlet"])
@EnableConfigurationProperties(ArcProperties::class)
public class ArcWebAutoConfiguration {
    /** Runs optional Arc authentication asynchronously before registered Arc endpoints. */
    @Bean("arcAuthenticationFilterRegistration")
    @ConditionalOnMissingBean(name = ["arcAuthenticationFilterRegistration"])
    internal fun arcAuthenticationFilterRegistration(
        authentication: Authentication,
        principalFactory: ArcPrincipalFactory,
        handlers: CommandHandlerRegistry,
        queryPerformers: QueryPerformerRegistry,
        coroutineScope: ArcApplicationCoroutineScope,
        properties: ArcProperties
    ): FilterRegistrationBean<ArcAuthenticationFilter> = FilterRegistrationBean(
        ArcAuthenticationFilter(
            authentication,
            principalFactory,
            handlers,
            queryPerformers,
            coroutineScope,
            properties
        )
    ).also { registration ->
        registration.setName("arcAuthenticationFilter")
        registration.order = ARC_AUTHENTICATION_FILTER_ORDER
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC)
        registration.isAsyncSupported = true
        registration.addUrlPatterns("/*")
    }

    /** Shared runtime for observable HTTP, SSE, and optional WebSocket transports. */
    @Bean(name = ["arcObservableQueryTransport"], destroyMethod = "close")
    @ConditionalOnMissingBean(name = ["arcObservableQueryTransport"])
    internal fun arcObservableQueryTransport(
        artifactModules: ArcArtifactModules,
        queryPerformers: QueryPerformerRegistry,
        observableQueryPipeline: ObservableQueryPipeline,
        serviceResolver: ServiceResolver,
        objectMapper: ObjectMapper,
        conversionServices: ObjectProvider<ConversionService>,
        coroutineScope: ArcApplicationCoroutineScope,
        properties: ArcProperties,
        environment: Environment,
        principalFactory: ArcPrincipalFactory,
        tenantResolution: ArcTenantResolutionService,
        queryHealthTracker: QueryHealthTracker
    ): ArcObservableQueryTransport {
        artifactModules.modules.size
        val conversionService = conversionServices.orderedStream().findFirst().orElseGet {
            org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()
        }
        val classLoader = artifactModules.modules.firstOrNull()?.javaClass?.classLoader
            ?: ArcWebAutoConfiguration::class.java.classLoader
        return ArcObservableQueryTransport(
            queryPerformers,
            observableQueryPipeline,
            serviceResolver,
            ArcQueryRequestBinder(
                objectMapper,
                conversionService,
                classLoader,
                setOf(properties.tenancy.queryParameterName)
            ),
            objectMapper,
            coroutineScope,
            properties,
            properties.shouldExposeExceptionDetails(environment),
            principalFactory,
            tenantResolution,
            queryHealthTracker
        )
    }

    /** Registers method-aware execute, validate, one-shot, observable, and SSE hub routes. */
    @Bean("arcCommandHandlerMapping")
    @ConditionalOnMissingBean(name = ["arcCommandHandlerMapping"])
    public fun arcCommandHandlerMapping(
        artifactModules: ArcArtifactModules,
        handlers: CommandHandlerRegistry,
        commandPipeline: CommandPipeline,
        queryPerformers: QueryPerformerRegistry,
        queryPipeline: QueryPipeline,
        serviceResolver: ServiceResolver,
        objectMapper: ObjectMapper,
        conversionServices: ObjectProvider<ConversionService>,
        coroutineScope: ArcApplicationCoroutineScope,
        properties: ArcProperties,
        environment: Environment,
        principalFactory: ArcPrincipalFactory,
        tenantIdResolver: TenantIdResolver,
        tenantAccessEvaluator: TenantAccessEvaluator,
        identityDetailsProviders: ObjectProvider<IdentityDetailsProvider<*>>,
        asyncIdentityDetailsProviders: ObjectProvider<AsyncIdentityDetailsProvider<*>>,
        usersProviders: ObjectProvider<UsersProvider>,
        asyncUsersProviders: ObjectProvider<AsyncUsersProvider>,
        tenantsProviders: ObjectProvider<TenantsProvider>,
        asyncTenantsProviders: ObjectProvider<AsyncTenantsProvider>,
        observableTransport: ArcObservableQueryTransport,
        queryHealthTracker: QueryHealthTracker,
        introspectionService: IntrospectionService
    ): SimpleUrlHandlerMapping {
        artifactModules.modules.size // Forces deterministic module registration before routes are built.
        val tenantResolution = ArcTenantResolutionService(tenantIdResolver, tenantAccessEvaluator, properties)
        val endpointOptions = properties.endpoints.toOptions()
        val exposeExceptionDetails = properties.shouldExposeExceptionDetails(environment)
        val routes = linkedMapOf<String, MutableMap<String, RegisteredEndpoint>>()
        val identityDetailsProvider = resolveIdentityDetailsProvider(
            identityDetailsProviders,
            asyncIdentityDetailsProviders
        )
        register(
            routes,
            COMMANDS_INTROSPECTION_ROUTE,
            GET_METHOD,
            "Introspect available command endpoints",
            ArcIntrospectionHttpRequestHandler({ introspectionService.commands }, objectMapper)
        )
        register(
            routes,
            QUERIES_INTROSPECTION_ROUTE,
            GET_METHOD,
            "Introspect available query endpoints",
            ArcIntrospectionHttpRequestHandler({ introspectionService.queries }, objectMapper)
        )
        val queryHealthHandler = ArcQueryHealthHttpRequestHandler(queryHealthTracker, objectMapper, properties)
        register(routes, QUERY_HEALTH_ROUTE, GET_METHOD, "Observable query health", queryHealthHandler)
        register(routes, QUERY_HEALTH_ROUTE, QUERY_METHOD, "Observable query health", queryHealthHandler)
        register(
            routes,
            USERS_ROUTE,
            GET_METHOD,
            "Arc development users",
            usersHttpRequestHandler(usersProviders, asyncUsersProviders, objectMapper, coroutineScope, properties)
        )
        register(
            routes,
            TENANTS_ROUTE,
            GET_METHOD,
            "Arc development tenants",
            tenantsHttpRequestHandler(tenantsProviders, asyncTenantsProviders, objectMapper, coroutineScope, properties)
        )
        register(
            routes,
            IDENTITY_SCHEMA_ROUTE,
            GET_METHOD,
            IDENTITY_SCHEMA_ARTIFACT,
            ArcIdentitySchemaHttpRequestHandler(identityDetailsProvider, objectMapper)
        )
        if (identityDetailsProvider != null) {
            register(
                routes,
                IDENTITY_ROUTE,
                GET_METHOD,
                IDENTITY_ARTIFACT,
                ArcIdentityHttpRequestHandler(
                    identityDetailsProvider,
                    principalFactory,
                    objectMapper,
                    coroutineScope,
                    properties,
                    tenantResolution,
                    environment
                )
            )
        }
        val commandHandlers = handlers.snapshot()
        val commandNamespaceCounts = commandHandlers.groupingBy { handler ->
            handler.metadata.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        commandHandlers.forEach { handler ->
            val namespace = handler.metadata.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.commandRoute(
                handler.metadata,
                endpointOptions,
                (commandNamespaceCounts[namespace] ?: 0) > 1
            )
            register(
                routes,
                route,
                POST_METHOD,
                handler.metadata.typeName,
                ArcCommandHttpRequestHandler(
                    handler,
                    false,
                    commandPipeline,
                    serviceResolver,
                    objectMapper,
                    coroutineScope,
                    properties,
                    exposeExceptionDetails,
                    principalFactory,
                    tenantResolution
                )
            )
            register(
                routes,
                validationRoute(route),
                POST_METHOD,
                handler.metadata.typeName,
                ArcCommandHttpRequestHandler(
                    handler,
                    true,
                    commandPipeline,
                    serviceResolver,
                    objectMapper,
                    coroutineScope,
                    properties,
                    exposeExceptionDetails,
                    principalFactory,
                    tenantResolution
                )
            )
        }

        val performers = queryPerformers.snapshot()
        val queryNamespaceCounts = performers.groupingBy { performer ->
            performer.descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        val conversionService = conversionServices.orderedStream().findFirst().orElseGet {
            org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()
        }
        val classLoader = artifactModules.modules.firstOrNull()?.javaClass?.classLoader
            ?: ArcWebAutoConfiguration::class.java.classLoader
        val requestBinder = ArcQueryRequestBinder(
            objectMapper,
            conversionService,
            classLoader,
            setOf(properties.tenancy.queryParameterName)
        )
        performers.forEach { performer ->
            val namespace = performer.descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.queryRoute(
                performer.descriptor,
                endpointOptions,
                (queryNamespaceCounts[namespace] ?: 0) > 1
            )
            val requestHandler = if (performer.descriptor.transport == QueryTransportType.OBSERVABLE) {
                observableTransport.directHttpHandler(performer)
            } else {
                ArcQueryHttpRequestHandler(
                    performer,
                    queryPipeline,
                    serviceResolver,
                    requestBinder,
                    objectMapper,
                    coroutineScope,
                    properties,
                    exposeExceptionDetails,
                    principalFactory,
                    tenantResolution
                )
            }
            register(routes, route, GET_METHOD, performer.fullyQualifiedName.value, requestHandler)
            if (properties.endpoints.isEnableQueryHttpMethod) {
                register(routes, route, QUERY_METHOD, performer.fullyQualifiedName.value, requestHandler)
            }
        }
        register(
            routes,
            OBSERVABLE_QUERY_SSE_ROUTE,
            GET_METHOD,
            "Observable query SSE hub",
            observableTransport.sseConnectHandler()
        )
        register(
            routes,
            OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE,
            POST_METHOD,
            "Observable query SSE subscribe",
            observableTransport.sseSubscribeHandler()
        )
        register(
            routes,
            OBSERVABLE_QUERY_SSE_UNSUBSCRIBE_ROUTE,
            POST_METHOD,
            "Observable query SSE unsubscribe",
            observableTransport.sseUnsubscribeHandler()
        )

        val mapping = SimpleUrlHandlerMapping()
        mapping.order = Ordered.HIGHEST_PRECEDENCE + 10
        mapping.setUrlMap(routes.mapValues { (_, endpoints) -> ArcHttpMethodDispatcher(endpoints) })
        return mapping
    }

    private fun register(
        routes: MutableMap<String, MutableMap<String, RegisteredEndpoint>>,
        route: String,
        method: String,
        artifactName: String,
        handler: HttpRequestHandler
    ) {
        val endpoints = routes.getOrPut(route, ::linkedMapOf)
        val endpoint = RegisteredEndpoint(artifactName, handler)
        val existing = endpoints.putIfAbsent(method, endpoint)
        if (existing != null) {
            throw IllegalStateException(
                "Duplicate Arc $method route '$route' for artifacts " +
                    "'${existing.artifactName}' and '$artifactName'."
            )
        }
    }

    private fun validationRoute(route: String): String = if (route == "/") "/validate" else "$route/validate"

    private class RegisteredEndpoint(val artifactName: String, val handler: HttpRequestHandler)

    private class ArcHttpMethodDispatcher(
        endpoints: Map<String, RegisteredEndpoint>
    ) : HttpRequestHandler {
        private val endpoints = java.util.Map.copyOf(endpoints)

        override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
            val endpoint = endpoints[request.method.uppercase()]
            if (endpoint == null) {
                response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
                response.setHeader("Allow", endpoints.keys.sorted().joinToString(", "))
                return
            }
            endpoint.handler.handleRequest(request, response)
        }
    }

    private companion object {
        const val GET_METHOD = "GET"
        const val POST_METHOD = "POST"
        const val QUERY_METHOD = "QUERY"
    }

    /** Uses Spring Security's captured Authentication only when Spring Security is present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
    public class Security {
        /** Captures authentication without consulting SecurityContextHolder after request entry. */
        @Bean
        @ConditionalOnMissingBean
        public fun arcPrincipalFactory(): ArcPrincipalFactory = SpringSecurityArcPrincipalFactory()
    }

    /** Servlet-native identity capture for applications without Spring Security. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.core.Authentication")
    public class ServletIdentity {
        /** Captures the servlet principal and required roles at request entry. */
        @Bean
        @ConditionalOnMissingBean
        public fun arcPrincipalFactory(): ArcPrincipalFactory = ServletArcPrincipalFactory()
    }
}

private class ArcQueryHealthHttpRequestHandler(
    private val tracker: QueryHealthTracker,
    private val objectMapper: ObjectMapper,
    private val properties: ArcProperties
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val correlationId = request.getHeader(properties.correlationHeader)
            ?.trim()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()
        response.setHeader(properties.correlationHeader, correlationId.toString())
        if (request.method.equals("QUERY", ignoreCase = true)) response.setHeader("Cache-Control", "no-store")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, QueryResult.success(correlationId, tracker.snapshot()))
    }
}

private class ArcIntrospectionHttpRequestHandler(
    private val metadata: () -> Any,
    private val objectMapper: ObjectMapper
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_OK
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, metadata())
    }
}

private class ArcCommandHttpRequestHandler(
    private val handler: CommandHandler,
    private val validationOnly: Boolean,
    private val pipeline: CommandPipeline,
    private val serviceResolver: ServiceResolver,
    private val objectMapper: ObjectMapper,
    private val coroutineScope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties,
    private val exposeExceptionDetails: Boolean,
    private val principalFactory: ArcPrincipalFactory,
    private val tenantResolution: ArcTenantResolutionService
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val requestStartedAt = System.nanoTime()
        val asyncManager = WebAsyncUtils.getAsyncManager(request)
        if (asyncManager.hasConcurrentResult()) {
            writeConcurrentResult(asyncManager, request, response)
            return
        }

        if (!request.method.equals("POST", ignoreCase = true)) {
            response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
            response.setHeader("Allow", "POST")
            return
        }

        val correlationId = parseCorrelationId(request.getHeader(properties.correlationHeader))
        prepareResponse(response, correlationId)
        if (!request.isAsyncSupported) {
            writeResult(
                response,
                CommandResult.exception(
                    correlationId,
                    IllegalStateException("Servlet asynchronous processing is not available for the Arc command endpoint.")
                )
            )
            return
        }

        val capturedRequest = try {
            captureRequest(request, correlationId)
        } catch (exception: Throwable) {
            CapturedCommandRequest.Completed(
                correlationId,
                CommandResult.exception(correlationId, exception),
                exception
            )
        }
        if (asyncManager.asyncWebRequest == null) {
            asyncManager.setAsyncWebRequest(StandardServletAsyncWebRequest(request, response))
        }

        val timeoutException = TimeoutException("Arc command request timed out.")
        val remainingTimeout = properties.requestTimeout.toMillis() -
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt)
        if (remainingTimeout <= 0) {
            writeResult(response, CommandResult.exception(correlationId, timeoutException), timeoutException)
            return
        }
        val deferredResult = DeferredResult<HostedCommandResult>(
            remainingTimeout,
            HostedCommandResult(CommandResult.exception(correlationId, timeoutException), timeoutException)
        )
        val job = coroutineScope.tryLaunch(start = CoroutineStart.LAZY) {
            try {
                deferredResult.setResult(process(capturedRequest))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                deferredResult.setResult(
                    HostedCommandResult(CommandResult.exception(correlationId, exception), exception)
                )
            }
        }
        if (job == null) {
            response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
            writeResult(
                response,
                overloaded(correlationId),
                statusOverride = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            )
            return
        }
        deferredResult.onTimeout { job.cancel("Arc command request timed out.") }
        deferredResult.onError { exception ->
            job.cancel("Arc command servlet request failed.", exception)
            deferredResult.setResult(
                HostedCommandResult(CommandResult.exception(correlationId, exception), exception)
            )
        }
        deferredResult.onCompletion { job.cancel("Arc command servlet request completed.") }

        try {
            asyncManager.startDeferredResultProcessing(deferredResult, correlationId)
            job.start()
        } catch (exception: Throwable) {
            job.cancel("Spring MVC could not start Arc command request processing.", exception)
            writeResult(response, CommandResult.exception(correlationId, exception), exception)
        }
    }

    private fun captureRequest(request: HttpServletRequest, correlationId: UUID): CapturedCommandRequest {
        val requiredRoles = handler.metadata.authorization.roles
            .flatMap { declaration -> declaration.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val principal = principalFactory.create(request, requiredRoles)
        val tenantId = try {
            tenantResolution.resolve(request, principal).value
        } catch (_: TenantResolutionRequiredException) {
            return CapturedCommandRequest.Completed(correlationId, tenantRequired(correlationId))
        } catch (_: TenantAccessDeniedException) {
            return CapturedCommandRequest.Completed(correlationId, CommandResult.unauthorized(correlationId))
        }
        val allowedSeverityHeader = request.getHeader(ALLOWED_SEVERITY_HEADER)
        val parsedAllowedSeverity = parseAllowedSeverity(allowedSeverityHeader)
        if (allowedSeverityHeader != null && parsedAllowedSeverity == null) {
            return CapturedCommandRequest.Completed(correlationId, CommandResult.malformed(correlationId))
        }
        val allowedSeverity = parsedAllowedSeverity ?: if (handler.metadata.treatWarningsAsErrors) {
            ValidationResultSeverity.Information
        } else {
            null
        }
        val command = try {
            objectMapper.readValue(
                boundedRequestBody(request, properties.maximumRequestBodyBytes),
                handler.commandType
            ) ?: return CapturedCommandRequest.Completed(correlationId, CommandResult.malformed(correlationId))
        } catch (exception: Exception) {
            val status = if (exception.isArcRequestBodyTooLarge()) {
                HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            } else {
                null
            }
            return CapturedCommandRequest.Completed(
                correlationId,
                CommandResult.malformed(correlationId),
                statusOverride = status
            )
        }
        return CapturedCommandRequest.Ready(correlationId, principal, tenantId, allowedSeverity, command)
    }

    private suspend fun process(request: CapturedCommandRequest): HostedCommandResult = when (request) {
        is CapturedCommandRequest.Completed -> HostedCommandResult(
            request.result,
            request.hostException,
            request.statusOverride
        )
        is CapturedCommandRequest.Ready -> {
            val options = CommandExecutionOptions(
                request.correlationId,
                request.principal,
                serviceResolver,
                request.tenantId,
                request.tenantId,
                request.allowedSeverity,
                exposeExceptionDetails
            )
            val result = if (validationOnly) {
                pipeline.validate(request.command, options)
            } else {
                pipeline.execute(request.command, options)
            }
            HostedCommandResult(result)
        }
    }

    private fun writeConcurrentResult(
        asyncManager: WebAsyncManager,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val concurrentResult = asyncManager.concurrentResult
        val correlationId = (concurrentResult as? HostedCommandResult)?.result?.correlationId
            ?: asyncManager.concurrentResultContext?.firstOrNull() as? UUID
            ?: parseCorrelationId(request.getHeader(properties.correlationHeader))
        asyncManager.clearConcurrentResult()
        val hostedResult = when (concurrentResult) {
            is HostedCommandResult -> concurrentResult
            is Throwable -> HostedCommandResult(CommandResult.exception(correlationId, concurrentResult), concurrentResult)
            else -> {
                val exception = IllegalStateException("Spring MVC returned an unexpected Arc command result.")
                HostedCommandResult(CommandResult.exception(correlationId, exception), exception)
            }
        }
        prepareResponse(response, hostedResult.result.correlationId)
        writeResult(
            response,
            hostedResult.result,
            hostedResult.hostException,
            hostedResult.statusOverride
        )
    }

    private fun prepareResponse(response: HttpServletResponse, correlationId: UUID) {
        response.setHeader(properties.correlationHeader, correlationId.toString())
        response.contentType = MediaType.APPLICATION_JSON_VALUE
    }

    private fun writeResult(
        response: HttpServletResponse,
        result: CommandResult<*>,
        hostException: Throwable? = null,
        statusOverride: Int? = null
    ) {
        if (result.hasExceptions) {
            if (hostException != null) {
                logger.error("Arc command request failed. correlationId={}", result.correlationId, hostException)
            } else {
                logger.error(
                    "Arc command request failed. correlationId={} exceptionMessages={} exceptionStackTrace={}",
                    result.correlationId,
                    result.exceptionMessages,
                    result.exceptionStackTrace
                )
            }
        }
        val wireResult = ExceptionDetailRedactor.redact(result, exposeExceptionDetails)
        response.status = statusOverride ?: ArcHttpStatusMapper.map(wireResult).code
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, wireResult)
    }

    private sealed interface CapturedCommandRequest {
        val correlationId: UUID

        data class Ready(
            override val correlationId: UUID,
            val principal: ArcPrincipal,
            val tenantId: String?,
            val allowedSeverity: ValidationResultSeverity?,
            val command: Any
        ) : CapturedCommandRequest

        data class Completed(
            override val correlationId: UUID,
            val result: CommandResult<*>,
            val hostException: Throwable? = null,
            val statusOverride: Int? = null
        ) : CapturedCommandRequest
    }

    private data class HostedCommandResult(
        val result: CommandResult<*>,
        val hostException: Throwable? = null,
        val statusOverride: Int? = null
    )

    private companion object {
        const val ALLOWED_SEVERITY_HEADER = "X-Allowed-Severity"
        val logger = LoggerFactory.getLogger(ArcCommandHttpRequestHandler::class.java)

        fun parseCorrelationId(value: String?): UUID = value
            ?.trim()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID()

        fun overloaded(correlationId: UUID): CommandResult<*> = CommandResult.invalid(
            correlationId,
            listOf(
                io.cratis.arc.results.ValidationResult(
                    ValidationResultSeverity.Error,
                    "The server is temporarily unable to accept more Arc requests."
                )
            )
        )

        fun tenantRequired(correlationId: UUID): CommandResult<*> = CommandResult.invalid(
            correlationId,
            listOf(
                io.cratis.arc.results.ValidationResult(
                    ValidationResultSeverity.Error,
                    "A tenant is required.",
                    reason = io.cratis.arc.results.ValidationResultReasons.MALFORMED_REQUEST
                )
            )
        )

        fun parseAllowedSeverity(value: String?): ValidationResultSeverity? {
            if (value == null) return null
            val normalized = value.trim()
            normalized.toIntOrNull()?.let { wireValue ->
                return ValidationResultSeverity.entries.firstOrNull { severity -> severity.value() == wireValue }
            }
            return ValidationResultSeverity.entries.firstOrNull { severity ->
                severity.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

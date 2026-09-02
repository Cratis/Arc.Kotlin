// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.authentication.Authentication
import io.cratis.arc.authentication.AuthenticationRequestContext
import io.cratis.arc.authentication.AuthenticationResult
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.identity.IdentityConstants
import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.tenancy.TenantId
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.security.Principal
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Stable servlet request attributes populated by Arc authentication. */
public object ArcAuthenticationAttributes {
    /** [AuthenticationResult] captured before an Arc endpoint is dispatched. */
    @JvmField
    public val RESULT: String = "${ArcAuthenticationAttributes::class.java.name}.result"

    /** [ArcPrincipal] supplied by the successful authentication handler. */
    @JvmField
    public val PRINCIPAL: String = "${ArcAuthenticationAttributes::class.java.name}.principal"
}

internal class ArcAuthenticationFilter(
    private val authentication: Authentication,
    private val principalFactory: ArcPrincipalFactory,
    private val commandHandlers: CommandHandlerRegistry,
    private val queryPerformers: QueryPerformerRegistry,
    private val coroutineScope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties
) : Filter {
    private val endpointMatcher = ArcAuthenticationEndpointMatcher(commandHandlers, queryPerformers, properties)

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as? HttpServletRequest ?: return chain.doFilter(request, response)
        val httpResponse = response as? HttpServletResponse ?: return chain.doFilter(request, response)
        val endpoint = endpointMatcher.endpoint(httpRequest) ?: return chain.doFilter(request, response)
        val correlationId = httpRequest.getHeader(properties.correlationHeader)
            ?.trim()
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: UUID.randomUUID()
        httpResponse.setHeader(properties.correlationHeader, correlationId.toString())
        if (!authentication.hasHandlers || endpoint.literalAnonymous) {
            chain.doFilter(request, response)
            return
        }
        if (httpRequest.getAttribute(AUTHENTICATION_DISPATCHED_ATTRIBUTE) == true) {
            chain.doFilter(authenticatedRequest(httpRequest), response)
            return
        }
        if (!httpRequest.isAsyncSupported) {
            writeUnauthorized(httpResponse)
            return
        }

        val context = captureContext(httpRequest)
        val asyncContext = httpRequest.startAsync()
        asyncContext.timeout = properties.requestTimeout.toMillis()
        val job = coroutineScope.tryLaunch(start = CoroutineStart.LAZY) {
            try {
                val result = authentication.handleAuthentication(context)
                httpRequest.setAttribute(ArcAuthenticationAttributes.RESULT, result)
                result.principal?.let { httpRequest.setAttribute(ArcAuthenticationAttributes.PRINCIPAL, it) }
                if (result.isAuthenticated || endpoint.allowAnonymous) {
                    httpRequest.setAttribute(AUTHENTICATION_DISPATCHED_ATTRIBUTE, true)
                    asyncContext.dispatch()
                } else {
                    writeUnauthorized(httpResponse)
                    asyncContext.complete()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                writeUnauthorized(httpResponse)
                asyncContext.complete()
            }
        }
        if (job == null) {
            writeServiceUnavailable(httpResponse)
            asyncContext.complete()
            return
        }
        asyncContext.addListener(AuthenticationAsyncListener(job))
        job.start()
    }

    private fun captureContext(request: HttpServletRequest): AuthenticationRequestContext {
        val headers = request.headerNames.toList().associateWith { name -> request.getHeaders(name).toList() }
        val cookies = request.cookies.orEmpty()
            .asSequence()
            .filterNot { cookie -> cookie.name == IdentityConstants.IDENTITY_COOKIE_NAME }
            .associate { cookie -> cookie.name to cookie.value }
        val principal = principalFactory.create(request, emptyList())
        val configuredHeader = properties.tenancy.headerName?.takeIf(String::isNotBlank) ?: properties.tenantHeader
        val tenant = headers.entries.firstOrNull { (name, _) -> name.equals(configuredHeader, ignoreCase = true) }
            ?.value?.firstOrNull()?.takeIf(String::isNotBlank)?.let(TenantId::of)
        return AuthenticationRequestContext(headers, cookies, principal, tenant)
    }

    private fun authenticatedRequest(request: HttpServletRequest): HttpServletRequest {
        val principal = request.getAttribute(ArcAuthenticationAttributes.PRINCIPAL) as? ArcPrincipal ?: return request
        return object : jakarta.servlet.http.HttpServletRequestWrapper(request) {
            override fun getUserPrincipal(): Principal = Principal { principal.name ?: principal.id }
            override fun getRemoteUser(): String = principal.name ?: principal.id
            override fun isUserInRole(role: String): Boolean = principal.isInRole(role)
        }
    }

    private fun writeServiceUnavailable(response: HttpServletResponse) {
        if (response.isCommitted) return
        response.reset()
        response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
        response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
        response.contentType = "text/plain"
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(SERVICE_UNAVAILABLE_RESPONSE)
        response.writer.flush()
    }

    private fun writeUnauthorized(response: HttpServletResponse) {
        if (response.isCommitted) return
        response.reset()
        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "text/plain"
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(UNAUTHORIZED_RESPONSE)
        response.writer.flush()
    }

    private class AuthenticationAsyncListener(private val job: Job) : AsyncListener {
        override fun onComplete(event: AsyncEvent) = Unit
        override fun onStartAsync(event: AsyncEvent) = Unit
        override fun onTimeout(event: AsyncEvent) {
            job.cancel(CancellationException("Arc authentication timed out."))
        }

        override fun onError(event: AsyncEvent) {
            job.cancel(CancellationException("Arc authentication servlet request failed."))
        }
    }
}

private class ArcAuthenticationEndpointMatcher(
    private val commandHandlers: CommandHandlerRegistry,
    private val queryPerformers: QueryPerformerRegistry,
    private val properties: ArcProperties
) {
    @Volatile
    private var cache: RouteCache? = null

    fun endpoint(request: HttpServletRequest): AuthenticationEndpoint? {
        val contextPath = request.contextPath.orEmpty()
        val path = request.requestURI.removePrefix(contextPath)
        return routes()[RouteKey(request.method.uppercase(Locale.ROOT), path)]
    }

    private fun routes(): Map<RouteKey, AuthenticationEndpoint> {
        val commandVersion = commandHandlers.version
        val queryVersion = queryPerformers.version
        cache?.takeIf { it.commandVersion == commandVersion && it.queryVersion == queryVersion }?.let { return it.routes }
        return synchronized(this) {
            cache?.takeIf { it.commandVersion == commandVersion && it.queryVersion == queryVersion }?.routes
                ?: build(commandVersion, queryVersion).also { cache = it }.routes
        }
    }

    private fun build(commandVersion: Long, queryVersion: Long): RouteCache {
        val options = properties.endpoints.toOptions()
        val literalAnonymous = AuthenticationEndpoint(true, true)
        val connectionAnonymous = AuthenticationEndpoint(true, false)
        val routes = linkedMapOf(
            RouteKey("GET", COMMANDS_INTROSPECTION_ROUTE) to literalAnonymous,
            RouteKey("GET", QUERIES_INTROSPECTION_ROUTE) to literalAnonymous,
            RouteKey("GET", USERS_ROUTE) to literalAnonymous,
            RouteKey("GET", TENANTS_ROUTE) to literalAnonymous,
            RouteKey("GET", IDENTITY_SCHEMA_ROUTE_VALUE) to literalAnonymous,
            RouteKey("GET", IDENTITY_ROUTE_VALUE) to AuthenticationEndpoint(false, false),
            RouteKey("GET", OBSERVABLE_QUERY_WS_ROUTE) to connectionAnonymous,
            RouteKey("GET", OBSERVABLE_QUERY_SSE_ROUTE) to connectionAnonymous,
            RouteKey("POST", OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE) to connectionAnonymous,
            RouteKey("POST", OBSERVABLE_QUERY_SSE_UNSUBSCRIBE_ROUTE) to connectionAnonymous
        )
        val handlers = commandHandlers.snapshot()
        val commandCounts = handlers.groupingBy { handler ->
            handler.metadata.location.drop(options.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        handlers.forEach { handler ->
            val namespace = handler.metadata.location.drop(options.segmentsToSkipForRoute).joinToString(".")
            val endpoint = AuthenticationEndpoint(handler.allowsAnonymous, false)
            val route = EndpointRouteHelper.commandRoute(handler.metadata, options, (commandCounts[namespace] ?: 0) > 1)
            routes[RouteKey("POST", route)] = endpoint
            routes[RouteKey("POST", if (route == "/") "/validate" else "$route/validate")] = endpoint
        }
        val performers = queryPerformers.snapshot()
        val queryCounts = performers.groupingBy { performer ->
            performer.descriptor.location.drop(options.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        performers.forEach { performer ->
            val namespace = performer.descriptor.location.drop(options.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.queryRoute(performer.descriptor, options, (queryCounts[namespace] ?: 0) > 1)
            val endpoint = AuthenticationEndpoint(performer.allowsAnonymous, false)
            routes[RouteKey("GET", route)] = endpoint
            if (properties.endpoints.isEnableQueryHttpMethod) {
                routes[RouteKey("QUERY", route)] = endpoint
            }
        }
        return RouteCache(commandVersion, queryVersion, java.util.Collections.unmodifiableMap(routes))
    }

    private data class RouteCache(
        val commandVersion: Long,
        val queryVersion: Long,
        val routes: Map<RouteKey, AuthenticationEndpoint>
    )

    private data class RouteKey(val method: String, val path: String)
}

private data class AuthenticationEndpoint(val allowAnonymous: Boolean, val literalAnonymous: Boolean)

private const val AUTHENTICATION_DISPATCHED_ATTRIBUTE = "io.cratis.arc.springboot.authentication.dispatched"
private const val COMMANDS_INTROSPECTION_ROUTE = "/.cratis/commands"
private const val QUERIES_INTROSPECTION_ROUTE = "/.cratis/queries"
private const val IDENTITY_ROUTE_VALUE = "/.cratis/me"
private const val IDENTITY_SCHEMA_ROUTE_VALUE = "/.cratis/identity-details/schema"
private const val UNAUTHORIZED_RESPONSE = "Unauthorized"
private const val SERVICE_UNAVAILABLE_RESPONSE = "Service Unavailable"

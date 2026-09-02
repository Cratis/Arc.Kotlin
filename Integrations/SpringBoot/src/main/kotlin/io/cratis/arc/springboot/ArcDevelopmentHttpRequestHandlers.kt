// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.identity.AsyncUsersProvider
import io.cratis.arc.identity.AsyncUsersProviderAdapter
import io.cratis.arc.identity.UsersProvider
import io.cratis.arc.identity.UsersProviderAggregator
import io.cratis.arc.tenancy.AsyncTenantsProvider
import io.cratis.arc.tenancy.AsyncTenantsProviderAdapter
import io.cratis.arc.tenancy.TenantsProvider
import io.cratis.arc.tenancy.TenantsProviderAggregator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.MediaType
import org.springframework.web.HttpRequestHandler
import org.springframework.web.context.request.async.DeferredResult
import org.springframework.web.context.request.async.StandardServletAsyncWebRequest
import org.springframework.web.context.request.async.WebAsyncManager
import org.springframework.web.context.request.async.WebAsyncUtils

internal fun usersHttpRequestHandler(
    coroutineProviders: ObjectProvider<UsersProvider>,
    asyncProviders: ObjectProvider<AsyncUsersProvider>,
    objectMapper: ObjectMapper,
    scope: ArcApplicationCoroutineScope,
    properties: ArcProperties
): HttpRequestHandler {
    val providers = adaptedProviders(coroutineProviders, asyncProviders, ::AsyncUsersProviderAdapter)
    val aggregator = UsersProviderAggregator(providers)
    return ArcDevelopmentHttpRequestHandler(objectMapper, scope, properties, USERS_ARTIFACT) { aggregator.provide() }
}

internal fun tenantsHttpRequestHandler(
    coroutineProviders: ObjectProvider<TenantsProvider>,
    asyncProviders: ObjectProvider<AsyncTenantsProvider>,
    objectMapper: ObjectMapper,
    scope: ArcApplicationCoroutineScope,
    properties: ArcProperties
): HttpRequestHandler {
    val providers = adaptedProviders(coroutineProviders, asyncProviders, ::AsyncTenantsProviderAdapter)
    val aggregator = TenantsProviderAggregator(providers)
    return ArcDevelopmentHttpRequestHandler(objectMapper, scope, properties, TENANTS_ARTIFACT) { aggregator.provide() }
}

private fun <T : Any, A : Any> adaptedProviders(
    coroutineProviders: ObjectProvider<T>,
    asyncProviders: ObjectProvider<A>,
    adapter: (A) -> T
): List<T> {
    val providers = mutableListOf<T>()
    val identities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    coroutineProviders.orderedStream().forEach { provider ->
        if (identities.add(provider)) providers.add(provider)
    }
    asyncProviders.orderedStream().forEach { provider ->
        if (identities.add(provider)) providers.add(adapter(provider))
    }
    return providers
}

private class ArcDevelopmentHttpRequestHandler<T>(
    private val objectMapper: ObjectMapper,
    private val scope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties,
    private val artifact: String,
    private val provide: suspend () -> List<T>
) : HttpRequestHandler {
    override fun handleRequest(request: HttpServletRequest, response: HttpServletResponse) {
        val asyncManager = WebAsyncUtils.getAsyncManager(request)
        if (asyncManager.hasConcurrentResult()) {
            writeConcurrentResult(asyncManager, response)
            return
        }
        prepare(response)
        if (!request.isAsyncSupported) {
            write(response, EndpointResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorBody()))
            return
        }
        if (asyncManager.asyncWebRequest == null) {
            asyncManager.setAsyncWebRequest(StandardServletAsyncWebRequest(request, response))
        }
        val timeout = EndpointResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorBody())
        val deferred = DeferredResult<EndpointResult>(properties.requestTimeout.toMillis(), timeout)
        val job = scope.tryLaunch(start = CoroutineStart.LAZY) {
            try {
                deferred.setResult(EndpointResult(HttpServletResponse.SC_OK, objectMapper.writeValueAsBytes(provide())))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Throwable) {
                logger.error("{} endpoint failed.", artifact, exception)
                deferred.setResult(EndpointResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorBody()))
            }
        }
        if (job == null) {
            response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
            write(
                response,
                EndpointResult(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    objectMapper.writeValueAsBytes(
                        mapOf("error" to "The server is temporarily unable to accept more Arc requests.")
                    )
                )
            )
            return
        }
        deferred.onTimeout { job.cancel("$artifact request timed out.") }
        deferred.onError { exception ->
            job.cancel("$artifact servlet request failed.", exception)
            deferred.setResult(EndpointResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorBody()))
        }
        deferred.onCompletion { job.cancel("$artifact servlet request completed.") }
        try {
            asyncManager.startDeferredResultProcessing(deferred)
            job.start()
        } catch (exception: Throwable) {
            job.cancel("Spring MVC could not start $artifact processing.", exception)
            logger.error("Spring MVC could not start {} processing.", artifact, exception)
            write(response, EndpointResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorBody()))
        }
    }

    private fun writeConcurrentResult(asyncManager: WebAsyncManager, response: HttpServletResponse) {
        val result = asyncManager.concurrentResult as? EndpointResult
            ?: EndpointResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorBody())
        asyncManager.clearConcurrentResult()
        write(response, result)
    }

    private fun write(response: HttpServletResponse, result: EndpointResult) {
        prepare(response)
        response.status = result.status
        response.outputStream.write(result.body)
    }

    private fun prepare(response: HttpServletResponse) {
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
    }

    private fun errorBody(): ByteArray = objectMapper.writeValueAsBytes(
        mapOf("error" to ExceptionDetailRedactor.REDACTED_MESSAGE)
    )

    private data class EndpointResult(val status: Int, val body: ByteArray)

    private companion object {
        val logger = LoggerFactory.getLogger(ArcDevelopmentHttpRequestHandler::class.java)
    }
}

internal const val USERS_ROUTE = "/.cratis/users"
internal const val TENANTS_ROUTE = "/.cratis/tenants"
private const val USERS_ARTIFACT = "Arc development users"
private const val TENANTS_ARTIFACT = "Arc development tenants"

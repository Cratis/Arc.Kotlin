// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.http.ArcHttpStatusMapper
import io.cratis.arc.queries.FullyQualifiedQueryName
import io.cratis.arc.queries.ObservableQueryHubMessage
import io.cratis.arc.queries.ObservableQueryHubMessageType
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryPipeline
import io.cratis.arc.queries.ObservableQuerySSESubscribeRequest
import io.cratis.arc.queries.ObservableQuerySSEUnsubscribeRequest
import io.cratis.arc.queries.ObservableQuerySubscriptionIdentity
import io.cratis.arc.queries.ObservableQuerySubscriptionOperation
import io.cratis.arc.queries.ObservableQuerySubscriptionRequest
import io.cratis.arc.queries.ObservableQuerySubscriptionRevision
import io.cratis.arc.queries.ObservableQuerySubscriptionStates
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryExecutionOptions
import io.cratis.arc.queries.QueryHealthTracker
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryRequest
import io.cratis.arc.queries.QuerySubscriptionClientInfo
import io.cratis.arc.queries.QuerySubscriptionMetadata
import io.cratis.arc.results.QueryResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import jakarta.servlet.AsyncEvent
import jakarta.servlet.AsyncListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.HttpRequestHandler

internal const val OBSERVABLE_QUERY_WS_ROUTE = "/.cratis/queries/ws"
internal const val OBSERVABLE_QUERY_SSE_ROUTE = "/.cratis/queries/sse"
internal const val OBSERVABLE_QUERY_SSE_SUBSCRIBE_ROUTE = "/.cratis/queries/sse/subscribe"
internal const val OBSERVABLE_QUERY_SSE_UNSUBSCRIBE_ROUTE = "/.cratis/queries/sse/unsubscribe"

/** Servlet transport runtime shared by direct observable-query endpoints and multiplexed hubs. */
public class ArcObservableQueryTransport internal constructor(
    private val performers: QueryPerformerRegistry,
    private val pipeline: ObservableQueryPipeline,
    private val serviceResolver: ServiceResolver,
    private val requestBinder: ArcQueryRequestBinder,
    private val objectMapper: ObjectMapper,
    private val applicationScope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties,
    private val exposeExceptionDetails: Boolean,
    private val principalFactory: ArcPrincipalFactory,
    private val tenantResolution: ArcTenantResolutionService,
    private val healthTracker: QueryHealthTracker
) : AutoCloseable {
    private val settings get() = properties.observableQueries
    private val subscriptionMapper = objectMapper.copy().setDefaultPropertyInclusion(
        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)
    )
    private val connections = AtomicInteger()
    private val sseConnections = ConcurrentHashMap<String, HubSseConnection>()

    internal val activeConnectionCount: Int get() = connections.get()

    internal fun directHttpHandler(performer: QueryPerformer): HttpRequestHandler = HttpRequestHandler { request, response ->
        when {
            request.method.equals("GET", ignoreCase = true) && acceptsSse(request) ->
                openDirectSse(request, response, performer)
            request.method.equals("GET", ignoreCase = true) || request.method.equals("QUERY", ignoreCase = true) ->
                openSnapshot(request, response, performer)
            else -> {
                response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
                response.setHeader("Allow", "GET, QUERY")
            }
        }
    }

    internal fun sseConnectHandler(): HttpRequestHandler = HttpRequestHandler(::openHubSse)
    internal fun sseSubscribeHandler(): HttpRequestHandler = HttpRequestHandler(::subscribeHubSse)
    internal fun sseUnsubscribeHandler(): HttpRequestHandler = HttpRequestHandler(::unsubscribeHubSse)

    internal fun tryReserveConnection(): ConnectionLease? {
        while (true) {
            val current = connections.get()
            if (current >= settings.maximumConnections) return null
            if (connections.compareAndSet(current, current + 1)) return ConnectionLease(connections)
        }
    }

    internal fun captureHandshake(
        request: HttpServletRequest,
        correlationId: UUID,
        performer: QueryPerformer? = null
    ): ArcObservableHandshake {
        val principal = principalFactory.create(request, requiredRoles(performer))
        val tenantId = tenantResolution.resolve(request, principal).value
        return ArcObservableHandshake(
            principal,
            tenantId,
            correlationId,
            request.parameterMap.mapValues { (_, value) -> value.toList() },
            allowedSeverity(request, performer),
            request.remoteAddr,
            request.getHeader("User-Agent")
        )
    }

    internal fun createDirectRequest(handshake: ArcObservableHandshake, performer: QueryPerformer): CapturedObservableQuery =
        captured(
            handshake.principal,
            handshake.tenantId,
            requestBinder.fromParameters(handshake.parameters, performer),
            correlationId = handshake.correlationId,
            allowedValidationSeverity = handshake.allowedValidationSeverity
        )

    internal suspend fun open(captured: CapturedObservableQuery, transferMode: ObservableQueryTransferMode): ObservableQueryOpenResult =
        pipeline.open(captured.request, captured.options, transferMode)

    internal fun wire(result: QueryResult<*>): QueryResult<*> = ExceptionDetailRedactor.redact(result, exposeExceptionDetails)

    internal fun json(value: Any): String = objectMapper.writeValueAsString(value)

    internal fun parseHubMessage(value: String): ObservableQueryHubMessage =
        objectMapper.readValue(value, ObservableQueryHubMessage::class.java)

    internal fun parseSubscription(payload: Any?): ObservableQuerySubscriptionRequest? = runCatching {
        subscriptionMapper.convertValue(payload, ObservableQuerySubscriptionRequest::class.java)
    }.getOrNull()

    internal fun createHubConnection(
        id: String,
        handshake: ArcObservableHandshake,
        send: (ObservableQueryHubMessage) -> Boolean,
        closeTransport: () -> Unit
    ): ArcHubConnection = ArcHubConnection(id, handshake, send) {
        healthTracker.removeConnection(id)
        closeTransport()
    }

    internal fun registerDirectSubscription(
        connectionId: String,
        subscriptionId: String,
        protocol: String,
        performer: QueryPerformer,
        handshake: ArcObservableHandshake
    ) {
        registerSubscription(connectionId, subscriptionId, protocol, performer, handshake)
    }

    internal fun recordDataServed(connectionId: String, subscriptionId: String) {
        healthTracker.recordDataServed(connectionId, subscriptionId)
    }

    internal fun recordPingSent(connectionId: String, subscriptionId: String? = null) {
        healthTracker.recordPingSent(connectionId, subscriptionId)
    }

    internal fun recordPongReceived(connectionId: String, subscriptionId: String? = null) {
        healthTracker.recordPongReceived(connectionId, subscriptionId)
    }

    internal fun removeHealthConnection(connectionId: String) {
        healthTracker.removeConnection(connectionId)
    }

    internal fun subscribe(
        connection: ArcHubConnection,
        queryId: String,
        revision: Long?,
        request: ObservableQuerySubscriptionRequest,
        subscriptionHandshake: ArcObservableHandshake = connection.handshake
    ): HubSubscribeResult {
        if (!ObservableQuerySubscriptionRevision.isValid(revision)) return HubSubscribeResult.MALFORMED
        if (queryId.isBlank() || request.queryName.isBlank()) return HubSubscribeResult.MALFORMED
        val performer = performers.find(FullyQualifiedQueryName(request.queryName)) ?: run {
            connection.send(error(queryId, revision, "No performer found for query ${request.queryName}"))
            return HubSubscribeResult.ACCEPTED
        }
        val queryRequest = try {
            requestBinder.fromSubscription(request, performer)
        } catch (_: MalformedQueryRequestException) {
            return HubSubscribeResult.MALFORMED
        }
        val principal = subscriptionHandshake.principal
        val identity = ObservableQuerySubscriptionIdentity(
            performer.fullyQualifiedName,
            queryRequest.arguments,
            principal,
            subscriptionHandshake.tenantId,
            subscriptionHandshake.tenantId,
            subscriptionHandshake.correlationId,
            objectMapper
        )
        val operation = connection.subscriptions.trySubscribe(queryId, revision, identity)
            ?: return HubSubscribeResult.ACCEPTED
        if (connection.subscriptions.activeCount > settings.maximumSubscriptionsPerConnection) {
            connection.subscriptions.terminate(queryId, operation)
            return HubSubscribeResult.OVERLOADED
        }
        registerSubscription(connection.id, queryId, protocol(connection.id), performer, subscriptionHandshake)

        val job = applicationScope.tryLaunch {
            runSubscription(connection, queryId, operation, request, queryRequest, identity)
        } ?: run {
            connection.subscriptions.terminate(queryId, operation)
            healthTracker.unregisterSubscription(connection.id, queryId)
            return HubSubscribeResult.UNAVAILABLE
        }
        operation.attach(job)
        return HubSubscribeResult.ACCEPTED
    }

    internal fun unsubscribe(connection: ArcHubConnection, queryId: String, revision: Long?): Boolean {
        val removed = ObservableQuerySubscriptionRevision.isValid(revision) &&
            connection.subscriptions.tryUnsubscribe(queryId, revision)
        if (removed) healthTracker.unregisterSubscription(connection.id, queryId)
        return removed
    }

    internal fun connected(connectionId: String): ObservableQueryHubMessage = ObservableQueryHubMessage(
        ObservableQueryHubMessageType.Connected,
        payload = connectionId,
        keepAliveIntervalMs = settings.keepAliveInterval.toMillis(),
        supportsSubscriptionRevisions = true
    )

    internal fun pong(timestamp: Long?): ObservableQueryHubMessage = ObservableQueryHubMessage(
        ObservableQueryHubMessageType.Pong,
        timestamp = timestamp ?: Instant.now().toEpochMilli()
    )

    internal fun ping(): ObservableQueryHubMessage = ObservableQueryHubMessage(
        ObservableQueryHubMessageType.Ping,
        timestamp = Instant.now().toEpochMilli()
    )

    override fun close() {
        sseConnections.values.toList().forEach(HubSseConnection::close)
        sseConnections.clear()
    }

    private fun openSnapshot(request: HttpServletRequest, response: HttpServletResponse, performer: QueryPerformer) {
        val correlationId = prepareCorrelation(request, response)
        if (request.method.equals("QUERY", ignoreCase = true)) response.setHeader("Cache-Control", "no-store")
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        if (!request.isAsyncSupported) {
            writeResult(response, QueryResult.error<Any?>(correlationId, "Servlet asynchronous processing is unavailable."))
            return
        }
        val captured = try {
            capture(request, performer, correlationId)
        } catch (_: TenantResolutionRequiredException) {
            writeResult(response, tenantRequired(correlationId))
            return
        } catch (_: TenantAccessDeniedException) {
            writeResult(response, QueryResult.unauthorized<Any?>(correlationId))
            return
        } catch (exception: ArcRequestBodyTooLargeException) {
            writeResult(response, malformed(correlationId), HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE)
            return
        } catch (_: Exception) {
            writeResult(response, malformed(correlationId))
            return
        }
        val async = request.startAsync(request, response)
        async.timeout = properties.requestTimeout.toMillis()
        val job = applicationScope.tryLaunch {
            try {
                when (val opened = pipeline.open(captured.request, captured.options)) {
                    is ObservableQueryOpenResult.Failure -> writeResult(response, opened.result)
                    is ObservableQueryOpenResult.Stream -> {
                        val wait = request.getParameter(WAIT_FOR_FIRST)?.toBooleanStrictOrNull() == true
                        if (!wait) {
                            writeResult(response, QueryResult.notReady<Any?>(correlationId), HttpServletResponse.SC_ACCEPTED)
                        } else {
                            val timeout = requestedSnapshotTimeout(request)
                            try {
                                writeResult(response, withTimeout(timeout.toMillis()) { opened.results.first() })
                            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                                writeResult(
                                    response,
                                    QueryResult.error<Any?>(correlationId, "Timed out waiting for the first observable query result."),
                                    HttpServletResponse.SC_REQUEST_TIMEOUT
                                )
                            } catch (_: NoSuchElementException) {
                                writeResult(
                                    response,
                                    QueryResult.error<Any?>(correlationId, "Observable query completed before producing its first result."),
                                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                                )
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                writeResult(response, QueryResult.exception<Any?>(correlationId, exception))
            } finally {
                runCatching { async.complete() }
            }
        }
        if (job == null) {
            overloaded(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            runCatching { async.complete() }
            return
        }
        async.addListener(cancelOnAsyncEnd(job))
    }

    private fun openDirectSse(request: HttpServletRequest, response: HttpServletResponse, performer: QueryPerformer) {
        val lease = tryReserveConnection() ?: run {
            overloaded(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            return
        }
        if (!request.isAsyncSupported) {
            lease.close()
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return
        }
        val correlationId = prepareCorrelation(request, response)
        val captured = try {
            capture(request, performer, correlationId)
        } catch (_: TenantAccessDeniedException) {
            lease.close()
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        } catch (_: Exception) {
            lease.close()
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        }
        prepareSse(response)
        val async = request.startAsync(request, response)
        async.timeout = settings.connectionTimeout.toMillis()
        val connectionId = "sse-direct-${UUID.randomUUID()}"
        val subscriptionId = correlationId.toString()
        val stream = ServletSseStream(async, response, lease, settings.outboundBufferCapacity) {
            healthTracker.removeConnection(connectionId)
        }
        registerSubscription(
            connectionId,
            subscriptionId,
            "sse",
            performer,
            ArcObservableHandshake(
                captured.options.principal,
                captured.options.tenantId,
                captured.options.correlationId,
                emptyMap(),
                captured.options.allowedValidationSeverity,
                request.remoteAddr,
                request.getHeader("User-Agent")
            )
        )
        async.addListener(stream)
        val job = applicationScope.tryLaunch {
            try {
                when (val opened = pipeline.open(captured.request, captured.options)) {
                    is ObservableQueryOpenResult.Failure -> stream.send(json(wire(opened.result)))
                    is ObservableQueryOpenResult.Stream -> opened.results.collect { result ->
                        if (!stream.send(json(wire(result)))) {
                            cancel("Observable SSE outbound buffer is full.")
                        } else {
                            healthTracker.recordDataServed(connectionId, subscriptionId)
                        }
                        if (!result.isAuthorized) cancel("Observable query became unauthorized.")
                    }
                }
            } finally {
                stream.close()
            }
        }
        if (job == null) {
            overloaded(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            stream.close()
            return
        }
        stream.attach(job)
    }

    private fun openHubSse(request: HttpServletRequest, response: HttpServletResponse) {
        val correlationId = prepareCorrelation(request, response)
        if (!request.method.equals("GET", ignoreCase = true)) {
            response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
            response.setHeader("Allow", "GET")
            return
        }
        val lease = tryReserveConnection() ?: run {
            overloaded(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            return
        }
        if (!request.isAsyncSupported) {
            lease.close()
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            return
        }
        val handshake = try {
            captureHandshake(request, correlationId)
        } catch (_: TenantAccessDeniedException) {
            lease.close()
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        } catch (_: TenantResolutionRequiredException) {
            lease.close()
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        }
        prepareSse(response)
        val async = request.startAsync(request, response)
        async.timeout = settings.connectionTimeout.toMillis()
        val connectionId = UUID.randomUUID().toString()
        lateinit var state: HubSseConnection
        val stream = ServletSseStream(async, response, lease, settings.outboundBufferCapacity) {
            sseConnections.remove(connectionId, state)
        }
        state = HubSseConnection(
            createHubConnection(connectionId, handshake, { message -> stream.send(json(message)) }, stream::close),
            stream
        )
        sseConnections[connectionId] = state
        async.addListener(stream)
        stream.send(json(connected(connectionId)))
        state.startHeartbeat()
    }

    private fun subscribeHubSse(request: HttpServletRequest, response: HttpServletResponse) {
        val correlationId = prepareCorrelation(request, response)
        if (!request.method.equals("POST", ignoreCase = true)) {
            methodNotAllowed(response)
            return
        }
        val body = readBody(request, ObservableQuerySSESubscribeRequest::class.java) ?: run {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        }
        val state = sseConnections[body.connectionId] ?: run {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }
        val performer = performers.find(FullyQualifiedQueryName(body.request.queryName))
        val subscriptionHandshake = try {
            captureHandshake(request, correlationId, performer)
        } catch (_: TenantAccessDeniedException) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        } catch (_: TenantResolutionRequiredException) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        }
        if (!state.connection.handshake.sameCaller(subscriptionHandshake)) {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }
        response.status = when (subscribe(
            state.connection,
            body.queryId,
            body.revision,
            body.request,
            subscriptionHandshake
        )) {
            HubSubscribeResult.ACCEPTED -> HttpServletResponse.SC_OK
            HubSubscribeResult.MALFORMED -> HttpServletResponse.SC_BAD_REQUEST
            HubSubscribeResult.OVERLOADED -> 429
            HubSubscribeResult.UNAVAILABLE -> HttpServletResponse.SC_SERVICE_UNAVAILABLE
        }
        if (response.status == 429 || response.status == HttpServletResponse.SC_SERVICE_UNAVAILABLE) {
            response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
        }
    }

    private fun unsubscribeHubSse(request: HttpServletRequest, response: HttpServletResponse) {
        val correlationId = prepareCorrelation(request, response)
        if (!request.method.equals("POST", ignoreCase = true)) {
            methodNotAllowed(response)
            return
        }
        val body = readBody(request, ObservableQuerySSEUnsubscribeRequest::class.java) ?: run {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        }
        val state = authorizedSseConnection(request, body.connectionId, correlationId) ?: run {
            response.status = HttpServletResponse.SC_NOT_FOUND
            return
        }
        if (body.queryId.isBlank() || !ObservableQuerySubscriptionRevision.isValid(body.revision)) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            return
        }
        unsubscribe(state.connection, body.queryId, body.revision)
        response.status = HttpServletResponse.SC_OK
    }

    private suspend fun runSubscription(
        connection: ArcHubConnection,
        queryId: String,
        operation: ObservableQuerySubscriptionOperation,
        request: ObservableQuerySubscriptionRequest,
        queryRequest: QueryRequest,
        identity: ObservableQuerySubscriptionIdentity
    ) {
        try {
            val captured = captured(
                identity.principal,
                identity.tenantId,
                QueryRequest(
                    identity.queryName,
                    identity.createArguments(),
                    queryRequest.paging,
                    queryRequest.sorting
                ),
                identity.correlationId
            )
            when (val opened = pipeline.open(
                captured.request,
                captured.options,
                request.transferMode ?: ObservableQueryTransferMode.FULL
            )) {
                is ObservableQueryOpenResult.Failure -> {
                    if (!opened.result.isAuthorized) {
                        connection.send(unauthorized(queryId, operation.revision))
                    } else {
                        connection.send(error(queryId, operation.revision, failureMessage(opened.result)))
                    }
                }
                is ObservableQueryOpenResult.Stream -> opened.results.collect { result ->
                    if (!connection.subscriptions.isCurrent(queryId, operation)) return@collect
                    if (!result.isAuthorized) {
                        connection.send(unauthorized(queryId, operation.revision))
                        connection.subscriptions.terminate(queryId, operation)
                        return@collect
                    }
                    if (!connection.send(ObservableQueryHubMessage(
                            ObservableQueryHubMessageType.QueryResult,
                            queryId,
                            operation.revision,
                            wire(result)
                        ))) {
                        connection.close()
                    } else {
                        healthTracker.recordDataServed(connection.id, queryId)
                    }
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (exception: Exception) {
            if (connection.subscriptions.isCurrent(queryId, operation)) {
                connection.send(error(queryId, operation.revision, safeMessage(exception)))
            }
        } finally {
            val wasCurrent = connection.subscriptions.isCurrent(queryId, operation)
            connection.subscriptions.terminate(queryId, operation)
            if (wasCurrent) healthTracker.unregisterSubscription(connection.id, queryId)
        }
    }

    private fun capture(request: HttpServletRequest, performer: QueryPerformer, correlationId: UUID): CapturedObservableQuery {
        val principal = principalFactory.create(request, requiredRoles(performer))
        val queryRequest = if (request.method.equals("QUERY", ignoreCase = true)) {
            requestBinder.fromQuery(performer, boundedRequestBody(request, properties.maximumRequestBodyBytes))
        } else {
            requestBinder.fromGet(request, performer)
        }
        return captured(
            principal,
            tenantResolution.resolve(request, principal).value,
            queryRequest,
            correlationId,
            allowedSeverity(request, performer)
        )
    }

    private fun captured(
        principal: ArcPrincipal,
        tenantId: String?,
        request: QueryRequest,
        correlationId: UUID,
        allowedValidationSeverity: ValidationResultSeverity? = null
    ): CapturedObservableQuery = CapturedObservableQuery(
        request,
        QueryExecutionOptions(
            correlationId,
            principal,
            serviceResolver,
            tenantId,
            tenantId,
            allowedValidationSeverity,
            exposeExceptionDetails
        )
    )

    private fun authorizedSseConnection(
        request: HttpServletRequest,
        connectionId: String,
        correlationId: UUID
    ): HubSseConnection? {
        val state = sseConnections[connectionId] ?: return null
        val current = runCatching { captureHandshake(request, correlationId) }.getOrNull() ?: return null
        return state.takeIf { it.connection.handshake.sameCaller(current) }
    }

    private fun <T> readBody(request: HttpServletRequest, type: Class<T>): T? = try {
        objectMapper.readValue(request.inputStream, type)
    } catch (_: Exception) {
        null
    }

    private fun writeResult(response: HttpServletResponse, result: QueryResult<*>, status: Int? = null) {
        if (response.isCommitted) return
        val wireResult = wire(result)
        response.status = status ?: ArcHttpStatusMapper.map(wireResult).code
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        objectMapper.writeValue(response.outputStream, wireResult)
    }

    private fun requestedSnapshotTimeout(request: HttpServletRequest): java.time.Duration {
        val requested = request.getParameter(WAIT_FOR_FIRST_TIMEOUT)?.toDoubleOrNull()?.takeIf { it > 0 }
            ?.let { java.time.Duration.ofMillis((it * 1000).toLong()) }
        return listOfNotNull(requested, settings.waitForFirstResultTimeout, properties.requestTimeout).minOrNull()!!
    }

    private fun allowedSeverity(request: HttpServletRequest, performer: QueryPerformer?): ValidationResultSeverity? {
        val header = request.getHeader(ALLOWED_SEVERITY_HEADER)
        val parsed = header?.trim()?.let { value ->
            value.toIntOrNull()?.let { wire ->
                ValidationResultSeverity.entries.firstOrNull { it.value() == wire }
            } ?: ValidationResultSeverity.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        }
        if (header != null && parsed == null) throw MalformedQueryRequestException()
        return parsed ?: ValidationResultSeverity.Information.takeIf { performer?.descriptor?.treatWarningsAsErrors == true }
    }

    private fun requiredRoles(performer: QueryPerformer?): List<String> = performer?.descriptor?.authorization?.roles.orEmpty()
        .flatMap { declaration -> declaration.split(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    private fun tenantRequired(correlationId: UUID): QueryResult<Any?> = QueryResult.invalid(
        correlationId,
        listOf(
            ValidationResult(
                ValidationResultSeverity.Error,
                "A tenant is required.",
                reason = ValidationResultReasons.MALFORMED_REQUEST
            )
        )
    )

    private fun malformed(correlationId: UUID): QueryResult<Any?> = QueryResult.invalid(
        correlationId,
        listOf(
            ValidationResult(
                ValidationResultSeverity.Error,
                "The request is malformed.",
                reason = ValidationResultReasons.MALFORMED_REQUEST
            )
        )
    )

    private fun registerSubscription(
        connectionId: String,
        subscriptionId: String,
        protocol: String,
        performer: QueryPerformer,
        handshake: ArcObservableHandshake
    ) {
        healthTracker.registerSubscription(
            connectionId,
            protocol,
            QuerySubscriptionMetadata(
                subscriptionId,
                performer.fullyQualifiedName.value,
                performer.descriptor.returnTypeName,
                Instant.now(),
                QuerySubscriptionClientInfo(
                    handshake.remoteIpAddress,
                    handshake.userAgent,
                    handshake.principal.id.takeIf(String::isNotBlank),
                    protocol
                )
            )
        )
    }

    private fun protocol(connectionId: String): String = if (connectionId.startsWith("ws-")) "websocket" else "sse"

    private fun failureMessage(result: QueryResult<*>): String = when {
        result.exceptionMessages.isNotEmpty() -> result.exceptionMessages.joinToString("; ")
        result.validationResults.isNotEmpty() -> result.validationResults.joinToString("; ") { it.message }
        else -> "Observable query failed."
    }

    private fun safeMessage(exception: Exception): String = if (exposeExceptionDetails) {
        exception.message ?: exception.javaClass.simpleName
    } else {
        ExceptionDetailRedactor.REDACTED_MESSAGE
    }

    private fun error(queryId: String, revision: Long?, message: String) = ObservableQueryHubMessage(
        ObservableQueryHubMessageType.Error,
        queryId,
        revision,
        message
    )

    private fun unauthorized(queryId: String, revision: Long?) = ObservableQueryHubMessage(
        ObservableQueryHubMessageType.Unauthorized,
        queryId,
        revision
    )

    private fun prepareSse(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_OK
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = MediaType.TEXT_EVENT_STREAM_VALUE
        response.setHeader("Cache-Control", "no-cache, no-store")
        response.setHeader("X-Accel-Buffering", "no")
    }

    private fun overloaded(response: HttpServletResponse, status: Int) {
        response.status = status
        response.setHeader("Retry-After", properties.overloadRetryAfterSeconds.toString())
        if (!response.isCommitted) {
            response.contentType = MediaType.TEXT_PLAIN_VALUE
            response.characterEncoding = StandardCharsets.UTF_8.name()
            response.writer.write("Service Unavailable")
            response.writer.flush()
        }
    }

    private fun methodNotAllowed(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_METHOD_NOT_ALLOWED
        response.setHeader("Allow", "POST")
    }

    private fun correlationId(request: HttpServletRequest): UUID = request.getHeader(properties.correlationHeader)
        ?.trim()
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: UUID.randomUUID()

    private fun prepareCorrelation(request: HttpServletRequest, response: HttpServletResponse): UUID =
        correlationId(request).also { correlationId ->
            response.setHeader(properties.correlationHeader, correlationId.toString())
        }

    private fun acceptsSse(request: HttpServletRequest): Boolean = request.getHeader("Accept")
        ?.contains(MediaType.TEXT_EVENT_STREAM_VALUE, ignoreCase = true) == true

    private fun cancelOnAsyncEnd(job: Job): AsyncListener = object : AsyncListener {
        override fun onComplete(event: AsyncEvent) = job.cancel()
        override fun onTimeout(event: AsyncEvent) = job.cancel()
        override fun onError(event: AsyncEvent) = job.cancel()
        override fun onStartAsync(event: AsyncEvent) = Unit
    }

    private inner class HubSseConnection(
        val connection: ArcHubConnection,
        private val stream: ServletSseStream
    ) {
        fun startHeartbeat() {
            if (settings.keepAliveInterval.isZero) return
            val heartbeat = applicationScope.tryLaunch {
                while (true) {
                    delay(settings.keepAliveInterval.toMillis())
                    if (!connection.send(ping())) break
                    healthTracker.recordPingSent(connection.id)
                }
            } ?: run {
                close()
                return
            }
            stream.attachHeartbeat(heartbeat)
        }

        fun close() {
            connection.close()
            stream.close()
        }
    }

    private companion object {
        private const val ALLOWED_SEVERITY_HEADER = "X-Allowed-Severity"
        private const val WAIT_FOR_FIRST = "waitForFirstResult"
        private const val WAIT_FOR_FIRST_TIMEOUT = "waitForFirstResultTimeout"
    }
}

internal data class CapturedObservableQuery(val request: QueryRequest, val options: QueryExecutionOptions)

internal data class ArcObservableHandshake(
    val principal: ArcPrincipal,
    val tenantId: String?,
    val correlationId: UUID,
    val parameters: Map<String, List<String>>,
    val allowedValidationSeverity: ValidationResultSeverity?,
    val remoteIpAddress: String? = null,
    val userAgent: String? = null
) {
    fun sameCaller(other: ArcObservableHandshake): Boolean =
        principal.id == other.principal.id &&
            principal.name == other.principal.name &&
            principal.isAuthenticated == other.principal.isAuthenticated &&
            tenantId == other.tenantId
}

internal enum class HubSubscribeResult { ACCEPTED, MALFORMED, OVERLOADED, UNAVAILABLE }

internal class ArcHubConnection(
    val id: String,
    val handshake: ArcObservableHandshake,
    private val sender: (ObservableQueryHubMessage) -> Boolean,
    private val closeTransport: () -> Unit
) : AutoCloseable {
    val subscriptions = ObservableQuerySubscriptionStates()
    private val closed = AtomicBoolean()

    fun send(message: ObservableQueryHubMessage): Boolean = !closed.get() && sender(message)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscriptions.close()
        closeTransport()
    }
}

internal class ConnectionLease(private val counter: AtomicInteger) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) counter.decrementAndGet()
    }
}

private class ServletSseStream(
    private val async: jakarta.servlet.AsyncContext,
    private val response: HttpServletResponse,
    private val lease: ConnectionLease,
    outboundBufferCapacity: Int,
    private val onClosed: () -> Unit = {}
) : AsyncListener, AutoCloseable {
    private val closed = AtomicBoolean()
    private val channel = Channel<String>(outboundBufferCapacity)
    private var collectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private val writer = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            for (json in channel) {
                val bytes = "data: $json\n\n".toByteArray(StandardCharsets.UTF_8)
                response.outputStream.write(bytes)
                response.outputStream.flush()
            }
        } catch (_: IOException) {
            // Client disconnected.
        } finally {
            close()
        }
    }

    fun attach(job: Job) {
        collectionJob = job
        if (closed.get()) job.cancel()
    }

    fun attachHeartbeat(job: Job) {
        heartbeatJob = job
        if (closed.get()) job.cancel()
    }

    fun send(json: String): Boolean = !closed.get() && channel.trySend(json).isSuccess

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        channel.close()
        collectionJob?.cancel()
        heartbeatJob?.cancel()
        lease.close()
        onClosed()
        runCatching { async.complete() }
        writer.cancel()
    }

    override fun onComplete(event: AsyncEvent) = close()
    override fun onTimeout(event: AsyncEvent) = close()
    override fun onError(event: AsyncEvent) = close()
    override fun onStartAsync(event: AsyncEvent) = Unit
}

internal fun parseUriParameters(uri: URI): Map<String, List<String>> {
    if (uri.rawQuery.isNullOrEmpty()) return emptyMap()
    return uri.rawQuery.split('&').filter(String::isNotEmpty).groupBy(
        keySelector = { pair -> decode(pair.substringBefore('=')) },
        valueTransform = { pair -> decode(pair.substringAfter('=', "")) }
    )
}

private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

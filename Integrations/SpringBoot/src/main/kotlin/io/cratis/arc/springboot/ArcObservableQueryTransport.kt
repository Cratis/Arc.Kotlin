// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import io.cratis.arc.ExceptionDetailRedactor
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.commands.ServiceResolver
import io.cratis.arc.correlation.CorrelationIdResolver
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
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
    private val subscriptionMapper = (objectMapper as? JsonMapper
        ?: throw IllegalArgumentException("Observable query transport requires a JSON mapper."))
        .rebuild()
        .changeDefaultPropertyInclusion { inclusion ->
            inclusion
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.ALWAYS)
        }
        .build()
    private val connections = AtomicInteger()
    private val closed = AtomicBoolean()
    private val sseConnections = ConcurrentHashMap<String, HubSseConnection>()
    private val directSseStreams = ConcurrentHashMap.newKeySet<ServletSseStream>()

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
            if (closed.get()) return null
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
    }.onFailure { exception ->
        logger.debug("Arc observable query subscription payload could not be read.", exception)
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
    ): HubSubscribeResult = connection.ifOpen {
        subscribeOpen(connection, queryId, revision, request, subscriptionHandshake)
    } ?: HubSubscribeResult.UNAVAILABLE

    private fun subscribeOpen(
        connection: ArcHubConnection,
        queryId: String,
        revision: Long?,
        request: ObservableQuerySubscriptionRequest,
        subscriptionHandshake: ArcObservableHandshake
    ): HubSubscribeResult {
        if (!ObservableQuerySubscriptionRevision.isValid(revision)) return HubSubscribeResult.MALFORMED
        if (queryId.isBlank() || request.queryName.isBlank()) return HubSubscribeResult.MALFORMED
        val performer = performers.find(FullyQualifiedQueryName(request.queryName)) ?: run {
            connection.send(error(queryId, revision, "No performer found for query ${request.queryName}"))
            return HubSubscribeResult.ACCEPTED
        }
        val queryRequest = try {
            requestBinder.fromSubscription(request, performer)
        } catch (exception: MalformedQueryRequestException) {
            logger.debug("Arc observable query subscription could not be bound. queryId={}", queryId, exception)
            return HubSubscribeResult.MALFORMED
        }
        // The lifecycle monitor is reentrant: application converters and serializers can
        // close the hub synchronously. Recheck before any reservation or later callback.
        if (connection.isClosed) return HubSubscribeResult.UNAVAILABLE
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
        if (connection.isClosed) return HubSubscribeResult.UNAVAILABLE
        val operation = connection.subscriptions.trySubscribe(queryId, revision, identity)
            ?: return HubSubscribeResult.ACCEPTED
        // Cancelling the replaced operation can also reenter close(). The new operation
        // is already owned by the hub and cancelled, but must not recreate health or work.
        if (connection.isClosed) return HubSubscribeResult.UNAVAILABLE
        if (connection.subscriptions.activeCount > settings.maximumSubscriptionsPerConnection) {
            connection.subscriptions.terminate(queryId, operation)
            return HubSubscribeResult.OVERLOADED
        }
        registerSubscription(connection.id, queryId, protocol(connection.id), performer, subscriptionHandshake)
        if (connection.isClosed) {
            // An application tracker may record health after its reentrant close returns.
            healthTracker.unregisterSubscription(connection.id, queryId)
            return HubSubscribeResult.UNAVAILABLE
        }

        val job = applicationScope.tryLaunch(start = CoroutineStart.LAZY) {
            runSubscription(
                connection, queryId, operation, request, performer, identity,
                subscriptionHandshake.allowedValidationSeverity
                    ?: ValidationResultSeverity.Information.takeIf { performer.descriptor.treatWarningsAsErrors }
            )
        } ?: run {
            connection.subscriptions.terminate(queryId, operation)
            healthTracker.unregisterSubscription(connection.id, queryId)
            return HubSubscribeResult.UNAVAILABLE
        }
        operation.attach(job)
        job.start()
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
        val states = synchronized(sseConnections) {
            if (!closed.compareAndSet(false, true)) return
            sseConnections.values.toList().also { sseConnections.clear() }
        }
        states.forEach(HubSseConnection::close)
        directSseStreams.toList().forEach(ServletSseStream::close)
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
        } catch (exception: Exception) {
            logger.debug("Arc observable query request could not be bound. correlationId={}", correlationId, exception)
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
                            // A source that already holds a value answers the snapshot straight away. Only a
                            // source with nothing to show yet is reported as not ready.
                            val current = opened.snapshot?.firstOrNull()
                            if (current != null) {
                                writeResult(response, current)
                            } else {
                                writeResult(response, QueryResult.notReady<Any?>(correlationId), HttpServletResponse.SC_ACCEPTED)
                            }
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
        val stream = ServletSseStream(
            async, response, lease, settings.outboundBufferCapacity, applicationScope, properties.requestTimeout.toMillis()
        )
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
        synchronized(sseConnections) {
            if (closed.get()) stream.close() else directSseStreams.add(stream)
        }
        stream.attachOwner {
            directSseStreams.remove(stream)
            healthTracker.removeConnection(connectionId)
        }
        async.addListener(stream)
        stream.start()
        val job = applicationScope.tryLaunch(start = CoroutineStart.LAZY) {
            try {
                when (val opened = pipeline.open(captured.request, captured.options)) {
                    is ObservableQueryOpenResult.Failure -> stream.send(json(wire(opened.result)))
                    is ObservableQueryOpenResult.Stream -> opened.results.collect { result ->
                        if (!stream.send(json(wire(result)))) {
                            stream.close()
                            throw CancellationException("Observable SSE outbound buffer is unavailable.")
                        }
                        healthTracker.recordDataServed(connectionId, subscriptionId)
                        if (!result.isAuthorized) {
                            stream.finish()
                            throw CancellationException("Observable query became unauthorized.")
                        }
                    }
                }
                stream.finish()
            } finally {
                // External cancellation/errors abort; a terminal envelope already selected draining.
                stream.producerEnded()
            }
        }
        if (job == null) {
            overloaded(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE)
            stream.close()
            return
        }
        stream.attach(job)
        job.start()
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
        val stream = ServletSseStream(async, response, lease, settings.outboundBufferCapacity, applicationScope)
        val state = HubSseConnection(
            createHubConnection(connectionId, handshake, { message -> stream.send(json(message)) }, stream::close),
            stream
        )
        synchronized(sseConnections) {
            if (closed.get()) state.close() else sseConnections[connectionId] = state
        }
        // Closing before attachment must close the late owner too. The writer starts only
        // after ownership and the servlet listener are installed; no lateinit callback race.
        stream.attachOwner {
            sseConnections.remove(connectionId, state)
            state.connection.close()
        }
        async.addListener(stream)
        stream.start()
        if (stream.send(json(connected(connectionId)))) state.startHeartbeat()
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
        response.status = when (state.connection.ifOpen {
            subscribe(state.connection, body.queryId, body.revision, body.request, subscriptionHandshake)
        }) {
            null -> HttpServletResponse.SC_NOT_FOUND
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
        performer: QueryPerformer,
        identity: ObservableQuerySubscriptionIdentity,
        allowedValidationSeverity: ValidationResultSeverity?
    ) {
        try {
            // Rebind the defensively copied wire input with the same declared metadata.
            // Identity's JSON snapshot deliberately does not preserve JVM argument types.
            // Application converters run twice and must be deterministic and thread-independent.
            val captured = captured(
                identity.principal,
                identity.tenantId,
                requestBinder.fromSubscription(request, performer),
                identity.correlationId,
                allowedValidationSeverity
            )
            // An omitted transfer mode is not "full": it selects the legacy snapshot-plus-change-set behavior,
            // which the pipeline expresses as a null mode.
            when (val opened = pipeline.open(captured.request, captured.options, request.transferMode)) {
                is ObservableQueryOpenResult.Failure -> {
                    // Opening can finish after cancellation if application code does not cooperate.
                    if (!connection.subscriptions.isCurrent(queryId, operation)) return
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
    } catch (exception: Exception) {
        logger.debug("Arc observable query body could not be read as {}.", type.name, exception)
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

    private fun prepareCorrelation(request: HttpServletRequest, response: HttpServletResponse): UUID =
        CorrelationIdResolver.resolveOrCreate(request.getHeader(properties.correlationHeader)).also { correlationId ->
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
            val heartbeat = applicationScope.tryLaunch(start = CoroutineStart.LAZY) {
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
            heartbeat.start()
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
        private val logger = LoggerFactory.getLogger(ArcObservableQueryTransport::class.java)
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
    val isClosed: Boolean get() = closed.get()

    fun send(message: ObservableQueryHubMessage): Boolean = !closed.get() && sender(message)

    /** Serializes subscription acceptance (including health and job attachment) with closure. */
    fun <T : Any> ifOpen(action: () -> T): T? = synchronized(this) {
        if (closed.get()) null else action().takeUnless { closed.get() }
    }

    override fun close(): Unit = synchronized(this) {
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

internal class ServletSseStream(
    private val async: jakarta.servlet.AsyncContext,
    private val response: HttpServletResponse,
    private val lease: ConnectionLease,
    outboundBufferCapacity: Int,
    applicationScope: CoroutineScope,
    drainTimeoutMillis: Long = 30_000
) : AsyncListener, AutoCloseable {
    private val lifecycle = ArcOutboundWriter(
        applicationScope, outboundBufferCapacity, drainTimeoutMillis,
        write = { json ->
            response.outputStream.write("data: $json\n\n".toByteArray(StandardCharsets.UTF_8))
            response.outputStream.flush()
        },
        release = lease::close,
        closeTransport = { async.complete() }
    )

    fun start() = lifecycle.start()
    fun attachOwner(onClosed: () -> Unit) = lifecycle.attachOwner(onClosed)
    fun attach(job: Job) = lifecycle.attach(job)
    fun attachHeartbeat(job: Job) = lifecycle.attachHeartbeat(job)
    fun send(json: String): Boolean = lifecycle.send(json) { close() }
    fun finish() = lifecycle.finish()
    fun producerEnded() = lifecycle.producerEnded()
    override fun close() = lifecycle.abort()
    override fun onComplete(event: AsyncEvent) = close()
    override fun onTimeout(event: AsyncEvent) = close()
    override fun onError(event: AsyncEvent) = close()
    override fun onStartAsync(event: AsyncEvent) = Unit
}

/** Bounded ordered writes, with nonblocking lifecycle APIs and application-owned cleanup. */
internal class ArcOutboundWriter(
    applicationScope: CoroutineScope,
    capacity: Int,
    private val drainTimeoutMillis: Long,
    private val write: (String) -> Unit,
    private val release: () -> Unit,
    private val closeTransport: () -> Unit
) {
    private enum class State { OPEN, DRAINING, CLOSED }
    private val lock = Any()
    private var state = State.OPEN
    private val channel = Channel<String>(capacity)
    private val closed = CompletableDeferred<Unit>()
    private var owner: (() -> Unit)? = null
    private var collectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private val ioScope = CoroutineScope(applicationScope.coroutineContext + Dispatchers.IO)
    private val deadline = CoroutineScope(applicationScope.coroutineContext + Dispatchers.Default).launch(start = CoroutineStart.LAZY) {
        delay(drainTimeoutMillis.coerceAtLeast(1))
        abort()
    }
    private val writer = ioScope.launch(start = CoroutineStart.LAZY) {
        try {
            for (payload in channel) write(payload)
        } catch (_: IOException) {
            // Client disconnected.
        } finally {
            abort()
        }
    }
    // This sibling remains cancellable even when the writer is inside blocking container I/O.
    // Native close runs outside lifecycle locks, not on the caller of finish/abort. It is NOT
    // a guarantee of writer-thread termination: the container controls blocking I/O teardown.
    init {
        writer.invokeOnCompletion { abort() }
        CoroutineScope(applicationScope.coroutineContext + Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                closed.await()
            } finally {
                abort()
                withContext(NonCancellable + Dispatchers.IO) { runCatching { closeTransport() } }
            }
        }
    }

    fun start() { writer.start() }

    fun attachOwner(onClosed: () -> Unit) {
        val alreadyClosed = synchronized(lock) {
            if (state == State.CLOSED) true else {
                check(owner == null) { "Outbound writer already has an owner." }
                owner = onClosed
                false
            }
        }
        if (alreadyClosed) onClosed()
    }

    fun attach(job: Job) {
        val reject = synchronized(lock) {
            if (state != State.OPEN) true else { collectionJob = job; false }
        }
        if (reject) job.cancel()
    }

    fun attachHeartbeat(job: Job) {
        val reject = synchronized(lock) {
            if (state != State.OPEN) true else { heartbeatJob = job; false }
        }
        if (reject) job.cancel()
    }

    fun send(payload: String, onOverflow: () -> Unit): Boolean {
        val accepted = synchronized(lock) {
            if (state != State.OPEN) return false
            channel.trySend(payload).isSuccess
        }
        if (!accepted) onOverflow()
        return accepted
    }

    fun finish() {
        val heartbeat = synchronized(lock) {
            if (state != State.OPEN) return
            state = State.DRAINING
            channel.close()
            heartbeatJob.also { heartbeatJob = null }
        }
        heartbeat?.cancel()
        deadline.start()
        writer.start()
    }

    /** A producer that did not explicitly finish was interrupted and must not drain. */
    fun producerEnded() {
        val interrupted = synchronized(lock) { state == State.OPEN }
        if (interrupted) abort()
    }

    fun abort(beforeClose: () -> Unit = {}) {
        val cleanup = synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            beforeClose()
            Triple(owner, collectionJob, heartbeatJob).also {
                owner = null
                collectionJob = null
                heartbeatJob = null
            }
        }
        channel.cancel()
        writer.cancel()
        deadline.cancel()
        cleanup.second?.cancel()
        cleanup.third?.cancel()
        try {
            release()
            cleanup.first?.invoke()
        } finally {
            closed.complete(Unit)
        }
    }
}

internal fun parseUriParameters(uri: URI): Map<String, List<String>> {
    if (uri.rawQuery.isNullOrEmpty()) return emptyMap()
    return uri.rawQuery.split('&').filter(String::isNotEmpty).groupBy(
        keySelector = { pair -> decode(pair.substringBefore('=')) },
        valueTransform = { pair -> decode(pair.substringAfter('=', "")) }
    )
}

private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

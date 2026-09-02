// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.springboot

import io.cratis.arc.metadata.EndpointRouteHelper
import io.cratis.arc.queries.ObservableQueryHubMessage
import io.cratis.arc.queries.ObservableQueryHubMessageType
import io.cratis.arc.queries.ObservableQueryOpenResult
import io.cratis.arc.queries.ObservableQueryTransferMode
import io.cratis.arc.queries.QueryPerformer
import io.cratis.arc.queries.QueryPerformerRegistry
import io.cratis.arc.queries.QueryTransportType
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.ServletWebSocketHandlerRegistry
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.socket.server.support.WebSocketHandlerMapping

/** Optional Spring WebSocket hosting for direct and multiplexed observable-query transports. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnClass(name = ["org.springframework.web.socket.config.annotation.WebSocketConfigurer"])
@ConditionalOnProperty(
    prefix = "cratis.arc.observable-queries",
    name = ["web-socket-enabled"],
    havingValue = "true",
    matchIfMissing = true
)
internal class ArcObservableQueryWebSocketConfiguration(
    private val artifactModules: ArcArtifactModules,
    private val performers: QueryPerformerRegistry,
    private val transport: ArcObservableQueryTransport,
    private val scope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties
) : WebSocketConfigurer {
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        (registry as? ServletWebSocketHandlerRegistry)?.order = org.springframework.core.Ordered.HIGHEST_PRECEDENCE
        artifactModules.modules.size
        val interceptor = ArcObservableHandshakeInterceptor(transport, properties)
        registry.addHandler(ArcObservableHubWebSocketHandler(transport, scope, properties), OBSERVABLE_QUERY_WS_ROUTE)
            .addInterceptors(interceptor)

        val observablePerformers = performers.snapshot().filter { it.descriptor.transport == QueryTransportType.OBSERVABLE }
        val endpointOptions = properties.endpoints.toOptions()
        val namespaceCounts = observablePerformers.groupingBy { performer ->
            performer.descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
        }.eachCount()
        observablePerformers.forEach { performer ->
            val namespace = performer.descriptor.location.drop(endpointOptions.segmentsToSkipForRoute).joinToString(".")
            val route = EndpointRouteHelper.queryRoute(
                performer.descriptor,
                endpointOptions,
                (namespaceCounts[namespace] ?: 0) > 1
            )
            registry.addHandler(ArcDirectObservableWebSocketHandler(performer, transport, scope, properties), route)
                .addInterceptors(ArcObservableHandshakeInterceptor(transport, properties, performer))
        }
    }

    internal companion object {
        /** Makes shared HTTP/WebSocket routes match this mapping only for a real WebSocket upgrade. */
        @Bean
        @JvmStatic
        fun arcObservableQueryWebSocketHandlerMappingPostProcessor(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                if (bean is WebSocketHandlerMapping) {
                    bean.setWebSocketUpgradeMatch(true)
                    bean.order = org.springframework.core.Ordered.HIGHEST_PRECEDENCE
                }
                return bean
            }
        }
    }
}

private class ArcObservableHandshakeInterceptor(
    private val transport: ArcObservableQueryTransport,
    private val properties: ArcProperties,
    private val performer: QueryPerformer? = null
) : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean {
        val servletRequest = (request as? ServletServerHttpRequest)?.servletRequest ?: return false
        val correlationId = servletRequest.getHeader(properties.correlationHeader)
            ?.trim()
            ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
            ?: UUID.randomUUID()
        response.headers.set(properties.correlationHeader, correlationId.toString())
        val lease = transport.tryReserveConnection() ?: run {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE)
            response.headers.set("Retry-After", properties.observableQueries.overloadRetryAfterSeconds.toString())
            return false
        }
        return try {
            attributes[HANDSHAKE_ATTRIBUTE] = transport.captureHandshake(servletRequest, correlationId, performer)
            attributes[LEASE_ATTRIBUTE] = lease
            servletRequest.setAttribute(LEASE_ATTRIBUTE, lease)
            true
        } catch (_: TenantResolutionRequiredException) {
            lease.close()
            response.setStatusCode(HttpStatus.BAD_REQUEST)
            false
        } catch (_: TenantAccessDeniedException) {
            lease.close()
            response.setStatusCode(HttpStatus.FORBIDDEN)
            false
        } catch (exception: Exception) {
            lease.close()
            throw exception
        }
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?
    ) {
        if (exception != null) {
            ((request as? ServletServerHttpRequest)?.servletRequest?.getAttribute(LEASE_ATTRIBUTE) as? ConnectionLease)
                ?.close()
        }
    }
}

private class ArcDirectObservableWebSocketHandler(
    private val performer: QueryPerformer,
    private val transport: ArcObservableQueryTransport,
    private val scope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties
) : TextWebSocketHandler() {
    private val connections = ConcurrentHashMap<String, DirectSocketConnection>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val handshake = session.attributes[HANDSHAKE_ATTRIBUTE] as? ArcObservableHandshake ?: run {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        val lease = session.attributes[LEASE_ATTRIBUTE] as? ConnectionLease ?: run {
            session.close(SERVICE_OVERLOAD)
            return
        }
        val connectionId = "ws-direct-${session.id}"
        val subscriptionId = session.id
        val writer = ArcSocketWriter(
            session,
            transport,
            properties.observableQueries.outboundBufferCapacity,
            lease
        ) { transport.removeHealthConnection(connectionId) }
        val captured = try {
            transport.createDirectRequest(
                handshake.copy(parameters = session.uri?.let(::parseUriParameters) ?: handshake.parameters),
                performer
            )
        } catch (_: MalformedQueryRequestException) {
            writer.close(CloseStatus.BAD_DATA)
            return
        }
        transport.registerDirectSubscription(connectionId, subscriptionId, "websocket", performer, handshake)
        val connection = DirectSocketConnection(writer)
        connections[session.id] = connection
        connection.streamJob = scope.tryLaunch {
            try {
                when (val opened = transport.open(captured, ObservableQueryTransferMode.FULL)) {
                    is ObservableQueryOpenResult.Failure -> writer.send(
                        mapOf("type" to "Data", "data" to transport.wire(opened.result))
                    )
                    is ObservableQueryOpenResult.Stream -> opened.results.collect { result ->
                        if (!writer.send(mapOf("type" to "Data", "data" to transport.wire(result)))) {
                            writer.close(SERVICE_OVERLOAD)
                            return@collect
                        }
                        transport.recordDataServed(connectionId, subscriptionId)
                        if (!result.isAuthorized) writer.close(CloseStatus.NORMAL)
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } finally {
                writer.close(CloseStatus.NORMAL)
            }
        }
        if (connection.streamJob == null) {
            writer.close(SERVICE_OVERLOAD)
            connections.remove(session.id)
            return
        }
        connection.heartbeatJob = heartbeat(writer, connectionId, subscriptionId)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        if (message.payload.toByteArray(StandardCharsets.UTF_8).size > properties.observableQueries.maximumInboundMessageSize) {
            connections[session.id]?.writer?.close(CloseStatus.TOO_BIG_TO_PROCESS)
            return
        }
        val root = runCatching { transport.parseHubMessage(message.payload) }.getOrNull() ?: return
        when (root.type) {
            ObservableQueryHubMessageType.Ping -> connections[session.id]?.writer?.send(
                mapOf("type" to "Pong", "timestamp" to (root.timestamp ?: System.currentTimeMillis()))
            )
            ObservableQueryHubMessageType.Pong -> transport.recordPongReceived("ws-direct-${session.id}", session.id)
            else -> Unit
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        connections.remove(session.id)?.close()
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        connections.remove(session.id)?.close()
    }

    private fun heartbeat(writer: ArcSocketWriter, connectionId: String, subscriptionId: String): Job? {
        val interval = properties.observableQueries.keepAliveInterval
        if (interval.isZero) return null
        return scope.tryLaunch {
            while (true) {
                delay(interval.toMillis())
                if (!writer.send(mapOf("type" to "Ping", "timestamp" to System.currentTimeMillis()))) break
                transport.recordPingSent(connectionId, subscriptionId)
            }
        }
    }
}

private class ArcObservableHubWebSocketHandler(
    private val transport: ArcObservableQueryTransport,
    private val scope: ArcApplicationCoroutineScope,
    private val properties: ArcProperties
) : TextWebSocketHandler() {
    private val connections = ConcurrentHashMap<String, HubSocketConnection>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val handshake = session.attributes[HANDSHAKE_ATTRIBUTE] as? ArcObservableHandshake ?: run {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        val lease = session.attributes[LEASE_ATTRIBUTE] as? ConnectionLease ?: run {
            session.close(SERVICE_OVERLOAD)
            return
        }
        val writer = ArcSocketWriter(session, transport, properties.observableQueries.outboundBufferCapacity, lease)
        lateinit var connection: HubSocketConnection
        val hub = transport.createHubConnection("ws-${session.id}", handshake, writer::send) { writer.close(CloseStatus.NORMAL) }
        connection = HubSocketConnection(hub, writer)
        connections[session.id] = connection
        writer.send(transport.connected(hub.id))
        connection.heartbeatJob = heartbeat(hub)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections[session.id] ?: return
        if (message.payload.toByteArray(StandardCharsets.UTF_8).size > properties.observableQueries.maximumInboundMessageSize) {
            connection.writer.close(CloseStatus.TOO_BIG_TO_PROCESS)
            return
        }
        val frame = runCatching { transport.parseHubMessage(message.payload) }.getOrNull() ?: return
        when (frame.type) {
            ObservableQueryHubMessageType.Subscribe -> {
                val queryId = frame.queryId ?: return
                val request = transport.parseSubscription(frame.payload) ?: run {
                    connection.writer.send(error(queryId, frame.revision, "Malformed observable query subscription."))
                    return
                }
                when (transport.subscribe(connection.hub, queryId, frame.revision, request)) {
                    HubSubscribeResult.ACCEPTED -> Unit
                    HubSubscribeResult.MALFORMED -> connection.writer.send(
                        error(queryId, frame.revision, "Malformed observable query subscription.")
                    )
                    HubSubscribeResult.OVERLOADED -> connection.writer.send(
                        error(queryId, frame.revision, "Observable query subscription limit exceeded.")
                    )
                    HubSubscribeResult.UNAVAILABLE -> connection.writer.send(
                        error(queryId, frame.revision, "Service Unavailable")
                    )
                }
            }
            ObservableQueryHubMessageType.Unsubscribe -> frame.queryId?.let { queryId ->
                transport.unsubscribe(connection.hub, queryId, frame.revision)
            }
            ObservableQueryHubMessageType.Ping -> connection.writer.send(transport.pong(frame.timestamp))
            ObservableQueryHubMessageType.Pong -> transport.recordPongReceived(connection.hub.id)
            else -> Unit
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        connections.remove(session.id)?.close()
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        connections.remove(session.id)?.close()
    }

    private fun heartbeat(hub: ArcHubConnection): Job? {
        val interval = properties.observableQueries.keepAliveInterval
        if (interval.isZero) return null
        return scope.tryLaunch {
            while (true) {
                delay(interval.toMillis())
                if (!hub.send(transport.ping())) break
                transport.recordPingSent(hub.id)
            }
        }
    }

    private fun error(queryId: String, revision: Long?, message: String) = ObservableQueryHubMessage(
        ObservableQueryHubMessageType.Error,
        queryId,
        revision,
        message
    )
}

private class ArcSocketWriter(
    private val session: WebSocketSession,
    private val transport: ArcObservableQueryTransport,
    capacity: Int,
    private val lease: ConnectionLease,
    private val onClosed: () -> Unit = {}
) : AutoCloseable {
    private val outbound = Channel<String>(capacity)
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val writer = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    ).launch {
        try {
            for (payload in outbound) {
                if (!session.isOpen) break
                session.sendMessage(TextMessage(payload))
            }
        } catch (_: Exception) {
            // Closing the writer performs the connection cleanup.
        } finally {
            close(CloseStatus.NORMAL)
        }
    }

    fun send(value: Any): Boolean {
        if (closed.get()) return false
        val accepted = outbound.trySend(transport.json(value)).isSuccess
        if (!accepted) close(SERVICE_OVERLOAD)
        return accepted
    }

    fun close(status: CloseStatus) {
        if (!closed.compareAndSet(false, true)) return
        outbound.close()
        lease.close()
        onClosed()
        runCatching { if (session.isOpen) session.close(status) }
        writer.cancel()
    }

    override fun close() = close(CloseStatus.NORMAL)
}

private class DirectSocketConnection(val writer: ArcSocketWriter) : AutoCloseable {
    var streamJob: Job? = null
    var heartbeatJob: Job? = null

    override fun close() {
        streamJob?.cancel()
        heartbeatJob?.cancel()
        writer.close()
    }
}

private class HubSocketConnection(
    val hub: ArcHubConnection,
    val writer: ArcSocketWriter
) : AutoCloseable {
    var heartbeatJob: Job? = null

    override fun close() {
        heartbeatJob?.cancel()
        hub.close()
        writer.close()
    }
}

private const val HANDSHAKE_ATTRIBUTE = "io.cratis.arc.observable.handshake"
private const val LEASE_ATTRIBUTE = "io.cratis.arc.observable.lease"
private val SERVICE_OVERLOAD = CloseStatus(1013, "Observable query transport overloaded.")

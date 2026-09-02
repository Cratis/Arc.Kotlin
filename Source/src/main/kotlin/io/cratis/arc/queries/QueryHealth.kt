// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Captured client information for an observable-query subscription. */
public class QuerySubscriptionClientInfo @JvmOverloads constructor(
    public val remoteIpAddress: String? = null,
    public val userAgent: String? = null,
    public val userId: String? = null,
    public val protocol: String
)

/** Immutable health metadata for one active observable-query subscription. */
public class QuerySubscriptionMetadata @JvmOverloads constructor(
    public val subscriptionId: String,
    public val queryIdentifier: String,
    public val readModelType: String,
    public val connectedAt: Instant,
    public val clientInfo: QuerySubscriptionClientInfo,
    public val lastPingSentAt: Instant? = null,
    public val lastPongReceivedAt: Instant? = null,
    public val lastDataServedAt: Instant? = null
)

/** Health snapshot for one physical observable-query connection. */
public class QueryConnectionHealth(
    public val connectionId: String,
    public val protocol: String,
    public val establishedAt: Instant,
    subscriptions: List<QuerySubscriptionMetadata>
) {
    public val subscriptions: List<QuerySubscriptionMetadata> = java.util.List.copyOf(subscriptions)
}

/** Query-centric subscriber snapshot. */
public class QuerySubscriber(
    public val connectionId: String,
    public val protocol: String,
    public val subscriptionId: String,
    public val connectedAt: Instant,
    public val lastPingSentAt: Instant?,
    public val lastPongReceivedAt: Instant?,
    public val lastDataServedAt: Instant?,
    public val clientInfo: QuerySubscriptionClientInfo
)

/** Active subscriptions grouped by exact fully qualified query name. */
public class QuerySubscriptionAggregate(public val queryName: String, subscribers: List<QuerySubscriber>) {
    public val subscribers: List<QuerySubscriber> = java.util.List.copyOf(subscribers)
    public val totalSubscriptions: Int get() = subscribers.size
}

/** Complete observable-query health snapshot. */
public class QueryHealth(connections: List<QueryConnectionHealth>) {
    public val connections: List<QueryConnectionHealth> = java.util.List.copyOf(connections)
    public val totalConnections: Int get() = connections.size
    public val totalSubscriptions: Int get() = connections.sumOf { it.subscriptions.size }
    public val querySubscriptions: List<QuerySubscriptionAggregate> = connections
        .flatMap { connection -> connection.subscriptions.map { connection to it } }
        .groupBy { (_, subscription) -> subscription.queryIdentifier }
        .toSortedMap()
        .map { (queryName, entries) ->
            QuerySubscriptionAggregate(queryName, entries.map { (connection, subscription) ->
                QuerySubscriber(
                    connection.connectionId,
                    connection.protocol,
                    subscription.subscriptionId,
                    subscription.connectedAt,
                    subscription.lastPingSentAt,
                    subscription.lastPongReceivedAt,
                    subscription.lastDataServedAt,
                    subscription.clientInfo
                )
            })
        }
}

/** Tracks live observable-query connection and subscription health. */
public interface QueryHealthTracker {
    public fun registerSubscription(connectionId: String, protocol: String, metadata: QuerySubscriptionMetadata)
    public fun unregisterSubscription(connectionId: String, subscriptionId: String)
    public fun recordPingSent(connectionId: String, subscriptionId: String? = null)
    public fun recordPongReceived(connectionId: String, subscriptionId: String? = null)
    public fun recordDataServed(connectionId: String, subscriptionId: String)
    public fun removeConnection(connectionId: String)
    public fun snapshot(): QueryHealth
    public fun observe(): Flow<QueryHealth>
}

/** Thread-safe in-memory query health tracker with immutable snapshots. */
public class DefaultQueryHealthTracker @JvmOverloads constructor(
    private val clock: () -> Instant = Instant::now
) : QueryHealthTracker {
    private val connections = ConcurrentHashMap<String, ConnectionState>()
    private val updates = MutableStateFlow(QueryHealth(emptyList()))

    override fun registerSubscription(connectionId: String, protocol: String, metadata: QuerySubscriptionMetadata) {
        val connection = connections.computeIfAbsent(connectionId) { ConnectionState(protocol, clock()) }
        connection.subscriptions[metadata.subscriptionId] = metadata
        publish()
    }

    override fun unregisterSubscription(connectionId: String, subscriptionId: String) {
        val connection = connections[connectionId] ?: return
        connection.subscriptions.remove(subscriptionId)
        if (connection.subscriptions.isEmpty()) connections.remove(connectionId, connection)
        publish()
    }

    override fun recordPingSent(connectionId: String, subscriptionId: String?) =
        update(connectionId, subscriptionId) { metadata -> copy(metadata, lastPingSentAt = clock()) }

    override fun recordPongReceived(connectionId: String, subscriptionId: String?) =
        update(connectionId, subscriptionId) { metadata -> copy(metadata, lastPongReceivedAt = clock()) }

    override fun recordDataServed(connectionId: String, subscriptionId: String) =
        update(connectionId, subscriptionId) { metadata -> copy(metadata, lastDataServedAt = clock()) }

    override fun removeConnection(connectionId: String) {
        connections.remove(connectionId)
        publish()
    }

    override fun snapshot(): QueryHealth = QueryHealth(connections.entries.sortedBy(Map.Entry<String, ConnectionState>::key).map {
        (connectionId, state) -> QueryConnectionHealth(
            connectionId,
            state.protocol,
            state.establishedAt,
            state.subscriptions.values.sortedBy(QuerySubscriptionMetadata::subscriptionId)
        )
    })

    override fun observe(): Flow<QueryHealth> = updates.asStateFlow()

    private fun update(
        connectionId: String,
        subscriptionId: String?,
        transform: (QuerySubscriptionMetadata) -> QuerySubscriptionMetadata
    ) {
        val connection = connections[connectionId] ?: return
        if (subscriptionId == null) {
            connection.subscriptions.replaceAll { _, metadata -> transform(metadata) }
        } else {
            connection.subscriptions.computeIfPresent(subscriptionId) { _, metadata -> transform(metadata) }
        }
        publish()
    }

    private fun publish() {
        updates.value = snapshot()
    }

    private fun copy(
        metadata: QuerySubscriptionMetadata,
        lastPingSentAt: Instant? = metadata.lastPingSentAt,
        lastPongReceivedAt: Instant? = metadata.lastPongReceivedAt,
        lastDataServedAt: Instant? = metadata.lastDataServedAt
    ): QuerySubscriptionMetadata = QuerySubscriptionMetadata(
        metadata.subscriptionId,
        metadata.queryIdentifier,
        metadata.readModelType,
        metadata.connectedAt,
        metadata.clientInfo,
        lastPingSentAt,
        lastPongReceivedAt,
        lastDataServedAt
    )

    private class ConnectionState(val protocol: String, val establishedAt: Instant) {
        val subscriptions = ConcurrentHashMap<String, QuerySubscriptionMetadata>()
    }
}

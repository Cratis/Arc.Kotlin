// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.queries

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.cratis.arc.authorization.ArcPrincipal
import io.cratis.arc.json.ArcObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Job

/** Immutable identity captured when a subscription is accepted. */
public class ObservableQuerySubscriptionIdentity @JvmOverloads constructor(
    public val queryName: FullyQualifiedQueryName,
    arguments: Map<String, Any?>,
    public val principal: ArcPrincipal,
    public val tenantId: String?,
    public val tenantNamespace: String?,
    public val correlationId: UUID,
    objectMapper: ObjectMapper = ArcObjectMapper.create()
) {
    private val mapper = objectMapper.copy().setDefaultPropertyInclusion(
        JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS)
    )
    private val argumentBytes = mapper.writeValueAsBytes(LinkedHashMap(arguments))

    /** A defensive copy of the arguments serialized at subscribe time. */
    public val serializedArguments: ByteArray
        get() = argumentBytes.copyOf()

    /** Creates an independent argument map from the captured serialized baseline. */
    public fun createArguments(): Map<String, Any?> = java.util.Collections.unmodifiableMap(
        mapper.readValue(argumentBytes, object : TypeReference<LinkedHashMap<String, Any?>>() {})
    )
}

/** One reservation established before asynchronous stream creation starts. */
public class ObservableQuerySubscriptionOperation internal constructor(
    public val revision: Long?,
    public val identity: ObservableQuerySubscriptionIdentity?
) : AutoCloseable {
    private val lock = Any()
    private var attached: Job? = null
    private var closed = false

    /** Attaches the upstream collection job, cancelling it if this reservation is already stale. */
    public fun attach(job: Job): Boolean = synchronized(lock) {
        if (closed) {
            job.cancel()
            false
        } else {
            attached = job
            true
        }
    }

    public val isCancelled: Boolean
        get() = synchronized(lock) { closed }

    override fun close() {
        val job = synchronized(lock) {
            if (closed) return
            closed = true
            attached.also { attached = null }
        }
        job?.cancel()
    }
}

/** Thread-safe revision state machine for one query identifier. */
public class ObservableQuerySubscriptionState {
    private var operation: ObservableQuerySubscriptionOperation? = null
    private var revision: Long? = null
    private var revisionAware = false
    private var tombstone = false
    private var tombstonedAt: Instant? = null

    public val isActive: Boolean get() = synchronized(this) { operation != null && !tombstone }
    public val isInactiveLegacy: Boolean get() = synchronized(this) { operation == null && !revisionAware }

    public fun trySubscribe(
        requestedRevision: Long?,
        identity: ObservableQuerySubscriptionIdentity? = null
    ): ObservableQuerySubscriptionOperation? {
        ObservableQuerySubscriptionRevision.requireValid(requestedRevision)
        val replaced: ObservableQuerySubscriptionOperation?
        val created: ObservableQuerySubscriptionOperation
        synchronized(this) {
            if (requestedRevision != null) {
                if (revisionAware && requestedRevision <= requireNotNull(revision)) return null
                revisionAware = true
                revision = requestedRevision
            } else if (revisionAware) {
                return null
            }
            created = ObservableQuerySubscriptionOperation(requestedRevision, identity)
            replaced = operation
            operation = created
            tombstone = false
            tombstonedAt = null
        }
        replaced?.close()
        return created
    }

    public fun isCurrent(candidate: ObservableQuerySubscriptionOperation): Boolean = synchronized(this) {
        operation === candidate && !tombstone
    }

    public fun tryTerminate(candidate: ObservableQuerySubscriptionOperation, now: Instant): Boolean {
        synchronized(this) {
            if (operation !== candidate) return false
            operation = null
            tombstone = revisionAware
            tombstonedAt = now.takeIf { tombstone }
        }
        candidate.close()
        return true
    }

    public fun tryUnsubscribe(requestedRevision: Long?, now: Instant): Boolean {
        ObservableQuerySubscriptionRevision.requireValid(requestedRevision)
        val cancelled: ObservableQuerySubscriptionOperation?
        synchronized(this) {
            if (requestedRevision != null) {
                if (revisionAware && requestedRevision < requireNotNull(revision)) return false
                if (revisionAware && requestedRevision == revision && tombstone) return true
                revisionAware = true
                revision = requestedRevision
                tombstone = true
                tombstonedAt = now
                cancelled = operation
                operation = null
            } else {
                if (revisionAware) return false
                cancelled = operation
                operation = null
                tombstonedAt = null
            }
        }
        cancelled?.close()
        return true
    }

    public fun tombstonedAt(): Instant? = synchronized(this) { tombstonedAt.takeIf { tombstone } }

    public fun close() {
        val cancelled = synchronized(this) {
            operation.also {
                operation = null
                tombstone = true
                tombstonedAt = null
            }
        }
        cancelled?.close()
    }
}

/** Thread-safe bounded subscription states for one multiplexed connection. */
public class ObservableQuerySubscriptionStates @JvmOverloads constructor(
    private val clock: () -> Instant = { Instant.now() },
    private val tombstoneRetention: Duration = TOMBSTONE_RETENTION,
    private val maximumRetainedTombstones: Int = MAXIMUM_RETAINED_TOMBSTONES
) : AutoCloseable {
    private val states = LinkedHashMap<String, ObservableQuerySubscriptionState>()

    public val count: Int get() = synchronized(states) { states.size }
    public val activeCount: Int get() = synchronized(states) { states.values.count { it.isActive } }

    public fun trySubscribe(
        queryId: String,
        revision: Long?,
        identity: ObservableQuerySubscriptionIdentity? = null
    ): ObservableQuerySubscriptionOperation? = synchronized(states) {
        cleanup(clock())
        val result = states.getOrPut(queryId, ::ObservableQuerySubscriptionState).trySubscribe(revision, identity)
        cleanup(clock())
        result
    }

    public fun tryUnsubscribe(queryId: String, revision: Long?): Boolean = synchronized(states) {
        val now = clock()
        cleanup(now)
        val state = if (revision != null) {
            states.getOrPut(queryId, ::ObservableQuerySubscriptionState)
        } else {
            states[queryId] ?: return false
        }
        val accepted = state.tryUnsubscribe(revision, now)
        cleanup(now)
        accepted
    }

    public fun isCurrent(queryId: String, operation: ObservableQuerySubscriptionOperation): Boolean =
        synchronized(states) { states[queryId]?.isCurrent(operation) == true }

    public fun terminate(queryId: String, operation: ObservableQuerySubscriptionOperation) = synchronized(states) {
        states[queryId]?.tryTerminate(operation, clock())
        cleanup(clock())
    }

    public fun cleanup() = synchronized(states) { cleanup(clock()) }

    override fun close() = synchronized(states) {
        states.values.forEach(ObservableQuerySubscriptionState::close)
        states.clear()
    }

    private fun cleanup(now: Instant) {
        states.entries.removeIf { (_, state) ->
            state.isInactiveLegacy || state.tombstonedAt()?.let { Duration.between(it, now) >= tombstoneRetention } == true
        }
        val tombstones = states.entries.mapNotNull { entry ->
            entry.value.tombstonedAt()?.let { Triple(entry.key, entry.value, it) }
        }.sortedWith(compareBy<Triple<String, ObservableQuerySubscriptionState, Instant>> { it.third }.thenBy { it.first })
        tombstones.take((tombstones.size - maximumRetainedTombstones).coerceAtLeast(0)).forEach { (queryId, state, _) ->
            if (states[queryId] === state) states.remove(queryId)
        }
    }

    public companion object {
        public const val MAXIMUM_RETAINED_TOMBSTONES: Int = 1024
        @JvmField public val TOMBSTONE_RETENTION: Duration = Duration.ofMinutes(2)
    }
}

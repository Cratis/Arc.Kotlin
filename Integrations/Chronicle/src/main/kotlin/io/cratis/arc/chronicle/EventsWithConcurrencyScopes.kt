// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("ChronicleCommandResponses")

package io.cratis.arc.chronicle

import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScopeBuilder
import java.util.function.Consumer

/** An ordered atomic cross-source event batch with exact concurrency scopes. */
public class EventsWithConcurrencyScopes internal constructor(
    events: List<EventForEventSourceId>,
    concurrencyScopes: Map<String, ConcurrencyScope>
) {
    /** Events to append, in declaration order. */
    public val events: List<EventForEventSourceId> = java.util.List.copyOf(events)

    /** Concurrency scope keyed by the event-source label Chronicle validates. */
    public val concurrencyScopes: Map<String, ConcurrencyScope> =
        java.util.Collections.unmodifiableMap(LinkedHashMap(concurrencyScopes))

    /** Gets the exact expected sequence for [eventSourceId], including from ordinary Java. */
    public fun expectedSequenceNumber(eventSourceId: String): Long? =
        concurrencyScopes[eventSourceId]?.sequenceNumber?.takeIf(EventSequenceNumber::isActualValue)?.value

    public companion object {
        /** Creates a Java- and Kotlin-friendly builder. */
        @JvmStatic
        public fun builder(): EventsWithConcurrencyScopesBuilder = EventsWithConcurrencyScopesBuilder()
    }
}

/** Builds [EventsWithConcurrencyScopes] while preserving event and scope declaration order. */
public class EventsWithConcurrencyScopesBuilder {
    private val events = mutableListOf<EventForEventSourceId>()
    private val concurrencyScopes = linkedMapOf<String, ConcurrencyScope>()

    /** Adds an already-shaped event. */
    public fun event(event: EventForEventSourceId): EventsWithConcurrencyScopesBuilder = apply {
        require(event.eventSourceId.isSafeEventSourceId()) { "eventSourceId must be nonblank and contain no control characters." }
        events.add(event)
    }

    /** Adds a plain event for [eventSourceId]. */
    public fun event(eventSourceId: String, event: Any): EventsWithConcurrencyScopesBuilder =
        event(EventForEventSourceId(eventSourceId, event))

    /** Adds an exact [scope] for [eventSourceId]. */
    public fun concurrencyScope(
        eventSourceId: String,
        scope: ConcurrencyScope
    ): EventsWithConcurrencyScopesBuilder = apply {
        require(eventSourceId.isSafeEventSourceId()) { "eventSourceId must be nonblank and contain no control characters." }
        val existing = concurrencyScopes[eventSourceId]
        require(existing == null || existing == scope) {
            "A different concurrency scope is already configured for event source '$eventSourceId'."
        }
        concurrencyScopes[eventSourceId] = scope
    }

    /** Adds an exact expected event-log position without exposing Chronicle's Kotlin inline value class to Java. */
    public fun expectedSequenceNumber(
        eventSourceId: String,
        sequenceNumber: Long
    ): EventsWithConcurrencyScopesBuilder {
        require(sequenceNumber >= 0) { "sequenceNumber must be zero or greater." }
        return concurrencyScope(
            eventSourceId,
            ConcurrencyScopeBuilder().withSequenceNumber(EventSequenceNumber(sequenceNumber)).build()
        )
    }

    /** Builds a Chronicle concurrency scope with a Kotlin receiver. */
    public fun concurrencyScope(
        eventSourceId: String,
        configure: ConcurrencyScopeBuilder.() -> Unit
    ): EventsWithConcurrencyScopesBuilder = concurrencyScope(
        eventSourceId,
        ConcurrencyScopeBuilder().apply(configure).build()
    )

    /** Builds a Chronicle concurrency scope with a Java [Consumer]. */
    public fun concurrencyScope(
        eventSourceId: String,
        configure: Consumer<ConcurrencyScopeBuilder>
    ): EventsWithConcurrencyScopesBuilder {
        val builder = ConcurrencyScopeBuilder()
        configure.accept(builder)
        return concurrencyScope(eventSourceId, builder.build())
    }

    /** Produces an immutable response. */
    public fun build(): EventsWithConcurrencyScopes {
        require(events.isNotEmpty()) { "At least one event is required." }
        return EventsWithConcurrencyScopes(events, concurrencyScopes)
    }
}

/** Kotlin DSL for an atomic event response carrying concurrency scopes. */
public fun eventsWithConcurrencyScopes(
    configure: EventsWithConcurrencyScopesBuilder.() -> Unit
): EventsWithConcurrencyScopes = EventsWithConcurrencyScopesBuilder().apply(configure).build()

private fun String.isSafeEventSourceId(): Boolean = isNotBlank() && none(Char::isISOControl)

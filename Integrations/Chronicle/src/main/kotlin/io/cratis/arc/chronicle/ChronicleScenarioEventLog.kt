// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.chronicle.eventSequences.AppendOptions
import io.cratis.chronicle.eventSequences.AppendResult
import io.cratis.chronicle.eventSequences.AppendedEvent
import io.cratis.chronicle.eventSequences.AppendedEventWithResult
import io.cratis.chronicle.eventSequences.CompleteStreamResult
import io.cratis.chronicle.eventSequences.ConstraintViolation
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.eventSequences.EventSequenceId
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.ITransactionalEventSequence
import io.cratis.chronicle.eventSequences.RedactionReason
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyScope
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyViolation
import io.cratis.chronicle.events.EventContext
import io.cratis.chronicle.events.EventType
import io.cratis.chronicle.events.EventTypeDescriptor
import io.cratis.chronicle.events.EventTypeGeneration
import io.cratis.chronicle.events.EventTypeId
import io.cratis.chronicle.identity.Identity
import io.cratis.chronicle.json.chronicleGson
import java.time.Instant
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.reflect.KClass

/** One successful event appended by command execution rather than scenario setup. */
public data class ChronicleScenarioEvent(
    public val eventSourceId: String,
    public val event: Any,
    public val sequenceNumber: Long
)

internal class ChronicleScenarioEventLog private constructor(
    private val namespace: String,
    private val state: State
) : IEventLog {
    constructor() : this("default", State())

    private data class Stored(val appended: AppendedEvent, val event: Any, val given: Boolean)
    private class State {
        val lock = Any()
        val stored = mutableListOf<Stored>()
        val failures = ArrayDeque<Failure>()
    }
    private sealed interface Failure {
        data class Constraint(val violation: ConstraintViolation) : Failure
        data class Concurrency(val violation: ConcurrencyViolation) : Failure
    }

    private val operations = MutableSharedFlow<List<AppendedEventWithResult>>(extraBufferCapacity = 32)

    private val lock: Any get() = state.lock
    private val stored: MutableList<Stored> get() = state.stored
    private val failures: ArrayDeque<Failure> get() = state.failures

    override val id: EventSequenceId = EventSequenceId.eventLog
    override val appendOperations: SharedFlow<List<AppendedEventWithResult>> = operations.asSharedFlow()
    override val transactional: ITransactionalEventSequence = object : ITransactionalEventSequence {
        override suspend fun append(
            eventSourceId: String,
            event: Any,
            options: AppendOptions?
        ): AppendResult = this@ChronicleScenarioEventLog.append(eventSourceId, event, options)

        override suspend fun appendMany(
            eventSourceId: String,
            events: List<Any>,
            options: AppendOptions?
        ): List<AppendResult> = this@ChronicleScenarioEventLog.appendMany(eventSourceId, events, options)
    }

    val appendedEvents: List<ChronicleScenarioEvent>
        get() = synchronized(lock) {
            stored.filterNot(Stored::given).map {
                ChronicleScenarioEvent(it.appended.context.eventSourceId, it.event, it.appended.context.sequenceNumber)
            }
        }

    fun forNamespace(namespace: String): ChronicleScenarioEventLog =
        if (namespace == this.namespace) this else ChronicleScenarioEventLog(namespace, state)

    suspend fun given(eventSourceId: String, events: Iterable<Any>) {
        synchronized(lock) {
            appendBatchSuccessful(
                events.map {
                    PendingEvent(eventSourceId, it, AppendOptions(correlationId = UUID(0, 0)), Identity.system)
                },
                true
            )
        }
    }

    fun rejectNextWithConstraint(violation: ConstraintViolation) {
        synchronized(lock) { failures.addLast(Failure.Constraint(violation)) }
    }

    fun rejectNextWithConcurrency(violation: ConcurrencyViolation) {
        synchronized(lock) { failures.addLast(Failure.Concurrency(violation)) }
    }

    override suspend fun append(
        eventSourceId: String,
        event: Any,
        options: AppendOptions?
    ): AppendResult = synchronized(lock) {
        val resolvedOptions = options ?: AppendOptions(correlationId = UUID.randomUUID())
        val pending = PendingEvent(eventSourceId, event, resolvedOptions)
        val failure = failures.pollFirst()
        if (failure != null) {
            val result = when (failure) {
                is Failure.Constraint -> failed(constraints = listOf(failure.violation))
                is Failure.Concurrency -> failed(concurrency = failure.violation)
            }
            operations.tryEmit(
                listOf(AppendedEventWithResult(contextFor(pending, -1), event, result))
            )
            result
        } else {
            appendBatchSuccessful(listOf(pending), false).single()
        }
    }

    override suspend fun appendMany(
        eventSourceId: String,
        events: List<Any>,
        options: AppendOptions?
    ): List<AppendResult> = synchronized(lock) {
        val resolvedOptions = options ?: AppendOptions(correlationId = UUID.randomUUID())
        val pending = events.map { event -> PendingEvent(eventSourceId, event, resolvedOptions) }
        val failure = failures.pollFirst()
        if (failure != null) {
            rejectBatch(pending, failure)
        } else {
            appendBatchSuccessful(pending, false)
        }
    }

    override suspend fun appendMany(
        events: List<EventForEventSourceId>,
        concurrencyScopes: Map<String, ConcurrencyScope>,
        correlationId: UUID?
    ): List<AppendResult> = synchronized(lock) {
        val resolvedCorrelationId = correlationId ?: UUID.randomUUID()
        val pending = events.map { shaped ->
            PendingEvent(
                shaped.eventSourceId,
                shaped.event,
                AppendOptions(
                    correlationId = resolvedCorrelationId,
                    concurrencyScope = concurrencyScopes[shaped.eventSourceId],
                    eventSourceType = shaped.eventSourceType,
                    eventStreamType = shaped.eventStreamType,
                    eventStreamId = shaped.eventStreamId,
                    subject = shaped.subject,
                    tags = shaped.tags,
                    occurred = shaped.occurred,
                    causation = shaped.causation
                )
            )
        }
        val failure = failures.pollFirst()
        if (failure != null) {
            rejectBatch(pending, failure)
        } else {
            appendBatchSuccessful(pending, false)
        }
    }

    override suspend fun hasEventsFor(eventSourceId: String): Boolean = synchronized(lock) {
        stored.any { it.appended.context.namespace == namespace && it.appended.context.eventSourceId == eventSourceId }
    }

    override suspend fun getTailSequenceNumber(eventSourceId: String?): EventSequenceNumber = synchronized(lock) {
        val events = stored.filter {
            it.appended.context.namespace == namespace &&
                (eventSourceId == null || it.appended.context.eventSourceId == eventSourceId)
        }
        events.lastOrNull()?.appended?.context?.sequenceNumber?.let(::EventSequenceNumber)
            ?: EventSequenceNumber.unavailable
    }

    override suspend fun getForEventSourceIdAndEventTypes(
        eventSourceId: String,
        eventTypes: List<KClass<*>>,
        eventStreamType: String?,
        eventStreamId: String?,
        eventSourceType: String?
    ): List<AppendedEvent> = synchronized(lock) {
        val typeIds = eventTypes.map(::eventTypeId).toSet()
        stored.map(Stored::appended).filter {
            it.context.namespace == namespace &&
                it.context.eventSourceId == eventSourceId &&
                it.context.eventType.id.value in typeIds &&
                (eventStreamType == null || it.context.eventStreamType == eventStreamType) &&
                (eventStreamId == null || it.context.eventStreamId == eventStreamId) &&
                (eventSourceType == null || it.context.eventSourceType == eventSourceType)
        }
    }

    override suspend fun getFromSequenceNumber(
        sequenceNumber: EventSequenceNumber,
        eventSourceId: String?,
        eventTypes: List<KClass<*>>?
    ): List<AppendedEvent> = synchronized(lock) {
        val typeIds = eventTypes?.map(::eventTypeId)?.toSet()
        stored.map(Stored::appended).filter {
            it.context.namespace == namespace &&
                it.context.sequenceNumber >= sequenceNumber.value &&
                (eventSourceId == null || it.context.eventSourceId == eventSourceId) &&
                (typeIds == null || it.context.eventType.id.value in typeIds)
        }
    }

    override suspend fun getNextSequenceNumber(): EventSequenceNumber = synchronized(lock) {
        EventSequenceNumber(stored.count { it.appended.context.namespace == namespace }.toLong())
    }

    override suspend fun getTailSequenceNumberForObserver(observerType: KClass<*>): EventSequenceNumber =
        getTailSequenceNumber()

    override suspend fun completeStream(eventStreamType: String, eventStreamId: String): CompleteStreamResult =
        CompleteStreamResult.Success(getTailSequenceNumber())

    override suspend fun redact(
        sequenceNumber: EventSequenceNumber,
        reason: RedactionReason
    ) = Unit

    override suspend fun redactForEventSource(
        eventSourceId: String,
        reason: RedactionReason,
        eventTypes: List<KClass<*>>
    ) = Unit

    private fun rejectBatch(
        events: List<PendingEvent>,
        failure: Failure
    ): List<AppendResult> {
        val results = events.map { event ->
            val result = when (failure) {
                is Failure.Constraint -> failed(constraints = listOf(failure.violation))
                is Failure.Concurrency -> failed(concurrency = failure.violation)
            }
            AppendedEventWithResult(contextFor(event, -1), event.event, result)
        }
        operations.tryEmit(results)
        return results.map(AppendedEventWithResult::result)
    }

    private fun appendBatchSuccessful(
        events: List<PendingEvent>,
        given: Boolean
    ): List<AppendResult> {
        val firstSequenceNumber = stored.count { it.appended.context.namespace == namespace }.toLong()
        val prepared = events.mapIndexed { index, pending ->
            val sequenceNumber = firstSequenceNumber + index
            val context = contextFor(pending, sequenceNumber)
            val result = AppendResult(
                EventSequenceNumber(sequenceNumber),
                emptyList(),
                emptyList(),
                true,
                null
            )
            val appended = AppendedEvent(context, chronicleGson.toJson(pending.event))
            Stored(appended, pending.event, given) to AppendedEventWithResult(context, pending.event, result)
        }
        stored.addAll(prepared.map(Pair<Stored, AppendedEventWithResult>::first))
        operations.tryEmit(prepared.map { it.second })
        return prepared.map { it.second.result }
    }

    private fun contextFor(pending: PendingEvent, sequenceNumber: Long): EventContext {
        val annotation = pending.event.javaClass.getAnnotation(EventType::class.java)
            ?: throw IllegalArgumentException("'${pending.event.javaClass.name}' is not annotated with @EventType.")
        return EventContext(
            sequenceNumber,
            pending.eventSourceId,
            EventTypeDescriptor(
                EventTypeId(annotation.id.ifBlank { pending.event.javaClass.simpleName }),
                EventTypeGeneration(annotation.generation),
                annotation.tombstone
            ),
            pending.options.occurred ?: Instant.now(),
            pending.options.correlationId ?: UUID.randomUUID(),
            pending.causedBy,
            pending.options.eventSourceType ?: "Default",
            pending.options.eventStreamType ?: "Default",
            pending.options.eventStreamId?.takeIf(String::isNotBlank) ?: pending.eventSourceId,
            "testing",
            namespace,
            pending.options.causation,
            pending.options.tags
        )
    }

    private fun eventTypeId(type: KClass<*>): String {
        val annotation = type.java.getAnnotation(EventType::class.java)
            ?: throw IllegalArgumentException("'${type.qualifiedName}' is not annotated with @EventType.")
        return annotation.id.ifBlank { type.simpleName.orEmpty() }
    }

    private fun failed(
        constraints: List<ConstraintViolation> = emptyList(),
        concurrency: ConcurrencyViolation? = null
    ): AppendResult = AppendResult(
        EventSequenceNumber.unavailable,
        constraints,
        emptyList(),
        false,
        concurrency
    )

    private data class PendingEvent(
        val eventSourceId: String,
        val event: Any,
        val options: AppendOptions,
        val causedBy: Identity = Identity.unknown
    )
}

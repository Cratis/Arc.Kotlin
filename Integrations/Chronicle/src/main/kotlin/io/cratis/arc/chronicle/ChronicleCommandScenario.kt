// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

@file:JvmName("ChronicleCommandScenarios")

package io.cratis.arc.chronicle

import io.cratis.arc.testing.CommandScenario
import io.cratis.arc.testing.CommandScenarioExtender
import io.cratis.arc.testing.CommandScenarioExtensionContext
import io.cratis.arc.testing.CommandScenarioResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.ConstraintViolation
import io.cratis.chronicle.eventSequences.EventSequenceNumber
import io.cratis.chronicle.eventSequences.IEventLog
import io.cratis.chronicle.eventSequences.concurrency.ConcurrencyViolation
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking

/** Adds an in-process Chronicle event log to every discovered Arc command scenario. */
public class ChronicleCommandScenarioExtender : CommandScenarioExtender {
    private val eventLog = ChronicleScenarioEventLog()
    private val eventStores = ConcurrentHashMap<String, IEventStore>()

    /** Chronicle state and setup surface owned by this extender instance. */
    public val chronicle: ChronicleCommandScenario = ChronicleCommandScenario(eventLog)

    override fun extend(context: CommandScenarioExtensionContext) {
        val transactions = ChronicleCommandTransaction()
        val defaultStore = eventStore("default")
        val resolver = TenantEventStoreResolver { namespace -> eventStore(namespace ?: "default") }

        context.addScope(ChronicleCommandExecutionScope(transactions))
        context.addResponseHandler(
            ChronicleCommandResponseValueHandler(resolver, context.commandHandlers, transactions)
        )
        context.addResponseHandler(
            EventsWithConcurrencyScopesCommandResponseValueHandler(resolver, transactions)
        )
        context.addService(IEventLog::class.java, eventLog)
        context.addService(IEventStore::class.java, defaultStore)
        context.addService(TenantEventStoreResolver::class.java, resolver)
        context.addService(ChronicleCommandScenario::class.java, chronicle)
    }

    private fun eventStore(namespace: String): IEventStore = eventStores.computeIfAbsent(namespace) {
        Proxy.newProxyInstance(
            IEventStore::class.java.classLoader,
            arrayOf(IEventStore::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getName" -> "testing"
                "getNamespace" -> namespace
                "getEventLog" -> eventLog.forNamespace(namespace)
                "toString" -> "Chronicle command scenario event store '$namespace'"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> throw UnsupportedOperationException(
                    "The Chronicle command scenario event store does not implement '${method.name}'."
                )
            }
        } as IEventStore
    }
}

/** Chronicle setup and captured append state for one command scenario. */
public class ChronicleCommandScenario internal constructor(private val eventLog: ChronicleScenarioEventLog) {
    /** Events appended by command execution, excluding events supplied as givens. */
    public val appendedEvents: List<ChronicleScenarioEvent>
        get() = eventLog.appendedEvents

    /** Creates a builder for pre-existing events and deterministic append failures. */
    public fun given(): ChronicleCommandScenarioGivenBuilder = ChronicleCommandScenarioGivenBuilder(eventLog)

    /** Asserts the exact number of events appended by command execution. */
    public fun shouldHaveAppendedEvents(count: Int): ChronicleCommandScenario = apply {
        if (appendedEvents.size != count) {
            fail("Expected $count appended event(s), but found ${appendedEvents.size}.")
        }
    }

    /** Asserts that command execution appended no events. */
    public fun shouldHaveNoAppendedEvents(): ChronicleCommandScenario = shouldHaveAppendedEvents(0)

    /** Asserts and returns the single appended event assignable to [eventType]. */
    public fun <TEvent : Any> shouldHaveAppendedEvent(eventType: Class<TEvent>): TEvent =
        findSingleEvent(null, eventType).event.let(eventType::cast)

    /** Asserts and returns the single [eventType] appended for [eventSourceId]. */
    public fun <TEvent : Any> shouldHaveAppendedEvent(
        eventSourceId: String,
        eventType: Class<TEvent>
    ): TEvent = findSingleEvent(eventSourceId, eventType).event.let(eventType::cast)

    /** Kotlin convenience for [shouldHaveAppendedEvent]. */
    public inline fun <reified TEvent : Any> shouldHaveAppendedEvent(): TEvent =
        shouldHaveAppendedEvent(TEvent::class.java)

    /** Kotlin convenience for [shouldHaveAppendedEvent]. */
    public inline fun <reified TEvent : Any> shouldHaveAppendedEvent(eventSourceId: String): TEvent =
        shouldHaveAppendedEvent(eventSourceId, TEvent::class.java)

    private fun <TEvent : Any> findSingleEvent(
        eventSourceId: String?,
        eventType: Class<TEvent>
    ): ChronicleScenarioEvent {
        val matches = appendedEvents.filter { captured ->
            eventType.isInstance(captured.event) &&
                (eventSourceId == null || captured.eventSourceId == eventSourceId)
        }
        if (matches.size != 1) {
            val source = eventSourceId?.let { " for event source '$it'" }.orEmpty()
            fail("Expected one appended '${eventType.name}' event$source, but found ${matches.size}.")
        }
        return matches.single()
    }

    private fun fail(expectation: String): Nothing {
        val captured = appendedEvents.joinToString(", ") {
            "${it.event.javaClass.name}@${it.eventSourceId}#${it.sequenceNumber}"
        }.ifEmpty { "<none>" }
        throw AssertionError("$expectation\nAppended events: $captured")
    }
}

/** Builds Chronicle preconditions without starting a Chronicle kernel. */
public class ChronicleCommandScenarioGivenBuilder internal constructor(
    private val eventLog: ChronicleScenarioEventLog
) {
    /** Selects the event source whose history is being established. */
    public fun forEventSource(eventSourceId: String): ChronicleCommandScenarioSourceGivenBuilder =
        ChronicleCommandScenarioSourceGivenBuilder(this, eventLog, eventSourceId)

    /** Adds ordered pre-existing [events] for [eventSourceId]. */
    public fun events(eventSourceId: String, vararg events: Any): ChronicleCommandScenarioGivenBuilder = apply {
        require(events.isNotEmpty()) { "At least one given event is required." }
        runBlocking { eventLog.given(eventSourceId, events.asIterable()) }
    }

    /** Makes the next append fail with [constraintId]. */
    @JvmOverloads
    public fun constraintViolation(
        constraintId: String,
        message: String,
        details: Map<String, String> = emptyMap()
    ): ChronicleCommandScenarioGivenBuilder = apply {
        eventLog.rejectNextWithConstraint(ConstraintViolation(constraintId, message, details))
    }

    /** Makes the next append fail with a deterministic concurrency violation. */
    public fun concurrencyViolation(
        eventSourceId: String,
        expectedSequenceNumber: Long,
        actualSequenceNumber: Long
    ): ChronicleCommandScenarioGivenBuilder = apply {
        eventLog.rejectNextWithConcurrency(
            ConcurrencyViolation(
                eventSourceId,
                EventSequenceNumber(expectedSequenceNumber),
                EventSequenceNumber(actualSequenceNumber)
            )
        )
    }
}

/** Adds ordered pre-existing events for one event source. */
public class ChronicleCommandScenarioSourceGivenBuilder internal constructor(
    private val parent: ChronicleCommandScenarioGivenBuilder,
    private val eventLog: ChronicleScenarioEventLog,
    private val eventSourceId: String
) {
    /** Adds [events] to the source history and returns this builder. */
    public fun events(vararg events: Any): ChronicleCommandScenarioSourceGivenBuilder = apply {
        require(events.isNotEmpty()) { "At least one given event is required." }
        runBlocking { eventLog.given(eventSourceId, events.asIterable()) }
    }

    /** Returns to the parent builder for another source or deterministic failure. */
    public fun and(): ChronicleCommandScenarioGivenBuilder = parent
}

/** Gets the automatically discovered Chronicle command-scenario extension. */
public fun <TCommand : Any> CommandScenario<TCommand>.chronicle(): ChronicleCommandScenario =
    extension(ChronicleCommandScenarioExtender::class.java).chronicle

/** Java- and Kotlin-friendly shortcut for `scenario.chronicle().given()`. */
public fun <TCommand : Any> CommandScenario<TCommand>.givenChronicle(): ChronicleCommandScenarioGivenBuilder =
    chronicle().given()

/** Asserts and returns a Chronicle constraint violation with [constraintId]. */
public fun CommandScenarioResult<*>.shouldHaveConstraintViolation(constraintId: String): ValidationResult =
    result.validationResults.firstOrNull { validation ->
        validation.reason == ValidationResultReasons.CONSTRAINT_VIOLATION &&
            validation.reasonDetail == constraintId
    } ?: failChronicleValidation("constraint violation '$constraintId'")

/** Asserts and returns a Chronicle concurrency violation, optionally for [eventSourceId]. */
@JvmOverloads
public fun CommandScenarioResult<*>.shouldHaveConcurrencyViolation(eventSourceId: String? = null): ValidationResult =
    result.validationResults.firstOrNull { validation ->
        validation.reason == ValidationResultReasons.CONCURRENCY_VIOLATION &&
            (eventSourceId == null || validation.reasonDetail == eventSourceId)
    } ?: failChronicleValidation(
        eventSourceId?.let { "concurrency violation for event source '$it'" } ?: "concurrency violation"
    )

private fun CommandScenarioResult<*>.failChronicleValidation(expectation: String): Nothing =
    throw AssertionError("Expected a Chronicle $expectation.\n${summary()}")

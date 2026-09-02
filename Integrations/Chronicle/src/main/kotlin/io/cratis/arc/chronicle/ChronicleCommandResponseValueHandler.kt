// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.chronicle

import io.cratis.arc.commands.CommandContext
import io.cratis.arc.commands.CommandHandlerRegistry
import io.cratis.arc.commands.CommandResponseValueHandler
import io.cratis.arc.metadata.CommandResponseValueDisposition
import io.cratis.arc.results.CommandResult
import io.cratis.arc.results.ValidationResult
import io.cratis.arc.results.ValidationResultReasons
import io.cratis.arc.results.ValidationResultSeverity
import io.cratis.chronicle.IEventStore
import io.cratis.chronicle.eventSequences.EventForEventSourceId
import io.cratis.chronicle.events.EventType
import kotlinx.coroutines.CancellationException

/** Appends Chronicle events returned by Arc command handlers. */
public class ChronicleCommandResponseValueHandler(
    private val eventStoreResolver: TenantEventStoreResolver,
    private val commandHandlers: CommandHandlerRegistry,
    private val transactions: ChronicleCommandTransaction?
) : CommandResponseValueHandler {
    /** Creates a handler without transactional enrollment. */
    public constructor(
        eventStoreResolver: TenantEventStoreResolver,
        commandHandlers: CommandHandlerRegistry
    ) : this(eventStoreResolver, commandHandlers, null)

    /** Creates a handler backed by one store that serves null tenants or its own namespace only. */
    public constructor(
        eventStore: IEventStore,
        commandHandlers: CommandHandlerRegistry
    ) : this(tenantEventStoreResolver(eventStore), commandHandlers, null)

    /** Creates a handler backed by one store and an explicit command transaction. */
    public constructor(
        eventStore: IEventStore,
        commandHandlers: CommandHandlerRegistry,
        transactions: ChronicleCommandTransaction
    ) : this(tenantEventStoreResolver(eventStore), commandHandlers, transactions)

    override fun canHandle(context: CommandContext, value: Any): Boolean = classify(context, value) != null

    override suspend fun handle(context: CommandContext, value: Any): CommandResult<*> {
        val response = classify(context, value) ?: return CommandResult.error(
            context.correlationId,
            "The command response is not a supported Chronicle event response."
        )
        if (response is EventResponse.InvalidRouted) return invalidRoutedResponse(context)
        if (response is EventResponse.EmptyRouted) {
            transactions?.requireActive(context)
            return CommandResult.success(context.correlationId)
        }
        val eventStore = resolveEventStore(context) ?: return eventStoreUnavailable(context)
        return when (response) {
            is EventResponse.Plain -> appendPlain(context, eventStore, response.events)
            is EventResponse.Routed -> appendRouted(context, eventStore, response.events)
            EventResponse.EmptyRouted -> CommandResult.success(context.correlationId)
            EventResponse.InvalidRouted -> invalidRoutedResponse(context)
        }
    }

    private suspend fun appendPlain(
        context: CommandContext,
        eventStore: IEventStore,
        events: List<Any>
    ): CommandResult<*> {
        val commandHandler = commandHandlers.find(context.commandType)
        val eventSourceId = commandHandler?.resolveCommandKey(context.command).toChronicleKey()
            ?: return missingCommandKey(context)
        val routedEvents = events.map { event -> EventForEventSourceId(eventSourceId, event) }
        if (transactions != null) {
            transactions.enroll(context, eventStore, routedEvents)
            return CommandResult.success(context.correlationId)
        }
        val appendOptions = context.toChronicleAppendOptions()
        val results = if (events.size == 1) {
            listOf(eventStore.eventLog.append(eventSourceId, events.single(), appendOptions))
        } else {
            eventStore.eventLog.appendMany(eventSourceId, events, appendOptions)
        }
        return appendResultsToCommandResult(context, results, events.size)
    }

    private suspend fun appendRouted(
        context: CommandContext,
        eventStore: IEventStore,
        events: List<EventForEventSourceId>
    ): CommandResult<*> {
        if (events.any { !it.eventSourceId.isValidEventSourceId() }) {
            return invalidEventSourceId(context, "eventSourceId")
        }
        if (transactions != null) {
            transactions.enroll(context, eventStore, events)
            return CommandResult.success(context.correlationId)
        }
        val results = eventStore.eventLog.appendMany(
            events = context.withChronicleCausation(events),
            concurrencyScopes = emptyMap(),
            correlationId = context.correlationId
        )
        return appendResultsToCommandResult(context, results, events.size)
    }

    private fun resolveEventStore(context: CommandContext): IEventStore? = try {
        eventStoreResolver.resolve(context.tenantNamespace)?.takeIf { eventStore ->
            context.tenantNamespace == null || eventStore.namespace == context.tenantNamespace
        }
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    }

    private fun eventStoreUnavailable(context: CommandContext): CommandResult<*> = CommandResult.invalid(
        context.correlationId,
        listOf(
            ValidationResult(
                severity = ValidationResultSeverity.Error,
                message = "A Chronicle event store is unavailable for the command tenant namespace.",
                members = listOf("tenantNamespace"),
                reason = ValidationResultReasons.DEPENDENCY_UNAVAILABLE,
                reasonDetail = context.tenantNamespace
            )
        )
    )

    private fun missingCommandKey(context: CommandContext): CommandResult<*> =
        invalidEventSourceId(context, "commandKey")

    private fun invalidEventSourceId(context: CommandContext, member: String): CommandResult<*> = CommandResult.invalid(
        context.correlationId,
        listOf(
            ValidationResult(
                severity = ValidationResultSeverity.Error,
                message = "A Chronicle event response requires a nonblank event-source identifier backed by a String, UUID, or number.",
                members = listOf(member),
                reason = ValidationResultReasons.RULE,
                reasonDetail = member
            )
        )
    )

    private fun invalidRoutedResponse(context: CommandContext): CommandResult<*> = CommandResult.invalid(
        context.correlationId,
        listOf(
            ValidationResult(
                severity = ValidationResultSeverity.Error,
                message = "Every routed event requires a Chronicle @EventType value.",
                members = listOf("events"),
                reason = ValidationResultReasons.RULE,
                reasonDetail = "events"
            )
        )
    )

    private fun classify(context: CommandContext, value: Any): EventResponse? {
        if (value is EventForEventSourceId) {
            return if (value.event.isChronicleEvent()) {
                EventResponse.Routed(listOf(value))
            } else {
                EventResponse.InvalidRouted
            }
        }
        if (value.isChronicleEvent()) return EventResponse.Plain(listOf(value))
        val values = when (value) {
            is Collection<*> -> value.toList()
            is Array<*> -> value.toList()
            else -> return null
        }
        if (values.isEmpty()) {
            return if (isStaticallyHandledRoutedEnumerable(context)) EventResponse.EmptyRouted else null
        }
        return when {
            values.any { item -> item is EventForEventSourceId } -> {
                val routedEvents = values.filterIsInstance<EventForEventSourceId>()
                if (
                    routedEvents.size == values.size &&
                    routedEvents.all { routedEvent -> routedEvent.event.isChronicleEvent() }
                ) {
                    EventResponse.Routed(routedEvents)
                } else {
                    EventResponse.InvalidRouted
                }
            }
            values.all { item -> item != null && item.isChronicleEvent() } ->
                EventResponse.Plain(values.filterNotNull())
            else -> null
        }
    }

    private fun isStaticallyHandledRoutedEnumerable(context: CommandContext): Boolean =
        commandHandlers.find(context.commandType)?.metadata?.responseValues?.any { descriptor ->
            descriptor.isEnumerable &&
                descriptor.disposition == CommandResponseValueDisposition.HANDLED &&
                descriptor.typeName == EventForEventSourceId::class.java.name
        } == true

    private fun Any.isChronicleEvent(): Boolean = javaClass.isAnnotationPresent(EventType::class.java)

    private fun String.isValidEventSourceId(): Boolean = isNotBlank() && none(Char::isISOControl)

    private sealed interface EventResponse {
        data class Plain(val events: List<Any>) : EventResponse
        data class Routed(val events: List<EventForEventSourceId>) : EventResponse
        data object EmptyRouted : EventResponse
        data object InvalidRouted : EventResponse
    }
}
